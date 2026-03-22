package io.github.gaming32.modloadingscreen.update;

import io.github.gaming32.modloadingscreen.AgentOptions;
import io.github.gaming32.modloadingscreen.MlsConstants;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;

public final class StartupUpdateCoordinator {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final String UPDATE_PROGRESS_ID = "mls-updater";
    private static final Path UPDATE_LOG_PATH = Paths.get(MlsConstants.UPDATE_CACHE_DIR, "updater.log");

    private StartupUpdateCoordinator() {
    }

    public static void runWithFabricLoader() {
        final Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        run(gameDir, null, null, "fabric-loader");
    }

    public static void runStandalone(AgentOptions agentOptions) {
        final Path gameDir = resolveStandaloneGameDir(agentOptions.getGameDir());
        run(gameDir, agentOptions.getPackwizUrl(), agentOptions.getAutoUpdate(), "java-agent");
    }

    private static void run(Path gameDir, String packwizUrlOverride, Boolean autoUpdateOverride, String source) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        openProgress("检查更新配置", true, 100, 0);
        appendLog(gameDir, "Updater started, source=" + source + ", gameDir=" + gameDir);

        final UpdateConfig config = UpdateConfig.load(gameDir);
        final String packwizUrl = firstNonBlank(packwizUrlOverride, config.getPackwizUrl());
        final boolean autoUpdate = autoUpdateOverride != null ? autoUpdateOverride.booleanValue() : config.isAutoUpdate();
        if (!autoUpdate) {
            logInfo("Auto update disabled for " + source);
            appendLog(gameDir, "Auto update disabled, source=" + source);
            closeProgress();
            return;
        }
        if (packwizUrl == null || packwizUrl.trim().isEmpty()) {
            logInfo("Auto update skipped because packwizUrl is not configured");
            appendLog(gameDir, "Auto update skipped because packwizUrl is empty");
            closeProgress();
            return;
        }

        updateProgress("检查远端版本", true, 100, 10);
        final Optional<String> remoteHash = VersionChecker.fetchRemotePackHash(packwizUrl.trim());
        if (!remoteHash.isPresent()) {
            logWarn("Skip update because remote version cannot be checked");
            appendLog(gameDir, "Remote hash check failed");
            closeProgress();
            return;
        }

        final String remote = remoteHash.get();
        appendLog(gameDir, "Remote hash=" + remote);
        updateProgress("对比本地版本", true, 100, 20);
        final Optional<String> cachedHash = VersionChecker.readCachedHash(gameDir);
        if (!cachedHash.isPresent()) {
            final Optional<String> localPackHash = VersionChecker.readLocalPackHash(gameDir);
            if (localPackHash.isPresent() && remote.equals(localPackHash.get())) {
                VersionChecker.writeCachedHash(gameDir, remote);
                logInfo("No update found, initialized version cache from local pack.toml");
                appendLog(gameDir, "No update found, initialized cache from local pack.toml");
                closeProgress();
                return;
            }
        }

        final String cached = cachedHash.orElse("");
        if (remote.equals(cached)) {
            logInfo("No update found");
            appendLog(gameDir, "No update found, cached hash matches remote");
            closeProgress();
            return;
        }

        logWarn("New version detected, running updater in foreground");
        appendLog(gameDir, "New version detected, start update");
        updateProgress("执行更新", true, 100, 30);

        final boolean updated = runPackwizUpdate(gameDir, packwizUrl.trim());
        if (updated) {
            VersionChecker.writeCachedHash(gameDir, remote);
            appendLog(gameDir, "Update completed successfully");
            updateProgress("更新完成", false, 100, 100);
        } else {
            appendLog(gameDir, "Update failed");
            updateProgress("更新失败，请查看 .updatemod/updater.log", false, 100, 100);
        }

        closeProgress();
    }

    private static boolean runPackwizUpdate(Path gameDir, String packwizUrl) {
        final Path bootstrapJar = gameDir.resolve("packwiz-installer-bootstrap.jar");
        final Path installerJar = gameDir.resolve("packwiz-installer.jar");
        if (!Files.isRegularFile(bootstrapJar) || !Files.isRegularFile(installerJar)) {
            logWarn("Cannot find packwiz-installer-bootstrap.jar or packwiz-installer.jar in game dir");
            appendLog(gameDir, "Missing updater jars: " + bootstrapJar + " and/or " + installerJar);
            return false;
        }

        final List<String> javaCommands = JavaCommandHelper.resolveJavaCommands();
        if (javaCommands.isEmpty()) {
            appendLog(gameDir, "No java command candidate found");
            return false;
        }

        for (final String javaCommand : javaCommands) {
            final List<String> command = new ArrayList<String>();
            command.add(javaCommand);
            command.add("-jar");
            command.add("packwiz-installer-bootstrap.jar");
            command.add("--bootstrap-no-update");
            command.add("--bootstrap-main-jar");
            command.add("packwiz-installer.jar");
            command.add(packwizUrl);
            appendLog(gameDir, "Executing update command: " + command);
            updateProgress("正在更新文件", true, 100, 60);

            try {
                final ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(gameDir.toFile());
                builder.redirectErrorStream(true);
                final Process process = builder.start();

                streamProcessLog(gameDir, process);
                final int exitCode = process.waitFor();
                appendLog(gameDir, "Update process exited with code " + exitCode + " (java=" + javaCommand + ")");
                if (exitCode == 0) {
                    return true;
                }
            } catch (IOException e) {
                appendLog(gameDir, "IOException with java command '" + javaCommand + "': " + e.getMessage());
                if (JavaCommandHelper.isPermissionDenied(e) && JavaCommandHelper.ensureExecutable(javaCommand)) {
                    appendLog(gameDir, "Permission fixed for java command, retrying: " + javaCommand);
                    try {
                        final ProcessBuilder retryBuilder = new ProcessBuilder(command);
                        retryBuilder.directory(gameDir.toFile());
                        retryBuilder.redirectErrorStream(true);
                        final Process retryProcess = retryBuilder.start();
                        streamProcessLog(gameDir, retryProcess);
                        if (retryProcess.waitFor() == 0) {
                            return true;
                        }
                    } catch (Exception retryError) {
                        appendLog(gameDir, "Retry failed: " + retryError.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendLog(gameDir, "Update process interrupted");
                return false;
            }
        }
        return false;
    }

    private static void streamProcessLog(Path gameDir, Process process) {
        try {
            final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(gameDir, "[packwiz] " + line);
            }
        } catch (IOException e) {
            appendLog(gameDir, "Failed reading updater process output: " + e.getMessage());
        }
    }

    private static Path resolveStandaloneGameDir(Path fromAgentOptions) {
        if (fromAgentOptions != null) {
            return fromAgentOptions;
        }

        final String fromProperty = System.getProperty("mls.gameDir");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return Paths.get(fromProperty).toAbsolutePath().normalize();
        }

        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static void appendLog(Path gameDir, String message) {
        try {
            final Path logPath = gameDir.resolve(UPDATE_LOG_PATH);
            Files.createDirectories(logPath.getParent());
            Files.write(
                logPath,
                (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    private static void openProgress(String title, boolean indeterminate, int max, int value) {
        invokeLoadingScreen("createCustomProgressBar", new Class<?>[]{String.class, String.class, int.class},
            UPDATE_PROGRESS_ID, title, Integer.valueOf(max));
        updateProgress(title, indeterminate, max, value);
    }

    private static void updateProgress(String title, boolean indeterminate, int max, int value) {
        invokeLoadingScreen("customProgressBarOp", new Class<?>[]{String[].class},
            (Object)new String[]{UPDATE_PROGRESS_ID, "maximum", Integer.toString(max)});
        invokeLoadingScreen("customProgressBarOp", new Class<?>[]{String[].class},
            (Object)new String[]{UPDATE_PROGRESS_ID, "indeterminate", Boolean.toString(indeterminate)});
        invokeLoadingScreen("customProgressBarOp", new Class<?>[]{String[].class},
            (Object)new String[]{UPDATE_PROGRESS_ID, "title", title});
        invokeLoadingScreen("customProgressBarOp", new Class<?>[]{String[].class},
            (Object)new String[]{UPDATE_PROGRESS_ID, "progress", Integer.toString(value)});
    }

    private static void closeProgress() {
        invokeLoadingScreen("customProgressBarOp", new Class<?>[]{String[].class},
            (Object)new String[]{UPDATE_PROGRESS_ID, "close"});
    }

    private static void invokeLoadingScreen(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            final Class<?> loadingScreenClass = Class.forName(
                "io.github.gaming32.modloadingscreen.ActualLoadingScreen",
                false,
                ClassLoader.getSystemClassLoader()
            );
            final Method method = loadingScreenClass.getDeclaredMethod(methodName, parameterTypes);
            method.invoke(null, args);
        } catch (Throwable ignored) {
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    public static void logInfo(String message) {
        System.out.println("[ModLoadingScreen] [Updater] " + message);
    }

    public static void logWarn(String message) {
        System.err.println("[ModLoadingScreen] [Updater] " + message);
    }

    public static void logError(String message, Throwable throwable) {
        System.err.println("[ModLoadingScreen] [Updater] " + message);
        if (throwable != null) {
            throwable.printStackTrace(System.err);
        }
    }
}
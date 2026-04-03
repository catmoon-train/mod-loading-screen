package io.github.gaming32.modloadingscreen.update;

import io.github.gaming32.modloadingscreen.AgentOptions;
import io.github.gaming32.modloadingscreen.MlsConstants;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

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
        try {
            appendLog(gameDir, "Starting embedded packwiz update for " + packwizUrl);
            updateProgress("正在初始化更新器", true, 100, 35);
            PackwizUpdaterBridge.runUpdate(gameDir, packwizUrl);
            appendLog(gameDir, "Embedded packwiz update completed successfully");
            return true;
        } catch (Throwable t) {
            appendLog(gameDir, "Embedded packwiz update failed: " + t.getMessage());
            logError("Embedded packwiz update failed", t);
            return false;
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

    static void appendLog(Path gameDir, String message) {
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

    static void reportPackwizProgress(Path gameDir, String message, boolean hasProgress, int progressValue, int progressTotal) {
        final String normalizedMessage = message == null || message.trim().isEmpty() ? "正在更新文件" : message.trim();
        if (hasProgress && progressTotal > 0) {
            final int clampedProgress = Math.max(0, Math.min(progressValue, progressTotal));
            final String displayMessage = '(' + Integer.toString(clampedProgress) + '/' + progressTotal + ") " + normalizedMessage;
            appendLog(gameDir, "[packwiz] " + displayMessage);
            updateProgress(displayMessage, false, progressTotal, clampedProgress);
            return;
        }

        appendLog(gameDir, "[packwiz] " + normalizedMessage);
        updateProgress(normalizedMessage, true, 100, 0);
    }

    static void reportPackwizFailure(Path gameDir, String message, Throwable throwable) {
        appendLog(gameDir, "[packwiz] ERROR: " + message);
        logError(message, throwable);
    }

    static void reportPackwizNotice(Path gameDir, String message) {
        appendLog(gameDir, "[packwiz] " + message);
        logInfo(message);
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
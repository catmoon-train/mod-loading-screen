package io.github.gaming32.modloadingscreen.update;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class JavaCommandHelper {
    private JavaCommandHelper() {
    }

    public static List<String> resolveJavaCommands() {
        final LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        addJavaFromHome(System.getenv("JAVA_HOME"), candidates);
        addJavaFromHome(System.getProperty("java.home"), candidates);
        addCurrentJavaCommand(candidates);
        addCandidate(candidates, "java");
        return new ArrayList<String>(candidates);
    }

    public static boolean ensureExecutable(String command) {
        final Optional<Path> commandPath = toAbsoluteExistingPath(command);
        if (!commandPath.isPresent()) {
            return false;
        }

        final Path path = commandPath.get();
        try {
            if (Files.isExecutable(path)) {
                return true;
            }

            try {
                final Set<PosixFilePermission> currentPermissions = Files.getPosixFilePermissions(path);
                final Set<PosixFilePermission> updatedPermissions = EnumSet.copyOf(currentPermissions);
                updatedPermissions.add(PosixFilePermission.OWNER_EXECUTE);
                updatedPermissions.add(PosixFilePermission.GROUP_EXECUTE);
                updatedPermissions.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(path, updatedPermissions);
            } catch (UnsupportedOperationException ignored) {
                if (!path.toFile().setExecutable(true, false)) {
                    return false;
                }
            }

            return Files.isExecutable(path);
        } catch (IOException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static boolean isPermissionDenied(IOException exception) {
        final String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        final String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("permission denied")
            || normalized.contains("error=13")
            || normalized.contains("eacces");
    }

    private static void addCurrentJavaCommand(LinkedHashSet<String> candidates) {
        try {
            final Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
            final Method currentMethod = processHandleClass.getMethod("current");
            final Object currentHandle = currentMethod.invoke(null);
            final Method infoMethod = processHandleClass.getMethod("info");
            final Object info = infoMethod.invoke(currentHandle);
            final Method commandMethod = info.getClass().getMethod("command");
            final Object optional = commandMethod.invoke(info);
            final Method isPresentMethod = optional.getClass().getMethod("isPresent");
            final Boolean isPresent = (Boolean)isPresentMethod.invoke(optional);
            if (Boolean.TRUE.equals(isPresent)) {
                final Method getMethod = optional.getClass().getMethod("get");
                addCandidate(candidates, String.valueOf(getMethod.invoke(optional)));
            }
        } catch (Exception ignored) {
        }
    }

    private static void addJavaFromHome(String javaHome, LinkedHashSet<String> candidates) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return;
        }

        try {
            final Path javaHomePath = java.nio.file.Paths.get(javaHome);
            final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            final String executable = windows ? "java.exe" : "java";
            final Path javaBinary = javaHomePath.resolve("bin").resolve(executable);
            if (Files.isRegularFile(javaBinary)) {
                addCandidate(candidates, javaBinary.toString());
            }
        } catch (Exception ignored) {
        }
    }

    private static void addCandidate(LinkedHashSet<String> candidates, String command) {
        if (command == null) {
            return;
        }

        final String normalized = command.trim();
        if (!normalized.isEmpty()) {
            candidates.add(normalized);
        }
    }

    private static Optional<Path> toAbsoluteExistingPath(String command) {
        if (command == null || command.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            final Path path = java.nio.file.Paths.get(command);
            if (!path.isAbsolute() || !Files.exists(path)) {
                return Optional.empty();
            }
            return Optional.of(path);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
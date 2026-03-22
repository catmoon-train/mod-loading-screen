package io.github.gaming32.modloadingscreen.update;

import io.github.gaming32.modloadingscreen.MlsConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

public final class VersionChecker {
    private static final String HASH_FILE_NAME = "packwiz.hash";

    private VersionChecker() {
    }

    public static Optional<String> fetchRemotePackHash(String packwizUrl) {
        if (packwizUrl == null || packwizUrl.trim().isEmpty()) {
            return Optional.empty();
        }

        HttpURLConnection httpConnection = null;
        try {
            final URLConnection rawConnection = new URL(packwizUrl).openConnection();
            rawConnection.setConnectTimeout(10000);
            rawConnection.setReadTimeout(15000);
            if (rawConnection instanceof HttpURLConnection) {
                httpConnection = (HttpURLConnection)rawConnection;
                httpConnection.setInstanceFollowRedirects(true);
                httpConnection.setRequestMethod("GET");
                final int statusCode = httpConnection.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    StartupUpdateCoordinator.logWarn("Version check failed, HTTP " + statusCode);
                    return Optional.empty();
                }
            }

            try (InputStream is = rawConnection.getInputStream()) {
                final byte[] body = readFully(is);
                if (body.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(sha256Hex(body));
            }
        } catch (Exception e) {
            StartupUpdateCoordinator.logError("Failed to fetch remote pack.toml", e);
            return Optional.empty();
        } finally {
            if (httpConnection != null) {
                httpConnection.disconnect();
            }
        }
    }

    public static Optional<String> readCachedHash(Path gameDir) {
        final Path hashFile = cacheFile(gameDir);
        if (!Files.isRegularFile(hashFile)) {
            final Path legacyHashFile = gameDir.resolve(".updatemod").resolve(HASH_FILE_NAME);
            if (!Files.isRegularFile(legacyHashFile)) {
                return Optional.empty();
            }
            return readHash(legacyHashFile);
        }
        return readHash(hashFile);
    }

    public static Optional<String> readLocalPackHash(Path gameDir) {
        final Path localPack = gameDir.resolve("pack.toml");
        if (!Files.isRegularFile(localPack)) {
            return Optional.empty();
        }

        try {
            return Optional.of(sha256Hex(Files.readAllBytes(localPack)));
        } catch (IOException e) {
            StartupUpdateCoordinator.logError("Failed to read local pack.toml hash", e);
            return Optional.empty();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static void writeCachedHash(Path gameDir, String hash) {
        final Path hashFile = cacheFile(gameDir);
        try {
            Files.createDirectories(hashFile.getParent());
            Files.write(
                hashFile,
                (hash + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            StartupUpdateCoordinator.logError("Failed to write cached hash", e);
        }
    }

    private static Optional<String> readHash(Path hashFile) {
        try {
            final String hash = new String(Files.readAllBytes(hashFile), StandardCharsets.UTF_8).trim();
            return hash.isEmpty() ? Optional.<String>empty() : Optional.of(hash);
        } catch (IOException e) {
            StartupUpdateCoordinator.logError("Failed to read cached hash", e);
            return Optional.empty();
        }
    }

    private static Path cacheFile(Path gameDir) {
        return gameDir.resolve(MlsConstants.UPDATE_CACHE_DIR).resolve(HASH_FILE_NAME);
    }

    private static byte[] readFully(InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256Hex(byte[] input) throws NoSuchAlgorithmException {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        final StringBuilder builder = new StringBuilder(digest.length * 2);
        for (final byte b : digest) {
            final int unsigned = b & 0xff;
            if (unsigned < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(unsigned));
        }
        return builder.toString();
    }
}
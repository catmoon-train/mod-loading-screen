package io.github.gaming32.modloadingscreen.update;

import io.github.gaming32.modloadingscreen.MlsConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateConfig {
    private static final String LEGACY_CONFIG_DIR = ".updatemod";
    private static final String LEGACY_CONFIG_FILE = "config.json";
    private static final String PACKWIZ_URL_KEY = "packwizUrl";
    private static final String AUTO_UPDATE_KEY = "autoUpdate";

    private final String packwizUrl;
    private final boolean autoUpdate;

    public UpdateConfig(String packwizUrl, boolean autoUpdate) {
        this.packwizUrl = packwizUrl;
        this.autoUpdate = autoUpdate;
    }

    public static UpdateConfig load(Path gameDir) {
        final Path configPath = gameDir.resolve(MlsConstants.UPDATE_CONFIG_PATH);
        try {
            Files.createDirectories(configPath.getParent());

            final UpdateConfig legacyConfig = readLegacyConfig(gameDir).orElse(defaults());
            final Optional<UpdateConfig> propertiesConfig = Files.isRegularFile(configPath)
                ? Optional.of(readProperties(configPath))
                : Optional.empty();

            final UpdateConfig merged = merge(propertiesConfig.orElse(null), legacyConfig);
            writeDefaultsIfNecessary(configPath, merged);
            return merged;
        } catch (Exception e) {
            StartupUpdateCoordinator.logError("Failed to load update config", e);
            return defaults();
        }
    }

    public String getPackwizUrl() {
        return packwizUrl;
    }

    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    private static UpdateConfig defaults() {
        return new UpdateConfig("", true);
    }

    private static UpdateConfig readProperties(Path configPath) throws IOException {
        final Properties properties = new Properties();
        try (InputStream is = Files.newInputStream(configPath)) {
            properties.load(is);
        }

        return new UpdateConfig(
            properties.getProperty(PACKWIZ_URL_KEY, "").trim(),
            Boolean.parseBoolean(properties.getProperty(AUTO_UPDATE_KEY, "false"))
        );
    }

    private static Optional<UpdateConfig> readLegacyConfig(Path gameDir) {
        final Path legacyPath = gameDir.resolve(LEGACY_CONFIG_DIR).resolve(LEGACY_CONFIG_FILE);
        try {
            final String json = new String(Files.readAllBytes(legacyPath), StandardCharsets.UTF_8);
            final String packwizUrl = readJsonString(json, PACKWIZ_URL_KEY).orElse("");
            final boolean autoUpdate = readJsonBoolean(json, AUTO_UPDATE_KEY).orElse(Boolean.FALSE).booleanValue();
            return Optional.of(new UpdateConfig(packwizUrl, autoUpdate));
        } catch (NoSuchFileException ignored) {
            return Optional.empty();
        } catch (Exception e) {
            StartupUpdateCoordinator.logError("Failed to load legacy update config", e);
            return Optional.empty();
        }
    }

    private static void writeDefaultsIfNecessary(Path configPath, UpdateConfig config) throws IOException {
        if (Files.isRegularFile(configPath)) {
            return;
        }

        final Properties properties = new Properties();
        properties.setProperty(PACKWIZ_URL_KEY, config.getPackwizUrl());
        properties.setProperty(AUTO_UPDATE_KEY, Boolean.toString(config.isAutoUpdate()));

        try (OutputStream os = Files.newOutputStream(
            configPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )) {
            properties.store(
                os,
                "Set autoUpdate=true and provide a packwizUrl to enable startup update checks."
            );
        }
    }

    private static UpdateConfig merge(UpdateConfig propertiesConfig, UpdateConfig legacyConfig) {
        if (propertiesConfig == null) {
            return legacyConfig;
        }

        final String mergedUrl = !propertiesConfig.getPackwizUrl().isEmpty()
            ? propertiesConfig.getPackwizUrl()
            : legacyConfig.getPackwizUrl();

        final boolean mergedAutoUpdate;
        if (!propertiesConfig.getPackwizUrl().isEmpty()) {
            mergedAutoUpdate = propertiesConfig.isAutoUpdate();
        } else {
            mergedAutoUpdate = propertiesConfig.isAutoUpdate() || legacyConfig.isAutoUpdate();
        }

        return new UpdateConfig(mergedUrl, mergedAutoUpdate);
    }

    private static Optional<String> readJsonString(String json, String key) {
        final Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        final Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
    }

    private static Optional<Boolean> readJsonBoolean(String json, String key) {
        final Pattern pattern = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)",
            Pattern.CASE_INSENSITIVE
        );
        final Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(Boolean.valueOf(Boolean.parseBoolean(matcher.group(1))));
    }
}
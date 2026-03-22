package io.github.gaming32.modloadingscreen;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AgentOptions {
    private final Path gameDir;
    private final String packwizUrl;
    private final Boolean autoUpdate;

    private AgentOptions(Path gameDir, String packwizUrl, Boolean autoUpdate) {
        this.gameDir = gameDir;
        this.packwizUrl = packwizUrl;
        this.autoUpdate = autoUpdate;
    }

    public static AgentOptions parse(String rawArgs) {
        Path gameDir = null;
        String packwizUrl = null;
        Boolean autoUpdate = null;

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            final String[] options = rawArgs.split(";");
            for (final String option : options) {
                final int separator = option.indexOf('=');
                if (separator < 0) continue;

                final String key = option.substring(0, separator).trim();
                final String value = option.substring(separator + 1).trim();
                if (key.isEmpty() || value.isEmpty()) continue;

                if ("gameDir".equalsIgnoreCase(key)) {
                    gameDir = Paths.get(value).toAbsolutePath().normalize();
                } else if ("packwizUrl".equalsIgnoreCase(key)) {
                    packwizUrl = value;
                } else if ("autoUpdate".equalsIgnoreCase(key)) {
                    autoUpdate = Boolean.valueOf(Boolean.parseBoolean(value));
                }
            }
        }

        return new AgentOptions(gameDir, packwizUrl, autoUpdate);
    }

    public Path getGameDir() {
        return gameDir;
    }

    public String getPackwizUrl() {
        return packwizUrl;
    }

    public Boolean getAutoUpdate() {
        return autoUpdate;
    }
}
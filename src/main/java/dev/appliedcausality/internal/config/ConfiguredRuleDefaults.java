package dev.appliedcausality.internal.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Installs active first-run rules without replacing pack-authored files. */
final class ConfiguredRuleDefaults {
    private static final String RESOURCE_ROOT = "/applied_causality/config_defaults/appliedcausality/rules/";
    private static final String SMELTING = "smelting.toml";
    private static final String CRUSHING = "mekanism-crushing.toml";
    private static final String INSCRIBER = "ae2-inscriber.toml";

    private ConfiguredRuleDefaults() {
    }

    static void install(Path forgeConfigDir) {
        try {
            Path config = forgeConfigDir.toAbsolutePath().normalize();
            ensureDirectory(config);
            Path parent = config.resolve("appliedcausality");
            ensureDirectory(parent);
            Path rules = parent.resolve("rules");
            ensureDirectory(rules);
            seedOnce(parent, rules, SMELTING, ".smelting_seeded_v1");
            seedOnce(parent, rules, CRUSHING, ".mekanism_crushing_seeded_v1");
            seedOnce(parent, rules, INSCRIBER, ".ae2_inscriber_seeded_v1");
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("Could not install Applied Causality default rules", exception);
        }
    }

    private static void seedOnce(Path parent, Path rules, String filename, String markerName)
            throws IOException {
        Path marker = parent.resolve(markerName);
        Path target = rules.resolve(filename);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream source = ConfiguredRuleDefaults.class.getResourceAsStream(RESOURCE_ROOT + filename)) {
                if (source == null) {
                    throw new IOException("Missing bundled default rule: " + filename);
                }
                Files.write(target, source.readAllBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        }
        Files.createFile(marker);
    }

    private static void ensureDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Default-rule directory is not a regular directory: " + path);
            }
        } else {
            Files.createDirectory(path);
        }
    }
}

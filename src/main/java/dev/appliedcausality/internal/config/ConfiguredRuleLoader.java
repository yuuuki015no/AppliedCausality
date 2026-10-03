package dev.appliedcausality.internal.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Side-effect-free rule preparation. No RecipeManager or Provider is accessed here. */
public final class ConfiguredRuleLoader {
    private static final Path RELATIVE_ROOT = Path.of("appliedcausality", "rules");
    private static final Pattern FILE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*\\.toml");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Set<String> ROOT_KEYS = Set.of(
            "version", "requires_mod", "recipe_types", "installers", "grants", "unlock_rules");
    private static final Set<String> PROFILE_KEYS = Set.of(
            "id", "recipe_type", "semantics", "process_shape", "process_family",
            "machine_profile", "exclude_recipes");
    private static final Set<String> INSTALLER_KEYS = Set.of("id", "item", "item_tag", "grants");
    private static final Set<String> GRANT_KEYS = Set.of("id");
    private static final Set<String> UNLOCK_KEYS = Set.of(
            "id", "recipe_id", "recipe_type", "source_mod", "process_shape",
            "process_family", "machine_profile", "requires");

    private ConfiguredRuleLoader() {
    }

    public static LoadResult load(Path forgeConfigDir, Predicate<ResourceLocation> recipeTypeExists,
            Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf) {
        return loadWithMods(forgeConfigDir, recipeTypeExists, itemExists, recipeTypeOf,
                mod -> true);
    }

    public static LoadResult loadWithMods(Path forgeConfigDir, Predicate<ResourceLocation> recipeTypeExists,
            Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf,
            Predicate<String> modLoaded) {
        return load(forgeConfigDir, recipeTypeExists, itemExists, recipeTypeOf, modLoaded,
                Files::list, Files::isSymbolicLink);
    }

    static LoadResult load(Path forgeConfigDir, Predicate<ResourceLocation> recipeTypeExists,
            Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf,
            DirectoryOpener directoryOpener) {
        return load(forgeConfigDir, recipeTypeExists, itemExists, recipeTypeOf,
                mod -> true, directoryOpener, Files::isSymbolicLink);
    }

    static LoadResult load(Path forgeConfigDir, Predicate<ResourceLocation> recipeTypeExists,
            Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf,
            DirectoryOpener directoryOpener, Predicate<Path> isSymbolicLink) {
        Objects.requireNonNull(forgeConfigDir);
        Objects.requireNonNull(recipeTypeExists);
        Objects.requireNonNull(itemExists);
        Objects.requireNonNull(recipeTypeOf);
        Objects.requireNonNull(directoryOpener);
        Objects.requireNonNull(isSymbolicLink);
        return load(forgeConfigDir, recipeTypeExists, itemExists, recipeTypeOf,
                mod -> true, directoryOpener, isSymbolicLink);
    }

    private static LoadResult load(Path forgeConfigDir, Predicate<ResourceLocation> recipeTypeExists,
            Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf,
            Predicate<String> modLoaded, DirectoryOpener directoryOpener, Predicate<Path> isSymbolicLink) {
        Objects.requireNonNull(modLoaded);
        RuleDiagnostics diagnostics = new RuleDiagnostics();
        Map<ResourceLocation, ConfiguredRuleSnapshot.RecipeTypeProfile> profiles = new LinkedHashMap<>();
        Map<ResourceLocation, ConfiguredRuleSnapshot.Installer> installers = new LinkedHashMap<>();
        Set<ResourceLocation> grants = new LinkedHashSet<>();
        Map<ResourceLocation, ConfiguredRuleSnapshot.UnlockRule> unlocks = new LinkedHashMap<>();
        Set<ResourceLocation> allIds = new LinkedHashSet<>();
        try {
            Path config = forgeConfigDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(config) || isSymbolicLink.test(config)) {
                return failed(diagnostics, "<rules>", "Invalid Forge config directory");
            }
            Path root = config.resolve(RELATIVE_ROOT).normalize();
            if (!root.startsWith(config) || root.equals(config)
                    || !RELATIVE_ROOT.equals(config.relativize(root))) {
                return failed(diagnostics, "<rules>", "Rules root escapes Forge config directory");
            }
            // Existing parent symlinks must not redirect an apparently fixed root.
            Path parent = root.getParent();
            if (isSymbolicLink.test(parent) || isSymbolicLink.test(root)) {
                return failed(diagnostics, "<rules>", "Rules root contains a symbolic link");
            }
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return failed(diagnostics, "<rules>", "Invalid rules parent directory");
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return new LoadResult(ConfiguredRuleSnapshot.empty(), List.of());
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || root.toRealPath().equals(config.toRealPath())
                    || !root.toRealPath().startsWith(config.toRealPath())) {
                return failed(diagnostics, "<rules>", "Invalid rules directory");
            }
            List<Path> candidates;
            try (Stream<Path> stream = directoryOpener.open(root)) {
                candidates = stream.filter(path -> path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT).endsWith(".toml")
                                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                if (!FILE_NAME.matcher(name).matches()
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || isSymbolicLink.test(file)
                        || !file.toRealPath().startsWith(root.toRealPath())) {
                    diagnostics.add(name, "Invalid rule filename or file type");
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Config document = new TomlParser().parse(reader);
                    parseDocument(document, name, diagnostics, allIds, profiles, installers, grants, unlocks,
                            recipeTypeExists, itemExists, recipeTypeOf, modLoaded);
                } catch (RuntimeException exception) {
                    diagnostics.add(name, "Invalid TOML or rule value");
                } catch (IOException exception) {
                    diagnostics.add(name, "Cannot read rule file");
                }
            }
        } catch (IOException | UncheckedIOException | SecurityException exception) {
            diagnostics.add("<rules>", "Cannot enumerate rules directory");
        }
        for (var installer : installers.values()) {
            for (ResourceLocation grant : installer.grants()) {
                if (!grants.contains(grant)) {
                    diagnostics.add("<rules>", "Installer refers to an undefined Grant ID: " + grant);
                }
            }
        }
        for (var rule : unlocks.values()) {
            for (ResourceLocation grant : rule.requires()) {
                if (!grants.contains(grant)) {
                    diagnostics.add("<rules>", "Unlock Rule refers to an undefined Grant ID: " + grant);
                }
            }
        }
        return diagnostics.hasErrors()
                ? new LoadResult(null, diagnostics.entries())
                : new LoadResult(new ConfiguredRuleSnapshot(0, profiles, installers, grants, unlocks), List.of());
    }

    @FunctionalInterface
    interface DirectoryOpener {
        Stream<Path> open(Path root) throws IOException;
    }

    private static LoadResult failed(RuleDiagnostics diagnostics, String name, String reason) {
        diagnostics.add(name, reason);
        return new LoadResult(null, diagnostics.entries());
    }

    private static void parseDocument(Config document, String name, RuleDiagnostics diagnostics,
            Set<ResourceLocation> allIds,
            Map<ResourceLocation, ConfiguredRuleSnapshot.RecipeTypeProfile> profiles,
            Map<ResourceLocation, ConfiguredRuleSnapshot.Installer> installers,
            Set<ResourceLocation> grants,
            Map<ResourceLocation, ConfiguredRuleSnapshot.UnlockRule> unlocks,
            Predicate<ResourceLocation> recipeTypeExists, Predicate<ResourceLocation> itemExists,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeTypeOf,
            Predicate<String> modLoaded) {
        checkKeys(document, ROOT_KEYS);
        Object version = document.valueMap().get("version");
        if (!((version instanceof Integer || version instanceof Long)
                && ((Number) version).longValue() == 1L)) {
            throw new IllegalArgumentException("Unsupported rule schema version");
        }
        String requiredMod = optionalString(document, "requires_mod");
        if (requiredMod != null) {
            if (!MOD_ID.matcher(requiredMod).matches()) {
                throw new IllegalArgumentException("Invalid required mod");
            }
            if (!modLoaded.test(requiredMod)) {
                return;
            }
        }
        for (Config table : tables(document, "recipe_types")) {
            checkKeys(table, PROFILE_KEYS);
            ResourceLocation id = id(table, "id");
            unique(id, allIds, diagnostics, name);
            ResourceLocation type = id(table, "recipe_type");
            if (!recipeTypeExists.test(type)) {
                diagnostics.add(name, "Unknown Recipe Type: " + type);
            }
            if (!"standard_static_item".equals(string(table, "semantics"))) {
                throw new IllegalArgumentException("Unsupported semantics");
            }
            List<ResourceLocation> exclusions = ids(table, "exclude_recipes");
            for (ResourceLocation recipeId : exclusions) {
                Optional<ResourceLocation> actualType = recipeTypeOf.apply(recipeId);
                if (actualType.isEmpty()) {
                    diagnostics.add(name, "Unknown excluded recipe: " + recipeId);
                } else if (!actualType.orElseThrow().equals(type)) {
                    diagnostics.add(name, "Excluded recipe has a different Recipe Type: " + recipeId);
                }
            }
            var profile = new ConfiguredRuleSnapshot.RecipeTypeProfile(id, type,
                    id(table, "process_shape"), id(table, "process_family"),
                    optionalId(table, "machine_profile"), new LinkedHashSet<>(exclusions));
            if (profiles.putIfAbsent(type, profile) != null) {
                diagnostics.add(name, "Duplicate canonical Recipe Type");
            }
        }
        for (Config table : tables(document, "grants")) {
            checkKeys(table, GRANT_KEYS);
            ResourceLocation id = id(table, "id");
            unique(id, allIds, diagnostics, name);
            grants.add(id);
        }
        for (Config table : tables(document, "installers")) {
            checkKeys(table, INSTALLER_KEYS);
            ResourceLocation id = id(table, "id");
            unique(id, allIds, diagnostics, name);
            ResourceLocation item = optionalId(table, "item");
            ResourceLocation itemTag = optionalId(table, "item_tag");
            if ((item == null) == (itemTag == null)) {
                throw new IllegalArgumentException("Installer needs exactly one item or item_tag");
            }
            if (item != null && !itemExists.test(item)) {
                diagnostics.add(name, "Unknown Installer item: " + item);
            }
            installers.put(id, new ConfiguredRuleSnapshot.Installer(id, item, itemTag,
                    new LinkedHashSet<>(ids(table, "grants"))));
        }
        for (Config table : tables(document, "unlock_rules")) {
            checkKeys(table, UNLOCK_KEYS);
            ResourceLocation id = id(table, "id");
            unique(id, allIds, diagnostics, name);
            String mod = optionalString(table, "source_mod");
            if (mod != null && !MOD_ID.matcher(mod).matches()) {
                throw new IllegalArgumentException("Invalid source mod");
            }
            if (mod == null && !table.valueMap().containsKey("recipe_id")
                    && !table.valueMap().containsKey("recipe_type")
                    && !table.valueMap().containsKey("process_shape")
                    && !table.valueMap().containsKey("process_family")
                    && !table.valueMap().containsKey("machine_profile")) {
                throw new IllegalArgumentException("Unlock Rule has no matcher");
            }
            ResourceLocation ruleType = optionalId(table, "recipe_type");
            if (ruleType != null && !recipeTypeExists.test(ruleType)) {
                diagnostics.add(name, "Unknown Unlock Rule Recipe Type: " + ruleType);
            }
            ResourceLocation recipeId = optionalId(table, "recipe_id");
            if (recipeId != null) {
                Optional<ResourceLocation> actualType = recipeTypeOf.apply(recipeId);
                if (actualType.isEmpty()) {
                    diagnostics.add(name, "Unknown Unlock Rule recipe: " + recipeId);
                } else if (ruleType != null && !actualType.orElseThrow().equals(ruleType)) {
                    diagnostics.add(name, "Unlock Rule recipe has a different Recipe Type: " + recipeId);
                }
            }
            unlocks.put(id, new ConfiguredRuleSnapshot.UnlockRule(id,
                    recipeId, ruleType, mod,
                    optionalId(table, "process_shape"), optionalId(table, "process_family"),
                    optionalId(table, "machine_profile"), ids(table, "requires")));
        }
    }

    private static void unique(ResourceLocation id, Set<ResourceLocation> ids,
            RuleDiagnostics diagnostics, String name) {
        if (!ids.add(id)) {
            diagnostics.add(name, "Duplicate rule ID");
        }
    }

    private static void checkKeys(Config table, Set<String> allowed) {
        for (String key : table.valueMap().keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown rule key");
            }
        }
    }

    private static List<Config> tables(Config parent, String key) {
        Object raw = parent.valueMap().get(key);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected table array");
        }
        List<Config> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Config table)) {
                throw new IllegalArgumentException("Expected table");
            }
            result.add(table);
        }
        return result;
    }

    private static String string(Config table, String key) {
        Object value = table.valueMap().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid string");
        }
        return text;
    }

    private static String optionalString(Config table, String key) {
        return table.valueMap().containsKey(key) ? string(table, key) : null;
    }

    private static ResourceLocation id(Config table, String key) {
        String text = string(table, key);
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !text.equals(id.toString()) || !text.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID");
        }
        return id;
    }

    private static ResourceLocation optionalId(Config table, String key) {
        return table.valueMap().containsKey(key) ? id(table, key) : null;
    }

    private static List<ResourceLocation> ids(Config table, String key) {
        Object raw = table.valueMap().get(key);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected ID list");
        }
        List<ResourceLocation> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Expected ID");
            }
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null || !text.equals(id.toString()) || !text.contains(":")) {
                throw new IllegalArgumentException("Invalid namespaced ID");
            }
            if (result.contains(id)) {
                throw new IllegalArgumentException("Duplicate ID in list");
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    public record LoadResult(ConfiguredRuleSnapshot snapshot, List<String> diagnostics) {
        public LoadResult {
            diagnostics = List.copyOf(diagnostics);
            if ((snapshot == null) == diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Expected snapshot or diagnostics");
            }
        }

        public boolean valid() {
            return snapshot != null;
        }
    }
}

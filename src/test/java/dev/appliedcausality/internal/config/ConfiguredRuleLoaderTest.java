package dev.appliedcausality.internal.config;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredRuleLoaderTest {
    @TempDir Path config;

    private static final String PROFILE = """
            version = 1
            [[recipe_types]]
            id = "test:smelting_profile"
            recipe_type = "minecraft:smelting"
            semantics = "standard_static_item"
            process_shape = "test:item_to_item"
            process_family = "test:smelting"
            exclude_recipes = ["test:special"]
            """;

    @Test
    void validMultiFileRulesAreImmutableAndCrossReferencesResolve() throws IOException {
        rule("z_progression.toml", """
                version = 1
                [[grants]]
                id = "test:basic"
                [[installers]]
                id = "test:installer"
                item = "minecraft:stone"
                grants = ["test:basic"]
                [[unlock_rules]]
                id = "test:gate"
                recipe_type = "minecraft:smelting"
                requires = ["test:basic"]
                """);
        rule("a_recipe.toml", PROFILE);
        var result = load();

        assertTrue(result.valid(), result.diagnostics().toString());
        assertEquals(1, result.snapshot().recipeTypes().size());
        assertEquals(1, result.snapshot().installers().size());
        assertEquals(1, result.snapshot().unlockRules().size());
        assertTrue(result.snapshot().recipeTypes().get(id("minecraft:smelting"))
                .excludedRecipes().contains(id("test:special")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.snapshot().recipeTypes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.snapshot().grants().clear());
    }

    @Test
    void installerAcceptsExactlyOneItemOrItemTag() throws IOException {
        rule("tag.toml", """
                version = 1
                [[installers]]
                id = "test:tag_installer"
                item_tag = "minecraft:planks"
                """);
        var valid = load();
        assertTrue(valid.valid(), valid.diagnostics().toString());
        assertEquals(id("minecraft:planks"), valid.snapshot().installers()
                .get(id("test:tag_installer")).itemTag());

        rule("tag.toml", """
                version = 1
                [[installers]]
                id = "test:tag_installer"
                item = "minecraft:stone"
                item_tag = "minecraft:planks"
                """);
        assertFalse(load().valid());
    }

    @Test
    void mixedValidAndInvalidFilesPublishNothing() throws IOException {
        rule("a.toml", PROFILE);
        rule("b.toml", "version = 1\nunknown = true\n");
        var result = load();
        assertFalse(result.valid());
        assertEquals(List.of("b.toml: Invalid TOML or rule value"), result.diagnostics());
    }

    @Test
    void absentRequiredModSkipsRuleButPresentModValidatesIt() throws IOException {
        rule("optional.toml", """
                version = 1
                requires_mod = "mekanism"
                [[installers]]
                id = "test:crusher"
                item = "mekanism:crusher"
                [[unlock_rules]]
                id = "test:crushing"
                recipe_type = "mekanism:crushing"
                """);
        var absent = ConfiguredRuleLoader.loadWithMods(config,
                type -> false, item -> false, this::recipeTypeOf,
                mod -> false);
        assertTrue(absent.valid(), absent.diagnostics().toString());
        assertTrue(absent.snapshot().installers().isEmpty());
        assertTrue(absent.snapshot().unlockRules().isEmpty());

        var present = ConfiguredRuleLoader.loadWithMods(config,
                type -> false, item -> false, this::recipeTypeOf,
                mod -> true);
        assertFalse(present.valid());
        assertTrue(present.diagnostics().stream().anyMatch(s -> s.contains("Unknown Installer item")));

        rule("optional.toml", "version = 1\nrequires_mod = \"Mekanism\"\n");
        assertFalse(ConfiguredRuleLoader.loadWithMods(config,
                type -> false, item -> false, this::recipeTypeOf, mod -> false).valid());
    }

    @Test
    void duplicateRuleIdAndRecipeTypeRejectWholeLoad() throws IOException {
        rule("a.toml", PROFILE);
        rule("b.toml", PROFILE.replace("test:smelting_profile", "test:other_profile"));
        assertFalse(load().valid());
        assertTrue(load().diagnostics().stream().anyMatch(s -> s.contains("Duplicate canonical Recipe Type")));

        rule("b.toml", PROFILE);
        assertTrue(load().diagnostics().stream().anyMatch(s -> s.contains("Duplicate rule ID")));
    }

    @Test
    void unknownNestedAndCrossReferencedKeysReject() throws IOException {
        rule("a.toml", PROFILE.replace("exclude_recipes", "ignoreChanceOutputs"));
        assertFalse(load().valid());
        rule("a.toml", PROFILE);
        rule("b.toml", """
                version = 1
                [[unlock_rules]]
                id = "test:gate"
                process_shape = "test:item_to_item"
                requires = ["test:missing"]
                """);
        assertTrue(load().diagnostics().stream().anyMatch(s -> s.contains("undefined Grant ID: test:missing")));
    }

    @Test
    void unknownKeysInEveryTableAndIncludeAttemptsReject() throws IOException {
        List<String> invalidTables = List.of(
                "[[grants]]\nid = \"test:grant\"\ninclude = \"other.toml\"\n",
                "[[installers]]\nid = \"test:installer\"\nitem = \"minecraft:stone\"\nmode = \"CONSUME\"\n",
                "[[unlock_rules]]\nid = \"test:rule\"\nsource_mod = \"minecraft\"\ninherit = \"test:other\"\n");
        for (String table : invalidTables) {
            rule("bad.toml", "version = 1\n" + table);
            assertFalse(load().valid(), table);
        }
    }

    @Test
    void exactUnresolvedRecipeAndItemReferencesReject() throws IOException {
        rule("bad.toml", PROFILE.replace("test:special", "test:missing"));
        assertTrue(load().diagnostics().stream().anyMatch(s -> s.contains("Unknown excluded recipe: test:missing")));
        rule("bad.toml", """
                version = 1
                [[installers]]
                id = "test:installer"
                item = "test:missing_item"
                """);
        assertTrue(load().diagnostics().stream().anyMatch(s -> s.contains("Unknown Installer item: test:missing_item")));
        rule("bad.toml", """
                version = 1
                [[unlock_rules]]
                id = "test:rule"
                recipe_id = "test:existing_but_other_type"
                recipe_type = "minecraft:smelting"
                """);
        assertTrue(load().diagnostics().stream()
                .anyMatch(s -> s.contains("Unlock Rule recipe has a different Recipe Type")));
    }

    @Test
    void exclusionMustBelongToConfiguredRecipeType() throws IOException {
        rule("wrong_type.toml", PROFILE.replace("test:special", "test:existing_but_other_type"));
        var result = load();
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream()
                .anyMatch(s -> s.contains("Excluded recipe has a different Recipe Type")));
    }

    @Test
    void enumerationFailureAfterFirstEntryRejectsEntireCandidate() throws IOException {
        rule("a.toml", PROFILE);
        rule("b.toml", "version = 1\n");
        var result = ConfiguredRuleLoader.load(config,
                type -> type.equals(id("minecraft:smelting")),
                item -> item.equals(id("minecraft:stone")),
                this::recipeTypeOf,
                root -> Stream.of(root.resolve("a.toml"), root.resolve("b.toml"))
                        .peek(path -> {
                            if (path.getFileName().toString().equals("b.toml")) {
                                throw new UncheckedIOException(new IOException("injected enumeration failure"));
                            }
                        }));
        assertFalse(result.valid());
        assertEquals(List.of("<rules>: Cannot enumerate rules directory"), result.diagnostics());
    }

    @Test
    void unicodeTomlCandidateRejectsCompleteLoad() throws IOException {
        rule("a.toml", PROFILE);
        rule("é.toml", "version = 1\n");
        var result = load();
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(s -> s.startsWith("é.toml:")));
    }

    @Test
    void symbolicLinkCandidateAndRulesRootReject() throws IOException {
        Path root = config.resolve("appliedcausality/rules");
        Files.createDirectories(root);
        Path outside = config.resolve("outside.toml");
        Files.writeString(outside, PROFILE, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("linked.toml"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable on this host");
        }
        assertFalse(load().valid());
        Files.delete(root.resolve("linked.toml"));
        Files.delete(root);
        Files.createDirectory(config.resolve("elsewhere"));
        Files.createSymbolicLink(root, config.resolve("elsewhere"));
        assertFalse(load().valid());
    }

    @Test
    void symlinkPolicyRejectsCandidateAndRootOnHostsWithoutLinkPrivilege() throws IOException {
        rule("a.toml", PROFILE);
        Path root = config.resolve("appliedcausality/rules").toAbsolutePath().normalize();
        var candidateLink = ConfiguredRuleLoader.load(config,
                type -> type.equals(id("minecraft:smelting")),
                item -> item.equals(id("minecraft:stone")),
                this::recipeTypeOf, Files::list,
                path -> path.equals(root.resolve("a.toml")));
        assertFalse(candidateLink.valid());
        assertTrue(candidateLink.diagnostics().stream().anyMatch(s -> s.startsWith("a.toml:")));

        var rootLink = ConfiguredRuleLoader.load(config,
                type -> type.equals(id("minecraft:smelting")),
                item -> item.equals(id("minecraft:stone")),
                this::recipeTypeOf, Files::list,
                root::equals);
        assertFalse(rootLink.valid());
        assertTrue(rootLink.diagnostics().stream().anyMatch(s -> s.contains("symbolic link")));
    }

    @Test
    void invalidTomlCandidateNameRejectsButNestedDirectoryIsIgnored() throws IOException {
        rule("Upper.TOML", PROFILE);
        assertFalse(load().valid());
        Files.createDirectory(config.resolve("appliedcausality/rules/nested.toml"));
        Files.delete(config.resolve("appliedcausality/rules/Upper.TOML"));
        assertTrue(load().valid());
    }

    @Test
    void missingDirectoryIsEmptyAndRulesRootCannotBeAFile() throws IOException {
        assertTrue(load().valid());
        assertTrue(load().snapshot().recipeTypes().isEmpty());
        Files.createDirectory(config.resolve("appliedcausality"));
        Files.writeString(config.resolve("appliedcausality/rules"), "bad", StandardCharsets.UTF_8);
        assertFalse(load().valid());
    }

    private void rule(String name, String content) throws IOException {
        Path root = config.resolve("appliedcausality/rules");
        Files.createDirectories(root);
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
    }

    private ConfiguredRuleLoader.LoadResult load() {
        return ConfiguredRuleLoader.load(config,
                id -> id.equals(id("minecraft:smelting")),
                id -> id.equals(id("minecraft:stone")),
                this::recipeTypeOf);
    }

    private Optional<ResourceLocation> recipeTypeOf(ResourceLocation recipeId) {
        return recipeId.equals(id("test:special"))
                ? Optional.of(id("minecraft:smelting"))
                : recipeId.equals(id("test:existing_but_other_type"))
                        ? Optional.of(id("minecraft:blasting")) : Optional.empty();
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.tryParse(value);
    }
}

package dev.appliedcausality.internal.config;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredRuleDefaultsTest {
    private static final Set<ResourceLocation> AE2_PRESS_RECIPES = Set.of(
            id("ae2:inscriber/calculation_processor"),
            id("ae2:inscriber/engineering_processor"),
            id("ae2:inscriber/logic_processor"));
    private static final Set<ResourceLocation> AE2_INSCRIBE_RECIPES = Set.of(
            id("ae2:inscriber/calculation_processor_press"),
            id("ae2:inscriber/calculation_processor_print"),
            id("ae2:inscriber/engineering_processor_press"),
            id("ae2:inscriber/engineering_processor_print"),
            id("ae2:inscriber/logic_processor_press"),
            id("ae2:inscriber/logic_processor_print"),
            id("ae2:inscriber/silicon_press"),
            id("ae2:inscriber/silicon_print"));
    @TempDir
    Path config;

    @Test
    void activeDefaultsLoadWithOrWithoutMekanismAndPreservePackEdits() throws IOException {
        ConfiguredRuleDefaults.install(config);

        Path smelting = config.resolve("appliedcausality/rules/smelting.toml");
        Path crushing = config.resolve("appliedcausality/rules/mekanism-crushing.toml");
        Path inscriber = config.resolve("appliedcausality/rules/ae2-inscriber.toml");
        assertTrue(Files.readString(smelting).contains("item = \"minecraft:furnace\""));
        assertTrue(Files.exists(crushing));
        assertTrue(Files.readString(inscriber).contains("item = \"ae2:inscriber\""));
        assertFalse(Files.exists(config.resolve("appliedcausality-common.toml")));
        assertEquals(2, load(false).snapshot().unlockRules().size());
        assertEquals(AE2_INSCRIBE_RECIPES, load(false).snapshot().recipeTypes()
                .get(id("ae2:inscriber")).excludedRecipes());

        String edited = Files.readString(smelting) + "# pack author edit\n";
        Files.writeString(smelting, edited, StandardCharsets.UTF_8);
        ConfiguredRuleDefaults.install(config);
        assertEquals(edited, Files.readString(smelting));

        ConfiguredRuleDefaults.install(config);
        assertEquals(edited, Files.readString(smelting));
        assertTrue(Files.readString(crushing).contains("item = \"mekanism:crusher\""));
        assertEquals(3, load(true).snapshot().unlockRules().size());
        assertEquals(2, load(false).snapshot().unlockRules().size());
    }

    @Test
    void removedRuleIsNotRegeneratedOnLaterLaunch() throws IOException {
        ConfiguredRuleDefaults.install(config);
        Path smelting = config.resolve("appliedcausality/rules/smelting.toml");
        Files.delete(smelting);

        ConfiguredRuleDefaults.install(config);
        assertFalse(Files.exists(smelting));
        assertEquals(1, load(false).snapshot().unlockRules().size());
    }

    @Test
    void preexistingRuleIsNeverOverwritten() throws IOException {
        Path rules = config.resolve("appliedcausality/rules");
        Files.createDirectories(rules);
        Path smelting = rules.resolve("smelting.toml");
        Files.writeString(smelting, "version = 1\n", StandardCharsets.UTF_8);

        ConfiguredRuleDefaults.install(config);
        assertEquals("version = 1\n", Files.readString(smelting));
        assertEquals(1, load(false).snapshot().unlockRules().size());
    }

    @Test
    void excludedRecipesMatchBundledAe2InscriberModes() throws IOException {
        for (ResourceLocation recipe : AE2_INSCRIBE_RECIPES) {
            assertEquals("inscribe", bundledMode(recipe));
        }
        for (ResourceLocation recipe : AE2_PRESS_RECIPES) {
            assertEquals("press", bundledMode(recipe));
        }
    }

    private static String bundledMode(ResourceLocation recipe) throws IOException {
        String path = "/data/ae2/recipes/" + recipe.getPath() + ".json";
        try (var stream = ConfiguredRuleDefaultsTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "Missing AE2 recipe resource: " + path);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("ae2:inscriber", json.get("type").getAsString());
            return json.get("mode").getAsString();
        }
    }

    private ConfiguredRuleLoader.LoadResult load(boolean mekanismLoaded) {
        var result = ConfiguredRuleLoader.loadWithMods(config,
                type -> type.equals(id("minecraft:smelting"))
                        || type.equals(id("ae2:inscriber"))
                        || (mekanismLoaded && type.equals(id("mekanism:crushing"))),
                item -> item.equals(id("minecraft:furnace"))
                        || item.equals(id("ae2:inscriber"))
                        || (mekanismLoaded && item.equals(id("mekanism:crusher"))),
                recipe -> AE2_INSCRIBE_RECIPES.contains(recipe)
                        ? Optional.of(id("ae2:inscriber"))
                        : Optional.empty(),
                mod -> !mod.equals("mekanism") || mekanismLoaded);
        assertTrue(result.valid(), result.diagnostics().toString());
        return result;
    }

    private static ResourceLocation id(String text) {
        return ResourceLocation.tryParse(text);
    }
}

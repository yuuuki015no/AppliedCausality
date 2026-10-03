package dev.appliedcausality.internal.registry;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.config.ConfiguredRuleLoader;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationTestSupport;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.test.TestRecipeLevel;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.recipe.vanilla.VanillaSmeltingRecipeAdapter;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredStandardStaticItemResolverTest {
    private static final ResourceLocation TYPE_ID = ResourceLocation.withDefaultNamespace("smelting");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void containerSpecificRecipeAggregatesIngredientsAndStillRequiresCoreValidation() {
        // This recipe requires StrictContainer for assemble/remaining methods.
        // The configured contract must resolve it from Ingredients and result alone.
        var recipe = recipe("repeat", List.of(Ingredient.of(Items.IRON_ORE),
                Ingredient.of(Items.IRON_ORE)), new ItemStack(Items.IRON_INGOT, 3));
        var pattern = pattern(Items.IRON_ORE.getDefaultInstance(), 2, Items.IRON_INGOT.getDefaultInstance(), 3);
        var candidate = ConfiguredStandardStaticItemResolver.resolve(
                recipe, RegistryAccess.EMPTY, pattern, profile(Set.of())).orElseThrow();

        assertEquals(2L, candidate.inputsPerOperation().get(AEItemKey.of(Items.IRON_ORE)));
        assertEquals(3L, candidate.outputsPerOperation().get(AEItemKey.of(Items.IRON_INGOT)));
        assertEquals(ValidationDiagnostic.VALID, ValidationTestSupport.validate(
                new ProcessCandidate(ConfiguredStandardStaticItemResolver.ID, 0, candidate),
                pattern, ignored -> Optional.of(TYPE_ID)).diagnostic());

        var wrongOutput = pattern(Items.IRON_ORE.getDefaultInstance(), 2,
                Items.GOLD_INGOT.getDefaultInstance(), 3);
        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, ValidationTestSupport.validate(
                new ProcessCandidate(ConfiguredStandardStaticItemResolver.ID, 0, candidate),
                wrongOutput, ignored -> Optional.of(TYPE_ID)).diagnostic());
    }

    @Test
    void exclusionsAndUnconfiguredTypesDoNotProduceCandidates() {
        var recipe = recipe("excluded", List.of(Ingredient.of(Items.IRON_ORE)),
                new ItemStack(Items.IRON_INGOT));
        var snapshot = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of(recipe.getId()))),
                Map.of(), Set.of(), Map.of());
        var visited = new java.util.ArrayList<ResourceLocation>();

        var candidates = ProcessModelResolver.discoverConfiguredCandidates(snapshot,
                type -> type == RecipeType.SMELTING ? List.of(recipe) : List.of(),
                (found, ignored) -> {
                    visited.add(found.getId());
                    return Optional.empty();
                });
        assertTrue(candidates.isEmpty());
        assertTrue(visited.isEmpty());
        assertTrue(ProcessModelResolver.discoverConfiguredCandidates(
                ConfiguredRuleSnapshot.empty(), type -> List.of(recipe),
                (found, ignored) -> {
                    visited.add(found.getId());
                    return Optional.empty();
                }).isEmpty());
        assertTrue(visited.isEmpty());
    }

    @Test
    void unsupportedObservableStateAndFakeInputsReject() {
        var recipe = recipe("state", List.of(Ingredient.of(Items.IRON_ORE)),
                new ItemStack(Items.IRON_INGOT));
        var ordinary = pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                Items.IRON_INGOT.getDefaultInstance(), 1);
        var fake = pattern(Items.COBBLESTONE.getDefaultInstance(), 1,
                Items.NETHER_STAR.getDefaultInstance(), 1);
        var tagged = Items.IRON_INGOT.getDefaultInstance();
        tagged.getOrCreateTag().putBoolean("special", true);

        assertTrue(ConfiguredStandardStaticItemResolver.resolve(
                recipe, RegistryAccess.EMPTY, fake, profile(Set.of())).isEmpty());
        assertTrue(ConfiguredStandardStaticItemResolver.resolve(
                recipe("tagged", List.of(Ingredient.of(Items.IRON_ORE)), tagged),
                RegistryAccess.EMPTY, ordinary, profile(Set.of())).isEmpty());
        assertTrue(ConfiguredStandardStaticItemResolver.resolve(
                recipe("empty", List.of(Ingredient.of(Items.IRON_ORE)), ItemStack.EMPTY),
                RegistryAccess.EMPTY, ordinary, profile(Set.of())).isEmpty());
        var remainderInput = Items.WATER_BUCKET.getDefaultInstance();
        assertTrue(remainderInput.hasCraftingRemainingItem());
        assertTrue(ConfiguredStandardStaticItemResolver.resolve(
                recipe("crafting_remainder", List.of(Ingredient.of(Items.WATER_BUCKET)),
                        new ItemStack(Items.IRON_INGOT)),
                RegistryAccess.EMPTY,
                pattern(remainderInput, 1, Items.IRON_INGOT.getDefaultInstance(), 1),
                profile(Set.of())).isEmpty());
        var custom = new Ingredient(Stream.of(
                new Ingredient.ItemValue(Items.IRON_ORE.getDefaultInstance()))) {};
        assertTrue(ConfiguredStandardStaticItemResolver.resolve(
                recipe("custom", List.of(custom), new ItemStack(Items.IRON_INGOT)),
                RegistryAccess.EMPTY, ordinary, profile(Set.of())).isEmpty());
    }

    @Test
    void emptyOptionalIngredientsDoNotAuthorizeMiddleOnlyPatterns() {
        // AE2's four Inscriber dust recipes retain empty top/bottom Ingredients.
        // They must reject even without an explicit recipe-ID exclusion.
        var recipe = recipe("empty_optional_ingredients",
                List.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_ORE), Ingredient.EMPTY),
                new ItemStack(Items.IRON_INGOT));
        var middleOnly = pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                Items.IRON_INGOT.getDefaultInstance(), 1);

        var attempt = ConfiguredStandardStaticItemResolver.inspect(
                recipe, RegistryAccess.EMPTY, middleOnly, profile(Set.of()));
        assertTrue(attempt.process().isEmpty());
        assertEquals(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, attempt.diagnostic());
        assertEquals("RECIPE_INCOMPLETE", attempt.reason());
    }

    @Test
    void configuredRejectionRetainsRecipeIdAndExactOutputReasonThroughCoreResolution() {
        var tagged = new ItemStack(Items.IRON_INGOT);
        tagged.getOrCreateTag().putBoolean("special", true);
        var ordinary = pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                Items.IRON_INGOT.getDefaultInstance(), 1);
        var registry = new BulkRecipeAdapterRegistry();
        registry.freeze();
        var rules = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of())),
                Map.of(), Set.of(), Map.of());

        var taggedRecipe = recipe("tagged_result", List.of(Ingredient.of(Items.IRON_ORE)), tagged);
        var unrelated = recipe("a_unrelated", List.of(Ingredient.of(Items.GOLD_ORE)),
                new ItemStack(Items.GOLD_INGOT));
        var unsupportedUnrelated = recipe("a_unsupported_unrelated", List.of(new Ingredient(Stream.of(
                new Ingredient.ItemValue(Items.GOLD_ORE.getDefaultInstance()))) {}),
                new ItemStack(Items.GOLD_INGOT));
        var taggedOtherOutput = new ItemStack(Items.DIAMOND);
        taggedOtherOutput.getOrCreateTag().putBoolean("special", true);
        var sameInputOtherOutput = recipe("same_input_other_output",
                List.of(Ingredient.of(Items.IRON_ORE)), taggedOtherOutput);
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(unrelated, unsupportedUnrelated, sameInputOtherOutput, taggedRecipe));
        var result = ProcessModelResolver.resolve(registry,
                new PatternContext(TestRecipeLevel.create(manager), ordinary), rules);
        assertEquals(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, result.diagnostic());
        assertTrue(result.model().isEmpty());
        assertEquals(taggedRecipe.getId(), result.rejection().orElseThrow().recipeId());
        assertEquals("RECIPE_OUTPUT_NBT", result.rejection().orElseThrow().reason());

        manager.replaceRecipes(List.of(unrelated, unsupportedUnrelated, sameInputOtherOutput));
        var noMatch = ProcessModelResolver.resolve(registry,
                new PatternContext(TestRecipeLevel.create(manager), ordinary), rules);
        assertEquals(ValidationDiagnostic.NO_RECIPE, noMatch.diagnostic());
        assertTrue(noMatch.rejection().isEmpty());

        var emptyRecipe = recipe("empty_result", List.of(Ingredient.of(Items.IRON_ORE)), ItemStack.EMPTY);
        manager.replaceRecipes(List.of(emptyRecipe));
        var empty = ProcessModelResolver.resolve(registry,
                new PatternContext(TestRecipeLevel.create(manager), ordinary), rules);
        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, empty.diagnostic());
        assertEquals(emptyRecipe.getId(), empty.rejection().orElseThrow().recipeId());
        assertEquals("RECIPE_OUTPUT_EMPTY", empty.rejection().orElseThrow().reason());
    }

    @Test
    void configuredCandidateRunsThroughLiveRecipeManagerAndValidatedSelection() {
        var recipe = recipe("manager", List.of(Ingredient.of(Items.IRON_ORE)),
                new ItemStack(Items.IRON_INGOT));
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(recipe));
        var context = new PatternContext(TestRecipeLevel.create(manager),
                pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                        Items.IRON_INGOT.getDefaultInstance(), 1));
        var registry = new BulkRecipeAdapterRegistry();
        registry.freeze();
        var rules = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of())),
                Map.of(), Set.of(), Map.of());

        assertEquals(ValidationDiagnostic.VALID,
                ProcessModelResolver.resolve(registry, context, rules).diagnostic());
        assertEquals(ValidationDiagnostic.NO_RECIPE,
                ProcessModelResolver.resolve(registry, context, ConfiguredRuleSnapshot.empty()).diagnostic());
        assertEquals(ValidationDiagnostic.NO_RECIPE,
                ProcessModelResolver.resolve(registry, context,
                        new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of(recipe.getId()))),
                                Map.of(), Set.of(), Map.of())).diagnostic());
    }

    @Test
    void configuredAndJavaCandidatesUseExistingAmbiguityRules() {
        var recipe = new SmeltingRecipe(id("shared"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 200);
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(recipe));
        var context = new PatternContext(TestRecipeLevel.create(manager),
                pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                        Items.IRON_INGOT.getDefaultInstance(), 1));
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.freeze();
        var different = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of())),
                Map.of(), Set.of(), Map.of());
        var same = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID,
                new ConfiguredRuleSnapshot.RecipeTypeProfile(id("same"), TYPE_ID,
                        ResourceLocation.fromNamespaceAndPath("applied_causality", "item_to_item"),
                        TYPE_ID, ResourceLocation.withDefaultNamespace("furnace"), Set.of())),
                Map.of(), Set.of(), Map.of());

        assertEquals(ValidationDiagnostic.AMBIGUOUS_RECIPE,
                ProcessModelResolver.resolve(registry, context, different).diagnostic());
        assertEquals(ValidationDiagnostic.VALID,
                ProcessModelResolver.resolve(registry, context, same).diagnostic());
    }

    @Test
    void configuredManualBindingRevalidatesAfterProfileRemoval() {
        var recipe = recipe("bound", List.of(Ingredient.of(Items.IRON_ORE)),
                new ItemStack(Items.IRON_INGOT));
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(recipe));
        var definition = Items.STONE.getDefaultInstance();
        var pattern = TestProcessingPattern.create(AEItemKey.of(definition),
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)})},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)});
        var context = new PatternContext(TestRecipeLevel.create(manager), pattern);
        var binding = new ProcessBinding(0, PatternFingerprint.fromDefinition(definition),
                ConfiguredStandardStaticItemResolver.ID, recipe.getId());
        var registry = new BulkRecipeAdapterRegistry();
        registry.freeze();
        var rules = new ConfiguredRuleSnapshot(1, Map.of(TYPE_ID, profile(Set.of())),
                Map.of(), Set.of(), Map.of());

        assertEquals(ValidationDiagnostic.VALID,
                ProcessModelResolver.resolveBound(registry, context, binding, rules).diagnostic());
        assertEquals(ValidationDiagnostic.NO_RECIPE,
                ProcessModelResolver.resolveBound(registry, context, binding,
                        ConfiguredRuleSnapshot.empty()).diagnostic());
        assertEquals(ValidationDiagnostic.NO_RECIPE,
                ProcessModelResolver.resolveBound(registry, context, binding,
                        new ConfiguredRuleSnapshot(2,
                                Map.of(TYPE_ID, profile(Set.of(recipe.getId()))),
                                Map.of(), Set.of(), Map.of())).diagnostic());

        var unsupported = recipe("unsupported_bound", List.of(new Ingredient(Stream.of(
                new Ingredient.ItemValue(Items.IRON_ORE.getDefaultInstance()))) {}),
                new ItemStack(Items.IRON_INGOT));
        manager.replaceRecipes(List.of(unsupported));
        var unsupportedBinding = new ProcessBinding(0, PatternFingerprint.fromDefinition(definition),
                ConfiguredStandardStaticItemResolver.ID, unsupported.getId());
        var refusal = ProcessModelResolver.resolveBound(registry, context, unsupportedBinding, rules);
        assertEquals(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED, refusal.diagnostic());
        assertEquals(unsupported.getId(), refusal.rejection().orElseThrow().recipeId());
        assertEquals("UNSUPPORTED_INGREDIENT", refusal.rejection().orElseThrow().reason());
    }

    @Test
    void loadedTypeRuleResolvesRecipeWithoutPerRecipeAmounts(@TempDir Path config) throws IOException {
        var root = config.resolve("appliedcausality").resolve("rules");
        Files.createDirectories(root);
        Files.writeString(root.resolve("items.toml"), """
                version = 1
                [[recipe_types]]
                id = "test:static_items"
                recipe_type = "minecraft:smelting"
                semantics = "standard_static_item"
                process_shape = "test:item_to_item"
                process_family = "test:family"
                """);
        var loaded = ConfiguredRuleLoader.load(config,
                TYPE_ID::equals, ignored -> false, ignored -> Optional.empty());
        assertTrue(loaded.valid());

        var recipe = recipe("from_rule", List.of(Ingredient.of(Items.IRON_ORE)),
                new ItemStack(Items.IRON_INGOT, 2));
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(recipe));
        var context = new PatternContext(TestRecipeLevel.create(manager),
                pattern(Items.IRON_ORE.getDefaultInstance(), 1,
                        Items.IRON_INGOT.getDefaultInstance(), 2));
        var registry = new BulkRecipeAdapterRegistry();
        registry.freeze();

        var result = ProcessModelResolver.resolve(registry, context, loaded.snapshot());
        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(2L, result.model().orElseThrow()
                .outputsPerOperation().get(AEItemKey.of(Items.IRON_INGOT)));
    }

    private static ConfiguredRuleSnapshot.RecipeTypeProfile profile(Set<ResourceLocation> exclusions) {
        return new ConfiguredRuleSnapshot.RecipeTypeProfile(id("profile"), TYPE_ID,
                id("item_to_item"), id("family"), null, exclusions);
    }

    private static FixedRecipe recipe(String path, List<Ingredient> inputs, ItemStack output) {
        return new FixedRecipe(id(path), inputs, output);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static IPatternDetails pattern(ItemStack input, long amount, ItemStack output, long outputAmount) {
        return TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(input), amount)})},
                new GenericStack[]{new GenericStack(AEItemKey.of(output), outputAmount)});
    }

    private record TestInput(GenericStack[] values) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return values;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(AEKey key, Level level) {
            return key.equals(values[0].what());
        }

        @Override
        public AEKey getRemainingKey(AEKey key) {
            return null;
        }
    }

    private static final class StrictContainer extends SimpleContainer {
    }

    private record FixedRecipe(ResourceLocation id, List<Ingredient> inputs, ItemStack output)
            implements Recipe<StrictContainer> {
        @Override
        public boolean matches(StrictContainer container, Level level) {
            return true;
        }

        @Override
        public ItemStack assemble(StrictContainer container, RegistryAccess access) {
            return output.copy();
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess access) {
            return output.copy();
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return RecipeSerializer.SMELTING_RECIPE;
        }

        @Override
        public RecipeType<?> getType() {
            return RecipeType.SMELTING;
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            NonNullList<Ingredient> result = NonNullList.create();
            result.addAll(inputs);
            return result;
        }

        @Override
        public NonNullList<ItemStack> getRemainingItems(StrictContainer container) {
            return NonNullList.withSize(inputs.size(), ItemStack.EMPTY);
        }
    }
}

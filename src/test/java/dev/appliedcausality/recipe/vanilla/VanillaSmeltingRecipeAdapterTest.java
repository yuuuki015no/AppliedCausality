package dev.appliedcausality.recipe.vanilla;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonObject;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationTestSupport;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSmeltingRecipeAdapterTest {
    private static final ResourceLocation SMELTING_ID = ResourceLocation.withDefaultNamespace("smelting");
    private final VanillaSmeltingRecipeAdapter adapter = new VanillaSmeltingRecipeAdapter();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void validConcreteSmeltingRecipePromotesToVerifiedModel() {
        var recipe = recipe("iron", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT));
        var pattern = pattern(Items.IRON_ORE.getDefaultInstance(), 1L, Items.IRON_INGOT.getDefaultInstance(), 1L);

        var resolved = VanillaSmeltingRecipeAdapter.resolveRecipe(recipe, RegistryAccess.EMPTY, pattern)
                .orElseThrow();
        var validation = ValidationTestSupport.validate(
                new ProcessCandidate(adapter.id(), adapter.priority(), resolved),
                pattern,
                ignored -> Optional.of(SMELTING_ID));

        assertEquals(ValidationDiagnostic.VALID, validation.diagnostic());
        var model = validation.model().orElseThrow();
        assertEquals(MapEntry.of(AEItemKey.of(Items.IRON_ORE), 1L),
                MapEntry.only(model.inputsPerOperation()));
        assertEquals(MapEntry.of(AEItemKey.of(Items.IRON_INGOT), 1L),
                MapEntry.only(model.outputsPerOperation()));
    }

    @Test
    void validTagSmeltingRecipeUsesConcretePatternInput() {
        var recipe = recipe(
                "charcoal",
                tagIngredient("logs_that_burn", Items.OAK_LOG.getDefaultInstance()),
                new ItemStack(Items.CHARCOAL));
        var pattern = pattern(
                Items.OAK_LOG.getDefaultInstance(), 1L, Items.CHARCOAL.getDefaultInstance(), 1L);

        var resolved = VanillaSmeltingRecipeAdapter.resolveRecipe(recipe, RegistryAccess.EMPTY, pattern)
                .orElseThrow();
        var validation = ValidationTestSupport.validate(
                new ProcessCandidate(adapter.id(), adapter.priority(), resolved),
                pattern,
                ignored -> Optional.of(SMELTING_ID));

        assertEquals(ValidationDiagnostic.VALID, validation.diagnostic());
        assertEquals(MapEntry.of(AEItemKey.of(Items.OAK_LOG), 1L),
                MapEntry.only(validation.model().orElseThrow().inputsPerOperation()));
    }

    @Test
    void tagRecipeStillRejectsPatternSubstitutionAndCustomIngredients() {
        var tagRecipe = recipe(
                "charcoal",
                tagIngredient(
                        "logs_that_burn",
                        Items.OAK_LOG.getDefaultInstance(),
                        Items.BIRCH_LOG.getDefaultInstance()),
                new ItemStack(Items.CHARCOAL));
        var substitutingPattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{
                                new GenericStack(AEItemKey.of(Items.OAK_LOG), 1),
                                new GenericStack(AEItemKey.of(Items.BIRCH_LOG), 1)},
                        1)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.CHARCOAL), 1)});
        var customIngredient = new Ingredient(Stream.of(
                new Ingredient.ItemValue(Items.OAK_LOG.getDefaultInstance()))) {
        };
        var concretePattern = pattern(
                Items.OAK_LOG.getDefaultInstance(), 1L, Items.CHARCOAL.getDefaultInstance(), 1L);

        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                tagRecipe, RegistryAccess.EMPTY, substitutingPattern).isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                recipe("custom", customIngredient, new ItemStack(Items.CHARCOAL)),
                RegistryAccess.EMPTY,
                concretePattern).isEmpty());
    }

    @Test
    void wrongOutputAndArbitraryPatternNeverVerify() {
        var recipe = recipe("iron", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT));
        var wrongOutput = pattern(Items.IRON_ORE.getDefaultInstance(), 1L, Items.GOLD_INGOT.getDefaultInstance(), 1L);
        var fakeInput = pattern(Items.COBBLESTONE.getDefaultInstance(), 1L, Items.NETHER_STAR.getDefaultInstance(), 64L);

        var resolved = VanillaSmeltingRecipeAdapter.resolveRecipe(recipe, RegistryAccess.EMPTY, wrongOutput)
                .orElseThrow();
        var validation = ValidationTestSupport.validate(
                new ProcessCandidate(adapter.id(), adapter.priority(), resolved),
                wrongOutput,
                ignored -> Optional.of(SMELTING_ID));

        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, validation.diagnostic());
        assertTrue(validation.model().isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(recipe, RegistryAccess.EMPTY, fakeInput).isEmpty());
    }

    @Test
    void nonProcessingPatternIsRejectedBeforeAdapterResolution() {
        var recipe = recipe("iron", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT));
        IPatternDetails processingPattern = pattern(
                Items.IRON_ORE.getDefaultInstance(), 1L, Items.IRON_INGOT.getDefaultInstance(), 1L);
        IPatternDetails nonProcessingPattern = new TestPattern(
                processingPattern.getInputs(), processingPattern.getOutputs());

        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                recipe, RegistryAccess.EMPTY, nonProcessingPattern).isEmpty());
    }

    @Test
    void multipleConcreteInputsAndMachineStateSpecialCaseFailClosed() {
        var multiInput = recipe(
                "multi", Ingredient.of(Items.IRON_ORE, Items.GOLD_ORE), new ItemStack(Items.IRON_INGOT));
        var tagInput = recipe(
                "tag",
                tagIngredient("logs", Items.OAK_LOG.getDefaultInstance()),
                new ItemStack(Items.CHARCOAL));
        var matchingPattern = pattern(
                Items.IRON_ORE.getDefaultInstance(), 1L, Items.IRON_INGOT.getDefaultInstance(), 1L);
        var wetSponge = recipe(
                "wet_sponge", Ingredient.of(Blocks.WET_SPONGE), new ItemStack(Blocks.SPONGE));
        var wetSpongePattern = pattern(
                Blocks.WET_SPONGE.asItem().getDefaultInstance(), 1L,
                Blocks.SPONGE.asItem().getDefaultInstance(), 1L);

        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                multiInput, RegistryAccess.EMPTY, matchingPattern).isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                tagInput, RegistryAccess.EMPTY, matchingPattern).isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                wetSponge, RegistryAccess.EMPTY, wetSpongePattern).isEmpty());
    }

    @Test
    void specialNbtAndDamageSemanticsFailClosed() {
        var taggedInput = new ItemStack(Items.IRON_ORE);
        taggedInput.getOrCreateTag().putBoolean("special", true);
        var taggedOutput = new ItemStack(Items.IRON_INGOT);
        taggedOutput.getOrCreateTag().putBoolean("special", true);
        var taggedInputRecipe = recipe("tagged_input", Ingredient.of(taggedInput), new ItemStack(Items.IRON_INGOT));
        var taggedInputPattern = pattern(taggedInput, 1L, Items.IRON_INGOT.getDefaultInstance(), 1L);
        var taggedRecipe = recipe("tagged", Ingredient.of(Items.IRON_ORE), taggedOutput);
        var taggedPattern = pattern(Items.IRON_ORE.getDefaultInstance(), 1L, taggedOutput, 1L);
        var damageableRecipe = recipe(
                "damageable", Ingredient.of(Items.IRON_PICKAXE), new ItemStack(Items.IRON_INGOT));
        var damageablePattern = pattern(
                Items.IRON_PICKAXE.getDefaultInstance(), 1L, Items.IRON_INGOT.getDefaultInstance(), 1L);

        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                taggedInputRecipe, RegistryAccess.EMPTY, taggedInputPattern).isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                taggedRecipe, RegistryAccess.EMPTY, taggedPattern).isEmpty());
        assertTrue(VanillaSmeltingRecipeAdapter.resolveRecipe(
                damageableRecipe, RegistryAccess.EMPTY, damageablePattern).isEmpty());
    }

    private static SmeltingRecipe recipe(String path, Ingredient input, ItemStack output) {
        return new SmeltingRecipe(
                id(path), "", CookingBookCategory.MISC, input, output, 0.0F, 200);
    }

    private static Ingredient tagIngredient(String path, ItemStack... items) {
        return Ingredient.fromValues(Stream.of(new Ingredient.Value() {
            @Override
            public List<ItemStack> getItems() {
                return List.of(items);
            }

            @Override
            public JsonObject serialize() {
                var json = new JsonObject();
                json.addProperty("tag", "test:" + path);
                return json;
            }
        }));
    }

    private static IPatternDetails pattern(
            ItemStack input,
            long inputAmount,
            ItemStack output,
            long outputAmount) {
        AEItemKey inputKey = AEItemKey.of(input);
        AEItemKey outputKey = AEItemKey.of(output);
        return TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(inputKey, inputAmount)}, 1L)},
                new GenericStack[]{new GenericStack(outputKey, outputAmount)});
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private record TestPattern(IPatternDetails.IInput[] inputs, GenericStack[] outputs) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return outputs;
        }
    }

    private record TestInput(GenericStack[] possibleInputs, long multiplier) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(possibleInputs[0].what());
        }

        @Override
        public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
            return null;
        }
    }

    private record MapEntry(appeng.api.stacks.AEKey key, long amount) {
        private static MapEntry of(appeng.api.stacks.AEKey key, long amount) {
            return new MapEntry(key, amount);
        }

        private static MapEntry only(java.util.Map<appeng.api.stacks.AEKey, Long> amounts) {
            if (amounts.size() != 1) {
                throw new AssertionError("expected exactly one map entry: " + amounts);
            }
            var entry = amounts.entrySet().iterator().next();
            return new MapEntry(entry.getKey(), entry.getValue());
        }
    }
}

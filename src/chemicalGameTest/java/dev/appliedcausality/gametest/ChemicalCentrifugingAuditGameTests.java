package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.GasStack;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.CentrifugingIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** Audits centrifuging recipe quantities and the exact bundled Type. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCentrifugingAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "centrifuging");
    private static final ResourceLocation BUILTIN_ID = id("mekanism", "processing/uranium/fissile_fuel");
    private static final ResourceLocation PACK_ID = id("pack_test", "centrifuging");
    private static final String SINGLE_GAS_CLASS =
            "mekanism.common.recipe.ingredient.creator.GasStackIngredientCreator$SingleGasStackIngredient";

    private ChemicalCentrifugingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInCentrifugingHasFixedGasAmounts(GameTestHelper helper) {
        var loaded = helper.getLevel().getRecipeManager().byKey(BUILTIN_ID).orElseThrow();
        require(loaded.getClass() == CentrifugingIRecipe.class
                        && loaded.getType() == MekanismRecipeType.CENTRIFUGING.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID),
                "built-in centrifuging Type, serializer, or concrete class changed");
        var recipe = (CentrifugingIRecipe) loaded;
        var input = MekanismAPI.gasRegistry().getValue(id("mekanism", "uranium_hexafluoride")).getStack(1);
        verifyRecipeAndPattern(helper, recipe, input, 1L, id("mekanism", "fissile_fuel"), 1L);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packAddedCentrifugingPreservesLongGasUnits(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(PACK_ID).orElseThrow();
        require(loaded.getClass() == CentrifugingIRecipe.class
                        && loaded.getType() == MekanismRecipeType.CENTRIFUGING.get()
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.CENTRIFUGING.get()).contains(loaded),
                "pack centrifuging recipe was not indexed under its registered Type");
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(loaded.getType()),
                "audited centrifuging Type was not available through the canonical adapter");
        var recipe = (CentrifugingIRecipe) loaded;
        var input = MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(2);
        verifyRecipeAndPattern(helper, recipe, input, 2L, id("mekanism", "oxygen"), 17L);
        helper.succeed();
    }

    private static void verifyRecipeAndPattern(GameTestHelper helper, CentrifugingIRecipe recipe,
            GasStack input, long inputAmount, ResourceLocation outputId, long outputAmount) {
        var ingredient = recipe.getInput();
        require(ingredient.getClass().getName().equals(SINGLE_GAS_CLASS)
                        && !recipe.isIncomplete() && ingredient.test(input)
                        && ingredient.getNeededAmount(input) == inputAmount
                        && ingredient.getMatchingInstance(input).getAmount() == inputAmount,
                "centrifuging did not consume its exact single Gas input amount");
        var serialized = ingredient.serialize();
        require(serialized.isJsonObject() && serialized.getAsJsonObject().has("gas")
                        && serialized.getAsJsonObject().get("amount").getAsLong() == inputAmount,
                "centrifuging Gas ingredient serialization changed");
        var output = recipe.getOutput(input);
        require(!output.isEmpty() && output.getAmount() == outputAmount
                        && outputId.equals(MekanismAPI.gasRegistry().getKey(output.getType()))
                        && recipe.getOutputDefinition().size() == 1
                        && output.equals(recipe.getOutputDefinition().get(0)),
                "centrifuging output was not one fixed Gas stack");
        var inputKey = MekanismKey.of(input);
        var outputKey = MekanismKey.of(output);
        require(inputKey != null && outputKey != null
                        && inputKey.getForm() == MekanismKey.GAS && outputKey.getForm() == MekanismKey.GAS,
                "Applied Mekanistics Gas keys are unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(inputKey, inputAmount)},
                new GenericStack[]{new GenericStack(outputKey, outputAmount)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1 && pattern.getOutputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == inputAmount
                        && pattern.getInputs()[0].getPossibleInputs()[0].what().equals(inputKey)
                        && pattern.getOutputs()[0].what().equals(outputKey)
                        && pattern.getOutputs()[0].amount() == outputAmount,
                "centrifuging Gas amounts did not survive AE2 Processing Pattern normalization");
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

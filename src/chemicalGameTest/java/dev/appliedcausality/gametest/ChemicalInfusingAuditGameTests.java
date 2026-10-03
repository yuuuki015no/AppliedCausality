package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.recipes.ChemicalInfuserRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient.GasStackIngredient;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.ChemicalInfuserIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Audits the two interchangeable Gas inputs and canonical Type registration. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusingAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "chemical_infusing");
    private static final String SINGLE_GAS_CLASS =
            "mekanism.common.recipe.ingredient.creator.GasStackIngredientCreator$SingleGasStackIngredient";

    private ChemicalInfusingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInRecipeAcceptsEitherInputOrder(GameTestHelper helper) {
        var recipe = recipe(helper, id("mekanism", "chemical_infusing/sulfur_trioxide"));
        verifyDistinct(helper, recipe, gas("oxygen", 1), gas("sulfur_dioxide", 2),
                gas("sulfur_trioxide", 2));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packRecipePreservesAsymmetricAmountsAndOrder(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "chemical_infusing"));
        verifyDistinct(helper, recipe, gas("hydrogen", 2), gas("chlorine", 3), gas("oxygen", 17));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasRolesBecomeOnePatternInput(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "chemical_infusing_same_gas"));
        var left = gas("hydrogen", 2);
        var right = gas("hydrogen", 3);
        var output = gas("water_vapor", 7);
        verifyIngredient(recipe.getLeftInput(), left, 2);
        verifyIngredient(recipe.getRightInput(), right, 3);
        require(recipe.test(left, right) && recipe.test(right, left)
                        && recipe.getOutput(left, right).equals(output),
                "same-Gas recipe changed its symmetric role or output semantics");
        var key = MekanismKey.of(left);
        var outputKey = MekanismKey.of(output);
        require(key != null && outputKey != null, "same-Gas Pattern key is unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(key, 2), new GenericStack(key, 3)},
                new GenericStack[]{new GenericStack(outputKey, 7)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == 5
                        && pattern.getInputs()[0].getPossibleInputs().length == 1
                        && key.equals(pattern.getInputs()[0].getPossibleInputs()[0].what())
                        && pattern.getOutputs().length == 1
                        && pattern.getOutputs()[0].amount() == 7
                        && outputKey.equals(pattern.getOutputs()[0].what()),
                "AE2 did not aggregate the two same-Gas role quantities exactly");
        helper.succeed();
    }

    private static ChemicalInfuserRecipe recipe(GameTestHelper helper, ResourceLocation recipeId) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(recipeId).orElseThrow();
        require(loaded.getClass() == ChemicalInfuserIRecipe.class
                        && loaded.getType() == MekanismRecipeType.CHEMICAL_INFUSING.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.CHEMICAL_INFUSING.get()).contains(loaded),
                "chemical_infusing class/Type/serializer/index changed: " + recipeId);
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(loaded.getType()),
                "audited chemical_infusing Type was missing from the canonical adapter");
        return (ChemicalInfuserRecipe) loaded;
    }

    private static void verifyDistinct(GameTestHelper helper, ChemicalInfuserRecipe recipe,
            GasStack left, GasStack right, GasStack output) {
        verifyIngredient(recipe.getLeftInput(), left, left.getAmount());
        verifyIngredient(recipe.getRightInput(), right, right.getAmount());
        GasStack tooSmall = right.copy();
        tooSmall.setAmount(right.getAmount() - 1);
        require(!recipe.isIncomplete() && recipe.test(left, right) && recipe.test(right, left)
                        && !recipe.test(left, tooSmall) && !recipe.test(tooSmall, left)
                        && recipe.getOutput(left, right).equals(output)
                        && recipe.getOutput(right, left).equals(output)
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getOutputDefinition().get(0).equals(output),
                "chemical_infusing input roles, minimum amounts, or fixed output changed: " + recipe.getId());
        var leftKey = MekanismKey.of(left);
        var rightKey = MekanismKey.of(right);
        var outputKey = MekanismKey.of(output);
        require(leftKey != null && rightKey != null && outputKey != null
                        && leftKey.getForm() == MekanismKey.GAS
                        && rightKey.getForm() == MekanismKey.GAS
                        && outputKey.getForm() == MekanismKey.GAS,
                "chemical_infusing Gas Pattern keys are unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(rightKey, right.getAmount()),
                        new GenericStack(leftKey, left.getAmount())},
                new GenericStack[]{new GenericStack(outputKey, output.getAmount())}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 2
                        && pattern.getInputs()[0].getMultiplier() == right.getAmount()
                        && pattern.getInputs()[1].getMultiplier() == left.getAmount()
                        && rightKey.equals(pattern.getInputs()[0].getPossibleInputs()[0].what())
                        && leftKey.equals(pattern.getInputs()[1].getPossibleInputs()[0].what())
                        && pattern.getOutputs().length == 1
                        && pattern.getOutputs()[0].amount() == output.getAmount()
                        && outputKey.equals(pattern.getOutputs()[0].what()),
                "AE2 Pattern lost reversed Gas inputs or fixed output: " + recipe.getId());
    }

    private static void verifyIngredient(GasStackIngredient ingredient, GasStack stack, long amount) {
        var serialized = ingredient.serialize();
        require(ingredient.getClass().getName().equals(SINGLE_GAS_CLASS)
                        && serialized.isJsonObject()
                        && serialized.getAsJsonObject().keySet().equals(Set.of("amount", "gas"))
                        && serialized.getAsJsonObject().get("amount").getAsLong() == amount
                        && ingredient.test(stack)
                        && ingredient.getNeededAmount(stack) == amount
                        && ingredient.getMatchingInstance(stack).getAmount() == amount,
                "chemical_infusing ingredient is not one exact Gas amount");
    }

    private static GasStack gas(String path, long amount) {
        return MekanismAPI.gasRegistry().getValue(id("mekanism", path)).getStack(amount);
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

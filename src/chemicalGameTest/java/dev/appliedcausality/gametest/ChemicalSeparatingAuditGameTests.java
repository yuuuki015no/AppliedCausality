package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.ElectrolysisRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.ElectrolysisIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Active RecipeManager and Pattern audit for the exact separating subset. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalSeparatingAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "separating");
    private static final String DIRECT_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$SingleFluidStackIngredient";

    private ChemicalSeparatingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInWaterHasTwoFixedGasOutputs(GameTestHelper helper) {
        var recipe = recipe(helper, id("mekanism", "separator/water"));
        var input = new FluidStack(Fluids.WATER, 2);
        var output = recipe.getOutput(input);
        require(recipe.getInput().test(input)
                        && recipe.getInput().getNeededAmount(input) == 2
                        && output.left().equals(gas("hydrogen", 2))
                        && output.right().equals(gas("oxygen", 1))
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getOutputDefinition().get(0).equals(output),
                "built-in water separating material quantities changed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packDirectFluidAndTwoDistinctGasOutputs(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "separating"));
        var input = new FluidStack(Fluids.WATER, 3);
        var ingredient = recipe.getInput();
        var serialized = ingredient.serialize();
        var output = recipe.getOutput(input);
        require(ingredient.getClass().getName().equals(DIRECT_FLUID_CLASS)
                        && serialized.isJsonObject()
                        && serialized.getAsJsonObject().keySet().equals(Set.of("amount", "fluid"))
                        && ingredient.test(input)
                        && ingredient.getNeededAmount(input) == 3
                        && ingredient.getMatchingInstance(input).getAmount() == 3
                        && output.left().equals(gas("hydrogen", 5))
                        && output.right().equals(gas("oxygen", 7))
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getOutputDefinition().get(0).equals(output),
                "pack separating material quantities changed");
        var hydrogen = MekanismKey.of(output.left());
        var oxygen = MekanismKey.of(output.right());
        var fluid = AEFluidKey.of(Fluids.WATER);
        require(hydrogen != null && oxygen != null && fluid != null
                        && hydrogen.getForm() == MekanismKey.GAS
                        && oxygen.getForm() == MekanismKey.GAS,
                "Fluid/Gas Pattern keys were unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fluid, 3)},
                new GenericStack[]{new GenericStack(oxygen, 7), new GenericStack(hydrogen, 5)}),
                helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == 3
                        && fluid.equals(pattern.getInputs()[0].getPossibleInputs()[0].what())
                        && pattern.getOutputs().length == 2
                        && oxygen.equals(pattern.getOutputs()[0].what())
                        && pattern.getOutputs()[0].amount() == 7
                        && hydrogen.equals(pattern.getOutputs()[1].what())
                        && pattern.getOutputs()[1].amount() == 5,
                "AE2 changed distinct separating output keys or amounts");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasOutputsAggregate(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "separating_same_gas"));
        var input = new FluidStack(Fluids.WATER, 4);
        var output = recipe.getOutput(input);
        require(recipe.getInput().test(input)
                        && recipe.getInput().getNeededAmount(input) == 4
                        && output.left().equals(gas("hydrogen", 5))
                        && output.right().equals(gas("hydrogen", 7)),
                "same-Gas separating recipe material quantities changed");
        var gasKey = MekanismKey.of(output.left());
        var fluidKey = AEFluidKey.of(Fluids.WATER);
        require(gasKey != null && fluidKey != null, "same-Gas Pattern keys were unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fluidKey, 4)},
                new GenericStack[]{new GenericStack(gasKey, 5), new GenericStack(gasKey, 7)}),
                helper.getLevel());
        require(pattern != null && pattern.getOutputs().length == 1
                        && gasKey.equals(pattern.getOutputs()[0].what())
                        && pattern.getOutputs()[0].amount() == 12,
                "AE2 did not aggregate same-Gas separating outputs");
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(recipe.getType()),
                "exact separating Type was missing from the canonical adapter");
        helper.succeed();
    }

    private static ElectrolysisRecipe recipe(GameTestHelper helper, ResourceLocation id) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(id).orElseThrow();
        require(loaded.getClass() == ElectrolysisIRecipe.class
                        && loaded.getType() == MekanismRecipeType.SEPARATING.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.SEPARATING.get()).contains(loaded),
                "separating class/Type/serializer/index changed: " + id);
        return (ElectrolysisRecipe) loaded;
    }

    private static mekanism.api.chemical.gas.GasStack gas(String path, long amount) {
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

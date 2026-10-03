package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.FluidSlurryToSlurryRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.FluidSlurryToSlurryIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;

/** Active RecipeManager/Pattern evidence for the washing Type. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalWashingAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "washing");
    private static final String SINGLE_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$SingleFluidStackIngredient";
    private static final String TAGGED_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$TaggedFluidStackIngredient";
    private static final String SINGLE_SLURRY_CLASS =
            "mekanism.common.recipe.ingredient.creator.SlurryStackIngredientCreator$SingleSlurryStackIngredient";

    private ChemicalWashingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInWaterTagHasFixedMaterialQuantities(GameTestHelper helper) {
        var recipe = recipe(helper, id("mekanism", "processing/iron/slurry/clean"));
        var water = new FluidStack(Fluids.WATER, 5);
        var dirty = slurry("dirty_iron", 1);
        var clean = slurry("clean_iron", 1);
        require(recipe.getFluidInput().getClass().getName().equals(TAGGED_FLUID_CLASS)
                        && recipe.getFluidInput().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "tag"))
                        && recipe.getChemicalInput().getClass().getName().equals(SINGLE_SLURRY_CLASS)
                        && recipe.getChemicalInput().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "slurry"))
                        && recipe.test(water, dirty)
                        && recipe.getFluidInput().getNeededAmount(water) == 5
                        && recipe.getChemicalInput().getNeededAmount(dirty) == 1
                        && recipe.getOutput(water, dirty).equals(clean)
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getOutputDefinition().get(0).equals(clean),
                "built-in washing material semantics changed");
        assertPattern(helper, AEFluidKey.of(Fluids.WATER), 5,
                MekanismKey.of(dirty), 1, MekanismKey.of(clean), 1);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packDirectFluidAndSlurryKeepIndependentAmounts(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "washing"));
        var water = new FluidStack(Fluids.WATER, 7);
        var dirty = slurry("dirty_iron", 11);
        var clean = slurry("clean_iron", 13);
        require(recipe.getFluidInput().getClass().getName().equals(SINGLE_FLUID_CLASS)
                        && recipe.getFluidInput().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "fluid"))
                        && recipe.getChemicalInput().getClass().getName().equals(SINGLE_SLURRY_CLASS)
                        && recipe.getChemicalInput().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "slurry"))
                        && recipe.test(water, dirty)
                        && recipe.getFluidInput().getNeededAmount(water) == 7
                        && recipe.getChemicalInput().getNeededAmount(dirty) == 11
                        && recipe.getFluidInput().getMatchingInstance(water).getAmount() == 7
                        && recipe.getChemicalInput().getMatchingInstance(dirty).getAmount() == 11
                        && recipe.getOutput(water, dirty).equals(clean)
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getOutputDefinition().get(0).equals(clean),
                "pack washing Fluid/Slurry material quantities changed");
        assertPattern(helper, AEFluidKey.of(Fluids.WATER), 7,
                MekanismKey.of(dirty), 11, MekanismKey.of(clean), 13);
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes()
                        .contains(recipe.getType()),
                "washing Type was missing from the canonical adapter");
        helper.succeed();
    }

    private static FluidSlurryToSlurryRecipe recipe(GameTestHelper helper, ResourceLocation id) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(id).orElseThrow();
        require(loaded.getClass() == FluidSlurryToSlurryIRecipe.class
                        && loaded.getType() == MekanismRecipeType.WASHING.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.WASHING.get()).contains(loaded),
                "washing class/Type/serializer/index changed: " + id);
        return (FluidSlurryToSlurryRecipe) loaded;
    }

    private static void assertPattern(GameTestHelper helper, AEFluidKey fluid, long fluidAmount,
            MekanismKey input, long inputAmount, MekanismKey output, long outputAmount) {
        require(fluid != null && input != null && output != null
                        && input.getForm() == MekanismKey.SLURRY
                        && output.getForm() == MekanismKey.SLURRY,
                "washing Pattern keys are unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fluid, fluidAmount),
                        new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 2
                        && pattern.getOutputs().length == 1,
                "washing AE2 Processing Pattern did not decode");
        var actual = Map.of(pattern.getInputs()[0].getPossibleInputs()[0].what(),
                pattern.getInputs()[0].getMultiplier(),
                pattern.getInputs()[1].getPossibleInputs()[0].what(),
                pattern.getInputs()[1].getMultiplier());
        require(actual.equals(Map.of(fluid, fluidAmount, input, inputAmount))
                        && pattern.getOutputs()[0].what().equals(output)
                        && pattern.getOutputs()[0].amount() == outputAmount,
                "AE2 changed washing Pattern keys or amounts");
    }

    private static mekanism.api.chemical.slurry.SlurryStack slurry(String path, long amount) {
        return MekanismAPI.slurryRegistry().getValue(id("mekanism", path)).getStack(amount);
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

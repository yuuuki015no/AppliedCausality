package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.ChemicalOxidizerIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** Audits the live Mekanism recipe shape admitted by the bundled adapter. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalOxidizingAuditGameTests {
    private static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "oxidizing");
    private static final ResourceLocation RECIPE_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "oxidizing/brine");

    private ChemicalOxidizingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void activeManagerOxidizingRecipeHasExactPatternShape(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var recipe = manager.byKey(RECIPE_ID).orElseThrow();
        require(recipe.getClass() == ChemicalOxidizerIRecipe.class, "oxidizing recipe class changed");
        require(recipe.getType() == MekanismRecipeType.OXIDIZING.get()
                        && recipe.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && recipe.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.OXIDIZING.get()).contains(recipe),
                "oxidizing recipe is not indexed under its registered Type and serializer");
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(recipe.getType()),
                "verified oxidizing Type was not enabled with appmek present");

        var oxidizing = (ChemicalOxidizerIRecipe) recipe;
        var ingredient = oxidizing.getInput();
        require(ingredient.getClass().getName().equals(
                        "mekanism.common.recipe.ingredient.creator.ItemStackIngredientCreator$SingleItemStackIngredient"),
                "oxidizing ingredient is not the audited single-item implementation");
        var representations = ingredient.getRepresentations();
        require(!representations.isEmpty(), "oxidizing input tag is empty");
        var input = representations.get(0);
        long needed = ingredient.getNeededAmount(input);
        require(needed == 1L && needed <= input.getMaxStackSize(),
                "built-in brine oxidation input amount changed");
        input = input.copyWithCount((int) needed);
        require(ingredient.test(input) && ingredient.getNeededAmount(input) == needed,
                "oxidizing ingredient does not consume its declared amount");

        var output = oxidizing.getOutput(input);
        require(!oxidizing.isIncomplete() && !output.isEmpty() && output.getAmount() == 15L
                        && ResourceLocation.fromNamespaceAndPath("mekanism", "brine")
                                .equals(MekanismAPI.gasRegistry().getKey(output.getType()))
                        && oxidizing.getOutputDefinition().size() == 1
                        && output.equals(oxidizing.getOutputDefinition().get(0)),
                "oxidizing output is not one fixed GasStack");
        var key = MekanismKey.of(output);
        require(key != null && key.getForm() == MekanismKey.GAS, "oxidizing Gas key is unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), needed)},
                new GenericStack[]{new GenericStack(key, output.getAmount())}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1 && pattern.getOutputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == needed
                        && pattern.getOutputs()[0].what().equals(key)
                        && pattern.getOutputs()[0].amount() == output.getAmount(),
                "oxidizing recipe did not survive Processing Pattern normalization");
        helper.succeed();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

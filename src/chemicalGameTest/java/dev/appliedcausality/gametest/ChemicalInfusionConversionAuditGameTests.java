package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.ItemStackToInfuseTypeRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.InfusionConversionIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** Audits exact infusion-conversion recipe and Chemical Pattern semantics. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusionConversionAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "infusion_conversion");
    private static final ResourceLocation BUILTIN_ID = id("mekanism", "infusion_conversion/carbon/from_enriched");
    private static final ResourceLocation ARRAY_ID = id("mekanism", "infusion_conversion/carbon/from_coal");
    private static final ResourceLocation PACK_ID = id("pack_test", "infusion_conversion");
    private static final ResourceLocation CARBON_ID = id("mekanism", "carbon");
    private static final String SINGLE_INGREDIENT_CLASS =
            "mekanism.common.recipe.ingredient.creator.ItemStackIngredientCreator$SingleItemStackIngredient";

    private ChemicalInfusionConversionAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInConversionHasFixedInfusionOutput(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(BUILTIN_ID).orElseThrow();
        require(loaded.getClass() == InfusionConversionIRecipe.class
                        && loaded.getType() == MekanismRecipeType.INFUSION_CONVERSION.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.INFUSION_CONVERSION.get()).contains(loaded),
                "built-in infusion conversion did not use its audited Type/serializer/class");
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(loaded.getType()),
                "audited infusion conversion Type was not registered");

        var recipe = (InfusionConversionIRecipe) loaded;
        var ingredient = recipe.getInput();
        require(ingredient.getClass().getName().equals(SINGLE_INGREDIENT_CLASS),
                "built-in enriched ingredient has an unsupported implementation");
        var inputs = ingredient.getRepresentations();
        require(!inputs.isEmpty(), "built-in enriched carbon tag is empty");
        var input = inputs.get(0);
        long needed = ingredient.getNeededAmount(input);
        require(needed == 1L && ingredient.test(input), "built-in ingredient amount changed");
        verifyPattern(helper, recipe, input.copyWithCount((int) needed), needed, 80L);
        var arrayRecipe = manager.byKey(ARRAY_ID).orElseThrow();
        require(arrayRecipe.getClass() == InfusionConversionIRecipe.class,
                "array-backed built-in recipe class changed: " + arrayRecipe.getClass().getName());
        var arrayIngredient = ((InfusionConversionIRecipe) arrayRecipe).getInput();
        var serialized = arrayIngredient.serialize();
        require(arrayIngredient.getClass().getName().equals(SINGLE_INGREDIENT_CLASS)
                        && serialized.isJsonObject()
                        && serialized.getAsJsonObject().get("ingredient").isJsonArray(),
                "array alternatives did not survive ingredient normalization: " + serialized);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packAddedConversionRetainsExactItemAndInfusionAmounts(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(PACK_ID).orElseThrow();
        require(loaded.getClass() == InfusionConversionIRecipe.class
                        && loaded.getType() == MekanismRecipeType.INFUSION_CONVERSION.get()
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.INFUSION_CONVERSION.get()).contains(loaded),
                "pack infusion conversion did not use the registered RecipeManager Type");
        var recipe = (InfusionConversionIRecipe) loaded;
        var input = new ItemStack(Items.SPORE_BLOSSOM, 2);
        require(recipe.getInput().getClass().getName().equals(SINGLE_INGREDIENT_CLASS)
                        && recipe.getInput().test(input)
                        && recipe.getInput().getNeededAmount(input) == 2L,
                "pack tag ingredient did not consume two Items");
        verifyPattern(helper, recipe, input, 2L, 17L);
        helper.succeed();
    }

    private static void verifyPattern(GameTestHelper helper, ItemStackToInfuseTypeRecipe recipe,
            ItemStack input, long inputAmount, long outputAmount) {
        var output = recipe.getOutput(input);
        require(!recipe.isIncomplete() && !output.isEmpty() && output.getAmount() == outputAmount
                        && CARBON_ID.equals(MekanismAPI.infuseTypeRegistry().getKey(output.getType()))
                        && recipe.getOutputDefinition().size() == 1
                        && output.equals(recipe.getOutputDefinition().get(0)),
                "infusion conversion output is not one fixed Carbon stack");
        var key = MekanismKey.of(output);
        require(key != null && key.getForm() == MekanismKey.INFUSION,
                "Applied Mekanistics Infusion key is unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), inputAmount)},
                new GenericStack[]{new GenericStack(key, outputAmount)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1 && pattern.getOutputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == inputAmount
                        && pattern.getOutputs()[0].what().equals(key)
                        && pattern.getOutputs()[0].amount() == outputAmount,
                "infusion conversion did not survive AE2 Processing Pattern normalization");
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

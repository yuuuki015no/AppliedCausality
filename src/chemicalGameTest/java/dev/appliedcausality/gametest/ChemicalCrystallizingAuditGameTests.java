package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.merged.BoxedChemicalStack;
import mekanism.api.recipes.ChemicalCrystallizerRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.ChemicalCrystallizerIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** Audits four Chemical forms and their exact canonical Bulk Type registration. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCrystallizingAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "crystallizing");

    private ChemicalCrystallizingAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void packRecipesPreserveEachChemicalFormAndItemQuantity(GameTestHelper helper) {
        verify(helper, "crystallizing_gas", ChemicalType.GAS,
                MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(2),
                MekanismKey.GAS, "gas", 2, new ItemStack(Items.DIAMOND, 3));
        verify(helper, "crystallizing_infusion", ChemicalType.INFUSION,
                MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(11),
                MekanismKey.INFUSION, "infuse_type", 11, new ItemStack(Items.EMERALD, 4));
        verify(helper, "crystallizing_pigment", ChemicalType.PIGMENT,
                MekanismAPI.pigmentRegistry().getValue(id("mekanism", "red")).getStack(13),
                MekanismKey.PIGMENT, "pigment", 13, new ItemStack(Items.LAPIS_LAZULI, 5));
        verify(helper, "crystallizing_slurry", ChemicalType.SLURRY,
                MekanismAPI.slurryRegistry().getValue(id("mekanism", "clean_iron")).getStack(17),
                MekanismKey.SLURRY, "slurry", 17, new ItemStack(Items.QUARTZ, 6));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void builtInGasAndSlurryRecipesUseTheSameFixedOutputPath(GameTestHelper helper) {
        verifyBuiltIn(helper, id("mekanism", "crystallizing/salt"), ChemicalType.GAS,
                MekanismAPI.gasRegistry().getValue(id("mekanism", "brine")).getStack(15),
                15, new ItemStack(ForgeRegistries.ITEMS.getValue(id("mekanism", "salt"))));
        verifyBuiltIn(helper, id("mekanism", "processing/iron/crystal/from_slurry"), ChemicalType.SLURRY,
                MekanismAPI.slurryRegistry().getValue(id("mekanism", "clean_iron")).getStack(200),
                200, new ItemStack(ForgeRegistries.ITEMS.getValue(id("mekanism", "crystal_iron"))));
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, String path, ChemicalType form,
            ChemicalStack<?> input, int keyForm, String serializedKey, long inputAmount, ItemStack output) {
        ResourceLocation recipeId = id("pack_test", path);
        var loaded = helper.getLevel().getRecipeManager().byKey(recipeId).orElseThrow();
        require(loaded.getClass() == ChemicalCrystallizerIRecipe.class
                        && loaded.getType() == MekanismRecipeType.CRYSTALLIZING.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && helper.getLevel().getRecipeManager()
                                .getAllRecipesFor(MekanismRecipeType.CRYSTALLIZING.get()).contains(loaded),
                "pack crystallizing recipe changed its registered Type/serializer/class: " + recipeId);
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(loaded.getType()),
                "audited crystallizing Type was missing from the canonical adapter");
        var recipe = (ChemicalCrystallizerRecipe) loaded;
        var ingredient = recipe.getInput();
        var serialized = ingredient.serialize();
        String creator = switch (form) {
            case GAS -> "Gas";
            case INFUSION -> "Infusion";
            case PIGMENT -> "Pigment";
            case SLURRY -> "Slurry";
        };
        require(ChemicalType.getTypeFor(ingredient) == form
                        && ingredient.getClass().getName().equals(
                                "mekanism.common.recipe.ingredient.creator."
                                        + creator + "StackIngredientCreator$Single" + creator + "StackIngredient")
                        && serialized.isJsonObject()
                        && serialized.getAsJsonObject().keySet().size() == 2
                        && serialized.getAsJsonObject().has(serializedKey)
                        && serialized.getAsJsonObject().get("amount").getAsLong() == inputAmount,
                "pack crystallizing ingredient did not retain one exact Chemical form: " + recipeId);
        verifyRecipeAndPattern(helper, recipe, input, keyForm, inputAmount, output);
    }

    private static void verifyBuiltIn(GameTestHelper helper, ResourceLocation recipeId, ChemicalType form,
            ChemicalStack<?> input, long inputAmount, ItemStack output) {
        var loaded = helper.getLevel().getRecipeManager().byKey(recipeId).orElseThrow();
        require(loaded.getClass() == ChemicalCrystallizerIRecipe.class
                        && loaded.getType() == MekanismRecipeType.CRYSTALLIZING.get()
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && ChemicalType.getTypeFor(((ChemicalCrystallizerRecipe) loaded).getInput()) == form,
                "built-in crystallizing recipe changed its Chemical form or registration: " + recipeId);
        int keyForm = form == ChemicalType.GAS ? MekanismKey.GAS : MekanismKey.SLURRY;
        verifyRecipeAndPattern(helper, (ChemicalCrystallizerRecipe) loaded, input, keyForm, inputAmount, output);
    }

    private static void verifyRecipeAndPattern(GameTestHelper helper, ChemicalCrystallizerRecipe recipe,
            ChemicalStack<?> input, int keyForm, long inputAmount, ItemStack expectedOutput) {
        var output = recipe.getOutput(BoxedChemicalStack.box(input));
        ChemicalStack<?> tooSmall = input.copy();
        tooSmall.setAmount(inputAmount - 1);
        ChemicalStack<?> wrongForm = keyForm == MekanismKey.GAS
                ? MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(inputAmount)
                : MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(inputAmount);
        require(!recipe.isIncomplete() && recipe.test(input)
                        && !recipe.test(tooSmall) && !recipe.test(wrongForm) && !output.isEmpty()
                        && ItemStack.matches(output, expectedOutput)
                        && recipe.getOutputDefinition().size() == 1
                        && output != recipe.getOutputDefinition().get(0)
                        && ItemStack.matches(output, recipe.getOutputDefinition().get(0)),
                "crystallizing did not use one fixed Item output and matching Chemical input: " + recipe.getId());
        var inputKey = MekanismKey.of(input);
        var outputKey = AEItemKey.of(output);
        require(inputKey != null && inputKey.getForm() == keyForm && outputKey != null,
                "crystallizing Chemical or Item key was unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(inputKey, inputAmount)},
                new GenericStack[]{new GenericStack(outputKey, output.getCount())}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1 && pattern.getOutputs().length == 1
                        && pattern.getInputs()[0].getMultiplier() == inputAmount
                        && pattern.getInputs()[0].getPossibleInputs().length == 1
                        && pattern.getInputs()[0].getPossibleInputs()[0].what().equals(inputKey)
                        && pattern.getOutputs()[0].what().equals(outputKey)
                        && pattern.getOutputs()[0].amount() == output.getCount(),
                "crystallizing Chemical-to-Item amounts did not survive AE2 Pattern normalization: " + recipe.getId());
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

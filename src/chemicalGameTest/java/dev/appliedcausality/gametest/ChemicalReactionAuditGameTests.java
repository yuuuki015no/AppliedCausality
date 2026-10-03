package dev.appliedcausality.gametest;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.PressurizedReactionRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.PressurizedReactionIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Active recipe and machine-material evidence for the reaction Type. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalReactionAuditGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "reaction");
    private static final String SINGLE_ITEM_CLASS =
            "mekanism.common.recipe.ingredient.creator.ItemStackIngredientCreator$SingleItemStackIngredient";
    private static final String SINGLE_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$SingleFluidStackIngredient";
    private static final String TAGGED_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$TaggedFluidStackIngredient";
    private static final String SINGLE_GAS_CLASS =
            "mekanism.common.recipe.ingredient.creator.GasStackIngredientCreator$SingleGasStackIngredient";

    private ChemicalReactionAuditGameTests() {
    }

    @GameTest(template = "empty")
    public static void packDualOutputHasThreeIndependentInputs(GameTestHelper helper) {
        var recipe = recipe(helper, id("pack_test", "reaction"));
        var item = Items.COBBLESTONE.getDefaultInstance();
        item.setCount(2);
        var water = new FluidStack(Fluids.WATER, 7);
        var hydrogen = MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(11);
        var output = recipe.getOutput(item, water, hydrogen);
        require(recipe.getInputSolid().getClass().getName().equals(SINGLE_ITEM_CLASS)
                        && recipe.getInputFluid().getClass().getName().equals(SINGLE_FLUID_CLASS)
                        && recipe.getInputGas().getClass().getName().equals(SINGLE_GAS_CLASS)
                        && recipe.getInputSolid().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("ingredient", "amount"))
                        && recipe.getInputFluid().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "fluid"))
                        && recipe.getInputGas().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "gas"))
                        && recipe.test(item, water, hydrogen)
                        && recipe.getInputSolid().getNeededAmount(item) == 2
                        && recipe.getInputFluid().getNeededAmount(water) == 7
                        && recipe.getInputGas().getNeededAmount(hydrogen) == 11
                        && output.item().is(Items.IRON_INGOT)
                        && output.item().getCount() == 13
                        && output.gas().getAmount() == 17
                        && output.gas().getType() == MekanismAPI.gasRegistry()
                                .getValue(id("mekanism", "oxygen"))
                        && recipe.getOutputDefinition().size() == 1
                        && recipe.getDuration() == 60
                        && recipe.getEnergyRequired() != null,
                "pack reaction materials or fixed outputs changed");
        require(MekanismRecipeAdapter.createBundled().supportedRecipeTypes()
                        .contains(recipe.getType()),
                "reaction Type was missing from the canonical adapter");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void builtInTagAndOptionalOutputsAreStable(GameTestHelper helper) {
        var builtIn = recipe(helper, id("mekanism", "reaction/substrate/water_ethene"));
        require(builtIn.getInputFluid().getClass().getName().equals(TAGGED_FLUID_CLASS)
                        && builtIn.getInputFluid().serialize().getAsJsonObject().keySet()
                                .equals(Set.of("amount", "tag"))
                        && builtIn.getDuration() == 400,
                "built-in tagged Water reaction changed shape");
        var itemOnly = recipe(helper, id("pack_test", "reaction_item_only"))
                .getOutputDefinition().get(0);
        var gasOnly = recipe(helper, id("pack_test", "reaction_gas_only"))
                .getOutputDefinition().get(0);
        require(!itemOnly.item().isEmpty() && itemOnly.gas().isEmpty()
                        && gasOnly.item().isEmpty() && !gasOnly.gas().isEmpty(),
                "reaction optional output forms changed");
        helper.succeed();
    }

    private static PressurizedReactionRecipe recipe(GameTestHelper helper, ResourceLocation id) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(id).orElseThrow();
        require(loaded.getClass() == PressurizedReactionIRecipe.class
                        && loaded.getType() == MekanismRecipeType.REACTION.get()
                        && loaded.getType() == ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID)
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.REACTION.get()).contains(loaded),
                "reaction class/Type/serializer/index changed: " + id);
        return (PressurizedReactionRecipe) loaded;
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

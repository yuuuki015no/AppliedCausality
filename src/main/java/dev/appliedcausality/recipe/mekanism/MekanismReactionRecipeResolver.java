package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.recipes.PressurizedReactionRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exact Item, Fluid, Gas reaction with fixed optional Item and Gas outputs. */
final class MekanismReactionRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("reaction");
    private static final ResourceLocation MACHINE_ID = id("pressurized_reaction_chamber");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.PressurizedReactionIRecipe";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "item_fluid_gas_to_item_gas");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismReactionRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismReactionRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 reaction registry entry is unavailable");
        }
        return new MekanismReactionRecipeResolver(type, serializer);
    }

    @Override
    public RecipeType<?> supportedRecipeType() {
        return type;
    }

    @Override
    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null || !(context.pattern() instanceof AEProcessingPattern)
                || !recipe.getClass().getName().equals(RECIPE_CLASS)
                || recipe.getType() != type || recipe.getSerializer() != serializer
                || !(recipe instanceof PressurizedReactionRecipe reaction) || reaction.isIncomplete()
                || reaction.getDuration() <= 0) {
            return Optional.empty();
        }
        PatternInputs pattern = patternInputs(context.pattern());
        if (pattern == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(pattern.itemStack())) {
            return Optional.empty();
        }
        var itemAmount = MekanismItemToItemRecipeAdapter.exactRequiredAmount(
                reaction.getInputSolid(), pattern.itemStack(),
                MekanismItemToItemRecipeAdapter.SINGLE_INGREDIENT_CLASS);
        if (itemAmount.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = pattern.itemStack().copyWithCount(itemAmount.orElseThrow());
        ItemStack matchedItem = reaction.getInputSolid().getMatchingInstance(item);
        if (!reaction.getInputSolid().test(item) || matchedItem.isEmpty()
                || matchedItem.getCount() != item.getCount()
                || !ItemStack.isSameItemSameTags(matchedItem, item)
                || MekanismItemToItemRecipeAdapter.hasUnsupportedState(matchedItem)) {
            return Optional.empty();
        }
        FluidStack fluid = MekanismSeparatingRecipeResolver.exactInput(
                reaction.getInputFluid(), pattern.fluidKey());
        GasStack gas = MekanismChemicalInfusingRecipeResolver.exactInput(reaction.getInputGas());
        if (fluid == null || gas == null || !reaction.test(item, fluid, gas)) {
            return Optional.empty();
        }
        AEItemKey itemKey = AEItemKey.of(item);
        AEFluidKey fluidKey = AEFluidKey.of(fluid);
        MekanismKey gasKey = MekanismKey.of(gas);
        if (itemKey == null || fluidKey == null || gasKey == null
                || gasKey.getForm() != MekanismKey.GAS
                || !pattern.amounts().equals(Map.of(itemKey, (long) item.getCount(),
                        fluidKey, (long) fluid.getAmount(), gasKey, gas.getAmount()))) {
            return Optional.empty();
        }

        var output = reaction.getOutput(item, fluid, gas);
        List<PressurizedReactionRecipe.PressurizedReactionRecipeOutput> definitions =
                reaction.getOutputDefinition();
        if (output == null || definitions == null || definitions.size() != 1
                || !sameOutput(output, definitions.get(0))) {
            return Optional.empty();
        }
        Map<AEKey, Long> outputs = new HashMap<>();
        if (!output.item().isEmpty()) {
            if (MekanismItemToItemRecipeAdapter.hasUnsupportedState(output.item())) {
                return Optional.empty();
            }
            AEItemKey key = AEItemKey.of(output.item());
            if (key == null) {
                return Optional.empty();
            }
            outputs.put(key, (long) output.item().getCount());
        }
        if (!output.gas().isEmpty()) {
            MekanismKey key = MekanismKey.of(output.gas());
            if (key == null || key.getForm() != MekanismKey.GAS || output.gas().getAmount() <= 0) {
                return Optional.empty();
            }
            outputs.put(key, output.gas().getAmount());
        }
        if (outputs.isEmpty() || !outputs.equals(patternOutputs(context.pattern()))) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(itemKey, (long) item.getCount(), fluidKey, (long) fluid.getAmount(),
                        gasKey, gas.getAmount()), Map.copyOf(outputs), Map.of(), PROPERTIES));
    }

    private static boolean sameOutput(
            PressurizedReactionRecipe.PressurizedReactionRecipeOutput first,
            PressurizedReactionRecipe.PressurizedReactionRecipeOutput second) {
        if (second == null || first.item() == null || first.gas() == null
                || second.item() == null || second.gas() == null
                || first.item().isEmpty() != second.item().isEmpty()
                || first.gas().isEmpty() != second.gas().isEmpty()) {
            return false;
        }
        return (first.item().isEmpty()
                    || MekanismItemToItemRecipeAdapter.sameStackAndAmount(first.item(), second.item()))
                && (first.gas().isEmpty() || first.gas().equals(second.gas()));
    }

    private static PatternInputs patternInputs(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 3) {
            return null;
        }
        ItemStack item = null;
        AEFluidKey fluid = null;
        MekanismKey gas = null;
        Map<AEKey, Long> amounts = new HashMap<>();
        for (var input : inputs) {
            if (input == null || input.getMultiplier() <= 0) {
                return null;
            }
            var possible = input.getPossibleInputs();
            if (possible == null || possible.length != 1 || possible[0] == null
                    || possible[0].amount() <= 0) {
                return null;
            }
            AEKey key = possible[0].what();
            if (key instanceof AEItemKey itemKey && item == null) {
                item = itemKey.getReadOnlyStack();
            } else if (key instanceof AEFluidKey fluidKey && fluid == null) {
                fluid = fluidKey;
            } else if (key instanceof MekanismKey chemicalKey && gas == null
                    && chemicalKey.getForm() == MekanismKey.GAS
                    && chemicalKey.getStack() instanceof GasStack) {
                gas = chemicalKey;
            } else {
                return null;
            }
            try {
                amounts.put(key, Math.multiplyExact(possible[0].amount(), input.getMultiplier()));
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return item == null || fluid == null || gas == null
                ? null : new PatternInputs(item, fluid, amounts);
    }

    private static Map<AEKey, Long> patternOutputs(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        if (outputs == null || outputs.length < 1 || outputs.length > 2) {
            return null;
        }
        Map<AEKey, Long> amounts = new HashMap<>();
        for (var output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0
                    || amounts.put(output.what(), output.amount()) != null) {
                return null;
            }
        }
        return amounts;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private record PatternInputs(ItemStack itemStack, AEFluidKey fluidKey, Map<AEKey, Long> amounts) {
    }
}

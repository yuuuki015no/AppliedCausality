package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.recipes.RotaryRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Selects exactly one audited rotary direction from a Processing Pattern. */
final class MekanismRotaryRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("rotary");
    private static final ResourceLocation MACHINE_ID = id("rotary_condensentrator");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.RotaryIRecipe";
    private static final ProcessShapeId FLUID_TO_GAS =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "fluid_to_chemical");
    private static final ProcessShapeId GAS_TO_FLUID =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_fluid");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismRotaryRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismRotaryRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 rotary registry entry is unavailable");
        }
        return new MekanismRotaryRecipeResolver(type, serializer);
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
                || !(recipe instanceof RotaryRecipe rotary) || rotary.isIncomplete()) {
            return Optional.empty();
        }
        PatternPair pair = patternPair(context.pattern());
        if (pair == null) {
            return Optional.empty();
        }
        if (pair.inputKey() instanceof AEFluidKey fluidKey
                && pair.outputKey() instanceof MekanismKey gasKey
                && gasKey.getForm() == MekanismKey.GAS && gasKey.getStack() instanceof GasStack) {
            return fluidToGas(recipe, rotary, pair, fluidKey, gasKey);
        }
        if (pair.inputKey() instanceof MekanismKey gasKey
                && gasKey.getForm() == MekanismKey.GAS && gasKey.getStack() instanceof GasStack
                && pair.outputKey() instanceof AEFluidKey fluidKey) {
            return gasToFluid(recipe, rotary, pair, gasKey, fluidKey);
        }
        return Optional.empty();
    }

    private static Optional<ResolvedProcess> fluidToGas(Recipe<?> recipe, RotaryRecipe rotary,
            PatternPair pair, AEFluidKey fluidKey, MekanismKey gasKey) {
        if (!rotary.hasFluidToGas()) {
            return Optional.empty();
        }
        FluidStack input = MekanismSeparatingRecipeResolver.exactInput(rotary.getFluidInput(), fluidKey);
        if (input == null || !rotary.test(input)
                || !fluidKey.equals(AEFluidKey.of(input)) || pair.inputAmount() != input.getAmount()) {
            return Optional.empty();
        }
        GasStack output = rotary.getGasOutput(input);
        List<GasStack> definitions = rotary.getGasOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definitions == null || definitions.size() != 1 || !output.equals(definitions.get(0))
                || !gasKey.equals(MekanismKey.of(output)) || pair.outputAmount() != output.getAmount()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, FLUID_TO_GAS, TYPE_ID, MACHINE_ID,
                Map.of(fluidKey, (long) input.getAmount()), Map.of(gasKey, output.getAmount()),
                Map.of(), PROPERTIES));
    }

    private static Optional<ResolvedProcess> gasToFluid(Recipe<?> recipe, RotaryRecipe rotary,
            PatternPair pair, MekanismKey gasKey, AEFluidKey fluidKey) {
        if (!rotary.hasGasToFluid()) {
            return Optional.empty();
        }
        GasStack input = MekanismChemicalInfusingRecipeResolver.exactInput(rotary.getGasInput());
        if (input == null || !rotary.test(input)
                || !gasKey.equals(MekanismKey.of(input)) || pair.inputAmount() != input.getAmount()) {
            return Optional.empty();
        }
        FluidStack output = rotary.getFluidOutput(input);
        List<FluidStack> definitions = rotary.getFluidOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definitions == null || definitions.size() != 1 || definitions.get(0) == null
                || output.getAmount() != definitions.get(0).getAmount()
                || !output.isFluidEqual(definitions.get(0))
                || !fluidKey.equals(AEFluidKey.of(output)) || pair.outputAmount() != output.getAmount()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, GAS_TO_FLUID, TYPE_ID, MACHINE_ID,
                Map.of(gasKey, input.getAmount()), Map.of(fluidKey, (long) output.getAmount()),
                Map.of(), PROPERTIES));
    }

    private static PatternPair patternPair(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        var outputs = pattern.getOutputs();
        if (inputs == null || inputs.length != 1 || inputs[0] == null
                || inputs[0].getMultiplier() <= 0 || outputs == null || outputs.length != 1
                || outputs[0] == null || outputs[0].what() == null || outputs[0].amount() <= 0) {
            return null;
        }
        var possible = inputs[0].getPossibleInputs();
        if (possible == null || possible.length != 1 || possible[0] == null
                || possible[0].what() == null || possible[0].amount() <= 0) {
            return null;
        }
        try {
            long inputAmount = Math.multiplyExact(possible[0].amount(), inputs[0].getMultiplier());
            return new PatternPair(possible[0].what(), inputAmount,
                    outputs[0].what(), outputs[0].amount());
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private record PatternPair(AEKey inputKey, long inputAmount, AEKey outputKey, long outputAmount) {
    }
}

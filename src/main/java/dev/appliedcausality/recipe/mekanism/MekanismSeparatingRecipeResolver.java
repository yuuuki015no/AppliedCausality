package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.recipes.ElectrolysisRecipe;
import mekanism.api.recipes.ingredients.FluidStackIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact single-Fluid or tagged-Fluid to two fixed Gas outputs; Core authorizes execution. */
final class MekanismSeparatingRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("separating");
    private static final ResourceLocation MACHINE_ID = id("electrolytic_separator");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.ElectrolysisIRecipe";
    private static final String DIRECT_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$SingleFluidStackIngredient";
    private static final String TAGGED_FLUID_CLASS =
            "mekanism.common.recipe.ingredient.creator.FluidStackIngredientCreator$TaggedFluidStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "fluid_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismSeparatingRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismSeparatingRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 separating registry entry is unavailable");
        }
        return new MekanismSeparatingRecipeResolver(type, serializer);
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
                || !(recipe instanceof ElectrolysisRecipe separating) || separating.isIncomplete()) {
            return Optional.empty();
        }
        FluidStack input = exactInput(separating.getInput(), solePatternFluidKey(context.pattern()));
        if (input == null || !separating.test(input)) {
            return Optional.empty();
        }
        AEFluidKey inputKey = AEFluidKey.of(input);
        if (inputKey == null || !matchesInput(context.pattern(), inputKey, input.getAmount())) {
            return Optional.empty();
        }
        var output = separating.getOutput(input);
        var definition = separating.getOutputDefinition();
        if (output == null || definition == null || definition.size() != 1
                || !output.equals(definition.get(0))) {
            return Optional.empty();
        }
        Map<AEKey, Long> outputAmounts = new HashMap<>();
        if (!addGas(outputAmounts, output.left()) || !addGas(outputAmounts, output.right())
                || !outputAmounts.equals(patternOutputs(context.pattern()))) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(inputKey, (long) input.getAmount()), outputAmounts, Map.of(), PROPERTIES));
    }

    static FluidStack exactInput(FluidStackIngredient ingredient, AEFluidKey patternKey) {
        if (ingredient == null) {
            return null;
        }
        String className = ingredient.getClass().getName();
        String keyName = className.equals(DIRECT_FLUID_CLASS) ? "fluid"
                : className.equals(TAGGED_FLUID_CLASS) ? "tag" : null;
        if (keyName == null) {
            return null;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return null;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", keyName))
                || !(object.get(keyName) instanceof JsonPrimitive name) || !name.isString()
                || !(object.get("amount") instanceof JsonPrimitive number) || !number.isNumber()) {
            return null;
        }
        ResourceLocation declaredId = ResourceLocation.tryParse(name.getAsString());
        if (declaredId == null) {
            return null;
        }
        try {
            int amount = number.getAsBigDecimal().intValueExact();
            if (amount <= 0) {
                return null;
            }
            FluidStack input;
            if (keyName.equals("fluid")) {
                var fluid = ForgeRegistries.FLUIDS.getValue(declaredId);
                if (fluid == null || !declaredId.equals(ForgeRegistries.FLUIDS.getKey(fluid))) {
                    return null;
                }
                input = new FluidStack(fluid, amount);
            } else {
                if (patternKey == null || patternKey.getTag() != null) {
                    return null;
                }
                input = patternKey.toStack(amount);
            }
            FluidStack matched = ingredient.getMatchingInstance(input);
            return !input.isEmpty() && ingredient.testType(input) && ingredient.test(input)
                    && ingredient.getNeededAmount(input) == amount
                    && matched != null && !matched.isEmpty()
                    && matched.getAmount() == amount && matched.isFluidEqual(input)
                    ? input : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static AEFluidKey solePatternFluidKey(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 1 || inputs[0] == null) {
            return null;
        }
        var possible = inputs[0].getPossibleInputs();
        return possible != null && possible.length == 1 && possible[0] != null
                && possible[0].what() instanceof AEFluidKey key ? key : null;
    }

    private static boolean matchesInput(IPatternDetails pattern, AEFluidKey key, long amount) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 1 || inputs[0] == null
                || inputs[0].getMultiplier() <= 0) {
            return false;
        }
        var possible = inputs[0].getPossibleInputs();
        if (possible == null || possible.length != 1 || possible[0] == null
                || possible[0].amount() <= 0 || !(possible[0].what() instanceof AEFluidKey patternKey)
                || !key.equals(patternKey)) {
            return false;
        }
        try {
            return Math.multiplyExact(possible[0].amount(), inputs[0].getMultiplier()) == amount;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static boolean addGas(Map<AEKey, Long> amounts, GasStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) {
            return false;
        }
        MekanismKey key = MekanismKey.of(stack);
        if (key == null || key.getForm() != MekanismKey.GAS) {
            return false;
        }
        try {
            amounts.merge(key, stack.getAmount(), Math::addExact);
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static Map<AEKey, Long> patternOutputs(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        if (outputs == null || outputs.length < 1 || outputs.length > 2) {
            return null;
        }
        Map<AEKey, Long> amounts = new HashMap<>();
        for (var output : outputs) {
            if (output == null || output.amount() <= 0
                    || !(output.what() instanceof MekanismKey key)
                    || key.getForm() != MekanismKey.GAS
                    || !(key.getStack() instanceof GasStack)) {
                return null;
            }
            try {
                amounts.merge(key, output.amount(), Math::addExact);
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return amounts;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }
}

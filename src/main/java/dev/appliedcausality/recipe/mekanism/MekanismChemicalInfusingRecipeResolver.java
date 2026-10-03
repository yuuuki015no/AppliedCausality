package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
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
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.recipes.ChemicalInfuserRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient.GasStackIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact two-Gas candidate. Core validates the result against the active RecipeManager. */
final class MekanismChemicalInfusingRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("chemical_infusing");
    private static final ResourceLocation MACHINE_ID = id("chemical_infuser");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.ChemicalInfuserIRecipe";
    private static final String SINGLE_GAS_CLASS =
            "mekanism.common.recipe.ingredient.creator.GasStackIngredientCreator$SingleGasStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismChemicalInfusingRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismChemicalInfusingRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 chemical_infusing registry entry is unavailable");
        }
        return new MekanismChemicalInfusingRecipeResolver(type, serializer);
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
                || !(recipe instanceof ChemicalInfuserRecipe infusing) || infusing.isIncomplete()) {
            return Optional.empty();
        }
        GasStack left = exactInput(infusing.getLeftInput());
        GasStack right = exactInput(infusing.getRightInput());
        if (left == null || right == null || !infusing.test(left, right)
                || !infusing.test(right, left)) {
            return Optional.empty();
        }
        MekanismKey leftKey = MekanismKey.of(left);
        MekanismKey rightKey = MekanismKey.of(right);
        if (leftKey == null || rightKey == null || leftKey.getForm() != MekanismKey.GAS
                || rightKey.getForm() != MekanismKey.GAS) {
            return Optional.empty();
        }
        Map<AEKey, Long> recipeInputs = new HashMap<>();
        try {
            recipeInputs.merge(leftKey, left.getAmount(), Math::addExact);
            recipeInputs.merge(rightKey, right.getAmount(), Math::addExact);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        if (!recipeInputs.equals(patternInputs(context.pattern()))) {
            return Optional.empty();
        }
        GasStack output = infusing.getOutput(left, right);
        List<GasStack> definition = infusing.getOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definition == null || definition.size() != 1 || !output.equals(definition.get(0))
                || !output.equals(infusing.getOutput(right, left))) {
            return Optional.empty();
        }
        MekanismKey outputKey = MekanismKey.of(output);
        var outputs = context.pattern().getOutputs();
        if (outputKey == null || outputKey.getForm() != MekanismKey.GAS
                || outputs == null || outputs.length != 1 || outputs[0] == null
                || !outputKey.equals(outputs[0].what()) || outputs[0].amount() != output.getAmount()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                recipeInputs, Map.of(outputKey, output.getAmount()), Map.of(), PROPERTIES));
    }

    static GasStack exactInput(GasStackIngredient ingredient) {
        if (ingredient == null || !ingredient.getClass().getName().equals(SINGLE_GAS_CLASS)) {
            return null;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return null;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", "gas"))
                || !(object.get("gas") instanceof JsonPrimitive name) || !name.isString()
                || !(object.get("amount") instanceof JsonPrimitive number) || !number.isNumber()) {
            return null;
        }
        ResourceLocation gasId = ResourceLocation.tryParse(name.getAsString());
        if (gasId == null) {
            return null;
        }
        try {
            long amount = number.getAsBigDecimal().longValueExact();
            if (amount <= 0) {
                return null;
            }
            var gas = MekanismAPI.gasRegistry().getValue(gasId);
            if (gas == null || !gasId.equals(MekanismAPI.gasRegistry().getKey(gas))) {
                return null;
            }
            GasStack stack = gas.getStack(amount);
            GasStack matched = ingredient.getMatchingInstance(stack);
            return !stack.isEmpty() && ingredient.test(stack)
                    && ingredient.getNeededAmount(stack) == amount
                    && matched != null && !matched.isEmpty()
                    && matched.getAmount() == amount && matched.isTypeEqual(stack) ? stack : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static Map<AEKey, Long> patternInputs(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length < 1 || inputs.length > 2) {
            return null;
        }
        Map<AEKey, Long> amounts = new HashMap<>();
        for (var input : inputs) {
            if (input == null || input.getMultiplier() <= 0) {
                return null;
            }
            var possible = input.getPossibleInputs();
            if (possible == null || possible.length != 1 || possible[0] == null
                    || possible[0].amount() <= 0
                    || !(possible[0].what() instanceof MekanismKey key)
                    || key.getForm() != MekanismKey.GAS
                    || !(key.getStack() instanceof GasStack)) {
                return null;
            }
            try {
                long amount = Math.multiplyExact(possible[0].amount(), input.getMultiplier());
                amounts.merge(key, amount, Math::addExact);
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

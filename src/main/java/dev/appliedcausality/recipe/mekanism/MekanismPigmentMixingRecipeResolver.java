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
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.recipes.PigmentMixingRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient.PigmentStackIngredient;
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

/** Exact two-Pigment candidate. Core validates the result against the active RecipeManager. */
final class MekanismPigmentMixingRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("pigment_mixing");
    private static final ResourceLocation MACHINE_ID = id("pigment_mixer");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.PigmentMixingIRecipe";
    private static final String SINGLE_PIGMENT_CLASS =
            "mekanism.common.recipe.ingredient.creator.PigmentStackIngredientCreator$SinglePigmentStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismPigmentMixingRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismPigmentMixingRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 pigment_mixing registry entry is unavailable");
        }
        return new MekanismPigmentMixingRecipeResolver(type, serializer);
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
                || !(recipe instanceof PigmentMixingRecipe mixing) || mixing.isIncomplete()) {
            return Optional.empty();
        }
        PigmentStack left = exactInput(mixing.getLeftInput());
        PigmentStack right = exactInput(mixing.getRightInput());
        if (left == null || right == null || !mixing.test(left, right)
                || !mixing.test(right, left)) {
            return Optional.empty();
        }
        MekanismKey leftKey = MekanismKey.of(left);
        MekanismKey rightKey = MekanismKey.of(right);
        if (leftKey == null || rightKey == null || leftKey.getForm() != MekanismKey.PIGMENT
                || rightKey.getForm() != MekanismKey.PIGMENT) {
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
        PigmentStack output = mixing.getOutput(left, right);
        List<PigmentStack> definition = mixing.getOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definition == null || definition.size() != 1 || !output.equals(definition.get(0))
                || !output.equals(mixing.getOutput(right, left))) {
            return Optional.empty();
        }
        MekanismKey outputKey = MekanismKey.of(output);
        var outputs = context.pattern().getOutputs();
        if (outputKey == null || outputKey.getForm() != MekanismKey.PIGMENT
                || outputs == null || outputs.length != 1 || outputs[0] == null
                || !outputKey.equals(outputs[0].what()) || outputs[0].amount() != output.getAmount()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                recipeInputs, Map.of(outputKey, output.getAmount()), Map.of(), PROPERTIES));
    }

    private static PigmentStack exactInput(PigmentStackIngredient ingredient) {
        if (ingredient == null || !ingredient.getClass().getName().equals(SINGLE_PIGMENT_CLASS)) {
            return null;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return null;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", "pigment"))
                || !(object.get("pigment") instanceof JsonPrimitive name) || !name.isString()
                || !(object.get("amount") instanceof JsonPrimitive number) || !number.isNumber()) {
            return null;
        }
        ResourceLocation pigmentId = ResourceLocation.tryParse(name.getAsString());
        if (pigmentId == null) {
            return null;
        }
        try {
            long amount = number.getAsBigDecimal().longValueExact();
            if (amount <= 0) {
                return null;
            }
            var pigment = MekanismAPI.pigmentRegistry().getValue(pigmentId);
            if (pigment == null || !pigmentId.equals(MekanismAPI.pigmentRegistry().getKey(pigment))) {
                return null;
            }
            PigmentStack stack = pigment.getStack(amount);
            PigmentStack matched = ingredient.getMatchingInstance(stack);
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
                    || key.getForm() != MekanismKey.PIGMENT
                    || !(key.getStack() instanceof PigmentStack)) {
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

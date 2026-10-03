package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
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
import mekanism.api.recipes.GasToGasRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact fixed Gas-to-Gas recipe candidate; Core alone authorizes execution. */
final class MekanismGasToGasRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("centrifuging");
    private static final ResourceLocation MACHINE_ID = id("isotopic_centrifuge");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.CentrifugingIRecipe";
    private static final String SINGLE_GAS_CLASS =
            "mekanism.common.recipe.ingredient.creator.GasStackIngredientCreator$SingleGasStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismGasToGasRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismGasToGasRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 centrifuging registry entry is unavailable");
        }
        return new MekanismGasToGasRecipeResolver(type, serializer);
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
                || !(recipe instanceof GasToGasRecipe centrifuging) || centrifuging.isIncomplete()) {
            return Optional.empty();
        }
        IPatternDetails pattern = context.pattern();
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 1 || inputs[0] == null
                || inputs[0].getMultiplier() <= 0) {
            return Optional.empty();
        }
        var possible = inputs[0].getPossibleInputs();
        if (possible == null || possible.length != 1 || possible[0] == null
                || possible[0].amount() <= 0
                || !(possible[0].what() instanceof MekanismKey inputKey)
                || inputKey.getForm() != MekanismKey.GAS
                || !(inputKey.getStack() instanceof GasStack base)) {
            return Optional.empty();
        }

        long amount = exactGasAmount(centrifuging, base);
        if (amount <= 0 || inputs[0].getMultiplier() != amount) {
            return Optional.empty();
        }
        GasStack input = new GasStack(base, amount);
        GasStack matching = centrifuging.getInput().getMatchingInstance(input);
        if (!centrifuging.getInput().test(input) || matching == null || matching.isEmpty()
                || matching.getAmount() != amount || !matching.isTypeEqual(input)
                || !centrifuging.test(input)) {
            return Optional.empty();
        }

        GasStack output = centrifuging.getOutput(input);
        List<GasStack> definition = centrifuging.getOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definition == null || definition.size() != 1 || !output.equals(definition.get(0))) {
            return Optional.empty();
        }
        MekanismKey outputKey = MekanismKey.of(output);
        if (outputKey == null || outputKey.getForm() != MekanismKey.GAS
                || !matchesSinglePatternOutput(pattern, outputKey, output.getAmount())) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(inputKey, amount), Map.of(outputKey, output.getAmount()), Map.of(), PROPERTIES));
    }

    private static long exactGasAmount(GasToGasRecipe recipe, GasStack input) {
        var ingredient = recipe.getInput();
        String className = ingredient.getClass().getName();
        if (!className.equals(SINGLE_GAS_CLASS)) {
            return 0;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return 0;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", "gas"))) {
            return 0;
        }
        JsonElement id = object.get("gas");
        JsonElement amountElement = object.get("amount");
        if (!(id instanceof JsonPrimitive name) || !name.isString()
                || ResourceLocation.tryParse(name.getAsString()) == null
                || !(amountElement instanceof JsonPrimitive number) || !number.isNumber()) {
            return 0;
        }
        try {
            long declared = number.getAsBigDecimal().longValueExact();
            return declared > 0 && ingredient.testType(input)
                    && ingredient.getNeededAmount(input) == declared ? declared : 0;
        } catch (ArithmeticException | NumberFormatException exception) {
            return 0;
        }
    }

    private static boolean matchesSinglePatternOutput(IPatternDetails pattern, MekanismKey key, long amount) {
        var outputs = pattern.getOutputs();
        return outputs != null && outputs.length == 1 && outputs[0] != null
                && outputs[0].amount() == amount && key.equals(outputs[0].what());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }
}

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
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.recipes.FluidSlurryToSlurryRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient.SlurryStackIngredient;
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

/** One exact Fluid and one exact Slurry input to a fixed Slurry output. */
final class MekanismWashingRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("washing");
    private static final ResourceLocation MACHINE_ID = id("chemical_washer");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.FluidSlurryToSlurryIRecipe";
    private static final String SINGLE_SLURRY_CLASS =
            "mekanism.common.recipe.ingredient.creator.SlurryStackIngredientCreator$SingleSlurryStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "fluid_and_chemical_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismWashingRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismWashingRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 washing registry entry is unavailable");
        }
        return new MekanismWashingRecipeResolver(type, serializer);
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
                || !(recipe instanceof FluidSlurryToSlurryRecipe washing) || washing.isIncomplete()) {
            return Optional.empty();
        }
        PatternInputs patternInputs = patternInputs(context.pattern());
        if (patternInputs == null) {
            return Optional.empty();
        }
        FluidStack fluid = MekanismSeparatingRecipeResolver.exactInput(
                washing.getFluidInput(), patternInputs.fluidKey());
        SlurryStack slurry = exactSlurry(washing.getChemicalInput());
        if (fluid == null || slurry == null || !washing.test(fluid, slurry)) {
            return Optional.empty();
        }
        AEFluidKey fluidKey = AEFluidKey.of(fluid);
        MekanismKey slurryKey = MekanismKey.of(slurry);
        if (fluidKey == null || slurryKey == null || slurryKey.getForm() != MekanismKey.SLURRY
                || !patternInputs.amounts().equals(Map.of(fluidKey, (long) fluid.getAmount(),
                        slurryKey, slurry.getAmount()))) {
            return Optional.empty();
        }
        SlurryStack output = washing.getOutput(fluid, slurry);
        List<SlurryStack> definition = washing.getOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definition == null || definition.size() != 1 || !output.equals(definition.get(0))) {
            return Optional.empty();
        }
        MekanismKey outputKey = MekanismKey.of(output);
        var outputs = context.pattern().getOutputs();
        if (outputKey == null || outputKey.getForm() != MekanismKey.SLURRY
                || outputs == null || outputs.length != 1 || outputs[0] == null
                || !outputKey.equals(outputs[0].what()) || outputs[0].amount() != output.getAmount()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(fluidKey, (long) fluid.getAmount(), slurryKey, slurry.getAmount()),
                Map.of(outputKey, output.getAmount()), Map.of(), PROPERTIES));
    }

    private static SlurryStack exactSlurry(SlurryStackIngredient ingredient) {
        if (ingredient == null || !ingredient.getClass().getName().equals(SINGLE_SLURRY_CLASS)) {
            return null;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return null;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", "slurry"))
                || !(object.get("slurry") instanceof JsonPrimitive name) || !name.isString()
                || !(object.get("amount") instanceof JsonPrimitive number) || !number.isNumber()) {
            return null;
        }
        ResourceLocation slurryId = ResourceLocation.tryParse(name.getAsString());
        if (slurryId == null) {
            return null;
        }
        try {
            long amount = number.getAsBigDecimal().longValueExact();
            if (amount <= 0) {
                return null;
            }
            var type = MekanismAPI.slurryRegistry().getValue(slurryId);
            if (type == null || !slurryId.equals(MekanismAPI.slurryRegistry().getKey(type))) {
                return null;
            }
            SlurryStack input = type.getStack(amount);
            SlurryStack matched = ingredient.getMatchingInstance(input);
            return !input.isEmpty() && ingredient.test(input)
                    && ingredient.getNeededAmount(input) == amount
                    && matched != null && !matched.isEmpty()
                    && matched.getAmount() == amount && matched.isTypeEqual(input) ? input : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static PatternInputs patternInputs(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 2) {
            return null;
        }
        AEFluidKey fluid = null;
        MekanismKey slurry = null;
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
            if (key instanceof AEFluidKey fluidKey && fluid == null) {
                fluid = fluidKey;
            } else if (key instanceof MekanismKey chemicalKey && slurry == null
                    && chemicalKey.getForm() == MekanismKey.SLURRY
                    && chemicalKey.getStack() instanceof SlurryStack) {
                slurry = chemicalKey;
            } else {
                return null;
            }
            try {
                amounts.put(key, Math.multiplyExact(possible[0].amount(), input.getMultiplier()));
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return fluid == null || slurry == null ? null : new PatternInputs(fluid, amounts);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private record PatternInputs(AEFluidKey fluidKey, Map<AEKey, Long> amounts) {
    }
}

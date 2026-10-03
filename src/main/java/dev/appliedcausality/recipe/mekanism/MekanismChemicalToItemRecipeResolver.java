package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
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
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.merged.BoxedChemicalStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.recipes.ChemicalCrystallizerRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact single-Chemical to fixed-Item candidate; Core alone authorizes execution. */
final class MekanismChemicalToItemRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = id("crystallizing");
    private static final ResourceLocation MACHINE_ID = id("chemical_crystallizer");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.ChemicalCrystallizerIRecipe";
    private static final String INGREDIENT_PACKAGE = "mekanism.common.recipe.ingredient.creator.";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismChemicalToItemRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismChemicalToItemRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 crystallizing registry entry is unavailable");
        }
        return new MekanismChemicalToItemRecipeResolver(type, serializer);
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
                || !(recipe instanceof ChemicalCrystallizerRecipe crystallizing)
                || crystallizing.isIncomplete()) {
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
                || !(possible[0].what() instanceof MekanismKey inputKey)) {
            return Optional.empty();
        }
        ChemicalStack<?> base = inputKey.getStack();
        if (base == null || base.isEmpty()) {
            return Optional.empty();
        }
        ChemicalType form = exactForm(inputKey.getForm(), base);
        if (form == null) {
            return Optional.empty();
        }
        FormGate gate = FormGate.forKey(inputKey.getForm(), form);
        long amount = exactChemicalAmount(crystallizing.getInput(), gate);
        if (amount <= 0 || inputs[0].getMultiplier() != amount
                || ChemicalType.getTypeFor(crystallizing.getInput()) != form) {
            return Optional.empty();
        }
        ChemicalStack<?> input = base.copy();
        input.setAmount(amount);
        if (!matchesIngredient(crystallizing.getInput(), input, amount)
                || !crystallizing.test(input) || !crystallizing.test(BoxedChemicalStack.box(input))) {
            return Optional.empty();
        }

        ItemStack output = crystallizing.getOutput(BoxedChemicalStack.box(input));
        List<ItemStack> definitions = crystallizing.getOutputDefinition();
        if (output == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(output)
                || definitions == null || definitions.size() != 1
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(output, definitions.get(0))
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(
                        output, crystallizing.getResultItem(context.registryAccess()))
                || !MekanismItemToItemRecipeAdapter.matchesSinglePatternOutput(pattern, output)) {
            return Optional.empty();
        }
        AEItemKey outputKey = AEItemKey.of(output);
        if (outputKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(inputKey, amount), Map.of(outputKey, (long) output.getCount()), Map.of(), PROPERTIES));
    }

    private static long exactChemicalAmount(ChemicalStackIngredient<?, ?> ingredient, FormGate gate) {
        if (!ingredient.getClass().getName().equals(gate.ingredientClass())) {
            return 0;
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return 0;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("amount", gate.serializedKey()))) {
            return 0;
        }
        JsonElement id = object.get(gate.serializedKey());
        JsonElement amount = object.get("amount");
        if (!(id instanceof JsonPrimitive name) || !name.isString()
                || ResourceLocation.tryParse(name.getAsString()) == null
                || !(amount instanceof JsonPrimitive number) || !number.isNumber()) {
            return 0;
        }
        try {
            long declared = number.getAsBigDecimal().longValueExact();
            return declared > 0 ? declared : 0;
        } catch (ArithmeticException | NumberFormatException exception) {
            return 0;
        }
    }

    private static ChemicalType exactForm(int keyForm, ChemicalStack<?> stack) {
        if (keyForm == MekanismKey.GAS && stack instanceof GasStack) {
            return ChemicalType.GAS;
        }
        if (keyForm == MekanismKey.INFUSION && stack instanceof InfusionStack) {
            return ChemicalType.INFUSION;
        }
        if (keyForm == MekanismKey.PIGMENT && stack instanceof PigmentStack) {
            return ChemicalType.PIGMENT;
        }
        return keyForm == MekanismKey.SLURRY && stack instanceof SlurryStack ? ChemicalType.SLURRY : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean matchesIngredient(ChemicalStackIngredient ingredient,
            ChemicalStack<?> input, long amount) {
        if (!ingredient.test(input) || ingredient.getNeededAmount(input) != amount) {
            return false;
        }
        Object matched = ingredient.getMatchingInstance(input);
        return matched instanceof ChemicalStack<?> stack && !stack.isEmpty()
                && stack.getAmount() == amount && stack.getType().equals(input.getType());
    }

    private record FormGate(String serializedKey, String ingredientClass) {
        private static FormGate forKey(int keyForm, ChemicalType type) {
            String form = switch (type) {
                case GAS -> keyForm == MekanismKey.GAS ? "Gas" : null;
                case INFUSION -> keyForm == MekanismKey.INFUSION ? "Infusion" : null;
                case PIGMENT -> keyForm == MekanismKey.PIGMENT ? "Pigment" : null;
                case SLURRY -> keyForm == MekanismKey.SLURRY ? "Slurry" : null;
            };
            if (form == null) {
                return null;
            }
            String serialized = type.getSerializedName();
            return new FormGate(serialized, INGREDIENT_PACKAGE + form
                    + "StackIngredientCreator$Single" + form + "StackIngredient");
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }
}

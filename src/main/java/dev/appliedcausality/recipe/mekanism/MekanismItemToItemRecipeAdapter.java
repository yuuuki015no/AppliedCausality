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
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Conservative adapter shared by Mekanism 10.4.16's three built-in
 * item-to-item recipe types.
 *
 * <p>The exact built-in recipe, serializer, and single-ingredient classes are
 * required. Addon subclasses and custom ingredient semantics fail closed.</p>
 */
final class MekanismItemToItemRecipeAdapter implements MekanismRecipeFamilyResolver {
    static final String SINGLE_INGREDIENT_CLASS =
            "mekanism.common.recipe.ingredient.creator.ItemStackIngredientCreator$SingleItemStackIngredient";
    private static final ProcessShapeId PROCESS_SHAPE = ProcessShapeId.of(
            AppliedCausality.MOD_ID, "item_to_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final ResourceLocation recipeTypeId;
    private final RecipeType<?> recipeType;
    private final RecipeSerializer<?> recipeSerializer;
    private final String expectedRecipeClass;
    private final String expectedIngredientClass;
    private final ResourceLocation machineProfile;

    MekanismItemToItemRecipeAdapter(
            ResourceLocation recipeTypeId,
            RecipeType<?> recipeType,
            RecipeSerializer<?> recipeSerializer,
            String expectedRecipeClass,
            String expectedIngredientClass,
            ResourceLocation machineProfile) {
        this.recipeTypeId = Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        this.recipeType = Objects.requireNonNull(recipeType, "recipeType");
        this.recipeSerializer = Objects.requireNonNull(recipeSerializer, "recipeSerializer");
        this.expectedRecipeClass = Objects.requireNonNull(expectedRecipeClass, "expectedRecipeClass");
        this.expectedIngredientClass = Objects.requireNonNull(expectedIngredientClass, "expectedIngredientClass");
        this.machineProfile = Objects.requireNonNull(machineProfile, "machineProfile");
    }

    /** Creates the exact 10.4.16 adapters after Forge has registered Mekanism. */
    static List<MekanismItemToItemRecipeAdapter> createBundledResolvers() {
        return List.of(
                create(
                        "enriching",
                        "mekanism.common.recipe.impl.EnrichingIRecipe",
                        "enrichment_chamber"),
                create(
                        "crushing",
                        "mekanism.common.recipe.impl.CrushingIRecipe",
                        "crusher"),
                create(
                        "smelting",
                        "mekanism.common.recipe.impl.SmeltingIRecipe",
                        "energized_smelter"));
    }

    private static MekanismItemToItemRecipeAdapter create(
            String recipePath,
            String recipeClass,
            String machinePath) {
        ResourceLocation recipeTypeId = ResourceLocation.fromNamespaceAndPath("mekanism", recipePath);
        RecipeType<?> recipeType = ForgeRegistries.RECIPE_TYPES.getValue(recipeTypeId);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(recipeTypeId);
        if (recipeType == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 recipe registry entry is unavailable: "
                    + recipeTypeId);
        }
        return new MekanismItemToItemRecipeAdapter(
                recipeTypeId,
                recipeType,
                serializer,
                recipeClass,
                SINGLE_INGREDIENT_CLASS,
                ResourceLocation.fromNamespaceAndPath("mekanism", machinePath));
    }

    public List<RecipeType<?>> supportedRecipeTypes() {
        return List.of(recipeType);
    }

    @Override
    public RecipeType<?> supportedRecipeType() {
        return recipeType;
    }

    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null) {
            return Optional.empty();
        }
        return resolveRecipe(recipe, context.registryAccess(), context.pattern());
    }

    Optional<ResolvedProcess> resolveRecipe(
            Recipe<?> recipe,
            RegistryAccess registryAccess,
            IPatternDetails pattern) {
        if (recipe == null || registryAccess == null || pattern == null
                || !(pattern instanceof AEProcessingPattern)
                || !(recipe instanceof ItemStackToItemStackRecipe itemRecipe)
                || !recipe.getClass().getName().equals(expectedRecipeClass)
                || recipe.getType() != recipeType
                || recipe.getSerializer() != recipeSerializer
                || itemRecipe.isIncomplete()) {
            return Optional.empty();
        }

        ItemStack input = concretePatternInput(pattern).orElse(null);
        if (input == null || hasUnsupportedState(input)) {
            return Optional.empty();
        }

        ItemStackIngredient ingredient = itemRecipe.getInput();
        Optional<Integer> requiredAmount = exactRequiredAmount(ingredient, input, expectedIngredientClass);
        if (requiredAmount.isEmpty()) {
            return Optional.empty();
        }

        ItemStack recipeInput = input.copyWithCount(requiredAmount.orElseThrow());
        if (!ingredient.test(recipeInput)) {
            return Optional.empty();
        }
        ItemStack matchingInput = ingredient.getMatchingInstance(recipeInput);
        if (matchingInput.isEmpty()
                || matchingInput.getCount() != requiredAmount.orElseThrow()
                || !ItemStack.isSameItemSameTags(matchingInput, recipeInput)
                || hasUnsupportedState(matchingInput)) {
            return Optional.empty();
        }

        ItemStack output = itemRecipe.getOutput(recipeInput);
        if (!isExactDeterministicOutput(itemRecipe, registryAccess, output)
                || hasUnsupportedState(output)
                || !matchesSinglePatternOutput(pattern, output)) {
            return Optional.empty();
        }

        AEItemKey inputKey = AEItemKey.of(input);
        AEItemKey outputKey = AEItemKey.of(output);
        if (inputKey == null || outputKey == null) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedProcess(
                recipe.getId(),
                recipeTypeId,
                PROCESS_SHAPE,
                recipeTypeId,
                machineProfile,
                Map.of(inputKey, requiredAmount.orElseThrow().longValue()),
                Map.of(outputKey, (long) output.getCount()),
                Map.of(),
                PROPERTIES));
    }

    static Optional<Integer> exactRequiredAmount(
            ItemStackIngredient ingredient, ItemStack input, String expectedIngredientClass) {
        if (ingredient == null || !ingredient.getClass().getName().equals(expectedIngredientClass)) {
            return Optional.empty();
        }
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("ingredient"))
                && !object.keySet().equals(Set.of("ingredient", "amount"))) {
            return Optional.empty();
        }
        if (!isVanillaSingleIngredient(object.get("ingredient"))) {
            return Optional.empty();
        }

        int declaredAmount = 1;
        if (object.has("amount")) {
            JsonElement amountElement = object.get("amount");
            if (!(amountElement instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                return Optional.empty();
            }
            try {
                declaredAmount = primitive.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                return Optional.empty();
            }
        }
        if (declaredAmount <= 0
                || !ingredient.testType(input)
                || ingredient.getNeededAmount(input) != declaredAmount) {
            return Optional.empty();
        }
        return Optional.of(declaredAmount);
    }

    private static boolean isVanillaSingleIngredient(JsonElement serialized) {
        if (serialized == null || !serialized.isJsonObject()) {
            return false;
        }
        JsonObject object = serialized.getAsJsonObject();
        if (!object.keySet().equals(Set.of("item")) && !object.keySet().equals(Set.of("tag"))) {
            return false;
        }
        JsonElement id = object.has("item") ? object.get("item") : object.get("tag");
        return id instanceof JsonPrimitive primitive
                && primitive.isString()
                && ResourceLocation.tryParse(primitive.getAsString()) != null;
    }

    private static boolean isExactDeterministicOutput(
            ItemStackToItemStackRecipe recipe,
            RegistryAccess registryAccess,
            ItemStack output) {
        if (output == null || output.isEmpty() || output.getCount() <= 0) {
            return false;
        }
        List<ItemStack> outputDefinition = recipe.getOutputDefinition();
        if (outputDefinition == null || outputDefinition.size() != 1) {
            return false;
        }
        ItemStack declaredOutput = outputDefinition.get(0);
        ItemStack resultItem = recipe.getResultItem(registryAccess);
        return sameStackAndAmount(output, declaredOutput) && sameStackAndAmount(output, resultItem);
    }

    static boolean sameStackAndAmount(ItemStack first, ItemStack second) {
        return second != null
                && !second.isEmpty()
                && first.getCount() == second.getCount()
                && ItemStack.isSameItemSameTags(first, second);
    }

    /**
     * Candidate discovery may use the recipe-derived output to discard recipes
     * for other Mekanism processes that share the same input. Core validation
     * still performs the authoritative full pattern comparison afterwards.
     */
    static boolean matchesSinglePatternOutput(IPatternDetails pattern, ItemStack recipeOutput) {
        var outputs = pattern.getOutputs();
        if (outputs == null || outputs.length != 1 || outputs[0] == null
                || outputs[0].amount() != recipeOutput.getCount()
                || !(outputs[0].what() instanceof AEItemKey outputKey)) {
            return false;
        }
        AEItemKey recipeOutputKey = AEItemKey.of(recipeOutput);
        return recipeOutputKey != null && recipeOutputKey.equals(outputKey);
    }

    static Optional<ItemStack> concretePatternInput(IPatternDetails pattern) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 1 || inputs[0] == null) {
            return Optional.empty();
        }
        var possibleInputs = inputs[0].getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length != 1 || possibleInputs[0] == null
                || !(possibleInputs[0].what() instanceof AEItemKey itemKey)) {
            return Optional.empty();
        }
        return Optional.of(itemKey.getReadOnlyStack());
    }

    static boolean hasUnsupportedState(ItemStack stack) {
        if (stack.isEmpty() || stack.hasTag() || stack.isDamageableItem()) {
            return true;
        }
        return stack.save(new CompoundTag()).contains("ForgeCaps");
    }
}

package dev.appliedcausality.recipe.vanilla;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonObject;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conservative adapter for the v0.1 Vanilla Smelting vertical slice.
 */
public final class VanillaSmeltingRecipeAdapter implements IBulkRecipeAdapter {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            AppliedCausality.MOD_ID, "vanilla_smelting");

    private static final ResourceLocation RECIPE_TYPE_ID = ResourceLocation.withDefaultNamespace("smelting");
    private static final ProcessShapeId PROCESS_SHAPE = ProcessShapeId.of(
            AppliedCausality.MOD_ID, "item_to_item");
    private static final ResourceLocation PROCESS_FAMILY = ResourceLocation.withDefaultNamespace("smelting");
    private static final ResourceLocation MACHINE_PROFILE = ResourceLocation.withDefaultNamespace("furnace");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public List<RecipeType<?>> supportedRecipeTypes() {
        return List.of(RecipeType.SMELTING);
    }

    @Override
    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null) {
            return Optional.empty();
        }
        return resolveRecipe(recipe, context.registryAccess(), context.pattern());
    }

    static Optional<ResolvedProcess> resolveRecipe(
            Recipe<?> recipe,
            RegistryAccess registryAccess,
            IPatternDetails pattern) {
        if (recipe == null || registryAccess == null || pattern == null
                || !(pattern instanceof AEProcessingPattern)
                || recipe.getClass() != SmeltingRecipe.class
                || recipe.getType() != RecipeType.SMELTING
                || recipe.getSerializer() != RecipeSerializer.SMELTING_RECIPE) {
            return Optional.empty();
        }

        var ingredients = recipe.getIngredients();
        if (ingredients.size() != 1) {
            return Optional.empty();
        }
        Ingredient ingredient = ingredients.get(0);
        var serializedIngredient = ingredient.toJson();
        if (!ingredient.isVanilla()
                || !serializedIngredient.isJsonObject()
                || !isSupportedSingleIngredient(serializedIngredient.getAsJsonObject())) {
            return Optional.empty();
        }

        ItemStack input = concretePatternInput(pattern).orElse(null);
        ItemStack output = recipe.getResultItem(registryAccess);
        if (input == null || hasUnsupportedState(input) || hasUnsupportedState(output)
                || input.is(Blocks.WET_SPONGE.asItem())
                || !ingredient.test(input)) {
            return Optional.empty();
        }

        AEItemKey inputKey = AEItemKey.of(input);
        AEItemKey outputKey = AEItemKey.of(output);
        if (inputKey == null || outputKey == null || output.getCount() <= 0) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedProcess(
                recipe.getId(),
                RECIPE_TYPE_ID,
                PROCESS_SHAPE,
                PROCESS_FAMILY,
                MACHINE_PROFILE,
                Map.of(inputKey, 1L),
                Map.of(outputKey, (long) output.getCount()),
                Map.of(),
                PROPERTIES));
    }

    private static boolean isSupportedSingleIngredient(JsonObject ingredient) {
        return ingredient.size() == 1 && (ingredient.has("item") || ingredient.has("tag"));
    }

    private static Optional<ItemStack> concretePatternInput(IPatternDetails pattern) {
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

    private static boolean hasUnsupportedState(ItemStack stack) {
        if (stack.isEmpty() || stack.hasTag() || stack.isDamageableItem()) {
            return true;
        }
        return stack.save(new CompoundTag()).contains("ForgeCaps");
    }
}

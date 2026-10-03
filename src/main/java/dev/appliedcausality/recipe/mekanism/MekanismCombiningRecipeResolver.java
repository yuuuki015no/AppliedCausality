package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AEProcessingPattern;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import mekanism.api.recipes.CombinerRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exact two-role resolver for Mekanism 10.4.16 Combiner recipes. */
final class MekanismCombiningRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "combining");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.CombinerIRecipe";
    private static final ResourceLocation MACHINE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "combiner");
    private static final ProcessShapeId SHAPE = ProcessShapeId.of(AppliedCausality.MOD_ID, "item_to_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismCombiningRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismCombiningRecipeResolver createBundled() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 combining registry entry is unavailable");
        }
        return new MekanismCombiningRecipeResolver(type, serializer);
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
                || !(recipe instanceof CombinerRecipe combiner) || combiner.isIncomplete()) {
            return Optional.empty();
        }

        List<AEItemKey> keys = concreteInputKeys(context.pattern());
        if (keys.isEmpty() || keys.size() > 2) {
            return Optional.empty();
        }
        RoleAssignment assignment = uniqueAssignment(combiner, keys).orElse(null);
        if (assignment == null) {
            return Optional.empty();
        }

        ItemStack output = combiner.getOutput(assignment.main(), assignment.extra());
        List<ItemStack> definitions = combiner.getOutputDefinition();
        if (output == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(output)
                || definitions == null || definitions.size() != 1
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(output, definitions.get(0))
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(
                        output, combiner.getResultItem(context.registryAccess()))
                || !MekanismItemToItemRecipeAdapter.matchesSinglePatternOutput(context.pattern(), output)) {
            return Optional.empty();
        }

        AEItemKey outputKey = AEItemKey.of(output);
        if (outputKey == null) {
            return Optional.empty();
        }
        Map<AEItemKey, Long> amounts = new HashMap<>();
        try {
            amounts.merge(assignment.mainKey(), (long) assignment.main().getCount(), Math::addExact);
            amounts.merge(assignment.extraKey(), (long) assignment.extra().getCount(), Math::addExact);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.copyOf(amounts), Map.of(outputKey, (long) output.getCount()), Map.of(), PROPERTIES));
    }

    private static List<AEItemKey> concreteInputKeys(IPatternDetails pattern) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null || inputs.length == 0) {
            return List.of();
        }
        List<AEItemKey> keys = new ArrayList<>(2);
        for (IPatternDetails.IInput input : inputs) {
            if (input == null || input.getMultiplier() <= 0) {
                return List.of();
            }
            var possible = input.getPossibleInputs();
            if (possible == null || possible.length != 1 || possible[0] == null
                    || possible[0].amount() <= 0 || !(possible[0].what() instanceof AEItemKey key)
                    || MekanismItemToItemRecipeAdapter.hasUnsupportedState(key.getReadOnlyStack())) {
                return List.of();
            }
            if (!keys.contains(key)) {
                keys.add(key);
                if (keys.size() > 2) {
                    return List.of();
                }
            }
        }
        return List.copyOf(keys);
    }

    private static Optional<RoleAssignment> uniqueAssignment(CombinerRecipe recipe, List<AEItemKey> keys) {
        RoleAssignment found = null;
        for (AEItemKey mainKey : keys) {
            ItemStack main = exactRole(recipe.getMainInput(), mainKey).orElse(null);
            if (main == null) {
                continue;
            }
            for (AEItemKey extraKey : keys) {
                if (keys.size() == 2 && mainKey.equals(extraKey)) {
                    continue;
                }
                ItemStack extra = exactRole(recipe.getExtraInput(), extraKey).orElse(null);
                if (extra == null) {
                    continue;
                }
                // Two distinct role assignments from overlapping tags cannot
                // safely identify which concrete input each Mekanism slot owns.
                if (found != null) {
                    return Optional.empty();
                }
                found = new RoleAssignment(mainKey, extraKey, main, extra);
            }
        }
        return Optional.ofNullable(found);
    }

    private static Optional<ItemStack> exactRole(ItemStackIngredient ingredient, AEItemKey key) {
        ItemStack concrete = key.getReadOnlyStack();
        Optional<Integer> amount = MekanismItemToItemRecipeAdapter.exactRequiredAmount(
                ingredient, concrete, MekanismItemToItemRecipeAdapter.SINGLE_INGREDIENT_CLASS);
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        ItemStack role = concrete.copyWithCount(amount.orElseThrow());
        ItemStack matching = ingredient.getMatchingInstance(role);
        if (!ingredient.test(role) || matching.isEmpty()
                || matching.getCount() != role.getCount()
                || !ItemStack.isSameItemSameTags(role, matching)
                || MekanismItemToItemRecipeAdapter.hasUnsupportedState(matching)) {
            return Optional.empty();
        }
        return Optional.of(role);
    }

    private record RoleAssignment(AEItemKey mainKey, AEItemKey extraKey, ItemStack main, ItemStack extra) {
    }
}

package dev.appliedcausality.recipe.mekanism;

import appeng.crafting.pattern.AEProcessingPattern;
import appeng.api.stacks.AEItemKey;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import mekanism.api.recipes.SawmillRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Only Mekanism sawmill recipes with a fixed main output and no random path. */
final class MekanismDeterministicSawingResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "sawing");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.SawmillIRecipe";
    private static final ResourceLocation MACHINE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "precision_sawmill");
    private static final ProcessShapeId SHAPE = ProcessShapeId.of(AppliedCausality.MOD_ID, "item_to_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismDeterministicSawingResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismDeterministicSawingResolver createBundled() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 sawing registry entry is unavailable");
        }
        return new MekanismDeterministicSawingResolver(type, serializer);
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
                || !(recipe instanceof SawmillRecipe sawmill) || sawmill.isIncomplete()
                || sawmill.getSecondaryChance() != 0.0) {
            return Optional.empty();
        }
        List<ItemStack> mainDefinitions = sawmill.getMainOutputDefinition();
        List<ItemStack> secondaryDefinitions = sawmill.getSecondaryOutputDefinition();
        if (mainDefinitions == null || mainDefinitions.size() != 1
                || secondaryDefinitions == null || !secondaryDefinitions.isEmpty()) {
            return Optional.empty();
        }

        ItemStack input = MekanismItemToItemRecipeAdapter.concretePatternInput(context.pattern()).orElse(null);
        if (input == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(input)) {
            return Optional.empty();
        }
        Optional<Integer> amount = MekanismItemToItemRecipeAdapter.exactRequiredAmount(
                sawmill.getInput(), input, MekanismItemToItemRecipeAdapter.SINGLE_INGREDIENT_CLASS);
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        ItemStack role = input.copyWithCount(amount.orElseThrow());
        ItemStack matching = sawmill.getInput().getMatchingInstance(role);
        if (!sawmill.getInput().test(role) || matching.isEmpty()
                || matching.getCount() != role.getCount()
                || !ItemStack.isSameItemSameTags(role, matching)
                || MekanismItemToItemRecipeAdapter.hasUnsupportedState(matching)) {
            return Optional.empty();
        }

        // The inspected 10.4.16 implementation skips Random.nextDouble when
        // secondaryChance is zero; this call is after the exact chance gate.
        SawmillRecipe.ChanceOutput result = sawmill.getOutput(role);
        ItemStack output = result.getMainOutput();
        if (output == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(output)
                || !result.getSecondaryOutput().isEmpty()
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(output, mainDefinitions.get(0))
                || !MekanismItemToItemRecipeAdapter.matchesSinglePatternOutput(context.pattern(), output)) {
            return Optional.empty();
        }
        AEItemKey inputKey = AEItemKey.of(input);
        AEItemKey outputKey = AEItemKey.of(output);
        if (inputKey == null || outputKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(inputKey, (long) amount.orElseThrow()),
                Map.of(outputKey, (long) output.getCount()), Map.of(), PROPERTIES));
    }
}

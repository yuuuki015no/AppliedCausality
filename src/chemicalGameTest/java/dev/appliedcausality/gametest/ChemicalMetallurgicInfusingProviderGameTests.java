package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.recipes.MetallurgicInfuserRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Exercises the real Provider under bundled indexed Metallurgic Infusing registration. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalMetallurgicInfusingProviderGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "metallurgic_infusing");
    private static final ResourceLocation BUILTIN_ID = id("mekanism", "metallurgic_infusing/mossy/cobblestone");
    private static final ResourceLocation PACK_ID = id("pack_test", "metallurgic_infusing");

    private ChemicalMetallurgicInfusingProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "chemical_metallurgic_provider", timeoutTicks = 200)
    public static void packBindingRevalidatesAfterReload(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var recipe = (MetallurgicInfuserRecipe) manager.byKey(PACK_ID).orElseThrow();
        var chemical = MekanismKey.of(recipe.getChemicalInput().getRepresentations().get(0));
        var provider = provider(helper, new BlockPos(1, 1, 1),
                pattern(Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD, helper));
        assertBound(provider, PACK_ID, Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe(3, 19));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed two-input recipe retained stale advertisement");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed two-input recipe remained advertised");
            var restored = provider(helper, new BlockPos(2, 1, 1), ItemStack.EMPTY);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved Binding trusted a removed two-input recipe");
            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider, PACK_ID, Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD);
            assertAdvertised(restored, PACK_ID, Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void screenshotMossyCobblestonePatternCanBind(GameTestHelper helper) {
        var recipe = (MetallurgicInfuserRecipe) helper.getLevel().getRecipeManager()
                .byKey(BUILTIN_ID).orElseThrow();
        var chemical = MekanismKey.of(recipe.getChemicalInput().getRepresentations().get(0));
        var provider = provider(helper, new BlockPos(1, 1, 1),
                pattern(Items.COBBLESTONE, 1, chemical, 10, Items.MOSSY_COBBLESTONE, helper));
        assertBound(provider, BUILTIN_ID, Items.COBBLESTONE, 1, chemical, 10,
                Items.MOSSY_COBBLESTONE);
        helper.succeed();
    }

    private static SingularPatternProviderBlockEntity provider(GameTestHelper helper, BlockPos pos,
            ItemStack definition) {
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        if (!definition.isEmpty()) {
            provider.getInternalInventory().setItemDirect(0, definition);
        }
        provider.rebuildAfterRecipeReload();
        return provider;
    }

    private static ItemStack pattern(Item input, long itemAmount, MekanismKey chemical,
            long chemicalAmount, Item output, GameTestHelper helper) {
        require(chemical != null && chemical.getForm() == MekanismKey.INFUSION,
                "Infusion Pattern key was unavailable");
        ItemStack definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), itemAmount),
                        new GenericStack(chemical, chemicalAmount)},
                new GenericStack[]{new GenericStack(AEItemKey.of(output), 1)});
        require(PatternDetailsHelper.decodePattern(definition, helper.getLevel()) != null,
                "two-input Pattern did not decode");
        return definition;
    }

    private static void assertBound(SingularPatternProviderBlockEntity provider, ResourceLocation recipeId,
            Item item, long itemAmount, MekanismKey chemical, long chemicalAmount, Item output) {
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(recipeId)),
                "Metallurgic Infusing Pattern was not indexed for manual Binding");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId),
                "Metallurgic Infusing Pattern could not bind");
        assertAdvertised(provider, recipeId, item, itemAmount, chemical, chemicalAmount, output);
    }

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider,
            ResourceLocation recipeId, Item item, long itemAmount, MekanismKey chemical,
            long chemicalAmount, Item output) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "two-input Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(recipeId)
                        && model.inputsPerOperation().get(AEItemKey.of(item)) == itemAmount
                        && model.inputsPerOperation().get(chemical) == chemicalAmount
                        && model.outputsPerOperation().get(AEItemKey.of(output)) == 1L,
                "advertised two-input Pattern changed exact Core quantities");
    }

    private static Recipe<?> recipe(int itemAmount, long chemicalAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        String json = "{\"itemInput\":{\"amount\":" + itemAmount
                + ",\"ingredient\":{\"tag\":\"pack_test:fungi\"}},\"chemicalInput\":{\"amount\":"
                + chemicalAmount + ",\"infuse_type\":\"mekanism:carbon\"},"
                + "\"output\":{\"item\":\"minecraft:echo_shard\"}}";
        return serializer.fromJson(PACK_ID, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(PACK_ID));
        if (replacement != null) {
            next.add(replacement);
        }
        manager.replaceRecipes(next);
    }

    private static void rebuild(SingularPatternProviderBlockEntity provider) {
        provider.invalidateForRecipeReload();
        provider.rebuildAfterRecipeReload();
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

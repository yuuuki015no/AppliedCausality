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
import mekanism.api.MekanismAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Checks canonical crystallizing discovery and saved Binding revalidation. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCrystallizingProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "crystallizing_gas");
    private static final ResourceLocation TYPE_ID = id("mekanism", "crystallizing");

    private ChemicalCrystallizingProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "chemical_crystallizing_provider", timeoutTicks = 200)
    public static void providerAdvertisesAndRevalidatesSavedBinding(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var input = MekanismKey.of(MekanismAPI.gasRegistry()
                .getValue(id("mekanism", "hydrogen")).getStack(2));
        var output = AEItemKey.of(Items.DIAMOND);
        require(input != null && output != null, "crystallizing Pattern keys are unavailable");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2)},
                new GenericStack[]{new GenericStack(output, 3)});
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID)),
                "canonical crystallizing adapter was not indexed for the pack recipe");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "pack crystallizing recipe could not be bound");
        assertAdvertised(provider, input, output);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe(3, 4));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed crystallizing recipe kept a stale Pattern advertised");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed crystallizing recipe remained advertised");

            var restoredPos = new BlockPos(2, 1, 1);
            helper.setBlock(restoredPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
            var restored = (SingularPatternProviderBlockEntity) helper.getBlockEntity(restoredPos);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved crystallizing Binding trusted a removed recipe");
            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider, input, output);
            assertAdvertised(restored, input, output);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider,
            MekanismKey input, AEItemKey output) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "pack crystallizing Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().get(input) == 2L
                        && model.outputsPerOperation().get(output) == 3L,
                "advertised crystallizing Pattern changed Core-verified quantities");
    }

    private static Recipe<?> recipe(long inputAmount, int outputCount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "crystallizing serializer is unavailable");
        String json = "{\"chemicalType\":\"gas\",\"input\":{\"amount\":"
                + inputAmount + ",\"gas\":\"mekanism:hydrogen\"},\"output\":{\"item\":"
                + "\"minecraft:diamond\",\"count\":" + outputCount + "}}";
        return serializer.fromJson(RECIPE_ID, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(RECIPE_ID));
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

package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks canonical two-Gas discovery and saved Binding revalidation. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusingProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "chemical_infusing");
    private static final ResourceLocation TYPE_ID = id("mekanism", "chemical_infusing");

    private ChemicalInfusingProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "chemical_infusing_provider", timeoutTicks = 200)
    public static void providerRevalidatesBindingAfterRecipeReload(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var hydrogen = key("hydrogen");
        var chlorine = key("chlorine");
        var oxygen = key("oxygen");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(chlorine, 3), new GenericStack(hydrogen, 2)},
                new GenericStack[]{new GenericStack(oxygen, 17)});
        var provider = provider(helper, new BlockPos(1, 1, 1), definition);
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID)),
                "canonical two-Gas adapter was not indexed for the pack recipe");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "pack chemical_infusing recipe could not be bound");
        assertAdvertised(provider, hydrogen, chlorine, oxygen);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe(4, 5, 19));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed two-Gas recipe kept a stale advertisement");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed two-Gas recipe remained advertised");
            var restored = provider(helper, new BlockPos(2, 1, 1), null);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved two-Gas Binding trusted a removed recipe");
            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider, hydrogen, chlorine, oxygen);
            assertAdvertised(restored, hydrogen, chlorine, oxygen);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasPatternBindsWithAggregatedInput(GameTestHelper helper) {
        var gas = key("hydrogen");
        var output = key("water_vapor");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(gas, 2), new GenericStack(gas, 3)},
                new GenericStack[]{new GenericStack(output, 7)});
        var provider = provider(helper, new BlockPos(1, 1, 1), definition);
        ResourceLocation recipeId = id("pack_test", "chemical_infusing_same_gas");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId),
                "same-Gas recipe could not bind");
        var available = provider.getAvailablePatterns();
        require(available.size() == 1
                        && provider.getVerifiedProcessModel(available.get(0)).orElseThrow()
                                .inputsPerOperation().equals(Map.of(gas, 5L)),
                "same-Gas Provider did not advertise checked aggregate input");
        helper.succeed();
    }

    private static SingularPatternProviderBlockEntity provider(GameTestHelper helper,
            BlockPos pos, net.minecraft.world.item.ItemStack definition) {
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        if (definition != null) {
            provider.getInternalInventory().setItemDirect(0, definition);
        }
        provider.rebuildAfterRecipeReload();
        return provider;
    }

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider,
            MekanismKey hydrogen, MekanismKey chlorine, MekanismKey oxygen) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "bound two-Gas Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().equals(Map.of(hydrogen, 2L, chlorine, 3L))
                        && model.outputsPerOperation().equals(Map.of(oxygen, 17L)),
                "advertised two-Gas Pattern changed Core quantities");
    }

    private static Recipe<?> recipe(long left, long right, long output) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "chemical_infusing serializer is unavailable");
        String json = "{\"leftInput\":{\"amount\":" + left
                + ",\"gas\":\"mekanism:hydrogen\"},\"rightInput\":{\"amount\":" + right
                + ",\"gas\":\"mekanism:chlorine\"},\"output\":{\"amount\":" + output
                + ",\"gas\":\"mekanism:oxygen\"}}";
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

    private static MekanismKey key(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", path)).getStack(1));
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

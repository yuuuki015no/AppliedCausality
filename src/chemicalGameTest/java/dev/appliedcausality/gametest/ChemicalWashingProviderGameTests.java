package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks canonical washing Binding and revalidation of both material inputs. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalWashingProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "washing");
    private static final ResourceLocation TYPE_ID = id("mekanism", "washing");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ChemicalWashingProviderGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInTaggedWaterBinds(GameTestHelper helper) {
        var provider = provider(helper, new BlockPos(1, 1, 1), pattern(5, 1, 1));
        var recipeId = id("mekanism", "processing/iron/slurry/clean");
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(recipeId))
                        && provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId)
                        && provider.getAvailablePatterns().size() == 1,
                "built-in tagged Water washing recipe did not bind and advertise");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_washing_provider", timeoutTicks = 200)
    public static void bindingReloadAndSavedBindingRevalidate(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var provider = provider(helper, new BlockPos(1, 1, 1), pattern(7, 11, 13));
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID))
                        && provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "canonical washing adapter did not bind the pack recipe");
        assertAdvertised(provider);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe(8, 12, 17));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed washing inputs kept stale advertisement");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed washing recipe remained advertised");
            var restored = provider(helper, new BlockPos(2, 1, 1), null);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved washing Binding trusted a removed recipe");
            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider);
            assertAdvertised(restored);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    private static ItemStack pattern(long fluid, long slurry, long output) {
        return PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, fluid),
                        new GenericStack(slurry("dirty_iron"), slurry)},
                new GenericStack[]{new GenericStack(slurry("clean_iron"), output)});
    }

    private static SingularPatternProviderBlockEntity provider(GameTestHelper helper,
            BlockPos pos, ItemStack definition) {
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        if (definition != null) {
            provider.getInternalInventory().setItemDirect(0, definition);
        }
        provider.rebuildAfterRecipeReload();
        return provider;
    }

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "bound washing Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().equals(Map.of(WATER, 7L,
                                slurry("dirty_iron"), 11L))
                        && model.outputsPerOperation().equals(Map.of(slurry("clean_iron"), 13L)),
                "advertised washing Pattern changed Core quantities");
    }

    private static Recipe<?> recipe(long fluid, long slurry, long output) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "washing serializer is unavailable");
        String json = "{\"fluidInput\":{\"amount\":" + fluid
                + ",\"fluid\":\"minecraft:water\"},\"slurryInput\":{\"amount\":" + slurry
                + ",\"slurry\":\"mekanism:dirty_iron\"},\"output\":{\"amount\":" + output
                + ",\"slurry\":\"mekanism:clean_iron\"}}";
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

    private static MekanismKey slurry(String path) {
        return MekanismKey.of(MekanismAPI.slurryRegistry().getValue(id("mekanism", path)).getStack(1));
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

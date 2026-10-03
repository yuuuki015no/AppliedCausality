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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks canonical separating Binding, reload, and paired output advertisement. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalSeparatingProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "separating");
    private static final ResourceLocation TYPE_ID = id("mekanism", "separating");

    private ChemicalSeparatingProviderGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInWaterAndBrineBindThroughCanonicalAdapter(GameTestHelper helper) {
        var waterProvider = provider(helper, new BlockPos(1, 1, 1),
                pattern(2, new GenericStack(gas("hydrogen"), 2),
                        new GenericStack(gas("oxygen"), 1)));
        var waterId = id("mekanism", "separator/water");
        require(waterProvider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(waterId))
                        && waterProvider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, waterId)
                        && waterProvider.getAvailablePatterns().size() == 1,
                "built-in tagged Water recipe could not be bound and advertised");

        var brine = ForgeRegistries.FLUIDS.getValue(id("mekanism", "brine"));
        require(brine != null, "Mekanism Brine Fluid is unavailable");
        var brineProvider = provider(helper, new BlockPos(2, 1, 1),
                PatternDetailsHelper.encodeProcessingPattern(
                        new GenericStack[]{new GenericStack(AEFluidKey.of(brine), 10)},
                        new GenericStack[]{new GenericStack(gas("sodium"), 1),
                                new GenericStack(gas("chlorine"), 1)}));
        var brineId = id("mekanism", "separator/brine");
        require(brineProvider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(brineId))
                        && brineProvider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, brineId)
                        && brineProvider.getAvailablePatterns().size() == 1,
                "built-in tagged Brine recipe could not be bound and advertised");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_separating_provider", timeoutTicks = 200)
    public static void providerRevalidatesBindingAfterRecipeReload(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var definition = pattern(3, new GenericStack(oxygen, 7), new GenericStack(hydrogen, 5));
        var provider = provider(helper, new BlockPos(1, 1, 1), definition);
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID)),
                "canonical separating adapter was not indexed for the pack recipe");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "pack separating recipe could not be bound");
        assertAdvertised(provider, hydrogen, oxygen);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe(4, 9, 11));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed separating recipe kept a stale advertisement");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed separating recipe remained advertised");
            var restored = provider(helper, new BlockPos(2, 1, 1), null);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved separating Binding trusted a removed recipe");
            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider, hydrogen, oxygen);
            assertAdvertised(restored, hydrogen, oxygen);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasOutputsBindAsOneAggregateKey(GameTestHelper helper) {
        var hydrogen = gas("hydrogen");
        var provider = provider(helper, new BlockPos(1, 1, 1),
                pattern(4, new GenericStack(hydrogen, 5), new GenericStack(hydrogen, 7)));
        var recipeId = id("pack_test", "separating_same_gas");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId),
                "same-Gas separating recipe could not bind");
        var available = provider.getAvailablePatterns();
        require(available.size() == 1
                        && provider.getVerifiedProcessModel(available.get(0)).orElseThrow()
                                .outputsPerOperation().equals(Map.of(hydrogen, 12L)),
                "same-Gas Provider did not advertise checked aggregate output");
        helper.succeed();
    }

    private static net.minecraft.world.item.ItemStack pattern(long inputAmount, GenericStack... outputs) {
        return PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEFluidKey.of(Fluids.WATER), inputAmount)}, outputs);
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
            MekanismKey hydrogen, MekanismKey oxygen) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "bound separating Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().equals(Map.of(AEFluidKey.of(Fluids.WATER), 3L))
                        && model.outputsPerOperation().equals(Map.of(hydrogen, 5L, oxygen, 7L)),
                "advertised separating Pattern changed Core quantities");
    }

    private static Recipe<?> recipe(long input, long left, long right) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "separating serializer is unavailable");
        String json = "{\"input\":{\"amount\":" + input
                + ",\"fluid\":\"minecraft:water\"},\"leftGasOutput\":{\"amount\":" + left
                + ",\"gas\":\"mekanism:hydrogen\"},\"rightGasOutput\":{\"amount\":" + right
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

    private static MekanismKey gas(String path) {
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

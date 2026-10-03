package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Canonical reaction Binding, optional outputs, and saved-Binding reload. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalReactionProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "reaction");
    private static final ResourceLocation TYPE_ID = id("mekanism", "reaction");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private ChemicalReactionProviderGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInTaggedWaterAndSingleOutputsBind(GameTestHelper helper) {
        var substrate = AEItemKey.of(ForgeRegistries.ITEMS.getValue(id("mekanism", "substrate")));
        var builtInProvider = provider(helper, new BlockPos(1, 1, 1),
                pattern(substrate, 1, 200, gas("ethene"), 100,
                        new GenericStack(substrate, 8), new GenericStack(gas("oxygen"), 10)));
        var builtInId = id("mekanism", "reaction/substrate/water_ethene");
        require(builtInProvider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(builtInId))
                        && builtInProvider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, builtInId)
                        && builtInProvider.getAvailablePatterns().size() == 1,
                "built-in reaction could not bind and advertise");
        var itemOnly = provider(helper, new BlockPos(2, 1, 1),
                pattern(COBBLE, 2, 7, gas("oxygen"), 11,
                        new GenericStack(IRON, 13)));
        var gasOnly = provider(helper, new BlockPos(3, 1, 1),
                pattern(COBBLE, 2, 7, gas("oxygen"), 11,
                        new GenericStack(gas("hydrogen"), 17)));
        require(itemOnly.trySetProcessBinding(0, MekanismRecipeAdapter.ID,
                        id("pack_test", "reaction_item_only"))
                        && gasOnly.trySetProcessBinding(0, MekanismRecipeAdapter.ID,
                        id("pack_test", "reaction_gas_only"))
                        && itemOnly.getAvailablePatterns().size() == 1
                        && gasOnly.getAvailablePatterns().size() == 1,
                "single-output reaction Binding failed");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_reaction_provider", timeoutTicks = 200)
    public static void bindingReloadAndSavedBindingRevalidate(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var provider = provider(helper, new BlockPos(1, 1, 1),
                pattern(COBBLE, 2, 7, gas("hydrogen"), 11,
                        new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17)));
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID))
                        && provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "canonical reaction adapter did not bind the pack recipe");
        assertAdvertised(provider);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        try {
            replace(manager, original, recipe());
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed reaction recipe kept stale advertisement");
            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed reaction recipe remained advertised");
            var restored = provider(helper, new BlockPos(2, 1, 1), null);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved reaction Binding trusted a removed recipe");
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

    private static ItemStack pattern(AEItemKey item, long itemAmount, long fluidAmount,
            MekanismKey gas, long gasAmount, GenericStack... outputs) {
        return PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(item, itemAmount),
                        new GenericStack(WATER, fluidAmount), new GenericStack(gas, gasAmount)}, outputs);
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
        require(patterns.size() == 1, "bound reaction Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().equals(Map.of(COBBLE, 2L,
                                WATER, 7L, gas("hydrogen"), 11L))
                        && model.outputsPerOperation().equals(Map.of(IRON, 13L,
                                gas("oxygen"), 17L)),
                "advertised reaction Pattern changed Core quantities");
    }

    private static Recipe<?> recipe() {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "reaction serializer is unavailable");
        String json = "{\"itemInput\":{\"amount\":3,\"ingredient\":{\"item\":\"minecraft:cobblestone\"}},"
                + "\"fluidInput\":{\"amount\":8,\"fluid\":\"minecraft:water\"},"
                + "\"gasInput\":{\"amount\":12,\"gas\":\"mekanism:hydrogen\"},"
                + "\"duration\":30,\"itemOutput\":{\"item\":\"minecraft:iron_ingot\",\"count\":19},"
                + "\"gasOutput\":{\"gas\":\"mekanism:oxygen\",\"amount\":23}}";
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
        return MekanismKey.of(MekanismAPI.gasRegistry()
                .getValue(id("mekanism", path)).getStack(1));
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

package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.RotaryRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** One canonical Binding ID selects either direction and revalidates after reload. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalRotaryProviderGameTests {
    private static final ResourceLocation PACK_ID = id("pack_test", "rotary");
    private static final ResourceLocation TYPE_ID = id("mekanism", "rotary");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEFluidKey LAVA = AEFluidKey.of(Fluids.LAVA);

    private ChemicalRotaryProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "chemical_rotary_provider", timeoutTicks = 200)
    public static void bothDirectionsBindAndSavedBindingsRevalidate(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var fluid = provider(helper, new BlockPos(1, 1, 1), pattern(WATER, 7, hydrogen, 11));
        var gas = provider(helper, new BlockPos(2, 1, 1), pattern(oxygen, 13, LAVA, 17));
        require(fluid.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(PACK_ID))
                        && gas.findBindingTargets(0).stream().anyMatch(target ->
                                target.adapterId().equals(MekanismRecipeAdapter.ID)
                                        && target.recipeId().equals(PACK_ID))
                        && fluid.trySetProcessBinding(0, MekanismRecipeAdapter.ID, PACK_ID)
                        && gas.trySetProcessBinding(0, MekanismRecipeAdapter.ID, PACK_ID),
                "canonical rotary Binding did not find both pack directions");
        assertAdvertised(fluid, WATER, 7, hydrogen, 11);
        assertAdvertised(gas, oxygen, 13, LAVA, 17);
        var fluidSaved = new CompoundTag();
        var gasSaved = new CompoundTag();
        fluid.saveAdditional(fluidSaved);
        gas.saveAdditional(gasSaved);
        try {
            var changedFluid = recipe(PACK_ID,
                    "{\"fluidInput\":{\"amount\":8,\"fluid\":\"minecraft:water\"},"
                            + "\"gasOutput\":{\"gas\":\"mekanism:hydrogen\",\"amount\":12},"
                            + "\"gasInput\":{\"amount\":13,\"gas\":\"mekanism:oxygen\"},"
                            + "\"fluidOutput\":{\"fluid\":\"minecraft:lava\",\"amount\":17}}");
            replace(manager, original, changedFluid);
            rebuild(fluid);
            rebuild(gas);
            require(fluid.getAvailablePatterns().isEmpty(),
                    "changed Fluid-to-Gas rotary direction retained stale advertisement");
            assertAdvertised(gas, oxygen, 13, LAVA, 17);
            replace(manager, original, null);
            rebuild(fluid);
            rebuild(gas);
            require(fluid.getAvailablePatterns().isEmpty() && gas.getAvailablePatterns().isEmpty(),
                    "removed rotary recipe remained advertised");
            var restoredFluid = provider(helper, new BlockPos(3, 1, 1), null);
            var restoredGas = provider(helper, new BlockPos(4, 1, 1), null);
            restoredFluid.loadTag(fluidSaved);
            restoredGas.loadTag(gasSaved);
            rebuild(restoredFluid);
            rebuild(restoredGas);
            require(restoredFluid.getAvailablePatterns().isEmpty()
                            && restoredGas.getAvailablePatterns().isEmpty(),
                    "saved rotary Binding trusted a removed recipe");
            manager.replaceRecipes(original);
            rebuild(fluid);
            rebuild(gas);
            rebuild(restoredFluid);
            rebuild(restoredGas);
            assertAdvertised(fluid, WATER, 7, hydrogen, 11);
            assertAdvertised(gas, oxygen, 13, LAVA, 17);
            assertAdvertised(restoredFluid, WATER, 7, hydrogen, 11);
            assertAdvertised(restoredGas, oxygen, 13, LAVA, 17);
        } finally {
            manager.replaceRecipes(original);
            rebuild(fluid);
            rebuild(gas);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void builtInTaggedWaterBindsBothDirections(GameTestHelper helper) {
        var waterVapor = gas("water_vapor");
        var recipeId = id("mekanism", "rotary/water_vapor");
        var fluid = provider(helper, new BlockPos(1, 1, 1), pattern(WATER, 1, waterVapor, 1));
        var gas = provider(helper, new BlockPos(2, 1, 1), pattern(waterVapor, 1, WATER, 1));
        require(fluid.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId)
                        && gas.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId),
                "built-in tagged Water rotary did not bind in both directions");
        assertAdvertised(fluid, WATER, 1, waterVapor, 1);
        assertAdvertised(gas, waterVapor, 1, WATER, 1);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_rotary_fluid_tag_reload", timeoutTicks = 200)
    public static void changedFluidTagRevalidatesOnlyAffectedDirection(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originalRecipes = List.copyOf(manager.getRecipes());
        var recipeId = id("mekanism", "rotary/water_vapor");
        var waterVapor = gas("water_vapor");
        var fluid = provider(helper, new BlockPos(1, 1, 1), pattern(WATER, 1, waterVapor, 1));
        var gas = provider(helper, new BlockPos(2, 1, 1), pattern(waterVapor, 1, WATER, 1));
        require(fluid.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId)
                        && gas.trySetProcessBinding(0, MekanismRecipeAdapter.ID, recipeId),
                "initial tagged rotary Binding failed");
        assertAdvertised(fluid, WATER, 1, waterVapor, 1);
        assertAdvertised(gas, waterVapor, 1, WATER, 1);
        var context = new PatternContext(helper.getLevel(), fluid.getAvailablePatterns().get(0));
        var adapter = MekanismRecipeAdapter.createBundled();
        require(adapter.resolve(manager.byKey(recipeId).orElseThrow(), context).isPresent(),
                "initial tagged rotary resolver failed");
        Map<TagKey<Fluid>, List<Holder<Fluid>>> originalTags = new IdentityHashMap<>();
        BuiltInRegistries.FLUID.getTags().forEach(pair ->
                originalTags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        try {
            var changedTags = new IdentityHashMap<>(originalTags);
            changedTags.put(TagKey.create(Registries.FLUID, id("minecraft", "water")),
                    List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA)));
            BuiltInRegistries.FLUID.bindTags(changedTags);
            var changedRecipe = recipe(recipeId,
                    "{\"fluidInput\":{\"amount\":1,\"tag\":\"minecraft:water\"},"
                            + "\"gasOutput\":{\"gas\":\"mekanism:water_vapor\",\"amount\":1},"
                            + "\"gasInput\":{\"amount\":1,\"gas\":\"mekanism:water_vapor\"},"
                            + "\"fluidOutput\":{\"fluid\":\"minecraft:water\",\"amount\":1}}");
            var rotary = (RotaryRecipe) changedRecipe;
            require(!rotary.isIncomplete()
                            && !rotary.test(new FluidStack(Fluids.WATER, 1))
                            && rotary.test(new FluidStack(Fluids.LAVA, 1)),
                    "rotary Fluid tag change did not produce valid changed membership");
            replace(manager, originalRecipes, recipeId, changedRecipe);
            rebuild(fluid);
            rebuild(gas);
            require(fluid.getAvailablePatterns().isEmpty()
                            && gas.getAvailablePatterns().size() == 1
                            && adapter.resolve(changedRecipe, context).isEmpty(),
                    "rotary tag reload state: Fluid patterns=" + fluid.getAvailablePatterns().size()
                            + ", Gas patterns=" + gas.getAvailablePatterns().size()
                            + ", resolver matched=" + adapter.resolve(changedRecipe, context).isPresent());
        } finally {
            BuiltInRegistries.FLUID.bindTags(originalTags);
            manager.replaceRecipes(originalRecipes);
            rebuild(fluid);
            rebuild(gas);
        }
        assertAdvertised(fluid, WATER, 1, waterVapor, 1);
        assertAdvertised(gas, waterVapor, 1, WATER, 1);
        helper.succeed();
    }

    private static ItemStack pattern(AEKey input, long inputAmount, AEKey output, long outputAmount) {
        return PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)});
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

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider,
            AEKey input, long inputAmount, AEKey output, long outputAmount) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "bound rotary Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.inputsPerOperation().equals(Map.of(input, inputAmount))
                        && model.outputsPerOperation().equals(Map.of(output, outputAmount)),
                "advertised rotary direction changed Core quantities");
    }

    private static Recipe<?> recipe(ResourceLocation id, String json) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "rotary serializer is unavailable");
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        replace(manager, original, PACK_ID, replacement);
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original,
            ResourceLocation recipeId, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(recipeId));
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

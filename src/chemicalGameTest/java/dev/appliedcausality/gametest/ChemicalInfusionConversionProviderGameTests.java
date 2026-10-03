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
import mekanism.api.recipes.ItemStackToInfuseTypeRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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

/** Proves indexed, bundled infusion conversion discovery reaches a real provider. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusionConversionProviderGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "infusion_conversion");
    private static final ResourceLocation TYPE_ID = id("mekanism", "infusion_conversion");

    private ChemicalInfusionConversionProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "chemical_infusion_conversion_provider", timeoutTicks = 200)
    public static void registeredAdapterAdvertisesAndRevalidatesSavedBinding(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var recipe = (ItemStackToInfuseTypeRecipe) manager.byKey(RECIPE_ID).orElseThrow();
        var key = MekanismKey.of(recipe.getOutput(new ItemStack(Items.SPORE_BLOSSOM)));
        require(key != null && key.getForm() == MekanismKey.INFUSION, "pack recipe Infusion key is unavailable");
        ItemStack definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.SPORE_BLOSSOM), 2)},
                new GenericStack[]{new GenericStack(key, 17)});
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        require(provider.findBindingTargets(0).stream().anyMatch(target ->
                        target.adapterId().equals(MekanismRecipeAdapter.ID)
                                && target.recipeId().equals(RECIPE_ID)),
                "registered infusion_conversion adapter was not indexed for the pack recipe");
        require(provider.trySetProcessBinding(0, MekanismRecipeAdapter.ID, RECIPE_ID),
                "pack infusion_conversion recipe could not be bound");
        assertAdvertised(provider, key, 2L, 17L);
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);

        try {
            replace(manager, original, recipe(3, 19));
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "changed infusion_conversion recipe kept a stale Pattern advertised");

            replace(manager, original, null);
            rebuild(provider);
            require(provider.getAvailablePatterns().isEmpty(),
                    "removed infusion_conversion recipe remained advertised");

            var restoredPos = new BlockPos(2, 1, 1);
            helper.setBlock(restoredPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
            var restored = (SingularPatternProviderBlockEntity) helper.getBlockEntity(restoredPos);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            require(restored.getAvailablePatterns().isEmpty(),
                    "saved binding trusted a removed infusion_conversion recipe");

            manager.replaceRecipes(original);
            rebuild(provider);
            rebuild(restored);
            assertAdvertised(provider, key, 2L, 17L);
            assertAdvertised(restored, key, 2L, 17L);
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    private static void assertAdvertised(SingularPatternProviderBlockEntity provider,
            MekanismKey output, long inputAmount, long outputAmount) {
        var patterns = provider.getAvailablePatterns();
        require(patterns.size() == 1, "pack infusion_conversion Pattern was not advertised");
        var model = provider.getVerifiedProcessModel(patterns.get(0)).orElseThrow();
        require(model.adapterId().equals(MekanismRecipeAdapter.ID)
                        && model.recipeId().equals(RECIPE_ID)
                        && model.inputsPerOperation().get(AEItemKey.of(Items.SPORE_BLOSSOM)) == inputAmount
                        && model.outputsPerOperation().get(output) == outputAmount,
                "advertised infusion_conversion Pattern did not retain exact Core-verified quantities");
    }

    private static Recipe<?> recipe(int inputAmount, long outputAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "infusion_conversion serializer is unavailable");
        String json = "{\"input\":{\"amount\":" + inputAmount
                + ",\"ingredient\":{\"tag\":\"pack_test:fungi\"}},\"output\":{\"amount\":"
                + outputAmount + ",\"infuse_type\":\"mekanism:carbon\"}}";
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

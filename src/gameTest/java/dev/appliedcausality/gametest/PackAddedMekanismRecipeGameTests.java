package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.internal.compat.MekanismCompatibility;
import dev.appliedcausality.internal.registry.ProcessModelResolver;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.recipe.impl.CrushingIRecipe;
import mekanism.common.recipe.impl.EnrichingIRecipe;
import mekanism.common.recipe.impl.SmeltingIRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exercises pack-authored Mekanism recipes through the active server RecipeManager. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackAddedMekanismRecipeGameTests {
    private static final ResourceLocation ADAPTER = acId("mekanism");
    private static final ResourceLocation ENRICHING = packId("enriching");

    private PackAddedMekanismRecipeGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void foreignNamespaceRecipesUseExactMekanismTypes(GameTestHelper helper) {
        helper.assertTrue(MekanismCompatibility.isLoadedAndCompatible(), "Mekanism 10.4.16 is required");
        verify(helper, "enriching", Items.SPORE_BLOSSOM, Items.BEACON, EnrichingIRecipe.class);
        verify(helper, "crushing", Items.GLOW_BERRIES, Items.DRAGON_EGG, CrushingIRecipe.class);
        verify(helper, "smelting", Items.SMALL_DRIPLEAF, Items.HEART_OF_THE_SEA, SmeltingIRecipe.class);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void activeManagerChangesRevalidateSavedBindingAndAdvertisement(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var definition = definition(Items.SPORE_BLOSSOM, 2, Items.BEACON, 3);
        var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
        helper.assertTrue(pattern != null, "pack pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        helper.assertTrue(provider.trySetProcessBinding(0, ADAPTER, ENRICHING),
                "pack recipe could not be bound");
        helper.assertTrue(provider.getAvailablePatterns().size() == 1, "pack recipe was not advertised");

        try {
            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:spore_blossom", 2,
                            "minecraft:beacon", 3));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.VALID, true);

            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:spore_blossom", 4,
                            "minecraft:beacon", 3));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.INPUT_MISMATCH, false);

            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:glow_berries", 2,
                            "minecraft:beacon", 3));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:spore_blossom", 2,
                            "minecraft:dragon_egg", 3));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:spore_blossom", 2,
                            "minecraft:beacon", 5));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            replace(manager, original, ENRICHING, null);
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            ItemStackToItemStackRecipe concrete = (ItemStackToItemStackRecipe) original.stream()
                    .filter(recipe -> recipe.getId().equals(ENRICHING)).findFirst().orElseThrow();
            Recipe<?> unknownClass = new EnrichingIRecipe(ENRICHING, concrete.getInput(),
                    concrete.getResultItem(helper.getLevel().registryAccess())) { };
            replace(manager, original, ENRICHING, unknownClass);
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            Recipe<?> wrongSerializer = new EnrichingIRecipe(ENRICHING, concrete.getInput(),
                    concrete.getResultItem(helper.getLevel().registryAccess())) {
                @Override
                public RecipeSerializer<ItemStackToItemStackRecipe> getSerializer() {
                    @SuppressWarnings("unchecked")
                    RecipeSerializer<ItemStackToItemStackRecipe> serializer =
                            (RecipeSerializer<ItemStackToItemStackRecipe>) ForgeRegistries.RECIPE_SERIALIZERS
                                    .getValue(mekanismId("crushing"));
                    return serializer;
                }
            };
            replace(manager, original, ENRICHING, wrongSerializer);
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);

            replace(manager, original, ENRICHING,
                    recipe(ENRICHING, "enriching", "minecraft:spore_blossom", 2,
                            "minecraft:beacon", 3));
            rebuild(provider);
            assertBound(helper, context, provider, ValidationDiagnostic.VALID, true);

            ResourceLocation competingId = packId("competing_crushing");
            List<Recipe<?>> withCompetitor = new ArrayList<>(original);
            withCompetitor.add(recipe(competingId, "crushing", "minecraft:spore_blossom", 2,
                    "minecraft:beacon", 3));
            manager.replaceRecipes(withCompetitor);
            rebuild(provider);
            helper.assertTrue(ProcessModelResolver.resolve(context).diagnostic()
                            == ValidationDiagnostic.AMBIGUOUS_RECIPE,
                    "two valid processes must be ambiguous without Binding");
            assertBound(helper, context, provider, ValidationDiagnostic.VALID, true);

            CompoundTag saved = new CompoundTag();
            provider.saveAdditional(saved);
            helper.assertTrue(ProviderBindingStore.load(
                            saved.getCompound("applied_causality").getCompound("bindings"),
                            SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                            .matching(0, PatternFingerprint.fromDefinition(definition)).orElseThrow()
                            .recipeId().equals(ENRICHING), "saved Binding identity changed");
        } finally {
            manager.replaceRecipes(original);
            rebuild(provider);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void changedTagMembershipInvalidatesPackBinding(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originalRecipes = List.copyOf(manager.getRecipes());
        TagKey<Item> tag = TagKey.create(Registries.ITEM, packId("fungi"));
        Map<TagKey<Item>, List<Holder<Item>>> original = new IdentityHashMap<>();
        BuiltInRegistries.ITEM.getTags().forEach(pair ->
                original.put(pair.getFirst(), pair.getSecond().stream().toList()));
        helper.assertTrue(original.getOrDefault(tag, List.of())
                        .contains(BuiltInRegistries.ITEM.wrapAsHolder(Items.SPORE_BLOSSOM)),
                "pack tag was not loaded");

        var definition = definition(Items.SPORE_BLOSSOM, 2, Items.BEACON, 3);
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        helper.assertTrue(provider.trySetProcessBinding(0, ADAPTER, ENRICHING),
                "tag-backed pack recipe could not be bound");
        try {
            var changed = new IdentityHashMap<>(original);
            changed.put(tag, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIRT)));
            BuiltInRegistries.ITEM.bindTags(changed);
            Ingredient.invalidateAll();
            replace(manager, originalRecipes, ENRICHING, tagRecipe());
            rebuild(provider);
            helper.assertTrue(provider.getAvailablePatterns().isEmpty(),
                    "stale tag-backed pack recipe remained advertised");
            var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
            helper.assertTrue(pattern != null, "tag-backed pattern did not decode");
            var context = new PatternContext(helper.getLevel(), pattern);
            assertBound(helper, context, provider, ValidationDiagnostic.NO_RECIPE, false);
        } finally {
            BuiltInRegistries.ITEM.bindTags(original);
            Ingredient.invalidateAll();
            manager.replaceRecipes(originalRecipes);
            rebuild(provider);
        }
        helper.assertTrue(provider.getAvailablePatterns().size() == 1,
                "tag-backed pack recipe did not recover after tag restoration");
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, String type, Item input, Item output,
            Class<?> expectedClass) {
        ResourceLocation id = packId(type);
        var recipe = helper.getLevel().getRecipeManager().byKey(id).orElse(null);
        helper.assertTrue(recipe != null && recipe.getClass() == expectedClass,
                "pack recipe was not loaded with its exact Mekanism class: " + id);
        var pattern = PatternDetailsHelper.decodePattern(definition(input, 2, output, 3), helper.getLevel());
        helper.assertTrue(pattern != null, "pack pattern did not decode: " + id);
        var result = ProcessModelResolver.resolve(new PatternContext(helper.getLevel(), pattern));
        helper.assertTrue(result.diagnostic() == ValidationDiagnostic.VALID,
                "pack recipe was not verified: " + id + " " + result.diagnostic());
        var model = result.model().orElseThrow();
        helper.assertTrue(model.recipeId().equals(id) && model.adapterId().equals(ADAPTER)
                        && model.recipeTypeId().equals(mekanismId(type))
                        && model.inputsPerOperation().get(AEItemKey.of(input)) == 2L
                        && model.outputsPerOperation().get(AEItemKey.of(output)) == 3L,
                "pack recipe was not normalized exactly: " + id);
        var wrongInput = PatternDetailsHelper.decodePattern(
                definition(Items.DIRT, 2, output, 3), helper.getLevel());
        var wrongOutput = PatternDetailsHelper.decodePattern(
                definition(input, 2, Items.NETHER_STAR, 3), helper.getLevel());
        helper.assertTrue(wrongInput != null && wrongOutput != null, "invalid control pattern did not decode");
        helper.assertTrue(ProcessModelResolver.resolve(new PatternContext(helper.getLevel(), wrongInput))
                        .model().isEmpty(), "wrong pack input was verified: " + id);
        helper.assertTrue(ProcessModelResolver.resolve(new PatternContext(helper.getLevel(), wrongOutput))
                        .model().isEmpty(), "wrong pack output was verified: " + id);
    }

    private static void assertBound(GameTestHelper helper, PatternContext context,
            SingularPatternProviderBlockEntity provider, ValidationDiagnostic expected, boolean advertised) {
        var binding = ProviderBindingStore.load(savedBindings(provider),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                .matching(0, PatternFingerprint.fromDefinition(context.pattern().getDefinition())).orElseThrow();
        var result = ProcessModelResolver.resolveBound(context, binding);
        helper.assertTrue(result.diagnostic() == expected,
                "Binding result was " + result.diagnostic() + ", expected " + expected);
        helper.assertTrue((provider.getAvailablePatterns().size() == 1) == advertised,
                "stale or missing pack recipe advertisement");
    }

    private static CompoundTag savedBindings(SingularPatternProviderBlockEntity provider) {
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        return saved.getCompound("applied_causality").getCompound("bindings");
    }

    private static void rebuild(SingularPatternProviderBlockEntity provider) {
        provider.invalidateForRecipeReload();
        provider.rebuildAfterRecipeReload();
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original,
            ResourceLocation id, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(id));
        if (replacement != null) {
            next.add(replacement);
        }
        manager.replaceRecipes(next);
    }

    private static Recipe<?> recipe(ResourceLocation id, String type, String input, int inputAmount,
            String output, int outputAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(mekanismId(type));
        if (serializer == null) {
            throw new IllegalStateException("Mekanism serializer missing: " + type);
        }
        String json = "{\"input\":{\"amount\":" + inputAmount
                + ",\"ingredient\":{\"item\":\"" + input + "\"}},\"output\":{\"count\":"
                + outputAmount + ",\"item\":\"" + output + "\"}}";
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static Recipe<?> tagRecipe() {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(mekanismId("enriching"));
        if (serializer == null) {
            throw new IllegalStateException("Mekanism enriching serializer missing");
        }
        String json = "{\"input\":{\"amount\":2,\"ingredient\":{\"tag\":\"pack_test:fungi\"}},"
                + "\"output\":{\"count\":3,\"item\":\"minecraft:beacon\"}}";
        return serializer.fromJson(ENRICHING, JsonParser.parseString(json).getAsJsonObject());
    }

    private static net.minecraft.world.item.ItemStack definition(Item input, long inputAmount,
            Item output, long outputAmount) {
        return PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), inputAmount)},
                new GenericStack[]{new GenericStack(AEItemKey.of(output), outputAmount)});
    }

    private static ResourceLocation packId(String path) {
        return ResourceLocation.fromNamespaceAndPath("pack_test", path);
    }

    private static ResourceLocation mekanismId(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private static ResourceLocation acId(String path) {
        return ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, path);
    }
}

package dev.appliedcausality.internal.provider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.internal.registry.ProcessRegistryTestBootstrap;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.recipe.vanilla.VanillaSmeltingRecipeAdapter;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.test.TestRecipeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRecipeReloadTest {
    private static final String PATTERN_MARKER = "applied_causality_phase16_reload";
    private static final ResourceLocation RECIPE_ID = id("reload_smelting");
    private static TagKey<Item> reloadInputs;
    private static final IPatternDetailsDecoder PATTERN_DECODER = new IPatternDetailsDecoder() {
        @Override
        public boolean isEncodedPattern(ItemStack stack) {
            return hasMarker(stack.getTag());
        }

        @Override
        public IPatternDetails decodePattern(AEItemKey definition, net.minecraft.world.level.Level level) {
            return definition != null && hasMarker(definition.getTag()) ? pattern(definition) : null;
        }

        @Override
        public IPatternDetails decodePattern(
                ItemStack definition,
                net.minecraft.world.level.Level level,
                boolean tryRecovery) {
            AEItemKey key = AEItemKey.of(definition);
            return key != null && hasMarker(key.getTag()) ? pattern(key) : null;
        }
    };

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
        reloadInputs = TagKey.create(Registries.ITEM, id("reload_inputs"));
        PatternDetailsHelper.registerDecoder(PATTERN_DECODER);
        ProcessRegistryTestBootstrap.initializeBundledAdapters();
    }

    @Test
    void adoptedRecipeRemovalAndChangeCannotExecuteStaleVerifiedModel() throws Exception {
        var originalRecipe = smelting(RECIPE_ID, Ingredient.of(Items.IRON_ORE), Items.IRON_INGOT);
        var level = TestRecipeLevel.create(manager(originalRecipe));
        var provider = provider(level);
        var definition = markedDefinition();
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        IPatternDetails advertised = provider.getAvailablePatterns().get(0);
        var originalModel = provider.getVerifiedProcessModel(advertised).orElseThrow();
        ProviderReloadCoordinator.register(provider);
        try {
            level.adoptRecipeManager(manager());
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            assertTrue(provider.getAvailablePatterns().isEmpty());
            assertTrue(provider.getVerifiedProcessModel(advertised).isEmpty());
            assertEquals(ValidationDiagnostic.NO_RECIPE,
                    provider.getPatternStatuses().get(0).diagnostic());

            level.adoptRecipeManager(manager(smelting(
                    RECIPE_ID, Ingredient.of(Items.IRON_ORE), Items.GOLD_INGOT)));
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            assertTrue(provider.getAvailablePatterns().isEmpty());
            assertTrue(provider.getVerifiedProcessModel(advertised).isEmpty());
            assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH,
                    provider.getPatternStatuses().get(0).diagnostic());

            level.adoptRecipeManager(manager(smelting(
                    RECIPE_ID, Ingredient.of(Items.IRON_ORE), Items.IRON_INGOT)));
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            var rebuiltModel = provider.getVerifiedProcessModel(advertised).orElseThrow();
            assertNotSame(originalModel, rebuiltModel,
                    "VerifiedProcessModel must be reconstructed from the adopted manager");
            assertEquals(RECIPE_ID, rebuiltModel.recipeId());
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
    }

    @Test
    void inventoryNotificationSurvivesRebuildFailureAndRetainsInstaller() throws Exception {
        var level = TestRecipeLevel.create(manager(smelting(RECIPE_ID,
                Ingredient.of(Items.IRON_ORE), Items.IRON_INGOT)));
        var provider = provider(level);
        provider.getInternalInventory().setItemDirect(0, markedDefinition());
        provider.rebuildAfterRecipeReload();
        assertEquals(1, provider.getAvailablePatterns().size());

        level.setRecipeLookupFailure(true);
        long before = provider.presentationVersion();
        provider.getInstallerInventory().setItemDirect(0, new ItemStack(Items.STONE));
        assertTrue(provider.presentationVersion() > before);
        assertTrue(provider.getAvailablePatterns().isEmpty(), "pending change must fail closed");
        provider.flushPendingInventoryRebuild();
        assertTrue(provider.getAvailablePatterns().isEmpty());
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        assertEquals(1, saved.getCompound("applied_causality")
                .getList("installers", net.minecraft.nbt.Tag.TAG_COMPOUND).size());

        level.setRecipeLookupFailure(false);
        before = provider.presentationVersion();
        provider.getInstallerInventory().setItemDirect(1, new ItemStack(Items.DIRT));
        assertTrue(provider.presentationVersion() > before,
                "AE2's notifyingChanges flag must have been released");
        provider.flushPendingInventoryRebuild();
        assertEquals(1, provider.getAvailablePatterns().size());
    }

    @Test
    void persistedBindingIsRetainedButRevalidatedAfterRecipeReload() throws Exception {
        var recipe = smelting(RECIPE_ID, Ingredient.of(Items.IRON_ORE), Items.IRON_INGOT);
        var level = TestRecipeLevel.create(manager(recipe));
        var provider = provider(level);
        ItemStack definition = markedDefinition();
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        assertTrue(provider.trySetProcessBinding(0, VanillaSmeltingRecipeAdapter.ID, RECIPE_ID));
        ProviderReloadCoordinator.register(provider);
        try {
            level.adoptRecipeManager(manager());
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            assertTrue(provider.getAvailablePatterns().isEmpty());
            assertEquals(ValidationDiagnostic.NO_RECIPE,
                    provider.getPatternStatuses().get(0).diagnostic());
            CompoundTag saved = new CompoundTag();
            provider.saveAdditional(saved);
            ProviderBindingStore persisted = ProviderBindingStore.load(
                    saved.getCompound("applied_causality").getCompound("bindings"),
                    SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
            assertEquals(RECIPE_ID, persisted.matching(
                    0, PatternFingerprint.fromDefinition(definition)).orElseThrow().recipeId());

            level.adoptRecipeManager(manager(recipe));
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            assertEquals(RECIPE_ID,
                    provider.getVerifiedProcessModel(provider.getAvailablePatterns().get(0))
                            .orElseThrow().recipeId());
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
    }

    @Test
    void tagBackedIngredientUsesMembershipFromNewlyAdoptedRecipes() throws Exception {
        Map<TagKey<Item>, List<Holder<Item>>> originalTags = snapshotItemTags();
        SingularPatternProviderBlockEntity provider = null;
        try {
            bindItemTag(originalTags, Items.IRON_ORE);
            var level = TestRecipeLevel.create(manager(smelting(
                    RECIPE_ID, Ingredient.of(reloadInputs), Items.IRON_INGOT)));
            provider = provider(level);
            provider.getInternalInventory().setItemDirect(0, markedDefinition());
            provider.rebuildAfterRecipeReload();
            IPatternDetails advertised = provider.getAvailablePatterns().get(0);
            ProviderReloadCoordinator.register(provider);

            bindItemTag(originalTags, Items.DIRT);
            level.adoptRecipeManager(manager(smelting(
                    RECIPE_ID, Ingredient.of(reloadInputs), Items.IRON_INGOT)));
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();

            assertTrue(provider.getAvailablePatterns().isEmpty());
            assertTrue(provider.getVerifiedProcessModel(advertised).isEmpty());
            assertEquals(ValidationDiagnostic.NO_RECIPE,
                    provider.getPatternStatuses().get(0).diagnostic());
        } finally {
            if (provider != null) {
                ProviderReloadCoordinator.unregister(provider);
            }
            restoreItemTags(originalTags);
        }
    }

    private static SingularPatternProviderBlockEntity provider(TestRecipeLevel level) throws Exception {
        BlockEntityType<SingularPatternProviderBlockEntity> type =
                BlockEntityType.Builder.<SingularPatternProviderBlockEntity>of(
                        (pos, state) -> {
                            throw new UnsupportedOperationException("test factory is not used");
                        },
                        Blocks.IRON_BLOCK).build(null);
        Constructor<SingularPatternProviderBlockEntity> constructor =
                SingularPatternProviderBlockEntity.class.getDeclaredConstructor(
                        BlockEntityType.class,
                        BlockPos.class,
                        net.minecraft.world.level.block.state.BlockState.class);
        constructor.setAccessible(true);
        var provider = constructor.newInstance(type, BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
        provider.setLevel(level);
        return provider;
    }

    @Test
    void recipeRemovedBetweenSaveAndFreshLoadCannotAuthorizeSavedBinding() throws Exception {
        var recipe = smelting(RECIPE_ID, Ingredient.of(Items.IRON_ORE), Items.IRON_INGOT);
        var original = provider(TestRecipeLevel.create(manager(recipe)));
        original.getInternalInventory().setItemDirect(0, markedDefinition());
        original.rebuildAfterRecipeReload();
        assertTrue(original.trySetProcessBinding(0, VanillaSmeltingRecipeAdapter.ID, RECIPE_ID));
        var saved = new CompoundTag();
        original.saveAdditional(saved);

        var restarted = provider(TestRecipeLevel.create(manager()));
        restarted.loadTag(saved);
        assertTrue(restarted.getAvailablePatterns().isEmpty());
        restarted.rebuildAfterRecipeReload();
        assertTrue(restarted.getAvailablePatterns().isEmpty());
        assertEquals(ValidationDiagnostic.NO_RECIPE, restarted.getPatternStatuses().get(0).diagnostic());
        var resaved = new CompoundTag();
        restarted.saveAdditional(resaved);
        var binding = ProviderBindingStore.load(resaved.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        assertEquals(RECIPE_ID, binding.matching(0,
                PatternFingerprint.fromDefinition(markedDefinition())).orElseThrow().recipeId());
    }

    private static RecipeManager manager(SmeltingRecipe... recipes) {
        var manager = new RecipeManager(ICondition.IContext.EMPTY);
        manager.replaceRecipes(List.of(recipes));
        return manager;
    }

    private static SmeltingRecipe smelting(
            ResourceLocation id,
            Ingredient input,
            Item output) {
        return new SmeltingRecipe(
                id, "", CookingBookCategory.MISC, input, new ItemStack(output), 0.0F, 200);
    }

    private static ItemStack markedDefinition() {
        var definition = new ItemStack(Items.BARRIER);
        definition.getOrCreateTag().putBoolean(PATTERN_MARKER, true);
        return definition;
    }

    private static TestProcessingPattern pattern(AEItemKey definition) {
        return TestProcessingPattern.create(
                definition,
                new IPatternDetails.IInput[] {new ExactInput(AEItemKey.of(Items.IRON_ORE))},
                new GenericStack[] {new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
    }

    private static boolean hasMarker(CompoundTag tag) {
        return tag != null && tag.getBoolean(PATTERN_MARKER);
    }

    private static Map<TagKey<Item>, List<Holder<Item>>> snapshotItemTags() {
        var snapshot = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>();
        BuiltInRegistries.ITEM.getTags().forEach(pair ->
                snapshot.put(pair.getFirst(), pair.getSecond().stream().toList()));
        return snapshot;
    }

    private static void bindItemTag(
            Map<TagKey<Item>, List<Holder<Item>>> originalTags,
            Item member) {
        var updated = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>(originalTags);
        updated.put(reloadInputs, List.of(BuiltInRegistries.ITEM.wrapAsHolder(member)));
        BuiltInRegistries.ITEM.bindTags(updated);
        Ingredient.invalidateAll();
    }

    private static void restoreItemTags(Map<TagKey<Item>, List<Holder<Item>>> originalTags) {
        var restored = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>(originalTags);
        // MappedRegistry retains previously-created named sets when a key is
        // absent from bindTags. Bind an explicitly empty test tag when it did
        // not exist before this test so no membership leaks into later tests.
        restored.putIfAbsent(reloadInputs, List.of());
        BuiltInRegistries.ITEM.bindTags(restored);
        Ingredient.invalidateAll();
        assertEquals(
                originalTags.getOrDefault(reloadInputs, List.of()),
                BuiltInRegistries.ITEM.getTag(reloadInputs).orElseThrow().stream().toList(),
                "test tag membership leaked after restoration");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private record ExactInput(AEItemKey key) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(key, 1L)};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return key.equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}

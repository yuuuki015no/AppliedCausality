package dev.appliedcausality.menu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.internal.registry.ProcessRegistryTestBootstrap;
import dev.appliedcausality.recipe.vanilla.VanillaSmeltingRecipeAdapter;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.test.TestRecipeLevel;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBindingMenuTest {
    private static final String TEST_PATTERN_MARKER = "applied_causality_phase22_menu";

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
        PatternDetailsHelper.registerDecoder(new IPatternDetailsDecoder() {
            @Override
            public boolean isEncodedPattern(ItemStack stack) {
                return hasMarker(stack);
            }

            @Override
            public IPatternDetails decodePattern(AEItemKey definition, net.minecraft.world.level.Level level) {
                return definition != null && hasMarker(definition.getTag()) ? pattern(definition) : null;
            }

            @Override
            public IPatternDetails decodePattern(ItemStack definition, net.minecraft.world.level.Level level,
                    boolean tryRecovery) {
                AEItemKey key = AEItemKey.of(definition);
                return key != null && hasMarker(key.getTag()) ? pattern(key) : null;
            }
        });
        ProcessRegistryTestBootstrap.initializeBundledAdapters();
    }

    @Test
    void snapshotCodecEnforcesBoundsAndRetainsPageTailAndSelection() {
        ProcessBindingTarget target = new ProcessBindingTarget(id("adapter"), id("recipe"));
        List<ProcessBindingTarget> maximumTargets = new ArrayList<>();
        for (int index = 0; index < ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT; index++) {
            maximumTargets.add(new ProcessBindingTarget(id("adapter_" + index), id("recipe_" + index)));
        }
        List<ProviderBindingMenu.SlotSnapshot> snapshots = emptySnapshots();
        snapshots.set(8, new ProviderBindingMenu.SlotSnapshot(
                dev.appliedcausality.internal.validation.ValidationDiagnostic.VALID,
                Optional.of(PatternFingerprint.fromDefinition(new ItemStack(Items.BARRIER))),
                maximumTargets, Optional.of(maximumTargets.get(31))));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ProviderBindingMenu.writeSnapshots(buffer, snapshots);
        List<ProviderBindingMenu.SlotSnapshot> decoded = ProviderBindingMenu.readSnapshots(buffer);

        assertEquals(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT, decoded.size());
        assertEquals(32, decoded.get(8).targets().size());
        assertEquals(maximumTargets.get(31), decoded.get(8).targets().get(31));
        assertEquals(Optional.of(maximumTargets.get(31)), decoded.get(8).selectedBinding());
        assertTrue(decoded.get(8).fingerprint().isEmpty(), "fingerprints never travel to the client");
        assertEquals(ProviderBindingMenu.CLEAR_BUTTON_BASE - 1, 8 * 32 + 31);

        FriendlyByteBuf wrongSlotCount = new FriendlyByteBuf(Unpooled.buffer());
        wrongSlotCount.writeVarInt(8);
        assertThrows(IllegalArgumentException.class, () -> ProviderBindingMenu.readSnapshots(wrongSlotCount));

        FriendlyByteBuf tooManyCandidates = new FriendlyByteBuf(Unpooled.buffer());
        tooManyCandidates.writeVarInt(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        tooManyCandidates.writeVarInt(0);
        tooManyCandidates.writeVarInt(33);
        assertThrows(IllegalArgumentException.class, () -> ProviderBindingMenu.readSnapshots(tooManyCandidates));

        FriendlyByteBuf invalidDiagnostic = new FriendlyByteBuf(Unpooled.buffer());
        invalidDiagnostic.writeVarInt(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        invalidDiagnostic.writeVarInt(dev.appliedcausality.internal.validation.ValidationDiagnostic.values().length);
        invalidDiagnostic.writeVarInt(0);
        assertThrows(IllegalArgumentException.class, () -> ProviderBindingMenu.readSnapshots(invalidDiagnostic));

        List<ProviderBindingMenu.SlotSnapshot> staleSelection = emptySnapshots();
        staleSelection.set(0, new ProviderBindingMenu.SlotSnapshot(
                dev.appliedcausality.internal.validation.ValidationDiagnostic.NO_RECIPE,
                Optional.empty(), List.of(), Optional.of(target)));
        FriendlyByteBuf staleSelectionBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ProviderBindingMenu.writeSnapshots(staleSelectionBuffer, staleSelection);
        assertEquals(Optional.of(target), ProviderBindingMenu.readSnapshots(staleSelectionBuffer)
                .get(0).selectedBinding());
    }

    @Test
    void snapshotWireBudgetCapsLongIdentifiersBeforeOpenScreenEncoding() {
        List<ProviderBindingMenu.SlotSnapshot> unbounded = emptySnapshots();
        for (int slot = 0; slot < SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT; slot++) {
            List<ProcessBindingTarget> targets = new ArrayList<>();
            for (int candidate = 0; candidate < ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT; candidate++) {
                targets.add(new ProcessBindingTarget(id(longPath("adapter", slot, candidate)),
                        id(longPath("recipe", slot, candidate))));
            }
            unbounded.set(slot, new ProviderBindingMenu.SlotSnapshot(
                    dev.appliedcausality.internal.validation.ValidationDiagnostic.VALID,
                    Optional.empty(), targets, Optional.empty()));
        }

        assertThrows(IllegalArgumentException.class, () -> ProviderBindingMenu.writeSnapshots(
                new FriendlyByteBuf(Unpooled.buffer()), unbounded));

        List<ProviderBindingMenu.SlotSnapshot> capped = ProviderBindingMenu.capSnapshotsForWire(unbounded);
        int bytes = ProviderBindingMenu.serializedSnapshotBytes(capped);
        assertTrue(bytes <= ProviderBindingMenu.MAX_SNAPSHOT_WIRE_BYTES);
        assertTrue(bytes + Long.BYTES + 3 <= 32_600,
                "BlockPos and the enclosing length VarInt also fit NetworkHooks.openScreen");
        assertTrue(capped.stream().allMatch(snapshot -> !snapshot.targets().isEmpty()),
                "round-robin admission keeps every ambiguous slot usable");
        assertTrue(capped.get(0).targets().get(0).recipeId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > 512,
                "a target larger than the former per-identifier cap remains selectable when the total fits");
        assertTrue(capped.stream().mapToInt(snapshot -> snapshot.targets().size()).sum()
                < SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT * ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT);
        for (int slot = 0; slot < capped.size(); slot++) {
            assertEquals(unbounded.get(slot).targets().subList(0, capped.get(slot).targets().size()),
                    capped.get(slot).targets(), "the client candidate index remains the server snapshot index");
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ProviderBindingMenu.writeSnapshots(buffer, capped);
        assertEquals(bytes, buffer.writerIndex());
        assertEquals(capped, ProviderBindingMenu.readSnapshots(buffer));

        ProcessBindingTarget oversizedSavedBinding = new ProcessBindingTarget(id("x".repeat(20_000)),
                id("y".repeat(20_000)));
        List<ProviderBindingMenu.SlotSnapshot> savedBindingSnapshot = emptySnapshots();
        savedBindingSnapshot.set(0, new ProviderBindingMenu.SlotSnapshot(
                dev.appliedcausality.internal.validation.ValidationDiagnostic.NO_RECIPE,
                Optional.empty(), List.of(), Optional.of(oversizedSavedBinding)));
        ProviderBindingMenu.SlotSnapshot elided = ProviderBindingMenu.capSnapshotsForWire(savedBindingSnapshot).get(0);
        assertTrue(elided.hasSelectedBinding());
        assertTrue(elided.selectedBinding().isEmpty());
        assertTrue(elided.selectedBindingLabel().orElseThrow().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 256);
        FriendlyByteBuf savedBindingBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ProviderBindingMenu.writeSnapshots(savedBindingBuffer,
                ProviderBindingMenu.capSnapshotsForWire(savedBindingSnapshot));
        ProviderBindingMenu.SlotSnapshot decodedElision = ProviderBindingMenu.readSnapshots(savedBindingBuffer).get(0);
        assertTrue(decodedElision.hasSelectedBinding());
        assertTrue(decodedElision.selectedBinding().isEmpty());
    }

    @Test
    void actionsRejectClientAndMalformedButtonIds() throws ReflectiveOperationException {
        Fixture fixture = fixture();
        assertFalse(ProviderBindingMenu.applyButton(null, fixture.snapshots(), 0),
                "a client menu has no provider and cannot mutate bindings");
        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), fixture.snapshots(), -1));
        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), fixture.snapshots(),
                ProviderBindingMenu.CLEAR_BUTTON_BASE + SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT));
        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), fixture.snapshots(), Integer.MAX_VALUE));
    }

    @Test
    void snapshotsReflectPatternInsertionReplacementAndRemoval() throws ReflectiveOperationException {
        SmeltingRecipe recipe = new SmeltingRecipe(id("live_snapshot_recipe"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        RecipeManager recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        SingularPatternProviderBlockEntity provider = createProvider();
        provider.setLevel(TestRecipeLevel.create(recipeManager));

        assertTrue(ProviderBindingMenu.snapshots(provider).get(0).fingerprint().isEmpty());

        ItemStack inserted = new ItemStack(Items.BARRIER);
        inserted.getOrCreateTag().putBoolean(TEST_PATTERN_MARKER, true);
        provider.getInternalInventory().setItemDirect(0, inserted);
        ProviderBindingMenu.SlotSnapshot afterInsertion = ProviderBindingMenu.snapshots(provider).get(0);
        assertTrue(afterInsertion.fingerprint().isPresent());
        assertEquals(List.of(new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, recipe.getId())),
                afterInsertion.targets());

        provider.getInternalInventory().setItemDirect(0, ItemStack.EMPTY);
        ProviderBindingMenu.SlotSnapshot afterRemoval = ProviderBindingMenu.snapshots(provider).get(0);
        assertTrue(afterRemoval.fingerprint().isEmpty());
        assertTrue(afterRemoval.targets().isEmpty());
    }

    @Test
    void serverRefreshBuildsIncrementedReplacementPacketForRecipeReplacement()
            throws ReflectiveOperationException {
        SmeltingRecipe ironRecipe = new SmeltingRecipe(id("refresh_iron"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        SmeltingRecipe goldRecipe = new SmeltingRecipe(id("refresh_gold"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.GOLD_INGOT), 0.0F, 200);
        RecipeManager recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(ironRecipe, goldRecipe));
        SingularPatternProviderBlockEntity provider = createProvider();
        ItemStack ironPattern = markedPattern(false);
        provider.getInternalInventory().setItemDirect(0, ironPattern);
        provider.setLevel(TestRecipeLevel.create(recipeManager));
        List<ProviderBindingMenu.SlotSnapshot> before = ProviderBindingMenu.snapshots(provider);
        assertEquals(List.of(new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, ironRecipe.getId())),
                before.get(0).targets());

        provider.getInternalInventory().setItemDirect(0, markedPattern(true));
        ProviderBindingMenu.SnapshotUpdate update = ProviderBindingMenu.refreshAfterServerInventoryAction(
                provider, before, 4, 91).orElseThrow();

        assertEquals(5, update.revision());
        assertTrue(update.packet().targetsContainer(91));
        assertFalse(update.packet().targetsContainer(92));
        assertEquals(List.of(new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, goldRecipe.getId())),
                update.packet().snapshots().get(0).targets());
        ProviderBindingMenu.SnapshotState clientBefore = new ProviderBindingMenu.SnapshotState(before, 7);
        ProviderBindingMenu.SnapshotState clientAfter = ProviderBindingMenu.SnapshotState.applyClientPacket(
                clientBefore, 91, update.packet()).orElseThrow();
        assertEquals(8, clientAfter.revision());
        assertEquals(update.packet().snapshots(), clientAfter.snapshots());
        assertTrue(ProviderBindingMenu.SnapshotState.applyClientPacket(
                clientBefore, 92, update.packet()).isEmpty());
        assertTrue(ProviderBindingMenu.refreshAfterServerInventoryAction(provider,
                update.packet().snapshots(), update.revision(), 91).isEmpty());
    }

    @Test
    void providerIdentityAndRevisionBoundariesAreFailClosed() throws ReflectiveOperationException {
        Fixture fixture = fixture();
        TestRecipeLevel level = (TestRecipeLevel) fixture.provider().getLevel();
        level.setBlockEntityForTest(BlockPos.ZERO, fixture.provider());
        assertTrue(ProviderBindingMenu.isCurrentProvider(level, BlockPos.ZERO, fixture.provider()));

        SingularPatternProviderBlockEntity replacement = createProvider();
        level.setBlockEntityForTest(BlockPos.ZERO, replacement);
        assertFalse(ProviderBindingMenu.isCurrentProvider(level, BlockPos.ZERO, fixture.provider()),
                "a menu may not retain an inventory from a replaced block entity");
        fixture.provider().setRemoved();
        assertFalse(ProviderBindingMenu.isCurrentProvider(level, BlockPos.ZERO, fixture.provider()));

        assertEquals(Integer.MAX_VALUE, ProviderBindingMenu.nextSnapshotRevision(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ProviderBindingMenu.nextSnapshotRevision(-1));
        ProviderBindingMenu.SnapshotState saturated = new ProviderBindingMenu.SnapshotState(emptySnapshots(),
                Integer.MAX_VALUE);
        ProviderBindingMenu.SnapshotState afterPacket = ProviderBindingMenu.SnapshotState.applyClientPacket(saturated,
                4, new dev.appliedcausality.network.ProviderBindingSnapshotPacket(4, emptySnapshots())).orElseThrow();
        assertEquals(Integer.MAX_VALUE, afterPacket.revision());
        assertEquals(1L, afterPacket.presentationGeneration());

        ProviderBindingMenu.SnapshotState generationAtMaximum = new ProviderBindingMenu.SnapshotState(emptySnapshots(),
                Integer.MAX_VALUE, Long.MAX_VALUE);
        ProviderBindingMenu.SnapshotState afterGenerationWrap = ProviderBindingMenu.SnapshotState.applyClientPacket(
                generationAtMaximum, 4,
                new dev.appliedcausality.network.ProviderBindingSnapshotPacket(4, emptySnapshots())).orElseThrow();
        assertEquals(Long.MIN_VALUE, afterGenerationWrap.presentationGeneration());

        Fixture changed = fixture();
        changed.provider().getInternalInventory().setItemDirect(0, markedPattern(true));
        assertEquals(Integer.MAX_VALUE, ProviderBindingMenu.refreshAfterServerInventoryAction(
                changed.provider(), changed.snapshots(), Integer.MAX_VALUE, 4).orElseThrow().revision());
    }

    @Test
    void actionsRejectStaleFingerprintRecipeAdapterAndClearSnapshots() throws ReflectiveOperationException {
        Fixture fixture = fixture();
        ProcessBindingTarget validTarget = new ProcessBindingTarget(
                VanillaSmeltingRecipeAdapter.ID, fixture.recipe().getId());

        List<ProcessBindingTarget> pageSizedTargets = java.util.Collections.nCopies(
                ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT, validTarget);
        List<ProviderBindingMenu.SlotSnapshot> lastCandidateSnapshot = replaceSlot(fixture.snapshots(), 0,
                new ProviderBindingMenu.SlotSnapshot(
                        fixture.snapshots().get(0).diagnostic(), fixture.snapshots().get(0).fingerprint(),
                        pageSizedTargets, Optional.empty()));
        assertTrue(ProviderBindingMenu.applyButton(fixture.provider(), lastCandidateSnapshot,
                ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT - 1),
                "the 32nd candidate remains a valid server action");

        List<ProviderBindingMenu.SlotSnapshot> beforePatternChange = fixture.snapshots();
        ItemStack changedPattern = fixture.definition().copy();
        changedPattern.getOrCreateTag().putString("changed", "after_snapshot");
        fixture.provider().getInternalInventory().setItemDirect(0, changedPattern);
        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), beforePatternChange, 0));
        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), beforePatternChange,
                ProviderBindingMenu.CLEAR_BUTTON_BASE));

        Fixture staleRecipe = fixture();
        staleRecipe.recipeManager().replaceRecipes(List.of());
        assertFalse(ProviderBindingMenu.applyButton(staleRecipe.provider(), staleRecipe.snapshots(), 0));

        Fixture staleAdapter = fixture();
        List<ProviderBindingMenu.SlotSnapshot> adapterSnapshot = replaceSlot(staleAdapter.snapshots(), 0,
                new ProviderBindingMenu.SlotSnapshot(
                        staleAdapter.snapshots().get(0).diagnostic(), staleAdapter.snapshots().get(0).fingerprint(),
                        List.of(new ProcessBindingTarget(id("removed_adapter"), staleAdapter.recipe().getId())),
                        Optional.empty()));
        assertFalse(ProviderBindingMenu.applyButton(staleAdapter.provider(), adapterSnapshot, 0));

        Fixture concurrentlyBound = fixture();
        List<ProviderBindingMenu.SlotSnapshot> beforeBinding = concurrentlyBound.snapshots();
        assertTrue(concurrentlyBound.provider().trySetProcessBinding(0,
                VanillaSmeltingRecipeAdapter.ID, concurrentlyBound.recipe().getId()));
        assertFalse(ProviderBindingMenu.applyButton(concurrentlyBound.provider(), beforeBinding,
                ProviderBindingMenu.CLEAR_BUTTON_BASE),
                "a clear button may remove only the binding identified by its server snapshot");
        assertEquals(Optional.of(new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID,
                concurrentlyBound.recipe().getId())), concurrentlyBound.provider().currentBindingTarget(0));
    }

    @Test
    void rejectedActionBuildsAReplacementSnapshotAfterRecipeReload() throws ReflectiveOperationException {
        Fixture fixture = fixture();
        fixture.recipeManager().replaceRecipes(List.of());
        fixture.provider().invalidateForRecipeReload();
        fixture.provider().rebuildAfterRecipeReload();

        assertFalse(ProviderBindingMenu.applyButton(fixture.provider(), fixture.snapshots(), 0));
        ProviderBindingMenu.SnapshotUpdate update = ProviderBindingMenu.refreshAfterServerInventoryAction(
                fixture.provider(), fixture.snapshots(), 3, 77).orElseThrow();
        assertEquals(4, update.revision());
        assertTrue(update.packet().targetsContainer(77));
        assertTrue(update.packet().snapshots().get(0).targets().isEmpty());
    }

    @Test
    void staleSavedBindingIsPresentableAndCanBeCleared() throws ReflectiveOperationException {
        Fixture fixture = fixture();
        ProcessBindingTarget target = new ProcessBindingTarget(
                VanillaSmeltingRecipeAdapter.ID, fixture.recipe().getId());
        assertTrue(fixture.provider().trySetProcessBinding(0, target.adapterId(), target.recipeId()));

        fixture.recipeManager().replaceRecipes(List.of());
        List<ProviderBindingMenu.SlotSnapshot> staleSnapshots = ProviderBindingMenu.snapshots(fixture.provider());
        ProviderBindingMenu.SlotSnapshot staleSlot = staleSnapshots.get(0);
        assertTrue(staleSlot.targets().isEmpty());
        assertEquals(Optional.of(target), staleSlot.selectedBinding());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ProviderBindingMenu.writeSnapshots(buffer, staleSnapshots);
        assertEquals(Optional.of(target), ProviderBindingMenu.readSnapshots(buffer).get(0).selectedBinding());
        assertTrue(ProviderBindingMenu.applyButton(fixture.provider(), staleSnapshots,
                ProviderBindingMenu.CLEAR_BUTTON_BASE));
        assertTrue(fixture.provider().currentBindingTarget(0).isEmpty());
    }

    @Test
    void oversizedSavedBindingPresentationStillClearsAgainstTheServerSnapshot()
            throws ReflectiveOperationException {
        Fixture fixture = fixture(id("z".repeat(33_000)));
        ProcessBindingTarget target = new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, fixture.recipe().getId());
        assertTrue(fixture.provider().trySetProcessBinding(0, target.adapterId(), target.recipeId()));

        List<ProviderBindingMenu.SlotSnapshot> serverSnapshots = ProviderBindingMenu.snapshots(fixture.provider());
        ProviderBindingMenu.SlotSnapshot wireSnapshot = ProviderBindingMenu.capSnapshotsForWire(serverSnapshots).get(0);
        assertTrue(wireSnapshot.hasSelectedBinding());
        assertTrue(wireSnapshot.selectedBinding().isEmpty());
        assertTrue(wireSnapshot.selectedBindingLabel().isPresent());

        assertTrue(ProviderBindingMenu.applyButton(fixture.provider(), serverSnapshots,
                ProviderBindingMenu.CLEAR_BUTTON_BASE));
        assertTrue(fixture.provider().currentBindingTarget(0).isEmpty());
    }

    private static Fixture fixture() throws ReflectiveOperationException {
        return fixture(id("menu_recipe"));
    }

    private static Fixture fixture(ResourceLocation recipeId) throws ReflectiveOperationException {
        SmeltingRecipe recipe = new SmeltingRecipe(recipeId, "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        RecipeManager recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        SingularPatternProviderBlockEntity provider = createProvider();
        ItemStack definition = markedPattern(false);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.setLevel(TestRecipeLevel.create(recipeManager));
        List<ProviderBindingMenu.SlotSnapshot> snapshots = emptySnapshots();
        snapshots.set(0, new ProviderBindingMenu.SlotSnapshot(
                dev.appliedcausality.internal.validation.ValidationDiagnostic.VALID,
                Optional.of(PatternFingerprint.fromDefinition(definition)),
                List.of(new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, recipe.getId())), Optional.empty()));
        return new Fixture(provider, recipeManager, recipe, definition, snapshots);
    }

    private static List<ProviderBindingMenu.SlotSnapshot> emptySnapshots() {
        List<ProviderBindingMenu.SlotSnapshot> snapshots = new ArrayList<>();
        for (int slot = 0; slot < SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT; slot++) {
            snapshots.add(new ProviderBindingMenu.SlotSnapshot(
                    dev.appliedcausality.internal.validation.ValidationDiagnostic.NO_RECIPE,
                    Optional.empty(), List.of(), Optional.empty()));
        }
        return snapshots;
    }

    private static List<ProviderBindingMenu.SlotSnapshot> replaceSlot(
            List<ProviderBindingMenu.SlotSnapshot> snapshots, int slot,
            ProviderBindingMenu.SlotSnapshot replacement) {
        List<ProviderBindingMenu.SlotSnapshot> result = new ArrayList<>(snapshots);
        result.set(slot, replacement);
        return result;
    }

    private static SingularPatternProviderBlockEntity createProvider() throws ReflectiveOperationException {
        BlockEntityType<SingularPatternProviderBlockEntity> type =
                BlockEntityType.Builder.<SingularPatternProviderBlockEntity>of(
                        (pos, state) -> {
                            throw new UnsupportedOperationException("test factory is not used");
                        }, Blocks.IRON_BLOCK).build(null);
        var constructor = SingularPatternProviderBlockEntity.class.getDeclaredConstructor(
                BlockEntityType.class, BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
    }

    private static boolean hasMarker(ItemStack definition) {
        return definition.hasTag() && definition.getTag().getBoolean(TEST_PATTERN_MARKER);
    }

    private static boolean hasMarker(net.minecraft.nbt.CompoundTag tag) {
        return tag != null && tag.getBoolean(TEST_PATTERN_MARKER);
    }

    private static TestProcessingPattern pattern(AEItemKey definition) {
        boolean goldOutput = definition.getTag() != null && definition.getTag().getBoolean("gold_output");
        return TestProcessingPattern.create(definition,
                new IPatternDetails.IInput[]{new TestInput(new GenericStack[]{
                        new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(
                        goldOutput ? Items.GOLD_INGOT : Items.IRON_INGOT), 1L)});
    }

    private static ItemStack markedPattern(boolean goldOutput) {
        ItemStack definition = new ItemStack(Items.BARRIER);
        definition.getOrCreateTag().putBoolean(TEST_PATTERN_MARKER, true);
        definition.getOrCreateTag().putBoolean("gold_output", goldOutput);
        return definition;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static String longPath(String prefix, int slot, int candidate) {
        return prefix + "_" + slot + "_" + candidate + "_" + "x".repeat(600);
    }

    private record Fixture(SingularPatternProviderBlockEntity provider, RecipeManager recipeManager,
            SmeltingRecipe recipe, ItemStack definition, List<ProviderBindingMenu.SlotSnapshot> snapshots) {
    }

    private record TestInput(GenericStack[] possibleInputs, long multiplier) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(possibleInputs[0].what());
        }

        @Override
        public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
            return null;
        }
    }
}

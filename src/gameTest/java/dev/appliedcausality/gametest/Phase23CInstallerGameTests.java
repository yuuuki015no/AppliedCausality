package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.GenericStack;
import appeng.me.GridNode;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import dev.appliedcausality.internal.config.ConfiguredRuleLoader;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.menu.ProviderBindingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Real block removal and capability boundary for retained Installer ownership. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase23CInstallerGameTests {
    private static final TagKey<Item> TEST_INSTALLER_TAG = TagKey.create(Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    AppliedCausality.MOD_ID, "phase23c_installer_test"));
    private Phase23CInstallerGameTests() {
    }

    @GameTest(template = "empty")
    public static void removalDropsRetainedInstallerAndAutomationSeesPatternsOnly(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(provider != null, "Provider block entity missing");
        provider.getInstallerInventory().setItemDirect(3, new ItemStack(Items.STONE));
        int automationSlots = provider.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .orElseThrow(() -> new IllegalStateException("Item capability missing")).getSlots();
        helper.assertTrue(automationSlots == SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT,
                "Installer slots leaked into automation capability");

        helper.getLevel().destroyBlock(absolute, true);
        helper.runAfterDelay(2, () -> {
            var dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(absolute).inflate(1.5)).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.STONE)).toList();
            helper.assertTrue(dropped.size() == 1 && dropped.get(0).getCount() == 1,
                    "Retained Installer was duplicated or lost on block removal");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void shiftClickExtractsRemovedDefinitionWithoutLoss(GameTestHelper helper) throws Exception {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(provider != null, "Provider block entity missing");
        provider.getInstallerInventory().setItemDirect(3, new ItemStack(Items.STONE));
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.getInventory().clearContent();
        Constructor<ProviderBindingMenu> constructor = ProviderBindingMenu.class.getDeclaredConstructor(
                int.class, net.minecraft.world.entity.player.Inventory.class,
                SingularPatternProviderBlockEntity.class, BlockPos.class, List.class);
        constructor.setAccessible(true);
        var menu = constructor.newInstance(1, player.getInventory(), provider, absolute,
                ProviderBindingMenu.snapshots(provider));
        var moved = menu.quickMoveStack(player, SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT + 3);
        helper.assertTrue(moved.is(Items.STONE) && moved.getCount() == 1,
                "Shift-click did not return the retained Installer");
        helper.assertTrue(provider.getInstallerInventory().getStackInSlot(3).isEmpty(),
                "Shift-click left duplicate Installer ownership");
        int playerCount = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.STONE)) {
                playerCount += stack.getCount();
            }
        }
        helper.assertTrue(playerCount == 1, "Shift-click lost or duplicated the Installer item");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "phase23c_unlock")
    public static void normalInsertionAndRuleReloadRefreshAe2AndOpenMenu(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = new SingularPatternProviderBlockEntity(BlockPos.ZERO,
                AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
        provider.setLevel(helper.getLevel());
        provider.getMainNode().create(helper.getLevel(), BlockPos.ZERO);
        provider.getMainNode().destroy(); // Keep the managed-node ready state without a second live test grid.
        Phase11StandardAe2GameTests.setActiveNode(provider, provider.getMainNode(), helper, f.grid, true);
        provider.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(f.raw, 1)},
                new GenericStack[]{new GenericStack(f.ingot, 1)}));
        var indexedNode = provider.getMainNode().getNode();
        ((GridNode) indexedNode).addService(ICraftingProvider.class, provider);
        f.service.addNode(indexedNode, null);
        ProviderReloadCoordinator.register(provider);
        ConfiguredRuleSnapshot original = ConfiguredRuleService.snapshot(helper.getLevel().getServer());
        Map<TagKey<Item>, List<Holder<Item>>> originalTags = snapshotItemTags();
        var grant = id("smelting_grant");
        var installer = id("smelting_installer");
        var unlock = id("smelting_unlock");
        var itemRules = new ConfiguredRuleSnapshot(0, Map.of(),
                Map.of(installer, new ConfiguredRuleSnapshot.Installer(installer,
                        id("stone"), Set.of(grant))),
                Set.of(grant), Map.of(unlock, new ConfiguredRuleSnapshot.UnlockRule(
                        unlock, null, id("smelting"), null, null, null, null, List.of(grant))), false);
        try {
            bindItemTag(originalTags, Items.OAK_PLANKS);
            reloadRules(helper, itemRules);
            assertState(helper, provider, f, ValidationDiagnostic.LOCKED, false);

            var player = FakePlayerFactory.getMinecraft(helper.getLevel());
            Constructor<ProviderBindingMenu> constructor = ProviderBindingMenu.class.getDeclaredConstructor(
                    int.class, net.minecraft.world.entity.player.Inventory.class,
                    SingularPatternProviderBlockEntity.class, BlockPos.class, List.class);
            constructor.setAccessible(true);
            var menu = constructor.newInstance(1, player.getInventory(), provider, BlockPos.ZERO,
                    ProviderBindingMenu.snapshots(provider));
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.LOCKED,
                    "Open menu did not start locked");

            var remainder = provider.getInstallerInventory().insertItem(0, new ItemStack(Items.STONE), false);
            helper.assertTrue(remainder.isEmpty(), "Normal Installer insertion was rejected");
            provider.flushPendingInventoryRebuild();
            assertState(helper, provider, f, ValidationDiagnostic.AVAILABLE, true);
            menu.broadcastChanges();
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.AVAILABLE,
                    "Open menu missed external Installer insertion");

            var removed = provider.getInstallerInventory().extractItem(0, 1, false);
            helper.assertTrue(removed.is(Items.STONE), "Normal Installer extraction lost item");
            provider.flushPendingInventoryRebuild();
            assertState(helper, provider, f, ValidationDiagnostic.LOCKED, false);
            menu.broadcastChanges();
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.LOCKED,
                    "Open menu missed external Installer extraction");

            provider.getInstallerInventory().insertItem(0, new ItemStack(Items.STONE), false);
            provider.flushPendingInventoryRebuild();
            reloadRules(helper, itemRules.withDefaultAllow(false).withInstallerSlots(4));
            assertState(helper, provider, f, ValidationDiagnostic.AVAILABLE, true);

            var tagRules = new ConfiguredRuleSnapshot(0, Map.of(),
                    Map.of(installer, new ConfiguredRuleSnapshot.Installer(installer,
                            null, TEST_INSTALLER_TAG.location(), Set.of(grant))),
                    Set.of(grant), itemRules.unlockRules(), false);
            reloadRules(helper, tagRules);
            assertState(helper, provider, f, ValidationDiagnostic.LOCKED, false);
            helper.assertTrue(provider.getInstallerInventory().getStackInSlot(0).is(Items.STONE),
                    "Removed Installer definition did not retain item ownership");
            menu.broadcastChanges();
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.LOCKED,
                    "Open menu missed rule reload");

            provider.getInstallerInventory().extractItem(0, 1, false);
            provider.getInstallerInventory().insertItem(0, new ItemStack(Items.OAK_PLANKS), false);
            provider.flushPendingInventoryRebuild();
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();
            assertState(helper, provider, f, ValidationDiagnostic.AVAILABLE, true);
            menu.broadcastChanges();
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.AVAILABLE,
                    "Open menu missed tag-backed reload");

            bindItemTag(originalTags, Items.DIRT);
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();
            assertState(helper, provider, f, ValidationDiagnostic.LOCKED, false);
            menu.broadcastChanges();
            helper.assertTrue(menu.diagnostic(0) == ValidationDiagnostic.LOCKED,
                    "Open menu missed tag membership removal");

            bindItemTag(originalTags, Items.OAK_PLANKS);
            ProviderReloadCoordinator.refreshAfterSuccessfulReload();
            assertState(helper, provider, f, ValidationDiagnostic.AVAILABLE, true);
        } finally {
            ProviderReloadCoordinator.unregister(provider);
            f.service.removeNode(indexedNode);
            restoreItemTags(originalTags);
            restoreRules(helper, original);
        }
        helper.succeed();
    }

    private static void assertState(GameTestHelper helper, SingularPatternProviderBlockEntity provider,
            Phase11StandardAe2GameTests.Fixture f, ValidationDiagnostic diagnostic, boolean advertised) {
        helper.assertTrue(provider.getPatternStatuses().get(0).diagnostic() == diagnostic,
                "Unexpected Provider diagnostic: " + provider.getPatternStatuses());
        helper.assertTrue(!provider.getAvailablePatterns().isEmpty() == advertised,
                "Provider availability did not match Installer grants");
        helper.assertTrue(!f.service.getCraftingFor(f.ingot).isEmpty() == advertised,
                "AE2 crafting index did not match Provider availability: diagnostic=" + diagnostic
                        + ", expected=" + advertised + ", actual="
                        + !f.service.getCraftingFor(f.ingot).isEmpty()
                        + ", nodeReady=" + provider.getMainNode().isReady());
    }

    private static Object ruleSession(GameTestHelper helper) throws Exception {
        Field sessionsField = ConfiguredRuleService.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        Object sessions = sessionsField.get(null);
        Method get = sessions.getClass().getDeclaredMethod("get", Object.class);
        get.setAccessible(true);
        Object session = get.invoke(sessions, helper.getLevel().getServer());
        if (session == null) {
            throw new IllegalStateException("Configured rule session is not active");
        }
        return session;
    }

    static void reloadRules(GameTestHelper helper, ConfiguredRuleSnapshot rules) throws Exception {
        Object session = ruleSession(helper);
        Method reload = ConfiguredRuleService.class.getDeclaredMethod("reloadSessionAfterResourceInstall",
                session.getClass(), Supplier.class, Runnable.class);
        reload.setAccessible(true);
        Supplier<ConfiguredRuleLoader.LoadResult> candidate =
                () -> new ConfiguredRuleLoader.LoadResult(rules, List.of());
        reload.invoke(null, session, candidate,
                (Runnable) ProviderReloadCoordinator::refreshAfterSuccessfulReload);
    }

    static void restoreRules(GameTestHelper helper, ConfiguredRuleSnapshot original) throws Exception {
        // Reset test-only session state exactly, including its generation, for other GameTests.
        Object session = ruleSession(helper);
        Field snapshot = session.getClass().getDeclaredField("snapshot");
        snapshot.setAccessible(true);
        snapshot.set(session, original);
        ProviderReloadCoordinator.refreshAfterSuccessfulReload();
    }

    private static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static Map<TagKey<Item>, List<Holder<Item>>> snapshotItemTags() {
        var snapshot = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>();
        BuiltInRegistries.ITEM.getTags().forEach(pair ->
                snapshot.put(pair.getFirst(), pair.getSecond().stream().toList()));
        return snapshot;
    }

    private static void bindItemTag(Map<TagKey<Item>, List<Holder<Item>>> originalTags, Item member) {
        var updated = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>(originalTags);
        updated.put(TEST_INSTALLER_TAG, List.of(BuiltInRegistries.ITEM.wrapAsHolder(member)));
        BuiltInRegistries.ITEM.bindTags(updated);
        Ingredient.invalidateAll();
    }

    private static void restoreItemTags(Map<TagKey<Item>, List<Holder<Item>>> originalTags) {
        var restored = new IdentityHashMap<TagKey<Item>, List<Holder<Item>>>(originalTags);
        restored.putIfAbsent(TEST_INSTALLER_TAG, List.of());
        BuiltInRegistries.ITEM.bindTags(restored);
        Ingredient.invalidateAll();
    }
}

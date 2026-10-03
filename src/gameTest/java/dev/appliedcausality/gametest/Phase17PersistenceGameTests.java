package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.crafting.CraftingLink;
import dev.appliedcausality.AppliedCausality;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase17PersistenceGameTests {
    @GameTest(template = "empty")
    public static void laterInventoryReturnSnapshotsAndFailuresStayQuarantined(GameTestHelper helper) throws Exception {
        for (boolean failAfterAcceptance : new boolean[] {false, true}) {
            var original = new Phase11StandardAe2GameTests.Fixture(helper);
            original.addSmeltingProvider();
            original.storage.items.set(original.raw, 10);
            original.storage.acceptInsert = false;
            original.submit(original.calculate(original.ingot, 10), null);
            require(original.execute() == 1 && original.cpu.bulk$getJob() == null
                            && original.logic.getInventory().list.get(original.ingot) == 10,
                    "initial full network did not leave jobless inventory");
            var snapshot = new CompoundTag();
            var accepted = new long[1];
            var failure = new IllegalStateException("later return failed after acceptance");
            original.networkStorage = new appeng.api.storage.MEStorage() {
                @Override
                public long insert(appeng.api.stacks.AEKey key, long amount, Actionable mode,
                        appeng.api.networking.security.IActionSource source) {
                    accepted[0] += 2;
                    original.logic.writeToNBT(snapshot);
                    require(snapshot.getCompound("applied_causality_transaction").getBoolean("uncertain"),
                            "later return callback saved unguarded inventory");
                    original.logic.storeItems();
                    original.logic.tickCraftingLogic(original.energy, original.service);
                    require(accepted[0] == 2, "callback recursively reoffered inventory");
                    if (failAfterAcceptance) throw failure;
                    return 2;
                }

                @Override
                public net.minecraft.network.chat.Component getDescription() {
                    return net.minecraft.network.chat.Component.literal("Phase17 later return");
                }
            };
            try {
                original.logic.tickCraftingLogic(original.energy, original.service);
                require(!failAfterAcceptance, "later return swallowed exception");
            } catch (IllegalStateException exception) {
                require(failAfterAcceptance && exception == failure, "original return exception was replaced");
            }
            var afterReturn = new CompoundTag();
            original.logic.writeToNBT(afterReturn);
            require(original.logic.getInventory().list.get(original.ingot) == (failAfterAcceptance ? 10 : 8),
                    "later return changed exact inventory remainder");
            require(afterReturn.contains("applied_causality_transaction") == failAfterAcceptance,
                    "stable/failed return checkpoint has wrong quarantine state");
            var restored = new Phase11StandardAe2GameTests.Fixture(helper);
            restored.logic.readFromNBT(snapshot);
            restored.logic.tickCraftingLogic(restored.energy, restored.service);
            restored.logic.storeItems();
            expectBlocked(restored.logic::cancel);
            expectBlocked(restored.core::breakCluster);
            require(accepted[0] == 2 && restored.storage.items.get(restored.ingot) == 0
                            && restored.logic.getInventory().list.get(restored.ingot) == 10,
                    "later return callback snapshot replayed ambiguous inventory");
            var checkpoint = new Phase11StandardAe2GameTests.Fixture(helper);
            checkpoint.logic.readFromNBT(afterReturn);
            checkpoint.logic.tickCraftingLogic(checkpoint.energy, checkpoint.service);
            if (failAfterAcceptance) {
                original.logic.storeItems();
                original.logic.tickCraftingLogic(original.energy, original.service);
                expectBlocked(original.core::breakCluster);
                expectBlocked(checkpoint.logic::cancel);
                require(accepted[0] == 2 && checkpoint.logic.getInventory().list.get(checkpoint.ingot) == 10
                                && checkpoint.storage.items.get(checkpoint.ingot) == 0,
                        "post-exception checkpoint retried unknown acceptance");
            } else {
                require(checkpoint.storage.items.get(checkpoint.ingot) + accepted[0] == 10
                                && checkpoint.logic.getInventory().list.isEmpty(),
                        "stable checkpoint did not return exactly eight remaining items");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void laterInventoryReturnDefersBlockBreakUntilExactSubtraction(GameTestHelper helper) throws Exception {
        for (long acceptance : new long[] {0, 2, 10}) {
            var f = new Phase11StandardAe2GameTests.Fixture(helper);
            f.addSmeltingProvider();
            f.storage.items.set(f.raw, 10);
            f.storage.acceptInsert = false;
            f.submit(f.calculate(f.ingot, 10), null);
            require(f.execute() == 1 && f.cpu.bulk$getJob() == null
                            && f.logic.getInventory().list.get(f.ingot) == 10,
                    "fixture did not defer the initial network return");
            long previousDrops = f.droppedIngots();
            var accepted = new long[1];
            f.networkStorage = new appeng.api.storage.MEStorage() {
                @Override
                public long insert(appeng.api.stacks.AEKey key, long amount, Actionable mode,
                        appeng.api.networking.security.IActionSource source) {
                    accepted[0] += acceptance;
                    f.core.breakCluster();
                    require(f.logic.getInventory().list.get(f.ingot) == 10 && f.droppedIngots() == previousDrops,
                            "callback tore down inventory before exact subtraction");
                    return acceptance;
                }

                @Override
                public net.minecraft.network.chat.Component getDescription() {
                    return net.minecraft.network.chat.Component.literal("Phase17 later return teardown");
                }
            };
            f.logic.tickCraftingLogic(f.energy, f.service);
            require(accepted[0] + f.droppedIngots() - previousDrops == 10
                            && f.logic.getInventory().list.isEmpty(),
                    "later return teardown duplicated or lost output");
            f.core.breakCluster();
            require(accepted[0] + f.droppedIngots() - previousDrops == 10, "repeated break duplicated output");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void joblessSnapshotBlocksNewSubmissionAndInventoryReturn(GameTestHelper helper) throws Exception {
        var original = new Phase11StandardAe2GameTests.Fixture(helper);
        original.addSmeltingProvider();
        original.storage.items.set(original.raw, 10);
        var plan = original.calculate(original.ingot, 10);
        original.submit(plan, null);
        var snapshot = new CompoundTag();
        original.networkStorage = new appeng.api.storage.MEStorage() {
            @Override
            public long insert(appeng.api.stacks.AEKey key, long amount, Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                require(original.cpu.bulk$getJob() == null, "snapshot still had a job");
                original.logic.writeToNBT(snapshot);
                return amount;
            }

            @Override
            public net.minecraft.network.chat.Component getDescription() {
                return net.minecraft.network.chat.Component.literal("Phase17 callback snapshot");
            }
        };
        require(original.execute() == 1, "standalone dispatch failed");
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(snapshot);
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        restored.storage.items.set(restored.raw, 10);
        require(!restored.logic.trySubmitJob(restored.grid, plan,
                        appeng.api.networking.security.IActionSource.empty(), null).successful()
                        && restored.storage.items.get(restored.raw) == 10
                        && restored.storage.items.get(restored.ingot) == 0
                        && restored.logic.getInventory().list.get(restored.ingot) == 10,
                "jobless quarantine admitted work or retried ambiguous inventory");
        expectBlocked(restored.core::breakCluster);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void intermediateRetryDefersCancelUntilAllKeysHaveSettled(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        f.addSmeltingProvider();
        f.storage.items.set(f.raw, 10);
        f.storage.acceptInsert = false;
        f.requester.limit = 0;
        f.submit(f.calculate(f.ingot, 10), f.requester);
        require(f.execute() == 1, "initial dispatch failed");
        var gold = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.GOLD_INGOT);
        var copper = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.COPPER_INGOT);
        var job = f.job();
        job.bulk$outputEscrow().add(gold, 3);
        job.bulk$outputEscrow().add(copper, 4);
        var armed = new boolean[] {true};
        f.logic.addListener(key -> {
            if (key.equals(gold) && armed[0]) {
                armed[0] = false;
                f.logic.cancel();
                require(f.cpu.bulk$getJob() == job, "intermediate callback deleted live retry job");
            }
        });
        f.logic.tickCraftingLogic(f.energy, f.service);
        require(f.cpu.bulk$getJob() == null && job.bulk$outputEscrow().isEmpty(), "deferred cancel not completed");
        require(f.logic.getInventory().list.get(gold) == 3 && f.logic.getInventory().list.get(copper) == 4
                        && f.logic.getInventory().list.get(f.ingot) == 10 && f.requester.accepted == 0
                        && f.storage.items.get(f.ingot) == 0 && f.droppedIngots() == 0,
                "intermediate retry cancellation lost or duplicated ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownAcceptanceAndCallbackSnapshotsCannotReplayAfterRestart(GameTestHelper helper)
            throws Exception {
        for (boolean retry : new boolean[] {false, true}) {
            var original = new Phase11StandardAe2GameTests.Fixture(helper);
            original.addSmeltingProvider();
            original.storage.items.set(original.raw, 10);
            original.requester.limit = 0;
            original.submit(original.calculate(original.ingot, 10), original.requester);
            if (retry) require(original.execute() == 1, "initial dispatch failed");
            var callbackSave = new CompoundTag();
            var linkSave = new CompoundTag();
            original.requesterLink.writeToNBT(linkSave);
            var failure = new IllegalStateException("injected failure after accepting two items");
            original.requester.limit = 2;
            original.requester.onModulate = () -> {
                original.logic.writeToNBT(callbackSave);
                throw failure;
            };
            try {
                if (retry) original.logic.tickCraftingLogic(original.energy, original.service);
                else original.execute();
                throw new AssertionError("injected exception was swallowed");
            } catch (IllegalStateException expected) {
                require(expected == failure, "original exception was replaced");
            }
            var failedSave = new CompoundTag();
            original.logic.writeToNBT(failedSave);
            require(original.requester.accepted == 2, "failure did not follow external mutation");
            for (var saved : new CompoundTag[] {callbackSave, failedSave}) {
                var restored = new Phase11StandardAe2GameTests.Fixture(helper);
                restored.logic.readFromNBT(saved);
                restored.service.addLink((CraftingLink) restored.logic.getLastLink());
                restored.service.addLink(new CraftingLink(linkSave, restored.requester));
                var job = restored.job();
                restored.logic.tickCraftingLogic(restored.energy, restored.service);
                require(restored.requester.accepted == 0 && restored.execute() == 0,
                        "restored unknown acceptance was replayed");
                require(!restored.cpu.bulk$tryFinishJob(), "quarantined job completed");
                require(restored.logic.insert(restored.ingot, 1, Actionable.MODULATE) == 0,
                        "stock insertion mutated quarantined accounting");
                expectBlocked(restored.logic::cancel);
                expectBlocked(restored.core::breakCluster);
                require(restored.job() == job && job.bulk$outputEscrow().get(restored.ingot) == 10
                                && restored.logic.getInventory().list.get(restored.ingot) == 0
                                && restored.storage.items.get(restored.ingot) == 0 && restored.droppedIngots() == 0,
                        "quarantined record was paid out or discarded");
                var resaved = new CompoundTag();
                restored.logic.writeToNBT(resaved);
                var twiceRestored = new Phase11StandardAe2GameTests.Fixture(helper);
                twiceRestored.logic.readFromNBT(resaved);
                expectBlocked(twiceRestored.logic::cancel);
            }
        }
        helper.succeed();
    }

    static void expectBlocked(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("unknown ownership operation was allowed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package dev.appliedcausality.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.CraftingServiceStorage;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Real AE2 planning, admission, CPU execution, provider routing, and persistence
 * across multi-pattern jobs. External machine output and requester capacity are
 * controlled boundaries so every ownership transition can be asserted exactly.
 */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase13CraftingChainGameTests {
    private Phase13CraftingChainGameTests() {
    }

    @GameTest(template = "empty")
    public static void bulkBulkNormalChainTracksWaitingInventoryTasksAndProgress(
            GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var cobblestone = AEItemKey.of(Items.COBBLESTONE);
        var stone = AEItemKey.of(Items.STONE);
        var smoothStone = AEItemKey.of(Items.SMOOTH_STONE);
        var diamond = AEItemKey.of(Items.DIAMOND);
        long multiplier = 3;

        var firstProvider = f.addSmeltingProvider(new BlockPos(1, 2, 1), cobblestone, stone);
        var secondProvider = f.addSmeltingProvider(new BlockPos(2, 2, 1), stone, smoothStone);
        var firstPattern = firstProvider.getAvailablePatterns().get(0);
        var secondPattern = secondProvider.getAvailablePatterns().get(0);
        var finalPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(smoothStone, 1) },
                new GenericStack[] { new GenericStack(diamond, 1) });
        var normal = new DeferredNormalProvider(finalPattern);
        f.register(normal);
        f.storage.items.set(cobblestone, multiplier);

        f.submit(f.calculate(diamond, multiplier), f.requester);
        var job = f.job();
        require(taskRemaining(job, firstPattern) == multiplier
                        && taskRemaining(job, secondPattern) == multiplier
                        && taskRemaining(job, finalPattern) == multiplier,
                "planner did not retain all chain task multipliers");
        require(startedItemWork(f) == 3 * multiplier && completedItemWork(f) == 0,
                "initial chain progress maximum was not registered once");

        require(f.execute() == 1, "first Bulk dependency did not dispatch");
        require(f.logic.getInventory().list.get(cobblestone) == 0
                        && f.logic.getInventory().list.get(stone) == multiplier,
                "first Bulk output did not become same-job CPU inventory");
        require(taskRemaining(job, firstPattern) == 0
                        && taskRemaining(job, secondPattern) == multiplier,
                "first Bulk dispatch changed the wrong tasks");
        require(job.bulk$getWaitingFor(stone) == 0
                        && job.bulk$outputEscrow().isEmpty()
                        && completedItemWork(f) == multiplier,
                "first intermediate output duplicated waiting/escrow/progress ownership");

        require(f.execute() == 1, "second Bulk dependency did not dispatch");
        require(f.logic.getInventory().list.get(stone) == 0
                        && f.logic.getInventory().list.get(smoothStone) == multiplier,
                "second Bulk output did not continue the same job");
        require(taskRemaining(job, secondPattern) == 0
                        && taskRemaining(job, finalPattern) == multiplier,
                "second Bulk dispatch changed the wrong tasks");
        require(job.bulk$getWaitingFor(smoothStone) == 0
                        && job.bulk$outputEscrow().isEmpty()
                        && completedItemWork(f) == 2 * multiplier,
                "second intermediate output duplicated waiting/escrow/progress ownership");

        for (int operation = 1; operation <= multiplier; operation++) {
            require(f.execute() == 1 && normal.pushes == operation,
                    "normal final task did not use stock one-operation dispatch");
            require(f.logic.getWaitingFor(diamond) == 1,
                    "normal output was not waiting before materialization");
            require(taskRemaining(job, finalPattern) == multiplier - operation,
                    "normal task progress changed by more than one operation");
            require(completedItemWork(f) == 2 * multiplier + operation - 1,
                    "normal dispatch advanced completed progress before output arrival");
            require(f.logic.insert(diamond, 1, Actionable.MODULATE) == 1,
                    "normal final output was not accepted through the stock CPU route");
        }

        require(f.cpu.bulk$getJob() == null && f.requester.accepted == multiplier,
                "mixed chain did not complete at the requester");
        require(f.logic.getInventory().list.get(smoothStone) == 0
                        && f.storage.items.get(cobblestone) == 0,
                "completed chain retained duplicate input/intermediate ownership");
        require(job.bulk$outputEscrow().isEmpty() && job.bulk$getWaitingFor(diamond) == 0,
                "completed chain retained waiting or escrow ownership");
        require(f.energyCalls == 2 * multiplier,
                "Bulk steps used energy or normal steps skipped the stock energy path");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mixedInversionsPreserveStockAndBulkOrdering(GameTestHelper helper) throws Exception {
        runBulkNormalBulk(helper);
        runNormalBulkNormal(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void independentBulkBranchesCompleteAndCancelWithoutCrossConsumption(
            GameTestHelper helper) throws Exception {
        long multiplier = 4;
        var completed = independentBranches(helper, multiplier, false);

        require(completed.f().execute() == 1, "first independent Bulk branch did not dispatch");
        boolean ironFirst = completed.f().logic.getInventory().list.get(completed.iron()) == multiplier;
        boolean goldFirst = completed.f().logic.getInventory().list.get(completed.gold()) == multiplier;
        require(ironFirst ^ goldFirst, "one scheduler operation did not isolate one independent branch");
        require(taskRemaining(completed.job(), completed.ironPattern()) == (ironFirst ? 0 : multiplier)
                        && taskRemaining(completed.job(), completed.goldPattern()) == (goldFirst ? 0 : multiplier),
                "independent task progress crossed branches");
        require(completed.job().bulk$outputEscrow().isEmpty(),
                "intermediate branch output remained in escrow");

        require(completed.f().execute() == 1, "second independent Bulk branch did not dispatch");
        require(completed.f().logic.getInventory().list.get(completed.iron()) == multiplier
                        && completed.f().logic.getInventory().list.get(completed.gold()) == multiplier,
                "independent branch outputs were not both retained for the join");
        require(completedItemWork(completed.f()) == 2 * multiplier,
                "independent Bulk progress was not counted exactly once");

        for (int operation = 1; operation <= multiplier; operation++) {
            require(completed.f().execute() == 1 && completed.join().pushes == operation,
                    "normal join did not dispatch one operation");
            require(completed.f().logic.getWaitingFor(completed.finalKey()) == 1,
                    "join output was not waiting before arrival");
            require(completed.f().logic.insert(completed.finalKey(), 1, Actionable.MODULATE) == 1,
                    "join output was not accepted");
        }
        require(completed.f().cpu.bulk$getJob() == null
                        && completed.f().requester.accepted == multiplier,
                "independent branches did not complete their shared requester result");

        var canceled = independentBranches(helper, multiplier, true);
        require(canceled.f().execute() == 1, "cancellation fixture did not dispatch one branch");
        var canceledJob = canceled.job();
        boolean canceledIronFirst = canceled.f().logic.getInventory().list.get(canceled.iron()) == multiplier;
        canceled.f().logic.cancel();

        require(canceled.f().cpu.bulk$getJob() == null && canceledJob.bulk$isCanceled(),
                "mid-chain cancellation did not remove the job");
        require(canceledJob.bulk$outputEscrow().isEmpty()
                        && canceledJob.bulk$getWaitingFor(canceled.iron()) == 0
                        && canceledJob.bulk$getWaitingFor(canceled.gold()) == 0,
                "cancellation retained duplicate waiting/escrow ownership");
        if (canceledIronFirst) {
            require(canceled.f().logic.getInventory().list.get(canceled.iron()) == multiplier
                            && canceled.f().logic.getInventory().list.get(canceled.rawGold()) == multiplier
                            && canceled.f().logic.getInventory().list.get(canceled.rawIron()) == 0
                            && canceled.f().logic.getInventory().list.get(canceled.gold()) == 0,
                    "cancellation lost or cross-consumed independent branch ownership");
        } else {
            require(canceled.f().logic.getInventory().list.get(canceled.gold()) == multiplier
                            && canceled.f().logic.getInventory().list.get(canceled.rawIron()) == multiplier
                            && canceled.f().logic.getInventory().list.get(canceled.rawGold()) == 0
                            && canceled.f().logic.getInventory().list.get(canceled.iron()) == 0,
                    "cancellation lost or cross-consumed independent branch ownership");
        }
        require(canceled.f().requester.accepted == 0,
                "canceled intermediate work reached the requester");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void waitingRegistrationReentrantCancelSettlesCommitBeforeTeardown(
            GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var cobblestone = AEItemKey.of(Items.COBBLESTONE);
        var stone = AEItemKey.of(Items.STONE);
        var diamond = AEItemKey.of(Items.DIAMOND);
        long multiplier = 3;

        f.addSmeltingProvider(new BlockPos(1, 2, 1), cobblestone, stone);
        var finalPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(stone, 1) },
                new GenericStack[] { new GenericStack(diamond, 1) });
        f.register(new DeferredNormalProvider(finalPattern));
        f.storage.items.set(cobblestone, multiplier);
        f.storage.acceptInsert = false;
        f.submit(f.calculate(diamond, multiplier), f.requester);
        var job = f.job();
        var canceledFromWaitingListener = new boolean[1];
        f.logic.addListener(changed -> {
            if (!canceledFromWaitingListener[0]
                    && changed.equals(stone)
                    && f.logic.getWaitingFor(stone) == multiplier) {
                canceledFromWaitingListener[0] = true;
                f.logic.cancel();
            }
        });

        require(f.execute() == 1, "listener cancellation prevented the committed scheduler operation");
        require(canceledFromWaitingListener[0] && f.cpu.bulk$getJob() == null && job.bulk$isCanceled(),
                "waiting listener cancellation was not deferred through commit");
        require(f.logic.getInventory().list.get(cobblestone) == 0
                        && f.logic.getInventory().list.get(stone) == multiplier,
                "deferred cancellation lost extracted input or committed intermediate output");
        require(job.bulk$getWaitingFor(stone) == 0 && job.bulk$outputEscrow().isEmpty(),
                "deferred cancellation retained duplicate waiting/escrow ownership");
        require(f.storage.items.get(cobblestone) == 0
                        && f.storage.items.get(stone) == 0
                        && f.requester.accepted == 0,
                "deferred cancellation leaked committed ownership outside the CPU");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bulkChainEscrowRetrySurvivesSaveLoadAndChunkUnload(
            GameTestHelper helper) throws Exception {
        var original = new Phase11StandardAe2GameTests.Fixture(helper);
        var cobblestone = AEItemKey.of(Items.COBBLESTONE);
        var stone = AEItemKey.of(Items.STONE);
        var smoothStone = AEItemKey.of(Items.SMOOTH_STONE);
        long multiplier = 10;
        original.addSmeltingProvider(new BlockPos(1, 2, 1), cobblestone, stone);
        original.addSmeltingProvider(new BlockPos(2, 2, 1), stone, smoothStone);
        original.storage.items.set(cobblestone, multiplier);
        original.requester.limit = 2;
        original.submit(original.calculate(smoothStone, multiplier), original.requester);

        require(original.execute() == 1 && original.execute() == 1,
                "Bulk chain did not materialize its final output");
        var originalJob = original.job();
        require(original.requester.accepted == 2
                        && originalJob.bulk$outputEscrow().get(smoothStone) == 8
                        && originalJob.bulk$getRemainingFinalAmount() == 8,
                "partial final acceptance did not retain the exact chain remainder");
        require(originalJob.bulk$getWaitingFor(stone) == 0
                        && originalJob.bulk$getWaitingFor(smoothStone) == 0
                        && original.logic.getInventory().list.get(stone) == 0,
                "chain materialization retained duplicate intermediate/waiting ownership");
        require(startedItemWork(original) == 2 * multiplier
                        && completedItemWork(original) == 2 * multiplier,
                "chain progress did not complete exactly at materialization");

        original.requester.limit = 0;
        original.logic.tickCraftingLogic(original.energy, original.service);
        require(originalJob.bulk$outputEscrow().get(smoothStone) == 8
                        && original.requester.accepted == 2,
                "zero-acceptance retry changed output ownership");

        var savedCpu = new CompoundTag();
        original.core.saveAdditional(savedCpu);
        require(savedCpu.getBoolean("core") && savedCpu.contains("job", Tag.TAG_COMPOUND),
                "core BlockEntity save omitted the active crafting job");
        var savedRequesterLink = new CompoundTag();
        original.requesterLink.writeToNBT(savedRequesterLink);
        var completedBeforeUnload = savedCpu.getCompound("job")
                .getCompound("timeTracker").getCompound("completedWork").copy();
        long completedItemsBeforeUnload = completedBeforeUnload
                .getLong(smoothStone.getType().getId().toString());
        long dropsBeforeUnload = droppedSmoothStone(original);
        original.core.onChunkUnloaded();
        require(!original.cluster.isActive(), "unloaded CPU remained active");
        original.logic.tickCraftingLogic(original.energy, original.service);
        require(originalJob.bulk$outputEscrow().get(smoothStone) == 8
                        && original.logic.getInventory().list.get(smoothStone) == 0
                        && original.storage.items.get(smoothStone) == 0
                        && droppedSmoothStone(original) == dropsBeforeUnload
                        && original.requester.accepted == 2,
                "chunk unload paid out or lost ownership on the old CPU side");

        var restored = new Phase11StandardAe2GameTests.Fixture(helper, savedCpu);
        var restoredJob = restored.job();
        var restoredTracker = restored.logic.getElapsedTimeTracker();
        require(restoredJob.bulk$outputEscrow().get(smoothStone) == 8
                        && restoredJob.bulk$getRemainingFinalAmount() == 8
                        && restored.core.isCoreBlock()
                        && restored.core.getPreviousState() == null,
                "BlockEntity load/cluster formation lost chain ownership or retained stale state");
        require(restored.logic.getInventory().list.get(smoothStone) == 0
                        && restored.storage.items.get(smoothStone) == 0
                        && droppedSmoothStone(restored) == dropsBeforeUnload,
                "restore duplicated escrow into another ownership destination");
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        require(restoredJob.bulk$outputEscrow().get(smoothStone) == 8,
                "disconnected requester retry lost restored output");

        var restoredRequesterLink = new CraftingLink(savedRequesterLink, restored.requester);
        var restoredCpuLink = (CraftingLink) restored.logic.getLastLink();
        require(restoredCpuLink.getCraftingID().equals(restoredRequesterLink.getCraftingID()),
                "restored chain changed crafting-link identity");
        restored.service.addLink(restoredCpuLink);
        restored.service.addLink(restoredRequesterLink);
        restored.requester.limit = Long.MAX_VALUE;
        restored.logic.tickCraftingLogic(restored.energy, restored.service);

        require(restored.cpu.bulk$getJob() == null && restored.requester.accepted == 8,
                "restored chain did not deliver and finish its exact remainder");
        require(original.requester.accepted + restored.requester.accepted == multiplier,
                "save/load duplicated or lost the final chain result");
        require(originalJob.bulk$outputEscrow().get(smoothStone) == 8
                        && original.logic.getInventory().list.get(smoothStone) == 0
                        && original.storage.items.get(smoothStone) == 0
                        && restored.logic.getInventory().list.get(smoothStone) == 0
                        && restored.storage.items.get(smoothStone) == 0
                        && droppedSmoothStone(original) == dropsBeforeUnload,
                "restored delivery also paid out through an old or alternate owner");
        require(restoredJob.bulk$outputEscrow().isEmpty()
                        && restoredJob.bulk$getRemainingFinalAmount() == 0,
                "restored completion retained escrow ownership");
        long completedItemsAfterRetry = restoredTracker.writeToNBT().getCompound("completedWork")
                .getLong(smoothStone.getType().getId().toString());
        require(completedItemsAfterRetry == completedItemsBeforeUnload,
                "restored retry repeated completed progress");
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        original.logic.tickCraftingLogic(original.energy, original.service);
        require(restored.requester.deliveries == 1,
                "finished restored chain redelivered output");
        require(original.requester.accepted == 2,
                "inactive old CPU redelivered the restored output");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void normalCpuPartialAndZeroAcceptanceRemainProviderRemainders(
            GameTestHelper helper) throws Exception {
        var partial = normalRoutingScenario(helper, new BlockPos(1, 2, 1), 2);
        var zero = normalRoutingScenario(helper, new BlockPos(2, 2, 1), 0);

        tick(partial.provider());
        tick(zero.provider());
        require(pending(partial.provider()) == 10 && pending(zero.provider()) == 10,
                "load barrier changed controlled fallback ownership");

        helper.runAfterDelay(1, () -> {
            tick(partial.provider());
            tick(zero.provider());
            require(partial.f().cpu.bulk$getJob() == null
                            && partial.f().requesterLink.isDone()
                            && partial.f().requester.accepted == 2
                            && pending(partial.provider()) == 8,
                    "normal CPU partial acceptance did not leave the exact Provider remainder");
            require(zero.f().cpu.bulk$getJob() == null
                            && zero.f().requesterLink.isDone()
                            && zero.f().requester.accepted == 0
                            && pending(zero.provider()) == 10,
                    "normal CPU zero acceptance did not leave all output at the Provider");
            require(partial.f().logic.getWaitingFor(partial.f().ingot) == 0
                            && zero.f().logic.getWaitingFor(zero.f().ingot) == 0,
                    "stock normal CPU did not advance its own waiting/completion state");
        });
        helper.runAfterDelay(2, () -> {
            tick(partial.provider());
            tick(zero.provider());
            require(partial.f().requester.accepted == 2 && pending(partial.provider()) == 8,
                    "partial remainder retry duplicated or lost output without CPU waiting");
            require(zero.f().requester.accepted == 0 && pending(zero.provider()) == 10,
                    "zero remainder retry duplicated or lost output without CPU waiting");
            helper.succeed();
        });
    }

    private static void runBulkNormalBulk(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var cobblestone = AEItemKey.of(Items.COBBLESTONE);
        var stone = AEItemKey.of(Items.STONE);
        var rawIron = AEItemKey.of(Items.RAW_IRON);
        var iron = AEItemKey.of(Items.IRON_INGOT);
        f.addSmeltingProvider(new BlockPos(1, 2, 1), cobblestone, stone);
        var middlePattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(stone, 1) },
                new GenericStack[] { new GenericStack(rawIron, 1) });
        var middle = new DeferredNormalProvider(middlePattern);
        f.register(middle);
        f.addSmeltingProvider(new BlockPos(2, 2, 1), rawIron, iron);
        f.storage.items.set(cobblestone, 1);
        f.submit(f.calculate(iron, 1), f.requester);

        require(f.execute() == 1 && f.logic.getInventory().list.get(stone) == 1,
                "Bulk-normal-Bulk did not start with its Bulk dependency");
        require(f.execute() == 1 && middle.pushes == 1
                        && f.logic.getWaitingFor(rawIron) == 1,
                "Bulk-normal-Bulk did not preserve the stock middle dispatch");
        require(f.logic.insert(rawIron, 1, Actionable.MODULATE) == 1
                        && f.logic.getInventory().list.get(rawIron) == 1,
                "normal middle output did not become same-job CPU inventory");
        require(f.execute() == 1 && f.requester.accepted == 1
                        && f.cpu.bulk$getJob() == null,
                "Bulk-normal-Bulk did not finish through its Bulk final task");
        require(f.energyCalls == 2, "mixed inversion changed normal/Bulk energy semantics");
    }

    private static void runNormalBulkNormal(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var coal = AEItemKey.of(Items.COAL);
        var cobblestone = AEItemKey.of(Items.COBBLESTONE);
        var stone = AEItemKey.of(Items.STONE);
        var diamond = AEItemKey.of(Items.DIAMOND);
        var firstPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(coal, 1) },
                new GenericStack[] { new GenericStack(cobblestone, 1) });
        var first = new DeferredNormalProvider(firstPattern);
        f.register(first);
        f.addSmeltingProvider(new BlockPos(1, 2, 1), cobblestone, stone);
        var finalPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(stone, 1) },
                new GenericStack[] { new GenericStack(diamond, 1) });
        var last = new DeferredNormalProvider(finalPattern);
        f.register(last);
        f.storage.items.set(coal, 1);
        f.submit(f.calculate(diamond, 1), f.requester);

        require(f.execute() == 1 && first.pushes == 1
                        && f.logic.getWaitingFor(cobblestone) == 1,
                "normal-Bulk-normal did not start on the stock path");
        require(f.logic.insert(cobblestone, 1, Actionable.MODULATE) == 1,
                "first normal output did not enter CPU inventory");
        require(f.execute() == 1 && f.logic.getInventory().list.get(stone) == 1,
                "normal-Bulk-normal did not execute its Bulk middle task");
        require(f.execute() == 1 && last.pushes == 1
                        && f.logic.getWaitingFor(diamond) == 1,
                "normal-Bulk-normal did not return to the stock path");
        require(f.logic.insert(diamond, 1, Actionable.MODULATE) == 1
                        && f.requester.accepted == 1
                        && f.cpu.bulk$getJob() == null,
                "normal-Bulk-normal did not complete at the requester");
        require(f.energyCalls == 4, "mixed inversion changed normal/Bulk energy semantics");
    }

    private static IndependentFixture independentBranches(
            GameTestHelper helper, long multiplier, boolean rejectStorageInsert) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var rawIron = AEItemKey.of(Items.RAW_IRON);
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var rawGold = AEItemKey.of(Items.RAW_GOLD);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var finalKey = AEItemKey.of(Items.DIAMOND);
        var ironProvider = f.addSmeltingProvider(new BlockPos(1, 2, 1), rawIron, iron);
        var goldProvider = f.addSmeltingProvider(new BlockPos(2, 2, 1), rawGold, gold);
        var joinPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(iron, 1), new GenericStack(gold, 1) },
                new GenericStack[] { new GenericStack(finalKey, 1) });
        var join = new DeferredNormalProvider(joinPattern);
        f.register(join);
        f.storage.items.set(rawIron, multiplier);
        f.storage.items.set(rawGold, multiplier);
        f.storage.acceptInsert = !rejectStorageInsert;
        f.submit(f.calculate(finalKey, multiplier), f.requester);
        return new IndependentFixture(
                f,
                f.job(),
                rawIron,
                iron,
                rawGold,
                gold,
                finalKey,
                ironProvider.getAvailablePatterns().get(0),
                goldProvider.getAvailablePatterns().get(0),
                join);
    }

    private static NormalRoutingFixture normalRoutingScenario(
            GameTestHelper helper, BlockPos providerPosition, long requesterLimit) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var fallbackProvider = f.addSmeltingProvider(providerPosition);
        f.service.removeNode(f.providerNodes.get(0));

        // Replace a valid one-operation checkpoint with a controlled aggregated
        // quantity. This isolates the real normal CPU requester callback and
        // Provider remainder accounting without authorizing a fabricated Bulk
        // recipe or changing production code.
        var borrowedInput = new KeyCounter();
        borrowedInput.set(f.raw, 1);
        require(fallbackProvider.pushPattern(
                        fallbackProvider.getAvailablePatterns().get(0),
                        new KeyCounter[] { borrowedInput }),
                "could not seed committed fallback output");
        var savedProvider = new CompoundTag();
        fallbackProvider.saveAdditional(savedProvider);
        var outputs = savedProvider.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND);
        require(outputs.size() == 1, "controlled fallback checkpoint was malformed");
        outputs.getCompound(0).putLong("amount", 10);
        fallbackProvider.loadTag(savedProvider);

        var normalPattern = processingPattern(helper,
                new GenericStack[] { new GenericStack(f.raw, 1) },
                new GenericStack[] { new GenericStack(f.ingot, 10) });
        var normal = new DeferredNormalProvider(normalPattern);
        f.register(normal);
        mountNormalCpuRoute(f);
        f.storage.items.set(f.raw, 1);
        f.requester.limit = requesterLimit;
        f.submit(f.calculate(f.ingot, 10), f.requester);
        require(f.execute() == 1 && normal.pushes == 1 && f.logic.getWaitingFor(f.ingot) == 10,
                "normal CPU did not establish the controlled ten-item waiting state");
        return new NormalRoutingFixture(f, fallbackProvider);
    }

    private static void mountNormalCpuRoute(Phase11StandardAe2GameTests.Fixture f) throws Exception {
        var clusters = CraftingService.class.getDeclaredField("craftingCPUClusters");
        clusters.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registered = (Set<CraftingCPUCluster>) clusters.get(f.service);
        registered.add(f.cluster);
        var mounted = new ArrayList<MEStorage>();
        new CraftingServiceStorage(f.service).mountInventories((storage, priority) -> mounted.add(storage));
        var cpuStorage = mounted.get(0);
        f.networkStorage = new MEStorage() {
            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                return f.storage.extract(key, amount, mode, source);
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                if (f.cpu.bulk$getJob() != null) {
                    require(f.logic.getWaitingFor(key) >= amount,
                            "fallback output reached the normal CPU before waiting registration");
                }
                return cpuStorage.insert(key, amount, mode, source);
            }

            @Override
            public Component getDescription() {
                return Component.literal("Phase 13 normal CPU routing");
            }
        };
    }

    private static IPatternDetails processingPattern(
            GameTestHelper helper, GenericStack[] inputs, GenericStack[] outputs) {
        var details = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeProcessingPattern(inputs, outputs), helper.getLevel());
        if (details == null) {
            throw new GameTestAssertException("could not decode controlled processing pattern");
        }
        return details;
    }

    private static long taskRemaining(IBulkJobBridge job, IPatternDetails pattern) {
        for (var task : job.bulk$tasks()) {
            if (task.pattern().equals(pattern)) {
                return task.remaining();
            }
        }
        return 0;
    }

    private static long startedItemWork(Phase11StandardAe2GameTests.Fixture f) {
        return f.logic.getElapsedTimeTracker().writeToNBT().getCompound("startedWork")
                .getLong(f.ingot.getType().getId().toString());
    }

    private static long completedItemWork(Phase11StandardAe2GameTests.Fixture f) {
        return f.logic.getElapsedTimeTracker().writeToNBT().getCompound("completedWork")
                .getLong(f.ingot.getType().getId().toString());
    }

    private static void tick(SingularPatternProviderBlockEntity provider) {
        SingularPatternProviderBlockEntity.serverTick(
                provider.getLevel(), provider.getBlockPos(), provider.getBlockState(), provider);
    }

    private static long pending(SingularPatternProviderBlockEntity provider) {
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        return saved.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND).stream()
                .mapToLong(tag -> ((CompoundTag) tag).getLong("amount")).sum();
    }

    private static long droppedSmoothStone(Phase11StandardAe2GameTests.Fixture f) {
        return f.helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, new AABB(f.core.getBlockPos()).inflate(2)).stream()
                .filter(entity -> entity.getItem().is(Items.SMOOTH_STONE))
                .mapToLong(entity -> entity.getItem().getCount()).sum();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private static final class DeferredNormalProvider implements ICraftingProvider {
        private final IPatternDetails pattern;
        private int pushes;

        private DeferredNormalProvider(IPatternDetails pattern) {
            this.pattern = pattern;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
            require(details.equals(pattern) && inputs.length == pattern.getInputs().length,
                    "normal provider received a different pattern/input shape");
            pushes++;
            return true;
        }
    }

    private record IndependentFixture(
            Phase11StandardAe2GameTests.Fixture f,
            IBulkJobBridge job,
            AEItemKey rawIron,
            AEItemKey iron,
            AEItemKey rawGold,
            AEItemKey gold,
            AEItemKey finalKey,
            IPatternDetails ironPattern,
            IPatternDetails goldPattern,
            DeferredNormalProvider join) {
    }

    private record NormalRoutingFixture(
            Phase11StandardAe2GameTests.Fixture f,
            SingularPatternProviderBlockEntity provider) {
    }
}

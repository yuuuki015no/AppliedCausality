package dev.appliedcausality.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.core.definitions.AEBlockEntities;
import appeng.core.definitions.AEBlocks;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.GridNode;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.budget.BulkDispatchBudgetService;
import dev.appliedcausality.internal.budget.IBulkDispatchBudget;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.cpu.IBulkCpuBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real AE2 planning, admission, providers, CPU, and transformed scheduler.
 * Network power/storage/requester boundaries are controlled test doubles. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase11StandardAe2GameTests {
    private static final long STOCK_BYTES = 256 * 1024;
    private static final long LARGEST_256K_N = (STOCK_BYTES - 16) / 3;

    private Phase11StandardAe2GameTests() {
    }

    @GameTest(template = "empty")
    public static void realProcessingPatternPreservesFluidMilliBucketsAndTags(GameTestHelper helper) {
        var tag = new CompoundTag();
        tag.putString("variant", "test");
        var input = AEFluidKey.of(Fluids.WATER, tag);
        var output = AEFluidKey.of(Fluids.LAVA);
        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 500L), new GenericStack(input, 2_000L)},
                new GenericStack[]{new GenericStack(output, 750L)});
        var decoded = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        require(decoded != null, "fluid pattern failed to decode");
        long normalizedInput = 0L;
        for (var ingredient : decoded.getInputs()) {
            var choices = ingredient.getPossibleInputs();
            require(choices.length == 1 && input.equals(choices[0].what()),
                    "fluid key or tag changed during pattern normalization");
            normalizedInput = Math.addExact(normalizedInput,
                    Math.multiplyExact(choices[0].amount(), ingredient.getMultiplier()));
        }
        require(normalizedInput == 2_500L, "fluid Pattern input changed mB amount");
        var outputs = decoded.getOutputs();
        require(outputs.length == 1 && output.equals(outputs[0].what()) && outputs[0].amount() == 750L,
                "fluid Pattern output changed key or mB amount");
        helper.succeed();
    }

    static Phase21PerformanceProbe.PreparedInvocation preparePhase21BulkInvocation(
            GameTestHelper helper, long multiplier) throws Exception {
        var fixture = new Fixture(helper);
        fixture.addSmeltingProvider();
        fixture.storage.items.set(fixture.raw, multiplier);
        var plan = fixture.calculate(fixture.ingot, multiplier);
        fixture.submit(plan, fixture.requester);
        var job = fixture.job();
        var task = job.bulk$tasks().iterator().next();
        int[] consumed = { -1 };
        return new Phase21PerformanceProbe.PreparedInvocation(
                "applied_causality_bulk_ae2",
                multiplier,
                plan.bytes(),
                () -> consumed[0] = fixture.execute(),
                () -> require(
                        consumed[0] == 1
                                && task.remaining() == 0
                                && fixture.cpu.bulk$getJob() == null
                                && fixture.requester.accepted == multiplier,
                        "Phase 21 Standard AE2 benchmark dispatch failed"));
    }

    @GameTest(template = "empty")
    public static void tagBackedCharcoalPatternVerifiesAndAdvertises(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        var provider = f.addSmeltingProvider(
                BlockPos.ZERO,
                AEItemKey.of(Items.OAK_LOG),
                AEItemKey.of(Items.CHARCOAL));

        require(provider.getAvailablePatterns().size() == 1,
                "tag-backed charcoal pattern did not verify/advertise");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stockAdmittedMultipliersUseOneOperation(GameTestHelper helper) throws Exception {
        Integer expectedNotifications = null;
        for (long n : new long[] { 1, 2, 1_000, LARGEST_256K_N }) {
            var f = new Fixture(helper);
            var provider = f.addSmeltingProvider();
            f.storage.items.set(f.raw, n);
            var plan = f.calculate(f.ingot, n);
            require(!plan.simulation() && plan.bytes() == 3 * n + 16, "unexpected real stock planning bytes");
            f.submit(plan, f.requester);
            var job = f.job();
            var task = job.bulk$tasks().iterator().next();
            require(task.remaining() == n, "admission changed task multiplier");
            var changes = new ArrayList<AEKey>();
            f.logic.addListener(changes::add);
            f.logic.tickCraftingLogic(f.energy, f.service);
            require(task.remaining() == 0, "one dispatch did not decrease task by N=" + n);
            require(((int[]) field(CraftingCpuLogic.class, "usedOps").get(f.logic))[0] == 1,
                    "scheduler charged more than one operation");
            require(f.cpu.bulk$getJob() == null && f.requester.accepted == n, "output/job did not complete");
            require(f.logic.getInventory().list.get(f.raw) == 0 && f.storage.items.get(f.raw) == 0,
                    "Bulk dispatch did not consume exactly N inputs");
            require(f.logic.getInventory().list.get(f.ingot) == 0 && f.storage.items.get(f.ingot) == 0,
                    "requester-delivered output retained duplicate inventory ownership");
            require(job.bulk$getWaitingFor(f.ingot) == 0 && job.bulk$outputEscrow().isEmpty(), "ownership duplicated");
            require(f.energyCalls == 0, "Bulk NONE policy called stock pattern energy");
            require(f.requester.simulations == 1 && f.requester.deliveries == 1, "delivery work scaled with N");
            if (expectedNotifications == null) {
                expectedNotifications = changes.size();
            }
            require(changes.size() == expectedNotifications, "CPU mutation/listener work scaled with N");
            require(provider.getInternalInventory().getStackInSlot(0).getCount() == 1, "pattern was consumed");
        }
        var tooLarge = new Fixture(helper);
        tooLarge.addSmeltingProvider();
        tooLarge.storage.items.set(tooLarge.raw, LARGEST_256K_N + 1);
        var rejectedPlan = tooLarge.calculate(tooLarge.ingot, LARGEST_256K_N + 1);
        require(rejectedPlan.bytes() > STOCK_BYTES, "representative maximum boundary was not measured");
        var rejected = tooLarge.service.submitJob(rejectedPlan, tooLarge.requester,
                tooLarge.cluster, false, IActionSource.empty());
        require(!rejected.successful() && "CPU_TOO_SMALL".equals(rejected.errorCode().name()), "oversized plan admitted");
        require(tooLarge.storage.items.get(tooLarge.raw) == LARGEST_256K_N + 1, "rejected admission consumed input");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialAcceptancePersistsRetriesAndCancels(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        f.addSmeltingProvider();
        f.storage.items.set(f.raw, 1_000);
        f.requester.limit = 2;
        f.submit(f.calculate(f.ingot, 1_000), f.requester);
        require(f.execute() == 1, "Bulk dispatch did not execute");
        var job = f.job();
        require(!job.bulk$tasks().iterator().hasNext(), "completed task was not removed");
        require(job.bulk$outputEscrow().get(f.ingot) == 998 && job.bulk$getRemainingFinalAmount() == 998,
                "partial acceptance lost output");
        require(job.bulk$getWaitingFor(f.ingot) == 0, "materialized output still waiting");
        var saved = new CompoundTag();
        f.logic.writeToNBT(saved);
        var completed = saved.getCompound("job").copy();
        var restored = new Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(f.ingot) == 998, "production escrow did not survive load");
        f.requester.limit = 0;
        f.logic.tickCraftingLogic(f.energy, f.service);
        require(f.cpu.bulk$getJob() == job && job.bulk$outputEscrow().get(f.ingot) == 998, "zero acceptance completed job");
        var retried = new CompoundTag();
        f.logic.writeToNBT(retried);
        var completedWork = completed.getCompound("timeTracker").getCompound("completedWork");
        require(completedWork.getLong(f.ingot.getType().getId().toString()) == 1_000,
                "materialization did not advance progress exactly once");
        require(completedWork.equals(retried.getCompound("job").getCompound("timeTracker").getCompound("completedWork")),
                "retry changed progress accounting");
        f.requester.limit = Long.MAX_VALUE;
        f.logic.tickCraftingLogic(f.energy, f.service);
        require(f.cpu.bulk$getJob() == null && f.requester.accepted == 1_000, "retry did not deliver exact remainder");
        restored.storage.acceptInsert = false;
        var restoredJob = restored.job();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null && restored.logic.getInventory().list.get(f.ingot) == 998,
                "cancel did not transfer persistent escrow to CPU inventory");
        require(restoredJob.bulk$outputEscrow().isEmpty(), "cancel retained duplicate escrow ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void modulateCancellationSettlesBeforeEscrowTransfer(GameTestHelper helper) throws Exception {
        for (boolean retry : new boolean[] { false, true }) {
            for (long accepted : new long[] { 0, 2, 10 }) {
                var f = new Fixture(helper);
                f.addSmeltingProvider();
                f.storage.items.set(f.raw, 10);
                f.storage.acceptInsert = false;
                f.requester.limit = 0;
                f.submit(f.calculate(f.ingot, 10), f.requester);
                var job = f.job();
                if (retry) {
                    require(f.execute() == 1 && job.bulk$outputEscrow().get(f.ingot) == 10,
                            "retry fixture did not retain output");
                }
                f.requester.limit = accepted;
                f.requester.onModulate = () -> {
                    require(f.cpu.bulk$deliverFinalFromEscrow(f.ingot, 10) == 0, "nested delivery reoffered escrow");
                    require(f.execute() == 0, "nested execution dispatched during delivery");
                    f.logic.tickCraftingLogic(f.energy, f.service);
                    require(!f.cpu.bulk$tryFinishJob(), "nested completion deleted a settling job");
                    require(f.logic.insert(f.ingot, 10, Actionable.MODULATE) == 0,
                            "nested stock insertion accepted settling output");
                    f.logic.cancel();
                    f.logic.cancel(); // Repeated cancellation must be idempotent.
                };
                f.requester.onStateChange = f.logic::cancel;
                if (retry) {
                    f.logic.tickCraftingLogic(f.energy, f.service);
                } else {
                    require(f.execute() == 1, "canceling dispatch must still count its committed operation");
                }
                require(f.requester.accepted == accepted, "requester acceptance changed");
                require(f.cpu.bulk$getJob() == null && job.bulk$isCanceled(), "deferred cancellation did not finish");
                require(job.bulk$outputEscrow().isEmpty(), "canceled job retained duplicate escrow");
                require(job.bulk$getRemainingFinalAmount() == 10 - accepted, "remaining ignored exact acceptance");
                require(f.logic.getInventory().list.get(f.ingot) == 10 - accepted,
                        "accepted output was duplicated into cancellation inventory");
                require(f.logic.getInventory().list.get(f.raw) == 0 && f.storage.items.get(f.raw) == 0,
                        "cancel restored consumed input");
                f.logic.tickCraftingLogic(f.energy, f.service);
                require(f.requester.accepted + f.logic.getInventory().list.get(f.ingot) == 10,
                        "post-cancel tick changed total output ownership");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restoredJobReconnectsAndDeliversRemainderExactlyOnce(GameTestHelper helper) throws Exception {
        var original = new Fixture(helper);
        original.addSmeltingProvider();
        original.storage.items.set(original.raw, 10);
        original.requester.limit = 2;
        original.submit(original.calculate(original.ingot, 10), original.requester);
        require(original.execute() == 1, "initial partial dispatch failed");
        var saved = new CompoundTag();
        original.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        original.requesterLink.writeToNBT(savedRequesterLink);

        var restored = new Fixture(helper);
        restored.logic.readFromNBT(saved);
        var job = restored.job();
        var tracker = restored.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        require(job.bulk$outputEscrow().get(restored.ingot) == 8, "restored remainder missing");
        // Disconnected requester must retain ownership until links reconnect.
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        require(job.bulk$outputEscrow().get(restored.ingot) == 8, "disconnected retry lost output");
        var requesterLink = new CraftingLink(savedRequesterLink, restored.requester);
        var cpuLink = (CraftingLink) restored.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()), "job identity changed on restore");
        restored.service.addLink(cpuLink);
        restored.service.addLink(requesterLink);
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        require(restored.requester.accepted == 8 && restored.requester.deliveries == 1,
                "restored job did not deliver its remainder exactly once");
        require(restored.cpu.bulk$getJob() == null && requesterLink.isDone(), "restored job did not complete");
        require(job.bulk$outputEscrow().isEmpty() && job.bulk$getRemainingFinalAmount() == 0,
                "restored delivery left duplicate ownership");
        require(completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "restored retry repeated progress accounting");
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        require(restored.requester.deliveries == 1 && original.requester.accepted == 2,
                "finished job redelivered or original fixture was retried");
        require(original.requester.accepted + restored.requester.accepted == 10,
                "restart duplicated or lost output");
        require(restored.logic.getInventory().list.get(restored.ingot) == 0
                        && restored.storage.items.get(restored.ingot) == 0,
                "restored delivered output also remained in inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void normalProviderAndMixedChainKeepStockPath(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        f.addSmeltingProvider();
        var finalKey = AEItemKey.of(Items.GOLD_INGOT);
        var normalPattern = f.pattern(f.ingot, finalKey);
        var normal = new NormalProvider(normalPattern);
        f.register(normal);
        f.storage.items.set(f.raw, 2);
        f.submit(f.calculate(finalKey, 2), f.requester);
        require(f.execute() == 1 && normal.pushes == 0, "Bulk intermediate step did not run first");
        require(f.logic.getInventory().list.get(f.ingot) == 2, "Bulk intermediate output missing");
        require(f.execute() == 1 && normal.pushes == 1, "normal task did not execute original pushPattern");
        require(f.job().bulk$getWaitingFor(finalKey) == 1 && f.energyCalls == 2, "stock waiting/energy path changed");
        require(f.logic.insert(finalKey, 1, Actionable.MODULATE) == 1, "normal output not accepted");
        require(f.execute() == 1 && normal.pushes == 2, "normal second operation failed");
        f.logic.insert(finalKey, 1, Actionable.MODULATE);
        require(f.requester.accepted == 2 && f.cpu.bulk$getJob() == null, "mixed chain did not complete");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void zeroBulkPreservesProviderRotationAndInputs(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        var singular = f.addSmeltingProvider();
        var pattern = singular.getAvailablePatterns().get(0);
        var first = new NormalProvider(pattern);
        var second = new NormalProvider(pattern);
        f.register(first);
        f.register(second);
        // Leave the shared cycle deliberately pointing at the first normal provider.
        require(f.service.getProviders(pattern).iterator().next() == singular, "unexpected registration order");
        setActiveNode(singular, singular.getMainNode(), helper, f.grid, false);
        f.storage.items.set(f.raw, 2);
        f.submit(f.calculate(f.ingot, 2), f.requester);
        require(f.execute() == 1 && first.pushes == 1 && second.pushes == 0,
                "zero Bulk attempt changed stock provider rotation");
        require(f.job().bulk$getWaitingFor(f.ingot) == 1 && f.logic.getInventory().list.get(f.raw) == 1,
                "stock path did not retain its one-operation accounting");
        // Remove the only remaining input: neither path can dispatch or mutate job state.
        f.logic.getInventory().extract(f.raw, 1, Actionable.MODULATE);
        setActiveNode(singular, singular.getMainNode(), helper, f.grid, true);
        var before = new CompoundTag();
        f.logic.writeToNBT(before);
        require(f.execute() == 0, "missing input dispatched");
        var after = new CompoundTag();
        f.logic.writeToNBT(after);
        require(before.equals(after), "unsuccessful execution mutated persisted CPU state");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void providerRemovalDuringPreflightFailsClosed(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        var singular = f.addSmeltingProvider();
        var node = f.providerNodes.get(0);
        f.storage.items.set(f.raw, 2);
        f.submit(f.calculate(f.ingot, 2), f.requester);
        f.requester.onSimulate = () -> f.service.removeNode(node);
        require(f.execute() == 0, "removed provider still authorized Bulk commit");
        require(f.logic.getInventory().list.get(f.raw) == 2 && f.job().bulk$outputEscrow().isEmpty(),
                "preflight rejection consumed or materialized output");
        require(f.job().bulk$tasks().iterator().next().remaining() == 2, "preflight rejection decremented task");
        require(singular.getVerifiedProcessModel(singular.getAvailablePatterns().get(0)).isPresent(),
                "test did not isolate registration from cached model identity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void modulateTeardownDropsOnlyUnacceptedOutput(GameTestHelper helper) throws Exception {
        for (boolean retry : new boolean[] { false, true }) {
            for (long accepted : new long[] { 0, 2, 10 }) {
                for (int teardown = 0; teardown < 8; teardown++) {
                    var f = new Fixture(helper);
                    f.addSmeltingProvider();
                    f.storage.items.set(f.raw, 10);
                    f.storage.acceptInsert = false;
                    f.requester.limit = 0;
                    f.submit(f.calculate(f.ingot, 10), f.requester);
                    var job = f.job();
                    if (retry) {
                        require(f.execute() == 1, "retry setup did not dispatch");
                    }
                    // Attach the controlled core to the real cluster lifecycle.
                    field(CraftingBlockEntity.class, "cluster").set(f.core, f.cluster);
                    long before = f.droppedIngots();
                    Runnable destroy = switch (teardown % 4) {
                        case 0 -> f.core::breakCluster;
                        case 1 -> f.cluster::destroy;
                        case 2 -> () -> f.core.updateStatus(null);
                        default -> () -> f.core.disconnect(false);
                    };
                    boolean disconnect = teardown >= 4;
                    f.requester.limit = accepted;
                    f.requester.onModulate = () -> {
                        destroy.run();
                        destroy.run();
                        f.core.disconnect(false);
                        if (disconnect) {
                            f.disconnectNode();
                        }
                    };
                    f.requester.onStateChange = destroy;
                    if (retry) {
                        f.logic.tickCraftingLogic(f.energy, f.service);
                    } else {
                        require(f.execute() == 1, "teardown changed committed scheduler operation");
                    }
                    require(f.droppedIngots() - before == 10 - accepted,
                            "teardown stranded or duplicated remainder: retry=" + retry
                                    + ", accepted=" + accepted + ", entry=" + teardown);
                    require(f.cpu.bulk$getJob() == null && job.bulk$outputEscrow().isEmpty(), "job retained output");
                    require(f.core.getCluster() == null && f.logic.getInventory().list.get(f.ingot) == 0,
                            "destroyed CPU retained output");
                    require(f.requester.accepted == accepted && f.storage.items.get(f.ingot) == 0,
                            "delivered/dropped output also entered storage");
                    f.core.breakCluster();
                    f.cluster.destroy();
                    f.logic.tickCraftingLogic(f.energy, f.service);
                    require(f.droppedIngots() - before + f.requester.accepted == 10, "teardown repeated ownership");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationNotificationTeardownWaitsForOuterLifecycle(GameTestHelper helper) throws Exception {
        for (boolean outerBreak : new boolean[] { false, true }) {
            var f = new Fixture(helper);
            f.addSmeltingProvider();
            f.storage.items.set(f.raw, 10);
            f.storage.acceptInsert = false;
            f.requester.limit = 2;
            f.submit(f.calculate(f.ingot, 10), f.requester);
            require(f.execute() == 1, "partial setup did not dispatch");
            field(CraftingBlockEntity.class, "cluster").set(f.core, f.cluster);
            var before = f.droppedIngots();
            f.requester.onStateChange = () -> {
                f.core.breakCluster();
                f.core.updateStatus(null);
                f.core.disconnect(false);
            };
            if (outerBreak) {
                f.core.breakCluster();
            } else {
                f.logic.cancel();
            }
            require(f.droppedIngots() - before == 8 && f.requester.accepted == 2,
                    "cancellation callback lost/duplicated output");
            require(f.core.getCluster() == null && f.cpu.bulk$getJob() == null
                    && f.logic.getInventory().list.get(f.ingot) == 0, "outer lifecycle retained output");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void teardownKeepsLatestClusterReplacement(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        f.addSmeltingProvider();
        f.storage.items.set(f.raw, 10);
        f.storage.acceptInsert = false;
        f.submit(f.calculate(f.ingot, 10), f.requester);
        field(CraftingBlockEntity.class, "cluster").set(f.core, f.cluster);
        var replacement = new CraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
        long before = f.droppedIngots();
        f.requester.limit = 2;
        f.requester.onModulate = () -> {
            f.core.updateStatus(null);
            f.core.breakCluster();
            f.core.updateStatus(replacement);
        };
        require(f.execute() == 1, "replacement dispatch failed");
        require(f.core.getCluster() == replacement, "latest cluster reference was lost or destroyed");
        require(f.droppedIngots() - before == 8 && f.logic.getInventory().list.get(f.ingot) == 0,
                "cluster replacement did not drop old output once");
        require(f.requester.accepted == 2 && f.cpu.bulk$getJob() == null, "replacement changed acceptance");
        helper.succeed();
    }

    static final class Fixture {
        final GameTestHelper helper;
        final AEItemKey raw = AEItemKey.of(Items.RAW_IRON);
        final AEItemKey ingot = AEItemKey.of(Items.IRON_INGOT);
        final TestStorage storage = new TestStorage();
        MEStorage networkStorage = storage;
        final Requester requester = new Requester();
        final BulkDispatchBudgetService dispatchBudget = new BulkDispatchBudgetService();
        final List<IGridNode> providerNodes = new ArrayList<>();
        final IGrid grid;
        final IEnergyService energy;
        final CraftingService service;
        final CraftingCPUCluster cluster;
        final CraftingBlockEntity core;
        final CraftingCpuLogic logic;
        final IBulkCpuBridge cpu;
        ICraftingLink requesterLink;
        int energyCalls;

        Fixture(GameTestHelper helper) throws Exception {
            this(helper, null);
        }

        Fixture(GameTestHelper helper, CompoundTag savedCoreState) throws Exception {
            this.helper = helper;
            var storageService = proxy(IStorageService.class, (method, args) -> switch (method) {
                case "getInventory" -> networkStorage;
                case "getCachedInventory" -> storage.items;
                case "addGlobalStorageProvider" -> null;
                default -> throw new AssertionError("unexpected storage call " + method);
            });
            energy = proxy(IEnergyService.class, (method, args) -> {
                if (method.equals("extractAEPower")) {
                    energyCalls++;
                    return args[0];
                }
                throw new AssertionError("unexpected energy call " + method);
            });
            grid = proxy(IGrid.class, (method, args) -> switch (method) {
                case "getService" -> args[0] == IBulkDispatchBudget.class ? dispatchBudget : null;
                case "getStorageService" -> storageService;
                case "getCraftingService" -> craftingService();
                case "postEvent" -> null;
                default -> throw new AssertionError("unexpected grid call " + method);
            });
            service = new CraftingService(grid, storageService, energy);
            cluster = new CraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
            var corePosition = new BlockPos(0, 2, 0);
            helper.setBlock(corePosition, AEBlocks.CRAFTING_STORAGE_256K.block());
            core = new CraftingBlockEntity(AEBlockEntities.CRAFTING_STORAGE, helper.absolutePos(corePosition),
                    AEBlocks.CRAFTING_STORAGE_256K.block().defaultBlockState());
            core.setLevel(helper.getLevel());
            if (savedCoreState != null) {
                // Real chunk load order: the core BE receives NBT before the
                // crafting multiblock is formed, so it retains previousState.
                core.loadTag(savedCoreState);
            }
            // Mirror CraftingCPUCalculator.updateBlockEntities: attach the BE,
            // add it to the cluster, then let done() restore previousState.
            core.updateStatus(cluster);
            var add = CraftingCPUCluster.class.getDeclaredMethod("addBlockEntity", CraftingBlockEntity.class);
            add.setAccessible(true);
            add.invoke(cluster, core);
            setActiveNode(core, core.getMainNode(), helper, grid, true);
            var done = CraftingCPUCluster.class.getDeclaredMethod("done");
            done.setAccessible(true);
            done.invoke(cluster);
            require(cluster.getAvailableStorage() == STOCK_BYTES, "fixture did not use stock 256 KiB storage");
            logic = cluster.craftingLogic;
            cpu = (IBulkCpuBridge) (Object) logic;
        }

        private CraftingService craftingService() {
            return service;
        }

        SingularPatternProviderBlockEntity addSmeltingProvider() throws Exception {
            return addSmeltingProvider(BlockPos.ZERO);
        }

        SingularPatternProviderBlockEntity addSmeltingProvider(BlockPos position) throws Exception {
            return addSmeltingProvider(position, raw, ingot);
        }

        SingularPatternProviderBlockEntity addSmeltingProvider(
                BlockPos position,
                AEKey input,
                AEKey output) throws Exception {
            return addProcessingProvider(position,
                    new GenericStack[] { new GenericStack(input, 1) },
                    new GenericStack[] { new GenericStack(output, 1) });
        }

        SingularPatternProviderBlockEntity addProcessingProvider(
                BlockPos position,
                GenericStack[] inputs,
                GenericStack[] outputs) throws Exception {
            var provider = new SingularPatternProviderBlockEntity(position,
                    AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
            provider.setLevel(helper.getLevel());
            setActiveNode(provider, provider.getMainNode(), helper, grid, true);
            provider.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                    inputs, outputs));
            require(provider.getAvailablePatterns().size() == 1, "real processing provider did not verify/advertise");
            register(provider);
            return provider;
        }

        IPatternDetails pattern(AEKey input, AEKey output) {
            return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[] { new GenericStack(input, 1) },
                    new GenericStack[] { new GenericStack(output, 1) }), helper.getLevel());
        }

        void register(ICraftingProvider provider) {
            var node = proxy(IGridNode.class, (method, args) -> switch (method) {
                case "getService" -> args[0] == ICraftingProvider.class ? provider : null;
                case "getOwner" -> provider;
                default -> throw new AssertionError("unexpected provider node call " + method);
            });
            providerNodes.add(node);
            service.addNode(node, null);
        }

        ICraftingPlan calculate(AEKey output, long amount) throws Exception {
            var simulationRequester = new ICraftingSimulationRequester() {
                @Override
                public IActionSource getActionSource() {
                    return IActionSource.empty();
                }

                @Override
                public IGridNode getGridNode() {
                    return cluster.getNode();
                }
            };
            var calculation = new CraftingCalculation(helper.getLevel(), grid, simulationRequester,
                    new GenericStack(output, amount), CalculationStrategy.REPORT_MISSING_ITEMS);
            var result = new FutureTask<ICraftingPlan>(calculation::run);
            var worker = new Thread(result, "phase11-crafting-plan");
            worker.setDaemon(true);
            worker.start();
            try {
                // AE2 planning waits for time slices from the server thread.
                // This work precedes, and is excluded from, execution measurements.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (calculation.simulateFor(1_000)) {
                    require(System.nanoTime() < deadline, "stock planning exceeded fixture time budget");
                }
                return result.get();
            } finally {
                worker.interrupt();
            }
        }

        void submit(ICraftingPlan plan, ICraftingRequester requester) {
            var result = service.submitJob(plan, requester, cluster, false, IActionSource.empty());
            require(result.successful(), "stock submission failed: " + result.errorCode());
            requesterLink = result.link();
        }

        IBulkJobBridge job() {
            var job = cpu.bulk$getJob();
            require(job != null, "expected an active job");
            return job;
        }

        int execute() {
            return logic.executeCrafting(1, service, energy, helper.getLevel());
        }

        long droppedIngots() {
            return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(core.getBlockPos()).inflate(2))
                    .stream().filter(entity -> entity.getItem().is(Items.IRON_INGOT))
                    .mapToLong(entity -> entity.getItem().getCount()).sum();
        }

        void disconnectNode() {
            try {
                var managed = core.getMainNode();
                field(managed.getClass(), "node").set(managed, null);
            } catch (Exception exception) {
                throw new AssertionError("could not disconnect controlled core", exception);
            }
        }

    }

    static void setActiveNode(BlockEntity owner, Object managed, GameTestHelper helper,
            IGrid grid, boolean active) throws Exception {
        var node = new GridNode(helper.getLevel(), owner, (BlockEntity o, IGridNode n) -> { }, Set.of()) {
            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public IGrid getGrid() {
                return grid;
            }
        };
        field(managed.getClass(), "node").set(managed, node);
    }

    private static final class NormalProvider implements ICraftingProvider {
        final IPatternDetails pattern;
        int pushes;

        NormalProvider(IPatternDetails pattern) {
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
            require(details.equals(pattern) && inputs.length == 1, "normal input contract changed");
            pushes++;
            return true;
        }
    }

    static final class Requester implements ICraftingRequester {
        long limit = Long.MAX_VALUE;
        long accepted;
        int simulations;
        int deliveries;
        Runnable onSimulate = () -> { };
        Runnable onModulate = () -> { };
        Runnable onStateChange = () -> { };

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.SIMULATE) {
                simulations++;
                onSimulate.run();
                return amount; // Deliberately optimistic: commit truth must use MODULATE.
            }
            deliveries++;
            long inserted = Math.min(amount, limit);
            accepted += inserted;
            onModulate.run();
            return inserted;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.of();
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
            onStateChange.run();
        }

        @Override
        public IGridNode getActionableNode() {
            return null;
        }
    }

    static final class TestStorage implements MEStorage {
        final KeyCounter items = new KeyCounter();
        boolean acceptInsert = true;

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long extracted = Math.min(amount, items.get(key));
            if (mode == Actionable.MODULATE) {
                items.remove(key, extracted);
            }
            return extracted;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (!acceptInsert) {
                return 0;
            }
            if (mode == Actionable.MODULATE) {
                items.add(key, amount);
            }
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("Phase 11 controlled network storage");
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] arguments);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Phase11 " + type.getSimpleName();
                default -> invocation.call(method.getName(), args);
            };
        }));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

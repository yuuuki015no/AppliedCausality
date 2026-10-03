package dev.appliedcausality.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.CraftingMonitorBlockEntity;
import appeng.core.definitions.AEBlockEntities;
import appeng.core.definitions.AEBlocks;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.GridNode;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.cpu.IBulkCpuBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase8Ae2BridgeGameTests {
    private Phase8Ae2BridgeGameTests() {
    }

    @GameTest(template = "empty")
    public static void fluidEscrowPartialAcceptanceRestartAndCancel(GameTestHelper helper) throws Exception {
        var fluid = AEFluidKey.of(Fluids.WATER);
        var requester = new TestRequester(123L);
        var fixture = newFixture(1_000L, 1L, 1_000L, false, requester, fluid);
        fixture.jobBridge().bulk$addWaitingFor(fluid, 1_000L);
        fixture.jobBridge().bulk$materializeWaitingToEscrow(fluid, 1_000L);
        require(completedWork(fixture.job(), fluid) == 1_000L,
                "fluid materialization did not complete exactly 1,000 mB");
        require(fixture.cpuBridge().bulk$deliverFinalFromEscrow(fluid, 1_000L) == 123L,
                "fluid requester did not accept exactly 123 mB");
        require(fixture.jobBridge().bulk$outputEscrow().get(fluid) == 877L
                        && fixture.jobBridge().bulk$getRemainingFinalAmount() == 877L,
                "partial fluid acceptance changed ownership");
        require(!fixture.cpuBridge().bulk$tryFinishJob(), "fluid escrow allowed early completion");

        var saved = new CompoundTag();
        fixture.logic().writeToNBT(saved);
        var restoredCluster = newCluster();
        restoredCluster.craftingLogic.readFromNBT(saved);
        var restoredCpu = (IBulkCpuBridge) (Object) restoredCluster.craftingLogic;
        var restoredJob = restoredCpu.bulk$getJob();
        require(restoredJob != null && restoredJob.bulk$outputEscrow().get(fluid) == 877L,
                "restarted AE2 job lost fluid escrow");
        require(restoredJob.bulk$getRemainingFinalAmount() == 877L
                        && restoredJob.bulk$getWaitingFor(fluid) == 0L,
                "restart repeated fluid materialization");
        restoredCluster.craftingLogic.cancel();
        require(restoredCpu.bulk$getJob() == null, "fluid cancel retained the job");
        require(restoredCluster.craftingLogic.getInventory()
                .extract(fluid, Long.MAX_VALUE, Actionable.SIMULATE) == 877L,
                "cancel did not retain the remaining fluid in CPU inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void taskWaitingAndProgressUseAppliedMixin(GameTestHelper helper) throws Exception {
        var fixture = newFixture(8L, 2L, 4L, true, null);
        var tasks = fixture.jobBridge().bulk$tasks().iterator();
        require(tasks.hasNext(), "expected one real AE2 task");
        var task = tasks.next();
        require(task.remaining() == 2L, "initial task amount was not exposed");
        task.decrement(1L);
        require(task.remaining() == 1L, "task decrement did not reach TaskProgress");

        fixture.jobBridge().bulk$addWaitingFor(fixture.key(), 8L);
        fixture.jobBridge().bulk$materializeWaitingToEscrow(fixture.key(), 8L);
        require(fixture.jobBridge().bulk$getWaitingFor(fixture.key()) == 0L,
                "waiting output was not consumed");
        require(fixture.jobBridge().bulk$outputEscrow().get(fixture.key()) == 8L,
                "materialized output was not escrowed");
        require(completedWork(fixture.job(), fixture.key()) == 8L,
                "progress completed was not consumed exactly once");

        expectFailure(() -> fixture.jobBridge().bulk$materializeWaitingToEscrow(fixture.key(), 1L));
        require(fixture.jobBridge().bulk$outputEscrow().get(fixture.key()) == 8L,
                "failed rematerialization changed escrow");
        require(completedWork(fixture.job(), fixture.key()) == 8L,
                "failed rematerialization changed progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void requesterPartialAcceptanceAndSurplusRemainOwned(GameTestHelper helper) throws Exception {
        var requester = new TestRequester(2L);
        var fixture = newFixture(4L, 1L, 4L, false, requester);
        var changes = new ArrayList<AEKey>();
        fixture.logic().addListener(changes::add);

        require(fixture.cpuBridge().bulk$simulateFinalDelivery(fixture.key(), 4L) == 2L,
                "planning simulation did not work with empty escrow");
        require(fixture.jobBridge().bulk$outputEscrow().isEmpty(),
                "planning simulation mutated escrow");

        fixture.jobBridge().bulk$addWaitingFor(fixture.key(), 4L);
        fixture.jobBridge().bulk$materializeWaitingToEscrow(fixture.key(), 4L);
        changes.clear();

        var visibleKeys = new KeyCounter();
        fixture.logic().getAllItems(visibleKeys);
        require(visibleKeys.get(fixture.key()) > 0L, "escrow-only monitor row was not enumerated");
        require(fixture.logic().getStored(fixture.key()) == 4L,
                "monitor stored count did not expose escrow");

        require(fixture.cpuBridge().bulk$deliverFinalFromEscrow(fixture.key(), 4L) == 2L,
                "actual accepted amount was not returned");
        require(fixture.jobBridge().bulk$getRemainingFinalAmount() == 2L,
                "remaining final amount did not follow actual acceptance");
        require(fixture.jobBridge().bulk$outputEscrow().get(fixture.key()) == 2L,
                "partial acceptance did not preserve escrow remainder");
        require(fixture.logic().getStored(fixture.key()) == 2L,
                "monitor stored count did not include escrow");
        require(changes.contains(fixture.key()), "requester delivery did not post a monitor change");
        require(!fixture.cpuBridge().bulk$tryFinishJob(), "non-empty escrow allowed completion");

        requester.setAcceptanceLimit(0L);
        require(fixture.cpuBridge().bulk$deliverFinalFromEscrow(fixture.key(), 2L) == 0L,
                "zero acceptance was not preserved");
        require(fixture.jobBridge().bulk$getRemainingFinalAmount() == 2L,
                "zero acceptance changed remaining final amount");

        requester.setAcceptanceLimit(2L);
        require(fixture.cpuBridge().bulk$deliverFinalFromEscrow(fixture.key(), 2L) == 2L,
                "final requester acceptance failed");
        require(fixture.jobBridge().bulk$outputEscrow().isEmpty(),
                "accepted final output remained in escrow");
        require(fixture.cpuBridge().bulk$tryFinishJob(), "empty completed job did not finish");
        require(fixture.cpuBridge().bulk$getJob() == null, "finished job was not deleted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void roundedRecipeSurplusMovesToCpuInventory(GameTestHelper helper) throws Exception {
        var requester = new TestRequester(1L);
        var fixture = newFixture(1L, 1L, 4L, false, requester);

        require(fixture.cpuBridge().bulk$simulateFinalDelivery(fixture.key(), 4L) == 1L,
                "rounded output simulation failed before materialization");
        fixture.jobBridge().bulk$addWaitingFor(fixture.key(), 4L);
        fixture.jobBridge().bulk$materializeWaitingToEscrow(fixture.key(), 4L);

        require(fixture.cpuBridge().bulk$deliverFinalFromEscrow(fixture.key(), 4L) == 1L,
                "request amount was not delivered");
        require(fixture.jobBridge().bulk$getRemainingFinalAmount() == 0L,
                "request did not complete");
        require(fixture.jobBridge().bulk$outputEscrow().isEmpty(),
                "rounded surplus remained in escrow");
        require(fixture.logic().getInventory().extract(fixture.key(), Long.MAX_VALUE, Actionable.SIMULATE) == 3L,
                "rounded surplus was not retained by CPU inventory");
        require(fixture.cpuBridge().bulk$tryFinishJob(), "completed rounded job did not finish");
        require(fixture.logic().getInventory().extract(fixture.key(), Long.MAX_VALUE, Actionable.SIMULATE) == 3L,
                "job completion lost rounded surplus");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualJobPersistenceAndCancelOrdering(GameTestHelper helper) throws Exception {
        var fixture = newFixture(5L, 1L, 5L, true, null);
        fixture.jobBridge().bulk$outputEscrow().add(fixture.key(), 5L);

        var saved = new CompoundTag();
        fixture.logic().writeToNBT(saved);
        var restoredCluster = newCluster();
        restoredCluster.craftingLogic.readFromNBT(saved);
        var restoredCpu = (IBulkCpuBridge) (Object) restoredCluster.craftingLogic;
        var restoredJob = restoredCpu.bulk$getJob();
        require(restoredJob != null, "actual AE2 job was not restored");
        require(restoredJob.bulk$outputEscrow().get(fixture.key()) == 5L,
                "per-job escrow was not restored");

        var ownershipDuringTransfer = new ArrayList<Boolean>();
        restoredCluster.craftingLogic.addListener(key -> {
            if (fixture.key().equals(key)) {
                var activeJob = restoredCpu.bulk$getJob();
                ownershipDuringTransfer.add(activeJob != null
                        && activeJob.bulk$outputEscrow().get(key) == 0L);
            }
        });
        restoredCluster.craftingLogic.cancel();

        require(!ownershipDuringTransfer.isEmpty(), "cancel transfer did not notify listeners");
        require(ownershipDuringTransfer.stream().allMatch(Boolean::booleanValue),
                "cancel deleted the job before escrow ownership transfer");
        require(restoredCpu.bulk$getJob() == null, "cancel did not delete the job");
        require(restoredCluster.craftingLogic.getInventory()
                .extract(fixture.key(), Long.MAX_VALUE, Actionable.SIMULATE) == 5L,
                "cancel did not retain escrow in CPU inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void intermediateOnlyMonitorRestoresFromJobSidecar(GameTestHelper helper) throws Exception {
        var fixture = newFixture(1L, 1L, 1L, true, null);
        var intermediateKey = AEItemKey.of(Items.GOLD_INGOT);
        fixture.jobBridge().bulk$outputEscrow().add(intermediateKey, 3L);
        fixture.jobBridge().bulk$addWaitingFor(fixture.key(), 1L);
        fixture.logic().insert(fixture.key(), 1L, Actionable.MODULATE);

        require(fixture.jobBridge().bulk$getRemainingFinalAmount() == 0L,
                "fixture did not reach intermediate-only completion delay");
        var saved = new CompoundTag();
        fixture.logic().writeToNBT(saved);

        var restoredCluster = newCluster();
        restoredCluster.craftingLogic.readFromNBT(saved);
        var restoredCpu = (IBulkCpuBridge) (Object) restoredCluster.craftingLogic;
        var restoredJob = restoredCpu.bulk$getJob();
        var restoredMonitor = monitor(restoredCluster);

        require(restoredJob != null, "delayed job was not restored");
        require(restoredJob.bulk$getRemainingFinalAmount() == 0L,
                "restored delayed job changed final remaining");
        require(restoredJob.bulk$outputEscrow().get(intermediateKey) == 3L,
                "restored delayed job lost intermediate escrow");
        require(restoredMonitor.getJobProgress() != null,
                "sidecar restore left the delayed monitor output empty");
        require(restoredMonitor.getJobProgress().amount() == 1L,
                "sidecar restore did not reapply the monitor presence marker");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stockCompletionGuardRetriesIntermediateEscrowOnTick(GameTestHelper helper) throws Exception {
        var fixture = newFixture(1L, 1L, 1L, true, null);
        var intermediateKey = AEItemKey.of(Items.GOLD_INGOT);
        fixture.jobBridge().bulk$outputEscrow().add(intermediateKey, 3L);
        fixture.jobBridge().bulk$addWaitingFor(fixture.key(), 1L);

        require(completedWork(fixture.job(), fixture.key()) == 0L,
                "fixture started with completed work");
        fixture.logic().insert(fixture.key(), 1L, Actionable.MODULATE);

        require(fixture.cpuBridge().bulk$getJob() == fixture.jobBridge(),
                "stock completion deleted a job with intermediate escrow");
        require(fixture.jobBridge().bulk$getRemainingFinalAmount() == 0L,
                "stock final insert did not complete request remaining");
        require(fixture.jobBridge().bulk$getWaitingFor(fixture.key()) == 0L,
                "stock final insert did not consume waiting state exactly once");
        require(completedWork(fixture.job(), fixture.key()) == 1L,
                "stock final insert did not consume progress exactly once");
        require(fixture.jobBridge().bulk$outputEscrow().get(intermediateKey) == 3L,
                "completion guard changed intermediate escrow");
        require(fixture.monitor().getJobProgress() != null,
                "intermediate-only escrow cleared the monitor output");
        require(fixture.monitor().getJobProgress().amount() == 1L,
                "intermediate-only escrow did not restore the monitor presence marker");

        forceClusterActive(fixture, helper);
        fixture.logic().tickCraftingLogic(null, null);

        require(fixture.cpuBridge().bulk$getJob() == null,
                "tick retry did not finish the drained job");
        require(fixture.jobBridge().bulk$outputEscrow().isEmpty(),
                "tick retry did not drain intermediate escrow");
        require(fixture.logic().getInventory()
                .extract(intermediateKey, Long.MAX_VALUE, Actionable.SIMULATE) == 3L,
                "tick retry did not retain intermediate output in CPU inventory");
        require(fixture.jobBridge().bulk$getWaitingFor(fixture.key()) == 0L,
                "tick retry consumed waiting state a second time");
        require(completedWork(fixture.job(), fixture.key()) == 1L,
                "tick retry consumed progress a second time");
        require(fixture.monitor().getJobProgress() == null,
                "completed tick retry left a monitor output");
        helper.succeed();
    }

    private static Fixture newFixture(
            long requestedAmount,
            long taskTimes,
            long recipeOutput,
            boolean standalone,
            TestRequester requester) throws Exception {
        return newFixture(requestedAmount, taskTimes, recipeOutput, standalone, requester,
                AEItemKey.of(Items.IRON_INGOT));
    }

    private static Fixture newFixture(
            long requestedAmount,
            long taskTimes,
            long recipeOutput,
            boolean standalone,
            TestRequester requester,
            AEKey key) throws Exception {
        var cluster = newCluster();
        var pattern = new TestPattern(new GenericStack(key, recipeOutput));
        var plan = new TestPlan(new GenericStack(key, requestedAmount), pattern, taskTimes);
        var cpuLink = newCpuLink(cluster, standalone, requester);
        var job = newExecutingJob(plan, cpuLink);
        setJob(cluster.craftingLogic, job);
        return new Fixture(
                cluster,
                cluster.craftingLogic,
                job,
                (IBulkCpuBridge) (Object) cluster.craftingLogic,
                (IBulkJobBridge) (Object) job,
                key,
                monitor(cluster));
    }

    private static CraftingCPUCluster newCluster() throws Exception {
        var cluster = new CraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
        var core = new CraftingBlockEntity(
                AEBlockEntities.CRAFTING_UNIT,
                BlockPos.ZERO,
                AEBlocks.CRAFTING_UNIT.block().defaultBlockState());
        Method addBlockEntity = CraftingCPUCluster.class
                .getDeclaredMethod("addBlockEntity", CraftingBlockEntity.class);
        addBlockEntity.setAccessible(true);
        addBlockEntity.invoke(cluster, core);
        var monitor = new CraftingMonitorBlockEntity(
                AEBlockEntities.CRAFTING_MONITOR,
                BlockPos.ZERO,
                AEBlocks.CRAFTING_MONITOR.block().defaultBlockState());
        addBlockEntity.invoke(cluster, monitor);
        return cluster;
    }

    private static CraftingMonitorBlockEntity monitor(CraftingCPUCluster cluster) {
        for (var blockEntity : (Iterable<CraftingBlockEntity>) cluster::getBlockEntities) {
            if (blockEntity instanceof CraftingMonitorBlockEntity monitor) {
                return monitor;
            }
        }
        throw new AssertionError("fixture has no crafting monitor");
    }

    private static void forceClusterActive(Fixture fixture, GameTestHelper helper) throws Exception {
        CraftingBlockEntity core = null;
        for (var blockEntity : (Iterable<CraftingBlockEntity>) fixture.cluster()::getBlockEntities) {
            if (!(blockEntity instanceof CraftingMonitorBlockEntity)) {
                core = blockEntity;
                break;
            }
        }
        if (core == null) {
            throw new AssertionError("fixture has no crafting core");
        }

        // Exercise the real transformed tick method without building the full
        // powered AE2 network that belongs to a later E2E phase.
        var activeNode = new GridNode(
                helper.getLevel(),
                core,
                (CraftingBlockEntity owner, IGridNode node) -> {
                },
                Set.of()) {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public IGrid getGrid() {
                return null;
            }
        };
        Field nodeField = core.getMainNode().getClass().getDeclaredField("node");
        nodeField.setAccessible(true);
        nodeField.set(core.getMainNode(), activeNode);
    }

    private static CraftingLink newCpuLink(
            CraftingCPUCluster cluster,
            boolean standalone,
            TestRequester requester) {
        UUID craftId = UUID.randomUUID();
        var cpuLink = new CraftingLink(linkTag(craftId, standalone, false), cluster);
        if (requester != null) {
            var requesterLink = new CraftingLink(linkTag(craftId, false, true), requester);
            var nexus = new CraftingLinkNexus(craftId);
            cpuLink.setNexus(nexus);
            requesterLink.setNexus(nexus);
        }
        return cpuLink;
    }

    private static CompoundTag linkTag(UUID craftId, boolean standalone, boolean requester) {
        var result = new CompoundTag();
        result.putUUID("craftId", craftId);
        result.putBoolean("standalone", standalone);
        result.putBoolean("req", requester);
        return result;
    }

    private static ExecutingCraftingJob newExecutingJob(ICraftingPlan plan, CraftingLink link) throws Exception {
        Class<?> listenerType = Class.forName(
                "appeng.crafting.execution.ExecutingCraftingJob$CraftingDifferenceListener");
        Object listener = Proxy.newProxyInstance(
                listenerType.getClassLoader(),
                new Class<?>[] { listenerType },
                (proxy, method, arguments) -> null);
        Constructor<ExecutingCraftingJob> constructor = ExecutingCraftingJob.class.getDeclaredConstructor(
                ICraftingPlan.class, listenerType, CraftingLink.class, Integer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(plan, listener, link, null);
    }

    private static void setJob(CraftingCpuLogic logic, ExecutingCraftingJob job) throws Exception {
        Field field = CraftingCpuLogic.class.getDeclaredField("job");
        field.setAccessible(true);
        field.set(logic, job);
    }

    private static ElapsedTimeTracker timeTracker(ExecutingCraftingJob job) throws Exception {
        Field field = ExecutingCraftingJob.class.getDeclaredField("timeTracker");
        field.setAccessible(true);
        return (ElapsedTimeTracker) field.get(job);
    }

    private static long completedWork(ExecutingCraftingJob job, AEKey key) throws Exception {
        return timeTracker(job).writeToNBT()
                .getCompound("completedWork")
                .getLong(key.getType().getId().toString());
    }

    private static void expectFailure(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Fixture(
            CraftingCPUCluster cluster,
            CraftingCpuLogic logic,
            ExecutingCraftingJob job,
            IBulkCpuBridge cpuBridge,
            IBulkJobBridge jobBridge,
            AEKey key,
            CraftingMonitorBlockEntity monitor) {
    }

    private record TestPlan(GenericStack finalOutput, IPatternDetails pattern, long taskTimes)
            implements ICraftingPlan {
        @Override
        public long bytes() {
            return taskTimes;
        }

        @Override
        public boolean simulation() {
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of(pattern, taskTimes);
        }
    }

    private record TestPattern(GenericStack output) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.PAPER);
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[] { output };
        }
    }

    private static final class TestRequester implements ICraftingRequester {
        private long acceptanceLimit;

        private TestRequester(long acceptanceLimit) {
            this.acceptanceLimit = acceptanceLimit;
        }

        private void setAcceptanceLimit(long acceptanceLimit) {
            this.acceptanceLimit = acceptanceLimit;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.of();
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            return Math.min(amount, acceptanceLimit);
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
        }

        @Override
        public IGridNode getActionableNode() {
            return null;
        }
    }
}

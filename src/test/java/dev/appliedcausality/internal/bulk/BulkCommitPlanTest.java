package dev.appliedcausality.internal.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import dev.appliedcausality.internal.cost.BulkCostPreflight;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.world.item.Items;

class BulkCommitPlanTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void multiplierLimitsTakeTheMinimumOfEveryNamedLimit() {
        long maximum = Long.MAX_VALUE;

        assertEquals(1L, limits(1L, maximum, maximum, maximum, maximum, maximum, maximum, maximum).minimum());
        assertEquals(2L, limits(maximum, 2L, maximum, maximum, maximum, maximum, maximum, maximum).minimum());
        assertEquals(3L, limits(maximum, maximum, 3L, maximum, maximum, maximum, maximum, maximum).minimum());
        assertEquals(4L, limits(maximum, maximum, maximum, 4L, maximum, maximum, maximum, maximum).minimum());
        assertEquals(5L, limits(maximum, maximum, maximum, maximum, 5L, maximum, maximum, maximum).minimum());
        assertEquals(6L, limits(maximum, maximum, maximum, maximum, maximum, 6L, maximum, maximum).minimum());
        assertEquals(7L, limits(maximum, maximum, maximum, maximum, maximum, maximum, 7L, maximum).minimum());
        assertEquals(8L, limits(maximum, maximum, maximum, maximum, maximum, maximum, maximum, 8L).minimum());
    }

    @Test
    void commitOrderEncodesWaitingBeforeMaterializationAndIntermediateBeforeFinal() {
        var empty = new BulkCommitPlan(
                limits(0L, 1L, 1L, 1L, 1L, 1L, 1L, 1L),
                0L,
                BulkCostPreflight.none().commitmentFor(1L),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty());

        assertEquals(List.of(
                BulkCommitPlan.CommitStage.CONSUME_COST,
                BulkCommitPlan.CommitStage.EXTRACT_INPUTS,
                BulkCommitPlan.CommitStage.REGISTER_WAITING,
                BulkCommitPlan.CommitStage.DECREMENT_TASK,
                BulkCommitPlan.CommitStage.MATERIALIZE_WAITING_TO_ESCROW,
                BulkCommitPlan.CommitStage.TRANSFER_INTERMEDIATE_OUTPUTS,
                BulkCommitPlan.CommitStage.DELIVER_FINAL_OUTPUT,
                BulkCommitPlan.CommitStage.MARK_CPU_DIRTY,
                BulkCommitPlan.CommitStage.ALLOW_JOB_COMPLETION,
                BulkCommitPlan.CommitStage.CLEAN_COMPLETED_TASKS),
                empty.commitOrder());
    }

    @Test
    void constructorRejectsPlansThatDoNotMatchTheirSelectedMultiplier() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var limits = limits(0L, 1L, 1L, 1L, 1L, 1L, 1L, 1L);

        assertThrows(IllegalArgumentException.class, () -> new BulkCommitPlan(
                limits,
                1L,
                BulkCostPreflight.none().commitmentFor(1L),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new BulkCommitPlan(
                limits,
                0L,
                BulkCostPreflight.none().commitmentFor(1L),
                Map.of(key, 1L), Map.of(), Map.of(), Map.of(), Map.of(), Optional.empty()));
    }

    @Test
    void finalDeliveryNeverPredeterminesMoreProgressThanItOwns() {
        var key = AEItemKey.of(Items.GOLD_INGOT);

        assertThrows(IllegalArgumentException.class,
                () -> new BulkCommitPlan.FinalDelivery(key, 0L, 0L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new BulkCommitPlan.FinalDelivery(key, 1L, 2L, false));
        assertEquals(3L,
                new BulkCommitPlan.FinalDelivery(key, 5L, 2L, false)
                        .cpuInventoryTransferIfProgressCompletes());
        assertEquals(5L,
                new BulkCommitPlan.FinalDelivery(key, 5L, 2L, true)
                        .cpuInventoryTransferIfProgressCompletes());
    }

    private static BulkCommitPlan.MultiplierLimits limits(
            long task,
            long provider,
            long server,
            long input,
            long output,
            long inventory,
            long waiting,
            long cost) {
        return new BulkCommitPlan.MultiplierLimits(
                task, provider, server, input, output, inventory, waiting, cost);
    }
}

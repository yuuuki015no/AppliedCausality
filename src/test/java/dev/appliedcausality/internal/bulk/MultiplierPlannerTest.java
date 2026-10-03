package dev.appliedcausality.internal.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.cost.BulkCostPreflight;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

class MultiplierPlannerTest {
    private static final long ABOVE_INTEGER_MAX = (long) Integer.MAX_VALUE + 1L;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void requiredNumericMatrixPlansExactlyWithoutMultiplierSizedWork() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var process = verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of());
        long[] values = {
                0L,
                1L,
                Integer.MAX_VALUE,
                ABOVE_INTEGER_MAX,
                Long.MAX_VALUE / 2L,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        };

        for (long value : values) {
            var plan = MultiplierPlanner.plan(process, state(
                    value,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    Map.of(input, value),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    output,
                    false,
                    Long.MAX_VALUE));

            assertEquals(value, plan.multiplier());
            assertEquals(value, plan.taskDecrement());
            assertEquals(value == 0 ? Map.of() : Map.of(input, value), plan.inputsToExtract());
            assertEquals(value == 0 ? Map.of() : Map.of(output, value), plan.waitingToRegister());
            assertEquals(value == 0 ? Map.of() : Map.of(output, value), plan.outputsToMaterialize());
            assertEquals(value == 0, plan.isEmpty());
            assertEquals(value == 0, plan.finalDelivery().isEmpty());
            if (value > 0) {
                assertEquals(value, plan.finalDelivery().orElseThrow().escrowAmount());
            }
        }
    }

    @Test
    void createsEveryExactCommitQuantityAndIncludesExistingEscrowOwnership() {
        AEKey iron = key(Items.IRON_INGOT);
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey gold = key(Items.GOLD_INGOT);
        AEKey slag = key(Items.FLINT);
        AEKey bucket = key(Items.BUCKET);
        var process = verifiedModel(
                Map.of(iron, 2L, copper, 3L),
                Map.of(gold, 4L, slag, 1L),
                Map.of(slag, 2L, bucket, 1L));

        var plan = MultiplierPlanner.plan(process, state(
                8L,
                10L,
                9L,
                Map.of(iron, 10L, copper, 20L),
                Map.of(gold, 1L),
                Map.of(gold, 3L, slag, 2L),
                Map.of(gold, 2L, slag, 7L, bucket, 1L),
                gold,
                false,
                17L));

        assertEquals(5L, plan.multiplier());
        assertEquals(5L, plan.limits().inputAvailabilityLimit());
        assertEquals(Map.of(iron, 10L, copper, 15L), plan.inputsToExtract());
        assertEquals(Map.of(gold, 20L, slag, 15L, bucket, 5L), plan.waitingToRegister());
        assertEquals(plan.waitingToRegister(), plan.outputsToMaterialize());
        assertEquals(Map.of(slag, 17L, bucket, 5L), plan.intermediateOutputsToTransfer());
        assertEquals(Map.of(gold, 23L, slag, 17L, bucket, 5L), plan.cpuInventoryReservations());

        var finalDelivery = plan.finalDelivery().orElseThrow();
        assertEquals(gold, finalDelivery.key());
        assertEquals(23L, finalDelivery.escrowAmount());
        assertEquals(17L, finalDelivery.remainingProgressLimit());
        assertEquals(6L, finalDelivery.cpuInventoryTransferIfProgressCompletes());
        assertFalse(finalDelivery.standalone());
    }

    @Test
    void everyComputedNumericLimitCanBecomeTheSelectedMinimum() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        AEKey finalKey = key(Items.DIAMOND);
        var process = verifiedModel(Map.of(input, 1L), Map.of(output, 2L), Map.of());

        var outputLimited = MultiplierPlanner.plan(process, state(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, Long.MAX_VALUE), Map.of(), Map.of(output, Long.MAX_VALUE - 5L),
                Map.of(), finalKey, false, Long.MAX_VALUE));
        assertEquals(2L, outputLimited.limits().outputNumericLimit());
        assertEquals(2L, outputLimited.multiplier());

        var waitingLimited = MultiplierPlanner.plan(process, state(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, Long.MAX_VALUE), Map.of(output, Long.MAX_VALUE - 3L), Map.of(),
                Map.of(), finalKey, false, Long.MAX_VALUE));
        assertEquals(1L, waitingLimited.limits().waitingForNumericLimit());
        assertEquals(1L, waitingLimited.multiplier());

        var inventoryLimited = MultiplierPlanner.plan(process, state(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, Long.MAX_VALUE), Map.of(), Map.of(output, 1L),
                Map.of(output, Long.MAX_VALUE - 4L), finalKey, false, Long.MAX_VALUE));
        assertEquals(1L, inventoryLimited.limits().cpuInventoryNumericLimit());
        assertEquals(1L, inventoryLimited.multiplier());
    }

    @Test
    void finalReservationCoversCancelAfterZeroOrPartialRequesterAcceptance() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var process = verifiedModel(Map.of(input, 1L), Map.of(output, 2L), Map.of());

        long cpuInventory = Long.MAX_VALUE - 2L;
        var cancelSafePlan = MultiplierPlanner.plan(process, state(
                1L, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, 1L), Map.of(), Map.of(),
                Map.of(output, cpuInventory), output, false, 2L));
        assertEquals(1L, cancelSafePlan.multiplier());
        assertEquals(Map.of(output, 2L), cancelSafePlan.cpuInventoryReservations());

        long escrowAfterZeroAcceptance = cancelSafePlan.finalDelivery().orElseThrow().escrowAmount();
        long escrowAfterPartialAcceptance = BulkLongMath.checkedSubtract(escrowAfterZeroAcceptance, 1L);
        assertEquals(Long.MAX_VALUE,
                BulkLongMath.checkedAdd(cpuInventory, escrowAfterZeroAcceptance));
        assertEquals(Long.MAX_VALUE - 1L,
                BulkLongMath.checkedAdd(cpuInventory, escrowAfterPartialAcceptance));

        var unsafeReviewCounterexample = MultiplierPlanner.plan(process, state(
                1L, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, 1L), Map.of(), Map.of(),
                Map.of(output, Long.MAX_VALUE - 1L), output, false, 2L));
        assertEquals(0L, unsafeReviewCounterexample.limits().cpuInventoryNumericLimit());
        assertTrue(unsafeReviewCounterexample.isEmpty());
    }

    @Test
    void costPreflightLimitSelectsThePlanBeforeCommit() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var process = verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of());

        var productLimited = MultiplierPlanner.plan(process, state(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, Long.MAX_VALUE), Map.of(), Map.of(), Map.of(),
                output, false, Long.MAX_VALUE), new BulkCostPreflight(
                Long.MAX_VALUE / 2L, multiplier -> BulkCostPreflight.none().commitmentFor(1L)));
        assertEquals(Long.MAX_VALUE / 2L, productLimited.limits().costPreflightLimit());
        assertEquals(Long.MAX_VALUE / 2L, productLimited.multiplier());

        var unavailable = MultiplierPlanner.plan(process, state(
                1L, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(input, 1L), Map.of(), Map.of(), Map.of(), output, false, 1L),
                new BulkCostPreflight(0L, multiplier -> BulkCostPreflight.none().commitmentFor(1L)));
        assertEquals(0L, unavailable.limits().costPreflightLimit());
        assertTrue(unavailable.isEmpty());
    }

    @Test
    void overlappingOutputAndRemainingItemAmountsAreCombinedOrFailClosed() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.BUCKET);
        var safe = verifiedModel(Map.of(input, 1L), Map.of(output, 2L), Map.of(output, 3L));
        var safePlan = MultiplierPlanner.plan(safe, unconstrainedState(input, output, 4L));

        assertEquals(4L, safePlan.multiplier());
        assertEquals(Map.of(output, 20L), safePlan.outputsToMaterialize());

        var overflowing = verifiedModel(
                Map.of(input, 1L), Map.of(output, Long.MAX_VALUE), Map.of(output, 1L));
        var rejectedPlan = MultiplierPlanner.plan(
                overflowing, unconstrainedState(input, output, Long.MAX_VALUE));

        assertEquals(0L, rejectedPlan.limits().outputNumericLimit());
        assertTrue(rejectedPlan.isEmpty());
    }

    @Test
    void planningStateAndPlanMapsAreImmutableSnapshots() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var availability = new HashMap<AEKey, Long>();
        availability.put(input, 3L);
        var snapshot = state(
                3L, Long.MAX_VALUE, Long.MAX_VALUE,
                availability, Map.of(), Map.of(), Map.of(), output, false, 3L);
        availability.put(input, 0L);

        var plan = MultiplierPlanner.plan(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()), snapshot);

        assertEquals(3L, plan.multiplier());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.inputsToExtract().put(input, 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.commitOrder().add(BulkCommitPlan.CommitStage.EXTRACT_INPUTS));
    }

    @Test
    void invalidPlanningQuantitiesAreRejectedBeforePlanning() {
        AEKey key = key(Items.IRON_INGOT);

        assertThrows(IllegalArgumentException.class, () -> state(
                -1L, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(), Map.of(), Map.of(), Map.of(), key, false, 0L));
        assertThrows(IllegalArgumentException.class, () -> state(
                1L, Long.MAX_VALUE, Long.MAX_VALUE,
                Map.of(key, -1L), Map.of(), Map.of(), Map.of(), key, false, 0L));
    }

    private static MultiplierPlanner.PlanningState unconstrainedState(
            AEKey input, AEKey finalOutput, long inputAmount) {
        return state(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Map.of(input, inputAmount),
                Map.of(),
                Map.of(),
                Map.of(),
                finalOutput,
                false,
                Long.MAX_VALUE);
    }

    private static MultiplierPlanner.PlanningState state(
            long taskRemaining,
            long providerLimit,
            long serverConfigLimit,
            Map<AEKey, Long> inputAvailability,
            Map<AEKey, Long> waitingFor,
            Map<AEKey, Long> escrow,
            Map<AEKey, Long> cpuInventory,
            AEKey finalOutput,
            boolean standalone,
            long remainingFinal) {
        return new MultiplierPlanner.PlanningState(
                taskRemaining,
                providerLimit,
                serverConfigLimit,
                inputAvailability,
                waitingFor,
                escrow,
                cpuInventory,
                finalOutput,
                standalone,
                remainingFinal);
    }

    private static VerifiedProcessModel verifiedModel(
            Map<AEKey, Long> inputs,
            Map<AEKey, Long> outputs,
            Map<AEKey, Long> remainingItems) {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ProcessShapeId.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    Map.class,
                    Map.class,
                    Map.class,
                    ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    id("adapter"),
                    id("recipe"),
                    ResourceLocation.withDefaultNamespace("smelting"),
                    ProcessShapeId.of("test", "item_to_item"),
                    id("family"),
                    null,
                    inputs,
                    outputs,
                    remainingItems,
                    new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to create test-only verified model", exception);
        }
    }

    private static AEKey key(Item item) {
        return AEItemKey.of(item);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}

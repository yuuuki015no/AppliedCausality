package dev.appliedcausality.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Isolated, rolling whole-server Phase 21 performance comparison. */
@GameTestHolder(Phase21ServerPerformanceGameTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class Phase21ServerPerformanceGameTests {
    static final String NAMESPACE = "applied_causality_phase21";
    private static final int INVOCATIONS_PER_TICK = 128;
    private static final int WARMUP_TICKS = 10;
    private static final int SAMPLE_TICKS = 21;

    private Phase21ServerPerformanceGameTests() {
    }

    @GameTest(
            templateNamespace = NAMESPACE,
            template = "empty",
            batch = "phase21_performance",
            timeoutTicks = 400)
    public static void rollingServerComparison(GameTestHelper helper) {
        Locale.setDefault(Locale.ROOT);
        var targets = List.of(
                new Target(
                        "normal_ae2_mekanism",
                        1,
                        19,
                        Phase21MachinePerformanceFixtures::prepareNormalAe2Mekanism,
                        new ExpectedCounts(0, 0, 0, 1, 23, 0, 0)),
                new Target(
                        "astral_mekanism_physical",
                        64,
                        -1,
                        Phase21MachinePerformanceFixtures::prepareAstralMachine,
                        new ExpectedCounts(0, 0, 0, 0, 0, 0, 0)),
                new Target(
                        "applied_causality_normal_fallback",
                        1,
                        19,
                        (h, slot) -> Phase12FallbackGameTests.preparePhase21FallbackInvocation(h),
                        new ExpectedCounts(1, 0, 0, 1, 0, 0, 0)),
                new Target(
                        "applied_causality_bulk_ae2",
                        1_000,
                        3_016,
                        (h, slot) -> Phase11StandardAe2GameTests.preparePhase21BulkInvocation(h, 1_000),
                        new ExpectedCounts(1, 3, 0, 0, 0, 1, 1_000)),
                new Target(
                        "applied_causality_advancedae_quantum",
                        (long) Integer.MAX_VALUE + 1,
                        6_442_450_960L,
                        (h, slot) -> Phase14AdvancedAeGameTests.preparePhase21AdvancedInvocation(h),
                        new ExpectedCounts(1, 3, 0, 0, 0, 1, (long) Integer.MAX_VALUE + 1)));
        var runner = new Runner(helper, targets);
        helper.onEachTick(runner::tick);
    }

    private record Target(
            String name,
            long multiplier,
            long craftingStorageBytes,
            InvocationFactory factory,
            ExpectedCounts expected) {
    }

    private record ExpectedCounts(
            long providerLookups,
            long verifiedModelCacheLookups,
            long recipeResolutions,
            long normalPushPatternCalls,
            long externalCapabilityCalls,
            long bulkDispatches,
            long bulkOperations) {
        void verify(BulkExecutionMetrics.Snapshot actual, int invocationCount, String target) {
            var expectedTotal = new BulkExecutionMetrics.Snapshot(
                    providerLookups * invocationCount,
                    verifiedModelCacheLookups * invocationCount,
                    recipeResolutions * invocationCount,
                    normalPushPatternCalls * invocationCount,
                    externalCapabilityCalls * invocationCount,
                    bulkDispatches * invocationCount,
                    Math.multiplyExact(bulkOperations, invocationCount));
            if (!expectedTotal.equals(actual)) {
                throw new AssertionError(target + " call counts changed: expected="
                        + expectedTotal + ", actual=" + actual);
            }
        }
    }

    @FunctionalInterface
    private interface InvocationFactory {
        Phase21PerformanceProbe.PreparedInvocation create(GameTestHelper helper, int slot) throws Exception;
    }

    private static final class Runner {
        private final GameTestHelper helper;
        private final List<Target> targets;
        private int targetIndex;
        private int completedTicks;
        private List<Phase21PerformanceProbe.PreparedInvocation> prepared;
        private List<Phase21PerformanceProbe.PreparedInvocation> executed;
        private Phase21PerformanceProbe.Measurement pendingMeasurement;
        private boolean collectPreviousTick;
        private final List<Double> mspt = new ArrayList<>();
        private final List<Double> wallPerInvocation = new ArrayList<>();
        private final List<Double> cpuPerInvocation = new ArrayList<>();
        private final List<Double> allocatedPerInvocation = new ArrayList<>();
        private BulkExecutionMetrics.Snapshot stablePerInvocation;

        private Runner(GameTestHelper helper, List<Target> targets) {
            this.helper = helper;
            this.targets = targets;
        }

        private void tick() {
            try {
                if (collectPreviousTick) {
                    collectPreviousTick = false;
                    collectCompletedTick();
                    targets.get(targetIndex).expected().verify(
                            pendingMeasurement.metrics(), executed.size(), targets.get(targetIndex).name());
                    for (var invocation : executed) {
                        invocation.verify().run();
                    }
                    executed = null;
                    if (completedTicks == WARMUP_TICKS + SAMPLE_TICKS) {
                        printTarget();
                        targetIndex++;
                        completedTicks = 0;
                        stablePerInvocation = null;
                        mspt.clear();
                        wallPerInvocation.clear();
                        cpuPerInvocation.clear();
                        allocatedPerInvocation.clear();
                        if (targetIndex == targets.size()) {
                            helper.succeed();
                            return;
                        }
                    }
                }

                if (prepared == null && targetIndex < targets.size()) {
                    prepared = prepareBatch(targets.get(targetIndex));
                    return;
                }

                if (prepared != null) {
                    pendingMeasurement = Phase21PerformanceProbe.measure(() -> {
                        for (var invocation : prepared) {
                            invocation.dispatch().run();
                        }
                    });
                    executed = prepared;
                    prepared = null;
                    collectPreviousTick = true;
                }
            } catch (Exception exception) {
                if (exception instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new AssertionError("Phase 21 benchmark setup failed", exception);
            }
        }

        private List<Phase21PerformanceProbe.PreparedInvocation> prepareBatch(Target target) throws Exception {
            var result = new ArrayList<Phase21PerformanceProbe.PreparedInvocation>(INVOCATIONS_PER_TICK);
            for (int index = 0; index < INVOCATIONS_PER_TICK; index++) {
                var invocation = target.factory().create(helper, index);
                if (!target.name().equals(invocation.integration())
                        || target.multiplier() != invocation.multiplier()
                        || target.craftingStorageBytes() != invocation.craftingStorageBytes()) {
                    throw new AssertionError("prepared benchmark metadata changed for " + target.name());
                }
                result.add(invocation);
            }
            return List.copyOf(result);
        }

        private void collectCompletedTick() {
            var server = helper.getLevel().getServer();
            int completedTick = server.getTickCount() - 1;
            long tickNanos = server.tickTimes[Math.floorMod(completedTick, server.tickTimes.length)];
            if (tickNanos <= 0) {
                throw new AssertionError("server did not publish the completed tick duration");
            }
            if (completedTicks >= WARMUP_TICKS) {
                mspt.add(tickNanos / 1_000_000.0);
                wallPerInvocation.add(
                        pendingMeasurement.wallNanos() / 1_000_000.0 / INVOCATIONS_PER_TICK);
                cpuPerInvocation.add(
                        pendingMeasurement.cpuNanos() < 0
                                ? Double.NaN
                                : pendingMeasurement.cpuNanos() / 1_000_000.0 / INVOCATIONS_PER_TICK);
                allocatedPerInvocation.add(
                        pendingMeasurement.allocatedBytes() < 0
                                ? Double.NaN
                                : (double) pendingMeasurement.allocatedBytes() / INVOCATIONS_PER_TICK);
                var perInvocation = divide(pendingMeasurement.metrics(), INVOCATIONS_PER_TICK);
                if (stablePerInvocation == null) {
                    stablePerInvocation = perInvocation;
                } else if (!stablePerInvocation.equals(perInvocation)) {
                    throw new AssertionError("per-invocation call counts changed between samples");
                }
            }
            completedTicks++;
        }

        private void printTarget() {
            Target target = targets.get(targetIndex);
            System.out.printf(
                    "phase21_server,target=%s,multiplier=%d,invocations_per_tick=%d,warmup_ticks=%d,"
                            + "sample_ticks=%d,mspt_p50=%.3f,mspt_p95=%.3f,mspt_max=%.3f,"
                            + "dispatch_wall_p50_ms=%.6f,dispatch_wall_p95_ms=%.6f,"
                            + "dispatch_cpu_mean_ms=%.6f,allocated_p50_bytes=%.0f,"
                            + "crafting_storage_bytes=%d,provider_lookups=%d,"
                            + "verified_model_cache_lookups=%d,recipe_resolutions=%d,"
                            + "normal_push_pattern_calls=%d,external_capability_calls=%d,"
                            + "bulk_dispatches=%d,bulk_operations=%d%n",
                    target.name(),
                    target.multiplier(),
                    INVOCATIONS_PER_TICK,
                    WARMUP_TICKS,
                    SAMPLE_TICKS,
                    percentile(mspt, 0.50),
                    percentile(mspt, 0.95),
                    percentile(mspt, 1.00),
                    percentile(wallPerInvocation, 0.50),
                    percentile(wallPerInvocation, 0.95),
                    mean(cpuPerInvocation),
                    percentile(allocatedPerInvocation, 0.50),
                    target.craftingStorageBytes(),
                    stablePerInvocation.providerLookups(),
                    stablePerInvocation.verifiedModelCacheLookups(),
                    stablePerInvocation.recipeResolutions(),
                    stablePerInvocation.normalPushPatternCalls(),
                    stablePerInvocation.externalCapabilityCalls(),
                    stablePerInvocation.bulkDispatches(),
                    stablePerInvocation.bulkOperations());
        }
    }

    private static BulkExecutionMetrics.Snapshot divide(
            BulkExecutionMetrics.Snapshot total, long divisor) {
        long[] values = {
                total.providerLookups(),
                total.verifiedModelCacheLookups(),
                total.recipeResolutions(),
                total.normalPushPatternCalls(),
                total.externalCapabilityCalls(),
                total.bulkDispatches(),
                total.bulkOperations()
        };
        for (long value : values) {
            if (value % divisor != 0) {
                throw new AssertionError("event count is not stable per invocation: " + value);
            }
        }
        return new BulkExecutionMetrics.Snapshot(
                values[0] / divisor,
                values[1] / divisor,
                values[2] / divisor,
                values[3] / divisor,
                values[4] / divisor,
                values[5] / divisor,
                values[6] / divisor);
    }

    private static double percentile(List<Double> values, double fraction) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) {
            throw new AssertionError("no performance samples were collected");
        }
        int index = Math.min(sorted.length - 1, (int) Math.ceil(fraction * sorted.length) - 1);
        return sorted[index];
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average()
                .orElseThrow(() -> new AssertionError("no performance samples were collected"));
    }
}

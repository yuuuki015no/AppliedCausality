package dev.appliedcausality.internal.bulk;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;

import dev.appliedcausality.test.MinecraftTestBootstrap;

/**
 * Phase 21 benchmark for the common fake/in-memory Bulk engine.
 *
 * <p>Fixture construction, verification, and representative Crafting Storage
 * arithmetic are outside the measured interval. Each timed invocation executes
 * one fresh fixed-shape one-input/one-output dispatch.</p>
 */
public final class BulkDispatchPerformanceBenchmark {
    private static final long[] MULTIPLIERS = {
            1L,
            1_000L,
            1_000_000L,
            Integer.MAX_VALUE,
            Long.MAX_VALUE
    };
    private static final int WARMUP_BATCHES = 5;
    private static final int WARMUP_INVOCATIONS = 2_000;
    private static final int MEASUREMENT_SAMPLES = 11;
    private static final int INVOCATIONS_PER_SAMPLE = 10_000;
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger SIXTEEN = BigInteger.valueOf(16);
    private static volatile long blackhole;

    private BulkDispatchPerformanceBenchmark() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        MinecraftTestBootstrap.initialize();

        var threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean.isCurrentThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        com.sun.management.ThreadMXBean allocationBean = allocationBean();

        System.out.printf(
                "phase21_environment,java=%s,jvm=%s,os=%s,arch=%s,warmup_batches=%d,"
                        + "warmup_invocations=%d,samples=%d,invocations_per_sample=%d%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                WARMUP_BATCHES,
                WARMUP_INVOCATIONS,
                MEASUREMENT_SAMPLES,
                INVOCATIONS_PER_SAMPLE);

        for (int batch = 0; batch < WARMUP_BATCHES; batch++) {
            for (long multiplier : MULTIPLIERS) {
                runBatch(multiplier, WARMUP_INVOCATIONS, threadBean, allocationBean, false);
            }
        }

        double[][] wallNanos = new double[MULTIPLIERS.length][MEASUREMENT_SAMPLES];
        double[][] cpuNanos = new double[MULTIPLIERS.length][MEASUREMENT_SAMPLES];
        double[][] allocatedBytes = new double[MULTIPLIERS.length][MEASUREMENT_SAMPLES];
        var observations = new BulkDispatchEngineTest.BenchmarkObservation[MULTIPLIERS.length];
        for (int sample = 0; sample < MEASUREMENT_SAMPLES; sample++) {
            for (int offset = 0; offset < MULTIPLIERS.length; offset++) {
                int multiplierIndex = (sample + offset) % MULTIPLIERS.length;
                BatchResult result = runBatch(
                        MULTIPLIERS[multiplierIndex],
                        INVOCATIONS_PER_SAMPLE,
                        threadBean,
                        allocationBean,
                        true);
                wallNanos[multiplierIndex][sample] = result.wallNanosPerDispatch();
                cpuNanos[multiplierIndex][sample] = result.cpuNanosPerDispatch();
                allocatedBytes[multiplierIndex][sample] = result.allocatedBytesPerDispatch();
                if (observations[multiplierIndex] == null) {
                    observations[multiplierIndex] = result.observation();
                } else if (!observations[multiplierIndex].equals(result.observation())) {
                    throw new AssertionError("benchmark work counters changed between samples");
                }
            }
        }

        for (int index = 0; index < MULTIPLIERS.length; index++) {
            printSummary(
                    MULTIPLIERS[index],
                    wallNanos[index],
                    cpuNanos[index],
                    allocatedBytes[index],
                    observations[index]);
        }
        System.out.println("phase21_blackhole," + blackhole);
    }

    private static BatchResult runBatch(
            long multiplier,
            int invocationCount,
            java.lang.management.ThreadMXBean threadBean,
            com.sun.management.ThreadMXBean allocationBean,
            boolean collectObservation) {
        System.gc();
        var invocations = new BulkDispatchEngineTest.BenchmarkInvocation[invocationCount];
        for (int index = 0; index < invocationCount; index++) {
            invocations[index] = BulkDispatchEngineTest.newBenchmarkInvocation(multiplier);
        }

        long threadId = Thread.currentThread().getId();
        long allocatedBefore = allocationBean == null
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId);
        long cpuBefore = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime()
                : -1L;
        long wallBefore = System.nanoTime();
        for (var invocation : invocations) {
            invocation.execute();
        }
        long wallAfter = System.nanoTime();
        long cpuAfter = cpuBefore < 0 ? -1L : threadBean.getCurrentThreadCpuTime();
        long allocatedAfter = allocatedBefore < 0
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId);

        BulkDispatchEngineTest.BenchmarkObservation observation = invocations[0].verify();
        blackhole ^= observation.bulkOperationsExecuted();
        for (int index = 1; index < invocations.length; index++) {
            var candidate = invocations[index].verify();
            if (collectObservation && !observation.equals(candidate)) {
                throw new AssertionError("benchmark work counters changed within a sample");
            }
            blackhole ^= candidate.measuredIterations();
        }

        return new BatchResult(
                divide(wallAfter - wallBefore, invocationCount),
                cpuBefore < 0 ? Double.NaN : divide(cpuAfter - cpuBefore, invocationCount),
                allocatedBefore < 0
                        ? Double.NaN
                        : divide(allocatedAfter - allocatedBefore, invocationCount),
                observation);
    }

    private static void printSummary(
            long multiplier,
            double[] wallNanos,
            double[] cpuNanos,
            double[] allocatedBytes,
            BulkDispatchEngineTest.BenchmarkObservation observation) {
        System.out.printf(
                "phase21_fake_bulk,multiplier=%d,wall_median_us=%.3f,wall_min_us=%.3f,wall_max_us=%.3f,"
                        + "cpu_median_us=%.3f,allocated_median_bytes=%.0f,measured_iterations=%d,"
                        + "provider_lookups=%d,verified_model_cache_lookups=%d,recipe_resolutions=%d,"
                        + "normal_push_pattern_calls=%d,external_capability_calls=%d,bulk_dispatches=%d,"
                        + "bulk_operations=%d,representative_planning_bytes=%s%n",
                multiplier,
                median(wallNanos) / 1_000.0,
                Arrays.stream(wallNanos).min().orElseThrow() / 1_000.0,
                Arrays.stream(wallNanos).max().orElseThrow() / 1_000.0,
                median(cpuNanos) / 1_000.0,
                median(allocatedBytes),
                observation.measuredIterations(),
                observation.providerLookups(),
                observation.verifiedModelCacheLookups(),
                observation.recipeResolutions(),
                observation.normalPushPatternCalls(),
                observation.externalCapabilityCalls(),
                observation.bulkDispatches(),
                observation.bulkOperationsExecuted(),
                representativePlanningBytes(multiplier));
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static String representativePlanningBytes(long multiplier) {
        return BigInteger.valueOf(multiplier).multiply(THREE).add(SIXTEEN).toString();
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double divide(long value, int divisor) {
        return (double) value / divisor;
    }

    private record BatchResult(
            double wallNanosPerDispatch,
            double cpuNanosPerDispatch,
            double allocatedBytesPerDispatch,
            BulkDispatchEngineTest.BenchmarkObservation observation) {
    }
}

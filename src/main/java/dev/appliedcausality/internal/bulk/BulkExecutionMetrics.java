package dev.appliedcausality.internal.bulk;

/**
 * Scoped internal event counter used by the Phase 21 verification harness.
 *
 * <p>The inactive path is one null check. Recording is thread-confined so a
 * benchmark cannot accidentally attribute unrelated server work to a Bulk
 * dispatch. This is diagnostic state only and never participates in execution
 * decisions.</p>
 */
public final class BulkExecutionMetrics {
    private static final ThreadLocal<MutableSnapshot> ACTIVE = new ThreadLocal<>();

    private BulkExecutionMetrics() {
    }

    public static Recording begin() {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Bulk execution metrics are already active on this thread");
        }
        var counters = new MutableSnapshot();
        ACTIVE.set(counters);
        return new Recording(counters);
    }

    public static void providerLookup() {
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.providerLookups = saturatedIncrement(counters.providerLookups);
        }
    }

    public static void verifiedModelCacheLookup() {
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.verifiedModelCacheLookups = saturatedIncrement(counters.verifiedModelCacheLookups);
        }
    }

    public static void recipeResolution() {
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.recipeResolutions = saturatedIncrement(counters.recipeResolutions);
        }
    }

    public static void normalPushPattern() {
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.normalPushPatternCalls = saturatedIncrement(counters.normalPushPatternCalls);
        }
    }

    public static void externalCapabilityCall() {
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.externalCapabilityCalls = saturatedIncrement(counters.externalCapabilityCalls);
        }
    }

    public static void bulkDispatch(long operations) {
        if (operations < 0) {
            throw new IllegalArgumentException("Bulk operation count must be non-negative");
        }
        var counters = ACTIVE.get();
        if (counters != null) {
            counters.bulkDispatches = saturatedIncrement(counters.bulkDispatches);
            counters.bulkOperations = saturatedAdd(counters.bulkOperations, operations);
        }
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public record Snapshot(
            long providerLookups,
            long verifiedModelCacheLookups,
            long recipeResolutions,
            long normalPushPatternCalls,
            long externalCapabilityCalls,
            long bulkDispatches,
            long bulkOperations) {
    }

    public static final class Recording implements AutoCloseable {
        private final MutableSnapshot counters;
        private boolean closed;

        private Recording(MutableSnapshot counters) {
            this.counters = counters;
        }

        public Snapshot snapshot() {
            if (closed) {
                throw new IllegalStateException("Bulk execution metrics recording is closed");
            }
            return counters.snapshot();
        }

        @Override
        public void close() {
            if (!closed) {
                if (ACTIVE.get() != counters) {
                    throw new IllegalStateException("Bulk execution metrics scope changed unexpectedly");
                }
                ACTIVE.remove();
                closed = true;
            }
        }
    }

    private static final class MutableSnapshot {
        private long providerLookups;
        private long verifiedModelCacheLookups;
        private long recipeResolutions;
        private long normalPushPatternCalls;
        private long externalCapabilityCalls;
        private long bulkDispatches;
        private long bulkOperations;

        private Snapshot snapshot() {
            return new Snapshot(
                    providerLookups,
                    verifiedModelCacheLookups,
                    recipeResolutions,
                    normalPushPatternCalls,
                    externalCapabilityCalls,
                    bulkDispatches,
                    bulkOperations);
        }
    }
}

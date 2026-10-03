package dev.appliedcausality.internal.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BulkCostPreflightTest {
    @Test
    void nonePolicyIsAStatelessNoOpAcrossTheRequiredLongRange() {
        long[] multipliers = {
                1L,
                Integer.MAX_VALUE,
                (long) Integer.MAX_VALUE + 1L,
                Long.MAX_VALUE / 2L,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        };

        BulkCostPreflight none = BulkCostPreflight.none();
        assertEquals(Long.MAX_VALUE, none.multiplierLimit());
        for (long multiplier : multipliers) {
            BulkCostCommitment commitment = none.commitmentFor(multiplier);
            assertTrue(commitment.tryConsume());
            commitment.restore();
        }
    }

    @Test
    void commitmentCanOnlyBeCreatedForAnApprovedPositiveMultiplier() {
        BulkCostPreflight limited = new BulkCostPreflight(
                4L, multiplier -> BulkCostPreflight.none().commitmentFor(1L));

        assertThrows(IllegalArgumentException.class, () -> limited.commitmentFor(0L));
        assertThrows(IllegalArgumentException.class, () -> limited.commitmentFor(5L));
    }
}

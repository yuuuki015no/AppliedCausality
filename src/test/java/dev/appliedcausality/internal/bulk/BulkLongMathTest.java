package dev.appliedcausality.internal.bulk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkLongMathTest {
    private static final long ABOVE_INTEGER_MAX = (long) Integer.MAX_VALUE + 1L;

    @Test
    void checkedAddAcceptsRequiredBoundaryValuesExactly() {
        assertEquals(0L, BulkLongMath.checkedAdd(0L, 0L));
        assertEquals(1L, BulkLongMath.checkedAdd(0L, 1L));
        assertEquals(Integer.MAX_VALUE, BulkLongMath.checkedAdd(Integer.MAX_VALUE - 1L, 1L));
        assertEquals(ABOVE_INTEGER_MAX, BulkLongMath.checkedAdd(Integer.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, BulkLongMath.checkedAdd(Long.MAX_VALUE - 1L, 1L));
        assertThrows(ArithmeticException.class, () -> BulkLongMath.checkedAdd(Long.MAX_VALUE, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedAdd(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedAdd(1L, -1L));
    }

    @Test
    void checkedSubtractRejectsNegativeStateAndUnderflow() {
        assertEquals(0L, BulkLongMath.checkedSubtract(0L, 0L));
        assertEquals(ABOVE_INTEGER_MAX, BulkLongMath.checkedSubtract(ABOVE_INTEGER_MAX + 1L, 1L));
        assertEquals(Long.MAX_VALUE - 1L, BulkLongMath.checkedSubtract(Long.MAX_VALUE, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedSubtract(0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedSubtract(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedSubtract(0L, -1L));
    }

    @Test
    void checkedMultiplyAcceptsRequiredBoundaryValuesExactly() {
        assertEquals(0L, BulkLongMath.checkedMultiply(0L, Long.MAX_VALUE));
        assertEquals(1L, BulkLongMath.checkedMultiply(1L, 1L));
        assertEquals(ABOVE_INTEGER_MAX, BulkLongMath.checkedMultiply(ABOVE_INTEGER_MAX, 1L));
        assertEquals(Long.MAX_VALUE - 1L, BulkLongMath.checkedMultiply(Long.MAX_VALUE - 1L, 1L));
        assertEquals(Long.MAX_VALUE, BulkLongMath.checkedMultiply(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE - 1L, BulkLongMath.checkedMultiply((Long.MAX_VALUE - 1L) / 2L, 2L));
        assertThrows(ArithmeticException.class, () -> BulkLongMath.checkedMultiply(Long.MAX_VALUE, 2L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedMultiply(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.checkedMultiply(1L, -1L));
    }

    @Test
    void productMultiplierUsesDivisionRatherThanTrialDecrement() {
        assertEquals(Long.MAX_VALUE, BulkLongMath.maxMultiplierForProduct(0L, Long.MAX_VALUE));
        assertEquals(0L, BulkLongMath.maxMultiplierForProduct(1L, 0L));
        assertEquals(1L, BulkLongMath.maxMultiplierForProduct(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals((Long.MAX_VALUE - 1L) / 2L,
                BulkLongMath.maxMultiplierForProduct(2L, Long.MAX_VALUE - 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.maxMultiplierForProduct(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> BulkLongMath.maxMultiplierForProduct(1L, -1L));
    }

    @Test
    void accumulationMultiplierRespectsExistingExactAmount() {
        assertEquals(Long.MAX_VALUE, BulkLongMath.maxMultiplierForAccumulation(0L, 0L, Long.MAX_VALUE));
        assertEquals(0L, BulkLongMath.maxMultiplierForAccumulation(Long.MAX_VALUE, 1L, Long.MAX_VALUE));
        assertEquals(ABOVE_INTEGER_MAX,
                BulkLongMath.maxMultiplierForAccumulation(0L, 1L, ABOVE_INTEGER_MAX));
        assertEquals((Long.MAX_VALUE - 1L) / 2L,
                BulkLongMath.maxMultiplierForAccumulation(1L, 2L, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> BulkLongMath.maxMultiplierForAccumulation(2L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BulkLongMath.maxMultiplierForAccumulation(-1L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BulkLongMath.maxMultiplierForAccumulation(0L, -1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> BulkLongMath.maxMultiplierForAccumulation(0L, 1L, -1L));
    }

    @Test
    void multiplierBoundsHoldAcrossRepresentativePositiveQuantities() {
        long[] maximums = {0L, 1L, Integer.MAX_VALUE, ABOVE_INTEGER_MAX,
                Long.MAX_VALUE / 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE};
        long[] perOperationAmounts = {1L, 2L, 3L, Integer.MAX_VALUE, ABOVE_INTEGER_MAX,
                Long.MAX_VALUE / 2L, Long.MAX_VALUE};
        long[] currentAmounts = {0L, 1L, Integer.MAX_VALUE, ABOVE_INTEGER_MAX,
                Long.MAX_VALUE / 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE};

        for (long maximum : maximums) {
            for (long perOperationAmount : perOperationAmounts) {
                long multiplier = BulkLongMath.maxMultiplierForProduct(perOperationAmount, maximum);
                assertEquals(maximum / perOperationAmount, multiplier);
                assertEquals(multiplier * perOperationAmount,
                        BulkLongMath.checkedMultiply(multiplier, perOperationAmount));
            }
        }

        for (long maximum : maximums) {
            for (long current : currentAmounts) {
                if (current > maximum) {
                    continue;
                }
                for (long perOperationAmount : perOperationAmounts) {
                    long multiplier = BulkLongMath.maxMultiplierForAccumulation(
                            current, perOperationAmount, maximum);
                    assertEquals((maximum - current) / perOperationAmount, multiplier);
                    assertEquals(maximum - ((maximum - current) % perOperationAmount),
                            BulkLongMath.checkedAdd(current,
                                    BulkLongMath.checkedMultiply(multiplier, perOperationAmount)));
                }
            }
        }
    }

    @Test
    void saturationIsAvailableOnlyAsAnExplicitDisplayOperation() {
        assertEquals(Long.MAX_VALUE, BulkLongMath.saturatedAddForDisplay(Long.MAX_VALUE, 1L));
        assertEquals(Long.MIN_VALUE, BulkLongMath.saturatedAddForDisplay(Long.MIN_VALUE, -1L));
        assertEquals(Long.MAX_VALUE, BulkLongMath.saturatedMultiplyForDisplay(Long.MAX_VALUE, 2L));
        assertEquals(Long.MIN_VALUE, BulkLongMath.saturatedMultiplyForDisplay(Long.MIN_VALUE, 2L));
    }

}

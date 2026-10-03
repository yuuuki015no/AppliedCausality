package dev.appliedcausality.internal.bulk;

/**
 * Exact arithmetic for Bulk execution quantities.
 *
 * <p>Methods whose names begin with {@code checked} are suitable for execution
 * paths and throw {@link ArithmeticException} instead of wrapping. The two
 * saturation methods are restricted to display and monitoring values.</p>
 */
public final class BulkLongMath {
    private BulkLongMath() {
    }

    /**
     * Adds two execution quantities exactly.
     *
     * @throws IllegalArgumentException if either quantity is negative
     * @throws ArithmeticException        if the result is outside the {@code long} range
     */
    public static long checkedAdd(long left, long right) {
        requireNonNegative(left, "left");
        requireNonNegative(right, "right");
        return Math.addExact(left, right);
    }

    /**
     * Subtracts one execution quantity from another exactly.
     *
     * @throws IllegalArgumentException if either quantity is negative or the
     *                                  result would be negative
     */
    public static long checkedSubtract(long left, long right) {
        requireNonNegative(left, "left");
        requireNonNegative(right, "right");
        if (right > left) {
            throw new IllegalArgumentException("right must not exceed left");
        }
        return left - right;
    }

    /**
     * Multiplies two execution quantities exactly.
     *
     * @throws IllegalArgumentException if either quantity is negative
     * @throws ArithmeticException        if the result is outside the {@code long} range
     */
    public static long checkedMultiply(long left, long right) {
        requireNonNegative(left, "left");
        requireNonNegative(right, "right");
        return Math.multiplyExact(left, right);
    }

    /**
     * Returns the greatest non-negative multiplier whose product with
     * {@code perOperationAmount} does not exceed {@code maximumProduct}.
     *
     * <p>A zero per-operation amount does not constrain the multiplier and
     * therefore returns {@link Long#MAX_VALUE}.</p>
     *
     * @throws IllegalArgumentException if either argument is negative
     */
    public static long maxMultiplierForProduct(long perOperationAmount, long maximumProduct) {
        requireNonNegative(perOperationAmount, "perOperationAmount");
        requireNonNegative(maximumProduct, "maximumProduct");
        return perOperationAmount == 0 ? Long.MAX_VALUE : maximumProduct / perOperationAmount;
    }

    /**
     * Returns the greatest non-negative multiplier that can be added to an
     * existing total without exceeding its exact numeric limit.
     *
     * @throws IllegalArgumentException if an amount is negative or the current
     *                                  amount already exceeds the limit
     */
    public static long maxMultiplierForAccumulation(
            long currentAmount, long perOperationAmount, long maximumAmount) {
        requireNonNegative(currentAmount, "currentAmount");
        requireNonNegative(perOperationAmount, "perOperationAmount");
        requireNonNegative(maximumAmount, "maximumAmount");
        if (currentAmount > maximumAmount) {
            throw new IllegalArgumentException("currentAmount must not exceed maximumAmount");
        }
        return perOperationAmount == 0
                ? Long.MAX_VALUE
                : (maximumAmount - currentAmount) / perOperationAmount;
    }

    /**
     * Adds values for display or monitoring only, clamping on overflow.
     */
    public static long saturatedAddForDisplay(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return left < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return result;
    }

    /**
     * Multiplies values for display or monitoring only, clamping on overflow.
     */
    public static long saturatedMultiplyForDisplay(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return (left < 0) == (right < 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}

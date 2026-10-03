package dev.appliedcausality.internal.cpu;

import appeng.api.crafting.IPatternDetails;

/**
 * Narrow checked view of one AE2 crafting task.
 */
public interface IBulkTaskView {
    IPatternDetails pattern();

    long remaining();

    void decrement(long amount);
}

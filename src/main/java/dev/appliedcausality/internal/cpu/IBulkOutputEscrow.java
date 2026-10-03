package dev.appliedcausality.internal.cpu;

import java.util.List;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;

/**
 * Aggregated ownership of materialized Bulk outputs that have not yet reached
 * their final destination.
 */
public interface IBulkOutputEscrow {
    long get(AEKey key);

    boolean isEmpty();

    int distinctKeyCount();

    /**
     * Returns an immutable snapshot with one entry per distinct key.
     */
    List<GenericStack> entries();

    void add(AEKey key, long amount);

    void remove(AEKey key, long amount);

    CompoundTag writeToNBT();

    void readFromNBT(CompoundTag data);
}

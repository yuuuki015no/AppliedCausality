package dev.appliedcausality.internal.cpu;

import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Namespaced sidecar codec stored inside one AE2 executing-job compound.
 */
public final class BulkJobEscrowPersistence {
    public static final String JOB_DATA_KEY = "applied_causality";

    private BulkJobEscrowPersistence() {
    }

    public static BulkOutputEscrow read(CompoundTag jobData) {
        Objects.requireNonNull(jobData, "jobData");
        var result = new BulkOutputEscrow();
        if (jobData.contains(JOB_DATA_KEY)
                && !jobData.contains(JOB_DATA_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Bulk job sidecar is not a compound");
        }
        if (jobData.contains(JOB_DATA_KEY, Tag.TAG_COMPOUND)) {
            result.readFromNBT(jobData.getCompound(JOB_DATA_KEY));
        }
        return result;
    }

    public static void write(CompoundTag jobData, IBulkOutputEscrow escrow) {
        Objects.requireNonNull(jobData, "jobData");
        Objects.requireNonNull(escrow, "escrow");
        if (escrow.isEmpty()) {
            jobData.remove(JOB_DATA_KEY);
        } else {
            jobData.put(JOB_DATA_KEY, escrow.writeToNBT());
        }
    }
}

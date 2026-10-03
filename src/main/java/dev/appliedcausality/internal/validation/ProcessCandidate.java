package dev.appliedcausality.internal.validation;

import dev.appliedcausality.api.process.ResolvedProcess;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Internal producer metadata paired with one unverified adapter result.
 */
public record ProcessCandidate(ResourceLocation adapterId, int adapterPriority, ResolvedProcess process) {
    public ProcessCandidate {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(process, "process");
    }
}

package dev.appliedcausality.internal.provider;

import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.internal.validation.ProcessRejection;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/** Minimal per-occupied-slot diagnostic state; never persisted as authority. */
public record ProviderPatternStatus(
        int slot,
        ValidationDiagnostic diagnostic,
        @Nullable ResourceLocation recipeId,
        @Nullable VerifiedProcessModel model,
        @Nullable ProcessRejection rejection) {
    public ProviderPatternStatus(int slot, ValidationDiagnostic diagnostic, @Nullable ResourceLocation recipeId) {
        this(slot, diagnostic, recipeId, null, null);
    }

    public ProviderPatternStatus(int slot, ValidationDiagnostic diagnostic, @Nullable ResourceLocation recipeId,
            @Nullable VerifiedProcessModel model) {
        this(slot, diagnostic, recipeId, model, null);
    }

    public ProviderPatternStatus {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        Objects.requireNonNull(diagnostic, "diagnostic");
        if ((diagnostic == ValidationDiagnostic.VALID
                || diagnostic == ValidationDiagnostic.AVAILABLE
                || diagnostic == ValidationDiagnostic.LOCKED) != (recipeId != null)) {
            throw new IllegalArgumentException("only verified status may contain a recipe ID");
        }
        if (model != null && !model.recipeId().equals(recipeId)) {
            throw new IllegalArgumentException("verified model and recipe ID differ");
        }
        if (model != null && rejection != null) {
            throw new IllegalArgumentException("verified status cannot contain rejection evidence");
        }
    }

    /** Derived model identity is diagnostic detail, not a status transition. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ProviderPatternStatus status
                && slot == status.slot
                && diagnostic == status.diagnostic
                && Objects.equals(recipeId, status.recipeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slot, diagnostic, recipeId);
    }
}

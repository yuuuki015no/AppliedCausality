package dev.appliedcausality.internal.validation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Author-only evidence for one rejected recipe candidate; never grants execution. */
public record ProcessRejection(ResourceLocation adapterId, ResourceLocation recipeId, String reason) {
    public ProcessRejection {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 96) {
            throw new IllegalArgumentException("Invalid rejection reason");
        }
    }
}

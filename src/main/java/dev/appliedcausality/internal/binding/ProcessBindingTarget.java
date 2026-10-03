package dev.appliedcausality.internal.binding;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** A server-validated identity offered by the manual binding menu, never an executable model. */
public record ProcessBindingTarget(ResourceLocation adapterId, ResourceLocation recipeId) {
    /** Fixed upper bound for one menu-open snapshot; menu pagination presents the entries. */
    public static final int MAX_MENU_CHOICES = 32;

    public ProcessBindingTarget {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(recipeId, "recipeId");
    }
}

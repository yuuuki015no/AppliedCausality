package dev.appliedcausality.internal.registry;

import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Registration-time identity and priority paired with its adapter instance.
 *
 * <p>This package-private value is the producer identity used by internal
 * candidate discovery. It prevents later calls to mutable adapter metadata
 * from changing the identity associated with a resolved candidate.</p>
 */
record RegisteredProcessAdapter(
        IBulkRecipeAdapter adapter,
        ResourceLocation id,
        int priority) {
    RegisteredProcessAdapter {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(id, "id");
    }
}

package dev.appliedcausality.api.process;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Namespaced and extensible identity for a process input/output shape.
 */
public record ProcessShapeId(ResourceLocation id) {
    public ProcessShapeId {
        Objects.requireNonNull(id, "id");
    }

    public static ProcessShapeId of(String namespace, String path) {
        return new ProcessShapeId(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}

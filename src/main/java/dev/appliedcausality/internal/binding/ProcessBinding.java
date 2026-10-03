package dev.appliedcausality.internal.binding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/** Persisted provider-side identity for a manually selected process recipe. */
public record ProcessBinding(
        int slot,
        PatternFingerprint fingerprint,
        ResourceLocation adapterId,
        ResourceLocation recipeId) {
    private static final String SLOT_TAG = "slot";
    private static final String FINGERPRINT_TAG = "fingerprint";
    private static final String ADAPTER_ID_TAG = "adapterId";
    private static final String RECIPE_ID_TAG = "recipeId";

    public ProcessBinding {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(recipeId, "recipeId");
    }

    public CompoundTag save() {
        CompoundTag data = new CompoundTag();
        data.putInt(SLOT_TAG, slot);
        data.put(FINGERPRINT_TAG, fingerprint.save());
        data.putString(ADAPTER_ID_TAG, adapterId.toString());
        data.putString(RECIPE_ID_TAG, recipeId.toString());
        return data;
    }

    public static Optional<ProcessBinding> load(CompoundTag data) {
        Objects.requireNonNull(data, "data");
        if (!data.contains(SLOT_TAG, Tag.TAG_INT)
                || !data.contains(FINGERPRINT_TAG, Tag.TAG_COMPOUND)
                || !data.contains(ADAPTER_ID_TAG, Tag.TAG_STRING)
                || !data.contains(RECIPE_ID_TAG, Tag.TAG_STRING)) {
            return Optional.empty();
        }

        int slot = data.getInt(SLOT_TAG);
        var fingerprint = PatternFingerprint.load(data.getCompound(FINGERPRINT_TAG));
        ResourceLocation adapterId = ResourceLocation.tryParse(data.getString(ADAPTER_ID_TAG));
        ResourceLocation recipeId = ResourceLocation.tryParse(data.getString(RECIPE_ID_TAG));
        if (slot < 0 || fingerprint.isEmpty() || adapterId == null || recipeId == null) {
            return Optional.empty();
        }
        return Optional.of(new ProcessBinding(slot, fingerprint.orElseThrow(), adapterId, recipeId));
    }
}

package dev.appliedcausality.internal.binding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Versioned, fail-closed persistence container for one provider's bindings. */
public final class ProviderBindingStore {
    public static final int DATA_VERSION = 1;

    private static final String VERSION_TAG = "version";
    private static final String ENTRIES_TAG = "entries";

    private final int slotCount;
    private final Map<Integer, ProcessBinding> bindings = new TreeMap<>();

    private ProviderBindingStore(int slotCount) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("slot count must be non-negative");
        }
        this.slotCount = slotCount;
    }

    public static ProviderBindingStore empty(int slotCount) {
        return new ProviderBindingStore(slotCount);
    }

    public static ProviderBindingStore load(CompoundTag data, int slotCount) {
        ProviderBindingStore store = empty(slotCount);
        if (!data.contains(VERSION_TAG, Tag.TAG_INT)
                || data.getInt(VERSION_TAG) != DATA_VERSION
                || !data.contains(ENTRIES_TAG, Tag.TAG_LIST)) {
            return store;
        }

        ListTag entries = data.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        var duplicateSlots = new HashSet<Integer>();
        for (Tag entry : entries) {
            ProcessBinding.load((CompoundTag) entry).ifPresent(binding -> {
                if (binding.slot() >= slotCount || duplicateSlots.contains(binding.slot())) {
                    return;
                }
                if (store.bindings.putIfAbsent(binding.slot(), binding) != null) {
                    store.bindings.remove(binding.slot());
                    duplicateSlots.add(binding.slot());
                }
            });
        }
        return store;
    }

    public CompoundTag save() {
        CompoundTag data = new CompoundTag();
        data.putInt(VERSION_TAG, DATA_VERSION);
        ListTag entries = new ListTag();
        for (ProcessBinding binding : bindings.values()) {
            entries.add(binding.save());
        }
        data.put(ENTRIES_TAG, entries);
        return data;
    }

    public Optional<ProcessBinding> matching(int slot, PatternFingerprint fingerprint) {
        checkSlot(slot);
        ProcessBinding binding = bindings.get(slot);
        return binding != null && binding.fingerprint().equals(fingerprint)
                ? Optional.of(binding)
                : Optional.empty();
    }

    public void put(ProcessBinding binding) {
        checkSlot(binding.slot());
        bindings.put(binding.slot(), binding);
    }

    public boolean remove(int slot) {
        checkSlot(slot);
        return bindings.remove(slot) != null;
    }

    /** Removes a binding when the slot is empty or contains a replacement definition. */
    public boolean removeIfFingerprintMismatch(int slot, @Nullable PatternFingerprint currentFingerprint) {
        checkSlot(slot);
        ProcessBinding binding = bindings.get(slot);
        if (binding == null || binding.fingerprint().equals(currentFingerprint)) {
            return false;
        }
        bindings.remove(slot);
        return true;
    }

    public int size() {
        return bindings.size();
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside [0, " + slotCount + ")");
        }
    }
}

package dev.appliedcausality.internal.cpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Exact distinct-key representation of per-job Bulk Output Escrow.
 */
public final class BulkOutputEscrow implements IBulkOutputEscrow {
    public static final int DATA_VERSION = 1;

    private static final String NBT_DATA_VERSION = "dataVersion";
    private static final String NBT_OUTPUTS = "bulkOutputEscrow";

    private final Map<AEKey, Long> amounts = new LinkedHashMap<>();

    @Override
    public long get(AEKey key) {
        return amounts.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    @Override
    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    @Override
    public int distinctKeyCount() {
        return amounts.size();
    }

    @Override
    public List<GenericStack> entries() {
        var result = new ArrayList<GenericStack>(amounts.size());
        amounts.forEach((key, amount) -> result.add(new GenericStack(key, amount)));
        return List.copyOf(result);
    }

    @Override
    public void add(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        requireNonNegative(amount, "amount");
        if (amount == 0) {
            return;
        }
        amounts.put(key, BulkLongMath.checkedAdd(get(key), amount));
    }

    @Override
    public void remove(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        requireNonNegative(amount, "amount");
        if (amount == 0) {
            return;
        }

        long remaining = BulkLongMath.checkedSubtract(get(key), amount);
        if (remaining == 0) {
            amounts.remove(key);
        } else {
            amounts.put(key, remaining);
        }
    }

    @Override
    public CompoundTag writeToNBT() {
        var result = new CompoundTag();
        result.putInt(NBT_DATA_VERSION, DATA_VERSION);

        var outputs = new ListTag();
        for (var entry : amounts.entrySet()) {
            var output = entry.getKey().toTagGeneric();
            output.putLong("#", entry.getValue());
            outputs.add(output);
        }
        result.put(NBT_OUTPUTS, outputs);
        return result;
    }

    @Override
    public void readFromNBT(CompoundTag data) {
        Objects.requireNonNull(data, "data");
        // Future schema migrations belong here, before decoding any entries.
        if (!data.contains(NBT_DATA_VERSION, Tag.TAG_INT)
                || data.getInt(NBT_DATA_VERSION) != DATA_VERSION) {
            throw new IllegalArgumentException("Unsupported Bulk Output Escrow data version");
        }
        if (!(data.get(NBT_OUTPUTS) instanceof ListTag outputs)
                || (!outputs.isEmpty() && outputs.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Bulk Output Escrow output list is missing");
        }

        var decoded = new LinkedHashMap<AEKey, Long>();
        for (int index = 0; index < outputs.size(); index++) {
            var output = outputs.getCompound(index);
            var key = AEKey.fromTagGeneric(output);
            long amount = output.getLong("#");
            if (key == null || !output.contains("#", Tag.TAG_LONG) || amount <= 0) {
                throw new IllegalArgumentException("Invalid Bulk Output Escrow entry at index " + index);
            }
            decoded.put(key, BulkLongMath.checkedAdd(decoded.getOrDefault(key, 0L), amount));
        }

        amounts.clear();
        amounts.putAll(decoded);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}

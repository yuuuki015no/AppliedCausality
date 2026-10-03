package dev.appliedcausality.internal.provider;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongBiFunction;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** One committed operation, represented only by its undelivered output quantities. */
public final class FallbackTransaction {
    private final Map<AEKey, Long> pending = new LinkedHashMap<>();
    private long lastTick;
    private boolean delayAfterLoad;
    private boolean delivering;
    private boolean uncertain;
    private boolean removing;

    public boolean isBusy() {
        return !pending.isEmpty() || delivering || uncertain || removing;
    }

    public boolean isDelivering() {
        return delivering;
    }

    public boolean hasOutput() {
        return !pending.isEmpty();
    }

    /** All arithmetic and input checks precede the ownership transfer on success. */
    public boolean accept(VerifiedProcessModel model, KeyCounter[] inputs, long tick) {
        if (model == null || inputs == null || isBusy()) {
            return false;
        }
        var received = new LinkedHashMap<AEKey, Long>();
        var output = new LinkedHashMap<AEKey, Long>();
        var holders = Collections.newSetFromMap(new IdentityHashMap<KeyCounter, Boolean>());
        try {
            for (var holder : inputs) {
                if (holder == null || !holders.add(holder)) {
                    return false;
                }
                for (var entry : holder) {
                    long amount = entry.getLongValue();
                    if (amount < 0) {
                        return false;
                    }
                    if (amount > 0) {
                        received.merge(entry.getKey(), amount, BulkLongMath::checkedAdd);
                    }
                }
            }
            if (!received.equals(model.inputsPerOperation())) {
                return false;
            }
            output.putAll(model.outputsPerOperation());
            model.remainingItemsPerOperation().forEach(
                    (key, amount) -> output.merge(key, amount, BulkLongMath::checkedAdd));
        } catch (ArithmeticException exception) {
            return false;
        }
        pending.putAll(output);
        lastTick = tick;
        delayAfterLoad = false;
        // AE2 transfers ownership on true; its input counters are borrowed, not mutated.
        return true;
    }

    /** Never invokes the sink inside accept, twice in one tick, or recursively. */
    public void deliver(long tick, ToLongBiFunction<AEKey, Long> sink, Runnable changed) {
        if (delivering || uncertain || removing || pending.isEmpty()) {
            return;
        }
        if (delayAfterLoad) {
            delayAfterLoad = false;
            lastTick = tick;
            return;
        }
        if (tick <= lastTick) {
            return;
        }
        lastTick = tick;
        delivering = true;
        try {
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long offered = entry.getValue();
                // If a callback throws or saves recursively, the saved state must
                // not authorize blind retry of output with unknown ownership.
                uncertain = true;
                changed.run();
                long accepted = sink.applyAsLong(entry.getKey(), offered);
                if (accepted < 0 || accepted > offered) {
                    throw new IllegalStateException("Fallback insertion reported invalid acceptance: " + accepted);
                }
                if (accepted == offered) {
                    iterator.remove();
                } else {
                    entry.setValue(BulkLongMath.checkedSubtract(offered, accepted));
                }
                uncertain = false;
                changed.run();
                if (removing) {
                    break;
                }
            }
        } finally {
            delivering = false;
        }
    }

    /** Stop new work immediately; a current insertion must settle before removal. */
    public void beginRemoval() {
        removing = true;
    }

    /** Moves the exact remainder into a recovery carrier without expanding quantities. */
    public CompoundTag takeForRemoval() {
        if (delivering) {
            throw new IllegalStateException("Cannot transfer fallback output with unsettled acceptance");
        }
        // Unknown outcomes may move only as quarantined records. Their saved
        // uncertain flag prevents the recovery item from retrying execution.
        var result = save();
        pending.clear();
        return result;
    }

    public CompoundTag save() {
        var result = new CompoundTag();
        result.putInt("dataVersion", 1);
        result.putBoolean("uncertain", uncertain);
        var outputs = new ListTag();
        pending.forEach((key, amount) -> {
            var entry = key.toTagGeneric();
            entry.putLong("amount", amount);
            outputs.add(entry);
        });
        result.put("outputs", outputs);
        return result;
    }

    public static FallbackTransaction load(CompoundTag data) {
        // Future schema migrations belong here, before decoding owned output.
        if (!data.contains("dataVersion", Tag.TAG_INT) || data.getInt("dataVersion") != 1
                || !data.contains("uncertain", Tag.TAG_BYTE)
                || (data.getByte("uncertain") != 0 && data.getByte("uncertain") != 1)
                || !(data.get("outputs") instanceof ListTag outputs)
                || (!outputs.isEmpty() && outputs.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Unsupported or malformed fallback transaction");
        }
        var result = new FallbackTransaction();
        for (int i = 0; i < outputs.size(); i++) {
            var entry = outputs.getCompound(i);
            var key = AEKey.fromTagGeneric(entry);
            long amount = entry.getLong("amount");
            if (key == null || !entry.contains("amount", Tag.TAG_LONG) || amount <= 0
                    || result.pending.putIfAbsent(key, amount) != null) {
                throw new IllegalArgumentException("Invalid fallback output at index " + i);
            }
        }
        result.uncertain = data.getBoolean("uncertain");
        result.delayAfterLoad = true;
        return result;
    }
}

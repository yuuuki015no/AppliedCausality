package dev.appliedcausality.internal.cpu;

import java.util.Objects;

import appeng.api.crafting.IPatternDetails;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import dev.appliedcausality.mixin.ae2.TaskProgressAccessor;

/**
 * Runtime task view kept outside the Mixin package so transformed AE2 classes
 * may construct it normally.
 */
public final class Ae2TaskView implements IBulkTaskView {
    private final IPatternDetails pattern;
    private final Object progress;

    public Ae2TaskView(IPatternDetails pattern, Object progress) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    @Override
    public IPatternDetails pattern() {
        return pattern;
    }

    @Override
    public long remaining() {
        return accessor().bulk$getValue();
    }

    @Override
    public void decrement(long amount) {
        accessor().bulk$setValue(BulkLongMath.checkedSubtract(remaining(), amount));
    }

    private TaskProgressAccessor accessor() {
        return (TaskProgressAccessor) progress;
    }
}

package dev.appliedcausality.test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Minimal processing-pattern implementation for Core unit tests. The real AE2
 * base class supplies the provenance while the test controls normalized data.
 */
public final class TestProcessingPattern extends AEProcessingPattern {
    private IPatternDetails.IInput[] inputs;
    private GenericStack[] outputs;

    private TestProcessingPattern() {
        super((AEItemKey) null);
        throw new AssertionError("allocated without invoking the AEProcessingPattern constructor");
    }

    public static TestProcessingPattern create(IPatternDetails.IInput[] inputs, GenericStack[] outputs) {
        return create(null, inputs, outputs);
    }

    public static TestProcessingPattern create(
            AEItemKey definition,
            IPatternDetails.IInput[] inputs,
            GenericStack[] outputs) {
        var pattern = UnsafeAllocator.allocate(TestProcessingPattern.class);
        setDefinition(pattern, definition);
        pattern.inputs = Objects.requireNonNull(inputs, "inputs");
        pattern.outputs = Objects.requireNonNull(outputs, "outputs");
        return pattern;
    }

    @Override
    public IPatternDetails.IInput[] getInputs() {
        return inputs;
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs;
    }

    private static void setDefinition(TestProcessingPattern pattern, AEItemKey definition) {
        try {
            Field field = AEProcessingPattern.class.getDeclaredField("definition");
            field.setAccessible(true);
            field.set(pattern, definition);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to initialize test pattern definition", exception);
        }
    }
}

package dev.appliedcausality.internal.validation;

import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCandidateSelectorTest {
    @Test
    void noCandidatesFailsClosed() {
        var selection = RecipeCandidateSelector.select(List.of());

        assertEquals(ValidationDiagnostic.NO_RECIPE, selection.diagnostic());
        assertTrue(selection.candidate().isEmpty());
    }

    @Test
    void identicalSemanticsUseStablePriorityThenAdapterIdOrder() {
        var low = candidate("z_low", 0, process("one"));
        var samePriorityLater = candidate("z_same", 10, process("two"));
        var samePriorityEarlier = candidate("a_same", 10, process("three"));

        var selection = RecipeCandidateSelector.select(List.of(low, samePriorityLater, samePriorityEarlier));

        assertEquals(ValidationDiagnostic.VALID, selection.diagnostic());
        assertEquals(id("a_same"), selection.candidate().orElseThrow().adapterId());
    }

    @Test
    void differentNormalizedSemanticsAreAmbiguous() {
        var first = candidate("first", 10, process("first"));
        var second = candidate("second", 0, new ResolvedProcess(
                id("recipe/second"), id("smelting"),
                ProcessShapeId.of("applied_causality", "other_shape"), id("family"), null,
                Map.of(), Map.of(), Map.of(), properties()));

        var selection = RecipeCandidateSelector.select(List.of(first, second));

        assertEquals(ValidationDiagnostic.AMBIGUOUS_RECIPE, selection.diagnostic());
        assertTrue(selection.candidate().isEmpty());
    }

    private static ProcessCandidate candidate(String adapterPath, int priority, ResolvedProcess process) {
        return new ProcessCandidate(id(adapterPath), priority, process);
    }

    private static ResolvedProcess process(String recipePath) {
        return new ResolvedProcess(
                id("recipe/" + recipePath), id("smelting"),
                ProcessShapeId.of("applied_causality", "item_to_item"), id("family"), null,
                Map.of(), Map.of(), Map.of(), properties());
    }

    private static ProcessProperties properties() {
        return new ProcessProperties(true, true, true, true);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}

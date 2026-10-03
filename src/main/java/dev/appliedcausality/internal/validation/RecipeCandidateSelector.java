package dev.appliedcausality.internal.validation;

import dev.appliedcausality.api.process.ResolvedProcess;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Selects only semantically identical candidates, using frozen adapter metadata
 * as a deterministic tie-breaker.
 */
public final class RecipeCandidateSelector {
    private static final Comparator<ProcessCandidate> STABLE_ORDER = Comparator
            .comparingInt(ProcessCandidate::adapterPriority).reversed()
            .thenComparing(candidate -> candidate.adapterId().toString())
            .thenComparing(candidate -> candidate.process().recipeId().toString());

    private RecipeCandidateSelector() {
    }

    public static CandidateSelection select(Collection<ProcessCandidate> candidates) {
        List<ProcessCandidate> ordered = stableOrder(candidates);
        if (ordered.isEmpty()) {
            return CandidateSelection.rejected(ValidationDiagnostic.NO_RECIPE);
        }

        ResolvedProcess first = ordered.get(0).process();
        if (ordered.stream().skip(1).anyMatch(candidate -> !sameSemantics(first, candidate.process()))) {
            return CandidateSelection.rejected(ValidationDiagnostic.AMBIGUOUS_RECIPE);
        }
        return CandidateSelection.selected(ordered.get(0));
    }

    /**
     * Returns the frozen deterministic order used both for valid-candidate
     * selection and for choosing a concrete diagnostic when no candidate
     * passes Core validation.
     */
    public static List<ProcessCandidate> stableOrder(Collection<ProcessCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return candidates.stream()
                .peek(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .sorted(STABLE_ORDER)
                .toList();
    }

    private static boolean sameSemantics(ResolvedProcess left, ResolvedProcess right) {
        return left.recipeTypeId().equals(right.recipeTypeId())
                && left.processShape().equals(right.processShape())
                && left.processFamily().equals(right.processFamily())
                && Objects.equals(left.machineProfile(), right.machineProfile())
                && left.inputsPerOperation().equals(right.inputsPerOperation())
                && left.outputsPerOperation().equals(right.outputsPerOperation())
                && left.remainingItemsPerOperation().equals(right.remainingItemsPerOperation())
                && left.properties().equals(right.properties());
    }
}

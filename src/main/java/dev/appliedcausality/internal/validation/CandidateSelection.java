package dev.appliedcausality.internal.validation;

import java.util.Objects;
import java.util.Optional;

/**
 * The deterministic selection result before static Pattern validation.
 */
public record CandidateSelection(ValidationDiagnostic diagnostic, Optional<ProcessCandidate> candidate) {
    public CandidateSelection {
        Objects.requireNonNull(diagnostic, "diagnostic");
        candidate = Objects.requireNonNull(candidate, "candidate");
        if ((diagnostic == ValidationDiagnostic.VALID) != candidate.isPresent()) {
            throw new IllegalArgumentException("VALID selection must contain exactly one candidate");
        }
    }

    static CandidateSelection selected(ProcessCandidate candidate) {
        return new CandidateSelection(ValidationDiagnostic.VALID, Optional.of(candidate));
    }

    static CandidateSelection rejected(ValidationDiagnostic diagnostic) {
        return new CandidateSelection(diagnostic, Optional.empty());
    }
}

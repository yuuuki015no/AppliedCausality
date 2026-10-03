package dev.appliedcausality.internal.validation;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of mandatory static validation. Rejections never contain a model.
 */
public record ValidationResult(ValidationDiagnostic diagnostic, Optional<VerifiedProcessModel> model,
        Optional<ProcessRejection> rejection) {
    public ValidationResult(ValidationDiagnostic diagnostic, Optional<VerifiedProcessModel> model) {
        this(diagnostic, model, Optional.empty());
    }

    public ValidationResult {
        Objects.requireNonNull(diagnostic, "diagnostic");
        model = Objects.requireNonNull(model, "model");
        rejection = Objects.requireNonNull(rejection, "rejection");
        if ((diagnostic == ValidationDiagnostic.VALID) != model.isPresent()) {
            throw new IllegalArgumentException("VALID result must contain exactly one verified model");
        }
        if (model.isPresent() && rejection.isPresent()) {
            throw new IllegalArgumentException("Verified result cannot contain a rejection");
        }
    }

    static ValidationResult verified(VerifiedProcessModel model) {
        return new ValidationResult(ValidationDiagnostic.VALID, Optional.of(model));
    }

    static ValidationResult rejected(ValidationDiagnostic diagnostic) {
        return new ValidationResult(diagnostic, Optional.empty());
    }

    public ValidationResult withRejection(ProcessRejection evidence) {
        if (model.isPresent()) {
            throw new IllegalStateException("Verified result cannot contain rejection evidence");
        }
        return new ValidationResult(diagnostic, Optional.empty(), Optional.of(evidence));
    }
}

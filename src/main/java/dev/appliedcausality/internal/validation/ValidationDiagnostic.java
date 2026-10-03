package dev.appliedcausality.internal.validation;

/**
 * Stable, fail-closed outcomes for process candidate selection and validation.
 */
public enum ValidationDiagnostic {
    VALID,
    AVAILABLE,
    LOCKED,
    UNSUPPORTED_PATTERN,
    PATTERN_FINGERPRINT_MISMATCH,
    CONFLICTING_BINDING,
    NO_RECIPE,
    AMBIGUOUS_RECIPE,
    RECIPE_TYPE_MISMATCH,
    INPUT_MISMATCH,
    OUTPUT_MISMATCH,
    NON_DETERMINISTIC,
    SUBSTITUTION_UNSUPPORTED,
    REMAINING_ITEMS_UNSUPPORTED,
    MUTABLE_STATE_UNSUPPORTED,
    NUMERICALLY_UNSAFE;
}

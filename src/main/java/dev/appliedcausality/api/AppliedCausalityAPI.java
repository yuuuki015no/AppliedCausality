package dev.appliedcausality.api;

/**
 * Read-only public facade for Applied Causality API metadata.
 *
 * <p>Process adapter mutation is deliberately absent. Java adapters register
 * only through {@code RegisterProcessAdaptersEvent}.</p>
 */
public final class AppliedCausalityAPI {
    /**
     * Version of the experimental public Java API.
     */
    public static final int API_VERSION = 1;

    private AppliedCausalityAPI() {
    }
}

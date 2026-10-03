package dev.appliedcausality.internal.cpu;

/** Internal ownership boundary around stock CPU drop/destruction operations. */
public interface IBulkCpuLifecycle {
    /** Failed ownership remains saveable even when stock finishJob already removed its job. */
    boolean bulk$isQuarantined();

    /** False means the entire operation was deferred; its body must not run. */
    boolean bulk$beginTeardown(Object operation, Runnable continuation);

    void bulk$endTeardown();
}

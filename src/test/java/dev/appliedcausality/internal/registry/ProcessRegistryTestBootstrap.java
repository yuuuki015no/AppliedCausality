package dev.appliedcausality.internal.registry;

/** Test-source bridge for initializing the package-private production registry. */
public final class ProcessRegistryTestBootstrap {
    private ProcessRegistryTestBootstrap() {
    }

    public static void initializeBundledAdapters() {
        var registry = ProcessAdapterRegistrationLifecycle.registry();
        if (!registry.isFrozen()) {
            ProcessAdapterRegistrationLifecycle.registerBundledAdapters(registry);
            registry.freeze();
        }
    }
}

package dev.appliedcausality.internal.registry;

import dev.appliedcausality.api.process.RegisterProcessAdaptersEvent;
import dev.appliedcausality.internal.compat.MekanismCompatibility;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import dev.appliedcausality.recipe.vanilla.VanillaSmeltingRecipeAdapter;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Forge lifecycle wiring for the package-private mutable adapter registry.
 *
 * <p>The only public operation installs lifecycle wiring. It cannot register an
 * adapter or expose the mutable registry. Addons register exclusively while the
 * {@link RegisterProcessAdaptersEvent} is being delivered.</p>
 */
public final class ProcessAdapterRegistrationLifecycle {
    private static final BulkRecipeAdapterRegistry REGISTRY = new BulkRecipeAdapterRegistry();

    private ProcessAdapterRegistrationLifecycle() {
    }

    public static void install(IEventBus modEventBus) {
        Objects.requireNonNull(modEventBus, "modEventBus")
                .addListener(ProcessAdapterRegistrationLifecycle::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        enqueueRegistrationWork(
                event::enqueueWork,
                ProcessAdapterRegistrationLifecycle::postRegistrationEventAndFreeze);
    }

    private static void postRegistrationEventAndFreeze() {
        registerBundledAdapters(REGISTRY);
        dispatchAndFreeze(REGISTRY, event -> ModLoader.get().postEventWrapContainerInModOrder(event));
    }

    static void registerBundledAdapters(BulkRecipeAdapterRegistry registry) {
        Objects.requireNonNull(registry, "registry").register(new VanillaSmeltingRecipeAdapter());
        if (MekanismCompatibility.isLoadedAndCompatible()) {
            registry.register(MekanismRecipeAdapter.createBundled());
        }
    }

    /**
     * Package-private seam for testing the synchronous delivery/freeze contract.
     */
    static void dispatchAndFreeze(
            BulkRecipeAdapterRegistry registry,
            Consumer<RegisterProcessAdaptersEvent> dispatcher) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(dispatcher, "dispatcher");

        var event = new RegisterProcessAdaptersEvent(registry::register);
        dispatcher.accept(event);
        registry.freeze();
    }

    /**
     * Package-private seam for verifying that common setup defers registry
     * mutation to its enqueue-work phase.
     */
    static void enqueueRegistrationWork(
            Consumer<Runnable> workQueue,
            Runnable registrationWork) {
        Objects.requireNonNull(workQueue, "workQueue")
                .accept(Objects.requireNonNull(registrationWork, "registrationWork"));
    }

    /**
     * Package-private access for future candidate discovery in this package.
     */
    static BulkRecipeAdapterRegistry registry() {
        return REGISTRY;
    }
}

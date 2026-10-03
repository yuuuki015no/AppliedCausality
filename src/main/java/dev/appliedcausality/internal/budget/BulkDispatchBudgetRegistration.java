package dev.appliedcausality.internal.budget;

import java.util.Objects;
import java.util.function.Consumer;

import appeng.api.networking.GridServices;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Registers the per-grid service before AE2 can construct runtime grids. */
public final class BulkDispatchBudgetRegistration {
    private BulkDispatchBudgetRegistration() {
    }

    public static void install(IEventBus modEventBus) {
        Objects.requireNonNull(modEventBus, "modEventBus")
                .addListener(BulkDispatchBudgetRegistration::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        enqueueRegistrationWork(event::enqueueWork, BulkDispatchBudgetRegistration::register);
    }

    static void enqueueRegistrationWork(Consumer<Runnable> workQueue, Runnable registrationWork) {
        Objects.requireNonNull(workQueue, "workQueue")
                .accept(Objects.requireNonNull(registrationWork, "registrationWork"));
    }

    private static void register() {
        GridServices.register(IBulkDispatchBudget.class, BulkDispatchBudgetService.class);
    }
}

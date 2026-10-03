package dev.appliedcausality.api.process;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Mod-bus lifecycle event and the sole public Java registration path for
 * process adapters.
 *
 * <p>Applied Causality posts this event to every mod container during common
 * setup and freezes registration immediately after synchronous delivery.</p>
 */
public final class RegisterProcessAdaptersEvent extends Event implements IModBusEvent {
    private final Consumer<IBulkRecipeAdapter> registrar;

    /**
     * Framework constructor. Addons should receive this event from their mod bus
     * rather than construct it directly.
     */
    public RegisterProcessAdaptersEvent(Consumer<IBulkRecipeAdapter> registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    /**
     * Registers one adapter for the current lifecycle event.
     *
     * @throws IllegalStateException if registration has already been frozen
     */
    public void register(IBulkRecipeAdapter adapter) {
        registrar.accept(Objects.requireNonNull(adapter, "adapter"));
    }
}

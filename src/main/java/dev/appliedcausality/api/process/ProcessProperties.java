package dev.appliedcausality.api.process;

/**
 * Adapter-supplied claims about recipe semantics.
 *
 * <p>These flags are input to Core validation, not proof of execution safety.
 * An adapter remains responsible for interpreting its own mod's recipe API
 * correctly, while the Core validator independently enforces common rules.</p>
 *
 * @param deterministicOutputs          whether the recipe has no random or chance output
 * @param exactInputSemantics           whether concrete inputs can be matched exactly
 * @param remainingItemsFullyModeled    whether every catalyst/container remainder is represented
 * @param machineStateIndependent       whether the result needs no external machine state
 */
public record ProcessProperties(
        boolean deterministicOutputs,
        boolean exactInputSemantics,
        boolean remainingItemsFullyModeled,
        boolean machineStateIndependent) {
}

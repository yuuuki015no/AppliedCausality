package dev.appliedcausality.internal.provider;

public interface ReloadAwareProvider {
    /**
     * Clears all derived executable state before performing any operation that
     * can throw, such as requesting an external crafting-service refresh.
     */
    void invalidateForRecipeReload();

    /** Rebuilds only from the RecipeManager currently adopted by the server. */
    void rebuildAfterRecipeReload();
}

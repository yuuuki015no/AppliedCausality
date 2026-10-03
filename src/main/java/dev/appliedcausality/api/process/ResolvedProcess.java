package dev.appliedcausality.api.process;

import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * Public, unverified normalized recipe candidate produced by an adapter.
 *
 * <p>This type deliberately contains no adapter identity and grants no execution
 * authority. The internal registry tracks the producing adapter, and only the
 * internal validator can create an executable model.</p>
 */
public record ResolvedProcess(
        ResourceLocation recipeId,
        ResourceLocation recipeTypeId,
        ProcessShapeId processShape,
        ResourceLocation processFamily,
        @Nullable ResourceLocation machineProfile,
        Map<AEKey, Long> inputsPerOperation,
        Map<AEKey, Long> outputsPerOperation,
        Map<AEKey, Long> remainingItemsPerOperation,
        ProcessProperties properties) {

    public ResolvedProcess {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(processShape, "processShape");
        Objects.requireNonNull(processFamily, "processFamily");
        inputsPerOperation = immutableCopy(inputsPerOperation, "inputsPerOperation");
        outputsPerOperation = immutableCopy(outputsPerOperation, "outputsPerOperation");
        remainingItemsPerOperation = immutableCopy(
                remainingItemsPerOperation, "remainingItemsPerOperation");
        Objects.requireNonNull(properties, "properties");
    }

    /**
     * Source mod identity derived without storing duplicate state.
     */
    public String sourceModId() {
        return recipeId.getNamespace();
    }

    private static Map<AEKey, Long> immutableCopy(Map<AEKey, Long> values, String name) {
        return Map.copyOf(Objects.requireNonNull(values, name));
    }
}

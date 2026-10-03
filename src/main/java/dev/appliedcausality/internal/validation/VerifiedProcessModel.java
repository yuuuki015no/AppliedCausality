package dev.appliedcausality.internal.validation;

import appeng.api.stacks.AEKey;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable executable process representation created only by Core validation.
 *
 * <p>This is derived state. It deliberately has no public constructor or
 * factory, so third-party adapters can submit only {@code ResolvedProcess}
 * candidates.</p>
 */
public final class VerifiedProcessModel {
    private final ResourceLocation adapterId;
    private final ResourceLocation recipeId;
    private final ResourceLocation recipeTypeId;
    private final ProcessShapeId processShape;
    private final ResourceLocation processFamily;
    @Nullable
    private final ResourceLocation machineProfile;
    private final Map<AEKey, Long> inputsPerOperation;
    private final Map<AEKey, Long> outputsPerOperation;
    private final Map<AEKey, Long> remainingItemsPerOperation;
    private final ProcessProperties properties;

    private VerifiedProcessModel(
            ResourceLocation adapterId,
            ResourceLocation recipeId,
            ResourceLocation recipeTypeId,
            ProcessShapeId processShape,
            ResourceLocation processFamily,
            @Nullable ResourceLocation machineProfile,
            Map<AEKey, Long> inputsPerOperation,
            Map<AEKey, Long> outputsPerOperation,
            Map<AEKey, Long> remainingItemsPerOperation,
            ProcessProperties properties) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.recipeId = Objects.requireNonNull(recipeId, "recipeId");
        this.recipeTypeId = Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        this.processShape = Objects.requireNonNull(processShape, "processShape");
        this.processFamily = Objects.requireNonNull(processFamily, "processFamily");
        this.machineProfile = machineProfile;
        this.inputsPerOperation = Map.copyOf(Objects.requireNonNull(inputsPerOperation, "inputsPerOperation"));
        this.outputsPerOperation = Map.copyOf(Objects.requireNonNull(outputsPerOperation, "outputsPerOperation"));
        this.remainingItemsPerOperation = Map.copyOf(
                Objects.requireNonNull(remainingItemsPerOperation, "remainingItemsPerOperation"));
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    static VerifiedProcessModel validated(
            ResourceLocation adapterId,
            ResourceLocation recipeId,
            ResourceLocation recipeTypeId,
            ProcessShapeId processShape,
            ResourceLocation processFamily,
            @Nullable ResourceLocation machineProfile,
            Map<AEKey, Long> inputsPerOperation,
            Map<AEKey, Long> outputsPerOperation,
            Map<AEKey, Long> remainingItemsPerOperation,
            ProcessProperties properties) {
        return new VerifiedProcessModel(
                adapterId, recipeId, recipeTypeId, processShape, processFamily, machineProfile,
                inputsPerOperation, outputsPerOperation, remainingItemsPerOperation, properties);
    }

    public ResourceLocation adapterId() {
        return adapterId;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public ResourceLocation recipeTypeId() {
        return recipeTypeId;
    }

    public ProcessShapeId processShape() {
        return processShape;
    }

    public ResourceLocation processFamily() {
        return processFamily;
    }

    @Nullable
    public ResourceLocation machineProfile() {
        return machineProfile;
    }

    public Map<AEKey, Long> inputsPerOperation() {
        return inputsPerOperation;
    }

    public Map<AEKey, Long> outputsPerOperation() {
        return outputsPerOperation;
    }

    public Map<AEKey, Long> remainingItemsPerOperation() {
        return remainingItemsPerOperation;
    }

    public ProcessProperties properties() {
        return properties;
    }

    public String sourceModId() {
        return recipeId.getNamespace();
    }
}

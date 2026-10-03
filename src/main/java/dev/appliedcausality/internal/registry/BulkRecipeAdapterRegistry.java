package dev.appliedcausality.internal.registry;

import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Internal lifecycle-frozen index of process adapters by supported recipe type.
 */
final class BulkRecipeAdapterRegistry {
    private static final Comparator<Registration> REGISTRATION_ORDER = Comparator
            .comparingInt(Registration::priority)
            .reversed()
            .thenComparing(registration -> registration.id().getNamespace())
            .thenComparing(registration -> registration.id().getPath());

    private final Map<ResourceLocation, Registration> registrationsById = new LinkedHashMap<>();
    private final Map<RecipeType<?>, List<Registration>> mutableIndex = new IdentityHashMap<>();
    private final List<RecipeType<?>> mutableRecipeTypes = new ArrayList<>();

    private Map<RecipeType<?>, List<RegisteredProcessAdapter>> frozenIndex = Map.of();
    private Map<ResourceLocation, RegisteredProcessAdapter> frozenAdaptersById = Map.of();
    private List<RecipeType<?>> frozenRecipeTypes = List.of();
    private List<ResourceLocation> frozenAdapterIds = List.of();
    private boolean frozen;

    BulkRecipeAdapterRegistry() {
    }

    /**
     * Registers and snapshots one adapter's identity, priority, and recipe types.
     */
    synchronized void register(IBulkRecipeAdapter adapter) {
        ensureMutable();
        Objects.requireNonNull(adapter, "adapter");

        ResourceLocation id = Objects.requireNonNull(adapter.id(), "adapter.id()");
        if (id.equals(ConfiguredStandardStaticItemResolver.ID)
                || ProcessModelResolver.legacyMekanismId(id)) {
            throw new IllegalArgumentException("Reserved process adapter ID: " + id);
        }
        if (registrationsById.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate process adapter ID: " + id);
        }

        Collection<RecipeType<?>> declaredTypes = Objects.requireNonNull(
                adapter.supportedRecipeTypes(), "adapter.supportedRecipeTypes()");
        Set<RecipeType<?>> seenTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RecipeType<?>> supportedTypes = new ArrayList<>(declaredTypes.size());
        for (RecipeType<?> recipeType : declaredTypes) {
            Objects.requireNonNull(recipeType, "supported recipe type");
            if (seenTypes.add(recipeType)) {
                supportedTypes.add(recipeType);
            }
        }
        if (supportedTypes.isEmpty()) {
            throw new IllegalArgumentException("Process adapter must support at least one recipe type: " + id);
        }

        var registration = new Registration(adapter, id, adapter.priority(), List.copyOf(supportedTypes));
        registrationsById.put(id, registration);
        for (RecipeType<?> recipeType : registration.supportedTypes()) {
            List<Registration> registrations = mutableIndex.get(recipeType);
            if (registrations == null) {
                registrations = new ArrayList<>();
                mutableIndex.put(recipeType, registrations);
                mutableRecipeTypes.add(recipeType);
            }
            registrations.add(registration);
        }
    }

    /**
     * Sorts and freezes the RecipeType index. This operation is idempotent.
     */
    synchronized void freeze() {
        if (frozen) {
            return;
        }

        Map<ResourceLocation, RegisteredProcessAdapter> adaptersById = new LinkedHashMap<>();
        for (Registration registration : registrationsById.values()) {
            adaptersById.put(registration.id(), registration.registeredAdapter());
        }

        Map<RecipeType<?>, List<RegisteredProcessAdapter>> index = new IdentityHashMap<>();
        for (var entry : mutableIndex.entrySet()) {
            List<RegisteredProcessAdapter> adapters = entry.getValue().stream()
                    .sorted(REGISTRATION_ORDER)
                    .map(registration -> adaptersById.get(registration.id()))
                    .toList();
            index.put(entry.getKey(), adapters);
        }
        frozenIndex = Collections.unmodifiableMap(index);
        frozenAdaptersById = Collections.unmodifiableMap(adaptersById);
        frozenRecipeTypes = List.copyOf(mutableRecipeTypes);
        frozenAdapterIds = registrationsById.values().stream()
                .sorted(REGISTRATION_ORDER)
                .map(Registration::id)
                .toList();
        frozen = true;
        mutableIndex.clear();
        mutableRecipeTypes.clear();
    }

    /**
     * Returns adapters for exactly one recipe type without scanning other types.
     */
    synchronized List<RegisteredProcessAdapter> adaptersFor(RecipeType<?> recipeType) {
        Objects.requireNonNull(recipeType, "recipeType");
        ensureFrozen();
        return frozenIndex.getOrDefault(recipeType, List.of());
    }

    /**
     * Returns only recipe types that have registered adapters, in stable
     * first-registration order.
     */
    synchronized List<RecipeType<?>> indexedRecipeTypes() {
        ensureFrozen();
        return frozenRecipeTypes;
    }

    synchronized boolean isFrozen() {
        return frozen;
    }

    /**
     * Returns registered IDs in the same stable order used within indexes.
     */
    synchronized List<ResourceLocation> registeredAdapterIds() {
        if (frozen) {
            return frozenAdapterIds;
        }
        return registrationsById.values().stream()
                .sorted(REGISTRATION_ORDER)
                .map(Registration::id)
                .toList();
    }

    synchronized Optional<RegisteredProcessAdapter> adapterById(ResourceLocation adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        ensureFrozen();
        return Optional.ofNullable(frozenAdaptersById.get(adapterId));
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("Process adapter registry is frozen");
        }
    }

    private void ensureFrozen() {
        if (!frozen) {
            throw new IllegalStateException("Process adapter registry is not frozen");
        }
    }

    private record Registration(
            IBulkRecipeAdapter adapter,
            ResourceLocation id,
            int priority,
            List<RecipeType<?>> supportedTypes) {
        private RegisteredProcessAdapter registeredAdapter() {
            return new RegisteredProcessAdapter(adapter, id, priority);
        }
    }
}

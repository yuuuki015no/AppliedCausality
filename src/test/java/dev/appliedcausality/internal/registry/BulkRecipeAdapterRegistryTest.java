package dev.appliedcausality.internal.registry;

import dev.appliedcausality.api.AppliedCausalityAPI;
import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkRecipeAdapterRegistryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void eventRegistrationBuildsRecipeTypeIndexInStableOrder() {
        var smelting = recipeType("smelting");
        var otherType = recipeType("other");
        var registry = new BulkRecipeAdapterRegistry();

        var low = adapter("z_low", 0, List.of(smelting));
        var samePriorityLaterId = adapter("z_same", 10, List.of(smelting));
        var samePriorityEarlierId = adapter("a_same", 10, List.of(smelting));
        ProcessAdapterRegistrationLifecycle.dispatchAndFreeze(registry, event -> {
            assertFalse(registry.isFrozen());
            event.register(low);
            event.register(samePriorityLaterId);
            event.register(samePriorityEarlierId);
            assertFalse(registry.isFrozen());
        });

        assertTrue(registry.isFrozen());
        assertEquals(List.of(samePriorityEarlierId, samePriorityLaterId, low),
                registry.adaptersFor(smelting).stream()
                        .map(RegisteredProcessAdapter::adapter)
                        .toList());
        assertTrue(registry.adaptersFor(otherType).isEmpty());
        assertEquals(List.of(id("test", "a_same"), id("test", "z_same"), id("test", "z_low")),
                registry.registeredAdapterIds());
    }

    @Test
    void oneExternalEventAdapterIsIndexedOnlyForEachDeclaredType() {
        var first = recipeType("first");
        var second = recipeType("second");
        var other = recipeType("other");
        var adapter = adapter("grouped", 3, List.of(first, second));
        var registry = new BulkRecipeAdapterRegistry();
        ProcessAdapterRegistrationLifecycle.dispatchAndFreeze(registry,
                event -> event.register(adapter));

        assertEquals(List.of(id("test", "grouped")), registry.registeredAdapterIds());
        assertEquals(List.of(adapter), registry.adaptersFor(first).stream()
                .map(RegisteredProcessAdapter::adapter).toList());
        assertEquals(List.of(adapter), registry.adaptersFor(second).stream()
                .map(RegisteredProcessAdapter::adapter).toList());
        assertTrue(registry.adaptersFor(other).isEmpty());
    }

    @Test
    void registrationSnapshotsSupportedTypesAndDeduplicatesIdentity() {
        var firstType = recipeType("first");
        var laterType = recipeType("later");
        var mutableTypes = new ArrayList<RecipeType<?>>();
        mutableTypes.add(firstType);
        mutableTypes.add(firstType);
        var registry = new BulkRecipeAdapterRegistry();
        var adapter = new MutableTestAdapter(id("test", "mutable"), 7, mutableTypes);

        registry.register(adapter);
        adapter.id = id("test", "changed_after_registration");
        adapter.priority = 99;
        mutableTypes.clear();
        mutableTypes.add(laterType);
        registry.freeze();

        var registered = registry.adaptersFor(firstType).get(0);
        assertSame(adapter, registered.adapter());
        assertEquals(id("test", "mutable"), registered.id());
        assertEquals(7, registered.priority());
        assertTrue(registry.adaptersFor(laterType).isEmpty());
    }

    @Test
    void freezeRejectsLateOrDuplicateRegistration() {
        var type = recipeType("type");
        var registry = new BulkRecipeAdapterRegistry();
        var first = adapter("duplicate", 0, List.of(type));
        registry.register(first);

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("duplicate", 1, List.of(type))));

        registry.freeze();

        assertThrows(IllegalStateException.class,
                () -> registry.register(adapter("late", 0, List.of(type))));
    }

    @Test
    void internalConfiguredAndLegacyMigrationIdsCannotBeRegisteredByAddons() {
        var registry = new BulkRecipeAdapterRegistry();
        var type = recipeType("reserved");
        for (String path : List.of("configured_standard_static_item", "mekanism_enriching",
                "mekanism_crushing", "mekanism_energized_smelting")) {
            assertThrows(IllegalArgumentException.class, () -> registry.register(
                    new TestAdapter(id("applied_causality", path), 0, List.of(type))));
        }
    }

    @Test
    void adaptersMustDeclareAtLeastOneNonNullRecipeType() {
        var registry = new BulkRecipeAdapterRegistry();
        var typesWithNull = new ArrayList<RecipeType<?>>();
        typesWithNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("empty", 0, List.of())));
        assertThrows(NullPointerException.class,
                () -> registry.register(adapter("null_type", 0, typesWithNull)));
    }

    @Test
    void failedEventDeliveryDoesNotFreezeRegistry() {
        var registry = new BulkRecipeAdapterRegistry();

        assertThrows(IllegalStateException.class,
                () -> ProcessAdapterRegistrationLifecycle.dispatchAndFreeze(
                        registry,
                        event -> {
                            throw new IllegalStateException("listener failed");
                        }));

        assertFalse(registry.isFrozen());
    }

    @Test
    void commonSetupDefersEventDeliveryAndFreezeUntilQueuedWorkRuns() {
        var type = recipeType("deferred");
        var adapter = adapter("deferred", 0, List.of(type));
        var registry = new BulkRecipeAdapterRegistry();
        var queuedWork = new ArrayList<Runnable>();

        ProcessAdapterRegistrationLifecycle.enqueueRegistrationWork(
                queuedWork::add,
                () -> ProcessAdapterRegistrationLifecycle.dispatchAndFreeze(
                        registry,
                        event -> event.register(adapter)));

        assertFalse(registry.isFrozen());
        assertTrue(registry.registeredAdapterIds().isEmpty());
        assertEquals(1, queuedWork.size());

        queuedWork.get(0).run();

        assertTrue(registry.isFrozen());
        assertEquals(id("test", "deferred"), registry.adaptersFor(type).get(0).id());
    }

    @Test
    void lifecycleRegistersVanillaSmeltingAsABundledAdapter() {
        var registry = new BulkRecipeAdapterRegistry();

        ProcessAdapterRegistrationLifecycle.registerBundledAdapters(registry);
        registry.freeze();

        assertEquals(List.of(id("applied_causality", "vanilla_smelting")),
                registry.registeredAdapterIds());
        assertEquals(1, registry.adaptersFor(RecipeType.SMELTING).size());
        assertEquals(RecipeType.SMELTING, registry.indexedRecipeTypes().get(0));
    }

    @Test
    void mutableRegistryOperationsAreNotPartOfThePublicJavaApi() throws NoSuchMethodException {
        assertFalse(Modifier.isPublic(BulkRecipeAdapterRegistry.class.getModifiers()));
        assertFalse(Modifier.isPublic(RegisteredProcessAdapter.class.getModifiers()));
        assertFalse(Modifier.isPublic(BulkRecipeAdapterRegistry.class
                .getDeclaredMethod("register", IBulkRecipeAdapter.class)
                .getModifiers()));
        assertFalse(Modifier.isPublic(BulkRecipeAdapterRegistry.class
                .getDeclaredMethod("freeze")
                .getModifiers()));
        assertTrue(List.of(AppliedCausalityAPI.class.getDeclaredMethods()).stream()
                .noneMatch(method -> method.getName().toLowerCase().contains("register")));
    }

    private static IBulkRecipeAdapter adapter(
            String path, int priority, Collection<RecipeType<?>> supportedTypes) {
        return new TestAdapter(id("test", path), priority, supportedTypes);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static RecipeType<Recipe<?>> recipeType(String name) {
        return new TestRecipeType(name);
    }

    private record TestAdapter(
            ResourceLocation id,
            int priority,
            Collection<RecipeType<?>> supportedRecipeTypes) implements IBulkRecipeAdapter {
        @Override
        public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
            return Optional.empty();
        }
    }

    private static final class MutableTestAdapter implements IBulkRecipeAdapter {
        private ResourceLocation id;
        private int priority;
        private final Collection<RecipeType<?>> supportedRecipeTypes;

        private MutableTestAdapter(
                ResourceLocation id,
                int priority,
                Collection<RecipeType<?>> supportedRecipeTypes) {
            this.id = id;
            this.priority = priority;
            this.supportedRecipeTypes = supportedRecipeTypes;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Collection<RecipeType<?>> supportedRecipeTypes() {
            return supportedRecipeTypes;
        }

        @Override
        public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
            return Optional.empty();
        }
    }

    private record TestRecipeType(String name) implements RecipeType<Recipe<?>> {
        @Override
        public String toString() {
            return name;
        }
    }
}

package dev.appliedcausality.api.process;

import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessApiTest {
    @Test
    void processShapeRetainsExtensibleNamespacedIdentity() {
        var shape = ProcessShapeId.of("examplemod", "item_heat_to_fluid");

        assertEquals("examplemod:item_heat_to_fluid", shape.toString());
    }

    @Test
    void resolvedProcessIsAnImmutableCandidateWithoutAdapterIdentity() {
        Map<AEKey, Long> mutableInputs = new HashMap<>();
        var process = new ResolvedProcess(
                id("examplemod", "smelting/example"),
                id("minecraft", "smelting"),
                ProcessShapeId.of("applied_causality", "item_to_item"),
                id("minecraft", "item_processing"),
                null,
                mutableInputs,
                Map.of(),
                Map.of(),
                new ProcessProperties(true, true, true, true));

        mutableInputs.put(null, 1L);

        assertTrue(process.inputsPerOperation().isEmpty());
        assertEquals("examplemod", process.sourceModId());
        assertThrows(UnsupportedOperationException.class,
                () -> process.inputsPerOperation().put(null, 1L));
    }

    @Test
    void resolvedProcessRejectsMissingRequiredMetadata() {
        assertThrows(NullPointerException.class, () -> new ResolvedProcess(
                null,
                id("minecraft", "smelting"),
                ProcessShapeId.of("applied_causality", "item_to_item"),
                id("minecraft", "item_processing"),
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                new ProcessProperties(true, true, true, true)));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}

package dev.appliedcausality.internal.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleDiagnosticsTest {
    @Test
    void entryAndTotalLimitsUseOneDeterministicMarker() {
        var diagnostics = new RuleDiagnostics();
        for (int index = 0; index < 400; index++) {
            diagnostics.add("file" + index + ".toml", "x".repeat(400));
        }
        assertTrue(diagnostics.entries().size() <= 256);
        assertTrue(diagnostics.utf8Bytes() <= 32 * 1024);
        assertEquals("Rule diagnostics truncated", diagnostics.entries().get(diagnostics.entries().size() - 1));
        assertEquals(1, diagnostics.entries().stream()
                .filter("Rule diagnostics truncated"::equals).count());
        assertTrue(diagnostics.entries().stream()
                .allMatch(entry -> entry.getBytes(StandardCharsets.UTF_8).length <= 512));
    }
}

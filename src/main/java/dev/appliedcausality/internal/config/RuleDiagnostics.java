package dev.appliedcausality.internal.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded, path-safe diagnostics for an untrusted rule directory. */
public final class RuleDiagnostics {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_ENTRY_BYTES = 512;
    private static final int MAX_TOTAL_BYTES = 32 * 1024;
    private static final String TRUNCATED = "Rule diagnostics truncated";

    private final List<String> entries = new ArrayList<>();
    private int bytes;
    private boolean truncated;

    public void add(String relativeName, String reason) {
        if (truncated) {
            return;
        }
        String line = relativeName + ": " + reason;
        int size = line.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_ENTRY_BYTES || entries.size() >= MAX_ENTRIES - 1
                || bytes + size + TRUNCATED.length() > MAX_TOTAL_BYTES) {
            truncate();
            return;
        }
        entries.add(line);
        bytes += size;
    }

    private void truncate() {
        if (!truncated) {
            truncated = true;
            entries.add(TRUNCATED);
            bytes += TRUNCATED.length();
        }
    }

    public boolean hasErrors() {
        return !entries.isEmpty();
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    public int utf8Bytes() {
        return bytes;
    }
}

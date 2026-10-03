package dev.appliedcausality.internal.config;

import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import dev.appliedcausality.internal.provider.ReloadAwareProvider;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfiguredRuleServiceTest {
    @Test
    void adoptionPublishesBeforeInvalidationAndNeverRollsBack() {
        var session = new ConfiguredRuleService.Session();
        var sequence = new ArrayList<Long>();
        assertThrows(IllegalStateException.class, () -> session.adopt(emptyCandidate(), () -> {
            sequence.add(session.snapshot().generation());
            throw new IllegalStateException("invalidation failed");
        }));
        assertEquals(List.of(1L), sequence);
        assertEquals(1L, session.snapshot().generation());
        session.adopt(emptyCandidate(), () -> sequence.add(session.snapshot().generation()));
        assertEquals(List.of(1L, 2L), sequence);
    }

    @Test
    void generationExhaustionDoesNotPublishOrInvalidate() throws ReflectiveOperationException {
        var session = new ConfiguredRuleService.Session();
        Field field = session.getClass().getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(session, new ConfiguredRuleSnapshot(Long.MAX_VALUE, Map.of(), Map.of(), Set.of(), Map.of()));
        var calls = new ArrayList<String>();
        assertThrows(ArithmeticException.class,
                () -> session.adopt(emptyCandidate(), () -> calls.add("invalidate")));
        assertEquals(Long.MAX_VALUE, session.snapshot().generation());
        assertEquals(List.of(), calls);
    }

    @Test
    void generationExhaustionAfterResourceInstallRetainsRulesAndRefreshesModels()
            throws ReflectiveOperationException {
        var session = new ConfiguredRuleService.Session();
        var active = new ConfiguredRuleSnapshot(Long.MAX_VALUE, Map.of(), Map.of(), Set.of(), Map.of());
        Field field = session.getClass().getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(session, active);
        var provider = new ProbeProvider();
        ProviderReloadCoordinator.register(provider);
        try {
            ConfiguredRuleService.reloadSessionAfterResourceInstall(session,
                    ConfiguredRuleServiceTest::validCandidate,
                    ProviderReloadCoordinator::refreshAfterSuccessfulReload);

            assertSame(active, session.snapshot(), "generation exhaustion must not publish the candidate");
            assertEquals(List.of("invalidate", "rebuild"), provider.lifecycle);
            assertEquals("new-manager-model", provider.visibleModel);
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
    }

    @Test
    void invalidInitialCandidateFailsBeforeAnySnapshotCanBePublished() {
        var sessions = new ConfiguredRuleService.ServerSessions<Object>();
        Object server = new Object();
        var invalid = new ConfiguredRuleLoader.LoadResult(null, List.of("bad.toml: Invalid TOML"));
        assertThrows(IllegalStateException.class,
                () -> ConfiguredRuleService.startSession(sessions, server, () -> invalid, () -> {}));
        assertNull(sessions.get(server));
    }

    @Test
    void failedInitialProviderRefreshDoesNotLeakServerSession() {
        var sessions = new ConfiguredRuleService.ServerSessions<Object>();
        Object server = new Object();
        assertThrows(IllegalStateException.class,
                () -> ConfiguredRuleService.startSession(sessions, server,
                        ConfiguredRuleServiceTest::validCandidate,
                        () -> { throw new IllegalStateException("injected refresh failure"); }));
        assertNull(sessions.get(server));
    }

    @Test
    void stoppingOneServerCannotExposeItsSnapshotToAnotherServer() {
        var sessions = new ConfiguredRuleService.ServerSessions<Object>();
        Object firstServer = new String("same-logical-server-name");
        Object secondServer = new String("same-logical-server-name");
        assertEquals(firstServer, secondServer);
        var firstCandidate = new ConfiguredRuleLoader.LoadResult(
                new ConfiguredRuleSnapshot(0, Map.of(), Map.of(),
                        Set.of(ResourceLocation.fromNamespaceAndPath("test", "old_grant")), Map.of()),
                List.of());
        ConfiguredRuleService.startSession(sessions, firstServer, () -> firstCandidate, () -> {});
        assertEquals(1L, sessions.get(firstServer).snapshot().generation());
        assertEquals(1, sessions.get(firstServer).snapshot().grants().size());
        sessions.remove(firstServer);
        ConfiguredRuleService.startSession(sessions, secondServer, ConfiguredRuleServiceTest::validCandidate,
                () -> {});
        var later = sessions.get(secondServer);
        assertEquals(1L, later.snapshot().generation());
        assertEquals(Set.of(), later.snapshot().grants());
        assertNull(sessions.get(firstServer));
        assertSame(later, sessions.get(secondServer));
    }

    private static ConfiguredRuleSnapshot emptyCandidate() {
        return ConfiguredRuleSnapshot.empty();
    }

    private static ConfiguredRuleLoader.LoadResult validCandidate() {
        return new ConfiguredRuleLoader.LoadResult(emptyCandidate(), List.of());
    }

    private static final class ProbeProvider implements ReloadAwareProvider {
        private final List<String> lifecycle = new ArrayList<>();
        private String visibleModel = "old-manager-model";

        @Override
        public void invalidateForRecipeReload() {
            visibleModel = null;
            lifecycle.add("invalidate");
        }

        @Override
        public void rebuildAfterRecipeReload() {
            visibleModel = "new-manager-model";
            lifecycle.add("rebuild");
        }
    }
}

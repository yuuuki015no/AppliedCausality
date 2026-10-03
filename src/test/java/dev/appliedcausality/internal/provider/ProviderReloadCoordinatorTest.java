package dev.appliedcausality.internal.provider;

import net.minecraftforge.event.TagsUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderReloadCoordinatorTest {
    @Test
    void successfulResourceInstallInvalidatesThenRebuildsProviders() {
        var provider = new TestProvider();
        ProviderReloadCoordinator.register(provider);
        try {
            ProviderReloadCoordinator.refreshForUpdateCause(
                    TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD);

            assertEquals(1, provider.invalidations);
            assertEquals(1, provider.rebuilds);
            assertEquals(List.of("invalidate", "rebuild"), provider.lifecycle);
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
    }

    @Test
    void clientTagPacketDoesNotRevalidateServerProviders() {
        var provider = new TestProvider();
        ProviderReloadCoordinator.register(provider);
        try {
            ProviderReloadCoordinator.refreshForUpdateCause(
                    TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED);

            assertEquals(0, provider.invalidations);
            assertEquals(0, provider.rebuilds);
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
    }

    @Test
    void rebuildFailureCannotLeaveOldModelsInLaterProviders() {
        var lifecycle = new ArrayList<String>();
        var first = new TestProvider("first", lifecycle, false);
        var failing = new TestProvider("failing", lifecycle, true);
        var last = new TestProvider("last", lifecycle, false);
        ProviderReloadCoordinator.register(first);
        ProviderReloadCoordinator.register(failing);
        ProviderReloadCoordinator.register(last);
        try {
            assertThrows(IllegalStateException.class,
                    ProviderReloadCoordinator::refreshAfterSuccessfulReload);

            assertEquals(List.of(
                    "invalidate:first",
                    "invalidate:failing",
                    "invalidate:last",
                    "rebuild:first",
                    "rebuild:failing",
                    "rebuild:last"), lifecycle);
            assertEquals("new", first.visibleModel);
            assertNull(failing.visibleModel);
            assertEquals("new", last.visibleModel);
            assertEquals(1, last.invalidations);
            assertEquals(1, last.rebuilds);
        } finally {
            ProviderReloadCoordinator.unregister(first);
            ProviderReloadCoordinator.unregister(failing);
            ProviderReloadCoordinator.unregister(last);
        }
    }

    @Test
    void invalidationFailureStillClearsEveryProviderAndPreventsAllRebuilds() {
        var lifecycle = new ArrayList<String>();
        var first = new TestProvider("first", lifecycle, false, false);
        var failing = new TestProvider("failing", lifecycle, true, false);
        var last = new TestProvider("last", lifecycle, false, false);
        ProviderReloadCoordinator.register(first);
        ProviderReloadCoordinator.register(failing);
        ProviderReloadCoordinator.register(last);
        try {
            assertThrows(IllegalStateException.class,
                    ProviderReloadCoordinator::refreshAfterSuccessfulReload);

            assertEquals(List.of(
                    "invalidate:first",
                    "invalidate:failing",
                    "invalidate:last"), lifecycle);
            assertNull(first.visibleModel);
            assertNull(failing.visibleModel);
            assertNull(last.visibleModel);
            assertEquals(0, first.rebuilds);
            assertEquals(0, failing.rebuilds);
            assertEquals(0, last.rebuilds);
        } finally {
            ProviderReloadCoordinator.unregister(first);
            ProviderReloadCoordinator.unregister(failing);
            ProviderReloadCoordinator.unregister(last);
        }
    }

    private static final class TestProvider implements ReloadAwareProvider {
        private final String name;
        private final List<String> lifecycle;
        private final boolean failInvalidation;
        private final boolean failRebuild;
        private int invalidations;
        private int rebuilds;
        private String visibleModel = "old";

        private TestProvider() {
            this("provider", new ArrayList<>(), false, false);
        }

        private TestProvider(String name, List<String> lifecycle, boolean failRebuild) {
            this(name, lifecycle, false, failRebuild);
        }

        private TestProvider(
                String name,
                List<String> lifecycle,
                boolean failInvalidation,
                boolean failRebuild) {
            this.name = name;
            this.lifecycle = lifecycle;
            this.failInvalidation = failInvalidation;
            this.failRebuild = failRebuild;
        }

        @Override
        public void invalidateForRecipeReload() {
            invalidations++;
            visibleModel = null;
            lifecycle.add("invalidate" + lifecycleSuffix());
            if (failInvalidation) {
                throw new IllegalStateException("injected invalidation failure");
            }
        }

        @Override
        public void rebuildAfterRecipeReload() {
            rebuilds++;
            lifecycle.add("rebuild" + lifecycleSuffix());
            if (failRebuild) {
                throw new IllegalStateException("injected rebuild failure");
            }
            visibleModel = "new";
        }

        private String lifecycleSuffix() {
            return "provider".equals(name) ? "" : ":" + name;
        }
    }
}

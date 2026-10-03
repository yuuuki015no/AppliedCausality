package dev.appliedcausality.internal.provider;

import dev.appliedcausality.internal.config.ConfiguredRuleService;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rebuilds provider models only after a successful server resource install.
 * Failed reloads never replace the authoritative RecipeManager, so the current
 * cache remains valid and available.
 */
public final class ProviderReloadCoordinator {
    private static final Set<ReloadAwareProvider> PROVIDERS = new LinkedHashSet<>();

    private ProviderReloadCoordinator() {
    }

    public static void install(IEventBus forgeEventBus) {
        forgeEventBus.addListener(ProviderReloadCoordinator::onTagsUpdated);
        forgeEventBus.addListener(ProviderReloadCoordinator::onServerStopped);
    }

    public static void register(ReloadAwareProvider provider) {
        PROVIDERS.add(Objects.requireNonNull(provider, "provider"));
    }

    public static void unregister(ReloadAwareProvider provider) {
        PROVIDERS.remove(Objects.requireNonNull(provider, "provider"));
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        Runnable refresh = () -> {
            ConfiguredRuleService.reloadAfterResourceInstall(server);
        };
        if (server.isSameThread()) {
            refresh.run();
        } else {
            server.execute(refresh);
        }
    }

    static boolean refreshForUpdateCause(TagsUpdatedEvent.UpdateCause updateCause) {
        Objects.requireNonNull(updateCause, "updateCause");
        if (updateCause == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            refreshAfterSuccessfulReload();
            return true;
        }
        return false;
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PROVIDERS.clear();
    }

    public static void refreshAfterSuccessfulReload() {
        List<ReloadAwareProvider> providers = List.copyOf(PROVIDERS);
        RuntimeException invalidationFailure = null;
        for (ReloadAwareProvider provider : providers) {
            try {
                provider.invalidateForRecipeReload();
            } catch (RuntimeException exception) {
                invalidationFailure = accumulate(invalidationFailure, exception);
            }
        }
        if (invalidationFailure != null) {
            throw invalidationFailure;
        }

        RuntimeException rebuildFailure = null;
        for (ReloadAwareProvider provider : providers) {
            try {
                provider.rebuildAfterRecipeReload();
            } catch (RuntimeException exception) {
                rebuildFailure = accumulate(rebuildFailure, exception);
            }
        }
        if (rebuildFailure != null) {
            throw rebuildFailure;
        }
    }

    private static RuntimeException accumulate(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }
}

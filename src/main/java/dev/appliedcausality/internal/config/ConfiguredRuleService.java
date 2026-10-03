package dev.appliedcausality.internal.config;

import com.mojang.logging.LogUtils;
import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;

/** Exact-server ownership and server-thread publication of structured rules. */
public final class ConfiguredRuleService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ServerSessions<MinecraftServer> SESSIONS = new ServerSessions<>();

    private ConfiguredRuleService() {
    }

    public static void install(IEventBus forgeBus) {
        forgeBus.addListener(ConfiguredRuleService::onServerStarting);
        forgeBus.addListener(ConfiguredRuleService::onServerStopped);
    }

    public static long generation(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        return session == null ? 0 : session.snapshot().generation();
    }

    public static ConfiguredRuleSnapshot snapshot(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        return session == null ? ConfiguredRuleSnapshot.empty() : session.snapshot();
    }

    /** Last rejected reload for this exact server; initial load errors are logged before startup aborts. */
    public static List<String> lastRejectedDiagnostics(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        return session == null ? List.of() : session.lastRejectedDiagnostics();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        requireServerThread(server);
        startSession(SESSIONS, server, () -> prepare(server),
                ProviderReloadCoordinator::refreshAfterSuccessfulReload);
    }

    static <S> void startSession(ServerSessions<S> sessions, S server,
            Supplier<ConfiguredRuleLoader.LoadResult> candidateSupplier, Runnable refreshProviders) {
        Objects.requireNonNull(sessions);
        Objects.requireNonNull(candidateSupplier);
        Objects.requireNonNull(refreshProviders);
        ConfiguredRuleSnapshot initial = requireInitialCandidate(candidateSupplier.get());
        Session session = new Session();
        sessions.put(server, session);
        try {
            session.adopt(initial, refreshProviders);
        } catch (RuntimeException exception) {
            sessions.remove(server);
            throw exception;
        }
    }

    static ConfiguredRuleSnapshot requireInitialCandidate(ConfiguredRuleLoader.LoadResult candidate) {
        Objects.requireNonNull(candidate);
        if (!candidate.valid()) {
            throw new IllegalStateException("Invalid initial Applied Causality rules: "
                    + String.join("; ", candidate.diagnostics()));
        }
        return candidate.snapshot();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.remove(event.getServer());
    }

    /** Called after a successful server resource install, on the owning server thread. */
    public static void reloadAfterResourceInstall(MinecraftServer server) {
        reloadAfterResourceInstall(server, () -> prepare(server));
    }

    static void reloadAfterResourceInstall(MinecraftServer server,
            Supplier<ConfiguredRuleLoader.LoadResult> candidateSupplier) {
        requireServerThread(server);
        reloadSessionAfterResourceInstall(SESSIONS.get(server), candidateSupplier,
                ProviderReloadCoordinator::refreshAfterSuccessfulReload);
    }

    /** Shared post-resource-install ordering, including failed adoption preflight. */
    static void reloadSessionAfterResourceInstall(Session session,
            Supplier<ConfiguredRuleLoader.LoadResult> candidateSupplier, Runnable refreshProviders) {
        Objects.requireNonNull(candidateSupplier);
        Objects.requireNonNull(refreshProviders);
        if (session == null) {
            refreshProviders.run();
            return;
        }
        ConfiguredRuleLoader.LoadResult candidate;
        try {
            candidate = Objects.requireNonNull(candidateSupplier.get(), "rule candidate");
        } catch (RuntimeException exception) {
            // The RecipeManager is already adopted at this hook. A preparation
            // failure must still invalidate/rebuild Providers.
            LOGGER.error("Applied Causality rules reload preparation failed", exception);
            session.reject(List.of("Rule reload preparation failed: " + exception.getClass().getSimpleName()));
            refreshProviders.run();
            return;
        }
        if (!candidate.valid()) {
            LOGGER.error("Applied Causality rules reload rejected: {}", candidate.diagnostics());
            session.reject(candidate.diagnostics());
            refreshProviders.run();
            return;
        }
        if (session.snapshot().generation() == Long.MAX_VALUE) {
            // Rejected config adoption cannot skip RecipeManager revalidation.
            LOGGER.error("Applied Causality rule generation exhausted; retaining current rules");
            session.reject(List.of("Rule generation exhausted"));
            refreshProviders.run();
            return;
        }
        session.adopt(candidate.snapshot(), refreshProviders);
    }

    private static ConfiguredRuleLoader.LoadResult prepare(MinecraftServer server) {
        ConfiguredRuleLoader.LoadResult result = ConfiguredRuleLoader.loadWithMods(FMLPaths.CONFIGDIR.get(),
                BuiltInRegistries.RECIPE_TYPE::containsKey, BuiltInRegistries.ITEM::containsKey,
                id -> server.getRecipeManager().byKey(id)
                        .map(recipe -> BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())),
                ModList.get()::isLoaded);
        return result.valid() ? new ConfiguredRuleLoader.LoadResult(
                result.snapshot().withDefaultAllow(ConfiguredUnlockSettings.defaultAllow())
                        .withInstallerSlots(ConfiguredUnlockSettings.installerSlots()), result.diagnostics())
                : result;
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) {
            throw new IllegalStateException("Rules adoption requires the owning server thread");
        }
    }

    /** Package-visible for testing failure ordering without a running server. */
    static final class Session {
        private ConfiguredRuleSnapshot snapshot = ConfiguredRuleSnapshot.empty();
        private List<String> lastRejectedDiagnostics = List.of();

        ConfiguredRuleSnapshot snapshot() {
            return snapshot;
        }

        List<String> lastRejectedDiagnostics() {
            return lastRejectedDiagnostics;
        }

        void reject(List<String> diagnostics) {
            lastRejectedDiagnostics = List.copyOf(diagnostics);
        }

        void adopt(ConfiguredRuleSnapshot candidate, Runnable invalidateAndRebuild) {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(invalidateAndRebuild);
            long next = Math.addExact(snapshot.generation(), 1L);
            snapshot = candidate.withGeneration(next);
            lastRejectedDiagnostics = List.of();
            invalidateAndRebuild.run();
        }
    }

    /** Identity ownership deliberately excludes value-equal or later servers. */
    static final class ServerSessions<S> {
        private final Map<S, Session> sessions = new IdentityHashMap<>();

        Session get(S server) {
            return sessions.get(server);
        }

        void put(S server, Session session) {
            sessions.put(Objects.requireNonNull(server), Objects.requireNonNull(session));
        }

        void remove(S server) {
            sessions.remove(server);
        }
    }
}

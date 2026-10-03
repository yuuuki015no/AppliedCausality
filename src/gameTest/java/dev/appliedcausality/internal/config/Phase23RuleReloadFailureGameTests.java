package dev.appliedcausality.internal.config;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import dev.appliedcausality.internal.provider.ReloadAwareProvider;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Exercises rule-preparation failure ordering on the actual server thread. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase23RuleReloadFailureGameTests {
    private Phase23RuleReloadFailureGameTests() {
    }

    @GameTest(template = "empty", batch = "phase23_rule_reload")
    public static void failedPreparationStillRevalidatesProviders(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        long generation = ConfiguredRuleService.generation(server);
        var provider = new ProbeProvider();
        ProviderReloadCoordinator.register(provider);
        try {
            ConfiguredRuleService.reloadAfterResourceInstall(server,
                    () -> new ConfiguredRuleLoader.LoadResult(null,
                            List.of("bad.toml: Invalid TOML or rule value")));
            helper.assertTrue(provider.invalidations == 1 && provider.rebuilds == 1,
                    "invalid candidate must still revalidate every Provider");
            ConfiguredRuleService.reloadAfterResourceInstall(server,
                    () -> { throw new UncheckedIOException(new IOException("injected enumeration failure")); });
            helper.assertTrue(provider.invalidations == 2 && provider.rebuilds == 2,
                    "preparation exception must still revalidate every Provider");
            helper.assertTrue(ConfiguredRuleService.generation(server) == generation,
                    "failed preparation must retain the server's prior snapshot generation");
        } finally {
            ProviderReloadCoordinator.unregister(provider);
        }
        helper.succeed();
    }

    private static final class ProbeProvider implements ReloadAwareProvider {
        private int invalidations;
        private int rebuilds;

        @Override
        public void invalidateForRecipeReload() {
            invalidations++;
        }

        @Override
        public void rebuildAfterRecipeReload() {
            rebuilds++;
        }
    }
}

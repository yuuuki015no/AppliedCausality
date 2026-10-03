package dev.appliedcausality.internal.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Small, fixed common-config switches; variable rules stay in the rules directory. */
public final class ConfiguredUnlockSettings {
    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.EnumValue<DefaultPolicy> DEFAULT_POLICY;
    private static final ForgeConfigSpec.IntValue INSTALLER_SLOTS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        DEFAULT_POLICY = builder.comment("Policy when no unlock rule matches a verified process",
                        "Initial Smelting, AE2 Inscriber, and optional Mekanism Crushing rules",
                        "are generated in config/appliedcausality/rules/",
                        "and their machine Installers grant access")
                .defineEnum("defaultUnlockPolicy", DefaultPolicy.DENY_ALL);
        INSTALLER_SLOTS = builder.comment("Active retained Installer slots; stored items in disabled slots are preserved")
                .defineInRange("installerSlots", 1, 1, 4);
        SPEC = builder.build();
    }

    private ConfiguredUnlockSettings() {
    }

    public static void register(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, SPEC, "appliedcausality-common.toml");
        context.getModEventBus().addListener(ConfiguredUnlockSettings::onReload);
        ConfiguredRuleDefaults.install(FMLPaths.CONFIGDIR.get());
    }

    private static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            Runnable refresh = () -> ConfiguredRuleService.reloadAfterResourceInstall(server);
            if (server.isSameThread()) {
                refresh.run();
            } else {
                server.execute(refresh);
            }
        }
    }

    public static boolean defaultAllow() {
        return DEFAULT_POLICY.get() == DefaultPolicy.ALLOW_ALL;
    }

    public static int installerSlots() {
        return INSTALLER_SLOTS.get();
    }

    public enum DefaultPolicy {
        ALLOW_ALL, DENY_ALL
    }
}

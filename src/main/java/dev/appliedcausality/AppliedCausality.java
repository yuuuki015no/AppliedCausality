package dev.appliedcausality;

import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.cpu.AdvancedAeBridgeRuntimeVerifier;
import dev.appliedcausality.internal.cpu.Ae2BridgeRuntimeVerifier;
import dev.appliedcausality.internal.budget.BulkDispatchBudgetRegistration;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import dev.appliedcausality.internal.config.ConfiguredUnlockSettings;
import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import dev.appliedcausality.internal.provider.ProviderDiagnosticsCommand;
import dev.appliedcausality.internal.registry.ProcessAdapterRegistrationLifecycle;
import dev.appliedcausality.network.AppliedCausalityNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entry point for Applied Causality.
 */
@Mod(AppliedCausality.MOD_ID)
public final class AppliedCausality {
    public static final String MOD_ID = "applied_causality";

    public AppliedCausality(FMLJavaModLoadingContext loadingContext) {
        Ae2BridgeRuntimeVerifier.verify();
        AdvancedAeBridgeRuntimeVerifier.verifyIfLoaded();
        var modEventBus = loadingContext.getModEventBus();
        ConfiguredUnlockSettings.register(loadingContext);
        AppliedCausalityContent.register(modEventBus);
        ProcessAdapterRegistrationLifecycle.install(modEventBus);
        BulkDispatchBudgetRegistration.install(modEventBus);
        AppliedCausalityNetwork.install();
        ProviderReloadCoordinator.install(MinecraftForge.EVENT_BUS);
        ProviderDiagnosticsCommand.install(MinecraftForge.EVENT_BUS);
        ConfiguredRuleService.install(MinecraftForge.EVENT_BUS);
    }
}

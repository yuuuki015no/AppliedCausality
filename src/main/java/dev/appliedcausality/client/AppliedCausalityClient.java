package dev.appliedcausality.client;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.network.ProviderBindingSnapshotPacket;
import dev.appliedcausality.menu.ProviderBindingMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only menu registration kept out of the dedicated-server class path. */
@Mod.EventBusSubscriber(modid = AppliedCausality.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AppliedCausalityClient {
    private AppliedCausalityClient() {
    }

    @SubscribeEvent
    public static void registerMenuScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                AppliedCausalityContent.PROVIDER_BINDING_MENU.get(), ProviderBindingScreen::new));
    }

    /** Called only by the clientbound packet handler; the menu checks its container ID. */
    public static void applyProviderBindingSnapshot(ProviderBindingSnapshotPacket packet) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof ProviderBindingMenu menu) {
            menu.applySnapshot(packet);
        }
    }
}

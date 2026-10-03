package dev.appliedcausality.network;

import dev.appliedcausality.AppliedCausality;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Narrow clientbound synchronization used only while a provider binding menu is open. */
public final class AppliedCausalityNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, "provider_binding"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean installed;

    private AppliedCausalityNetwork() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        CHANNEL.messageBuilder(ProviderBindingSnapshotPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ProviderBindingSnapshotPacket::encode)
                .decoder(ProviderBindingSnapshotPacket::decode)
                .consumerMainThread((packet, ignored) -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT, () -> () -> dev.appliedcausality.client.AppliedCausalityClient
                                .applyProviderBindingSnapshot(packet)))
                .add();
    }

    public static void sendMenuSnapshot(ServerPlayer player, ProviderBindingSnapshotPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                packet);
    }
}

package dev.appliedcausality.network;

import dev.appliedcausality.menu.ProviderBindingMenu;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** Clientbound replacement for one open provider menu's presentation snapshot. */
public record ProviderBindingSnapshotPacket(int containerId, List<ProviderBindingMenu.SlotSnapshot> snapshots) {
    public ProviderBindingSnapshotPacket {
        if (containerId < 0) {
            throw new IllegalArgumentException("container ID must be non-negative");
        }
        snapshots = List.copyOf(snapshots);
    }

    static void encode(ProviderBindingSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        ProviderBindingMenu.writeSnapshots(buffer, packet.snapshots());
    }

    static ProviderBindingSnapshotPacket decode(FriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        if (containerId < 0) {
            throw new IllegalArgumentException("container ID must be non-negative");
        }
        return new ProviderBindingSnapshotPacket(containerId, ProviderBindingMenu.readSnapshots(buffer));
    }

    /** True only for the exact open menu instance that received this packet. */
    public boolean targetsContainer(int openContainerId) {
        return containerId == openContainerId;
    }
}

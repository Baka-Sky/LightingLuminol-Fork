package net.minecraft.network.protocol.game;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.world.item.ItemStack;

public record ClientboundContainerSetContentPacket(int containerId, int stateId, List<ItemStack> items, ItemStack carriedItem)
    implements Packet<ClientGamePacketListener> {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundContainerSetContentPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.CONTAINER_ID,
        ClientboundContainerSetContentPacket::containerId,
        ByteBufCodecs.VAR_INT,
        ClientboundContainerSetContentPacket::stateId,
        ItemStack.OPTIONAL_LIST_STREAM_CODEC,
        ClientboundContainerSetContentPacket::items,
        ItemStack.OPTIONAL_STREAM_CODEC,
        ClientboundContainerSetContentPacket::carriedItem,
        ClientboundContainerSetContentPacket::new
    );

    // Paper start - Handle large packets disconnecting client
    @Override
    public boolean hasLargePacketFallback() {
        return true;
    }

    @Override
    public boolean packetTooLarge(net.minecraft.network.Connection manager) {
        for (int i = 0; i < this.items.size(); i++) {
            manager.send(new ClientboundContainerSetSlotPacket(this.containerId, this.stateId, i, this.items.get(i)));
        }
        return true;
    }
    // Paper end - Handle large packets disconnecting client

    @Override
    public PacketType<ClientboundContainerSetContentPacket> type() {
        return GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT;
    }

    @Override
    public void handle(final ClientGamePacketListener listener) {
        listener.handleContainerContent(this);
    }
}

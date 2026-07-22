package net.minecraft.network.protocol.game;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

public record ServerboundChatCommandPacket(String command) implements Packet<ServerGamePacketListener> {
    public static final Integer MAX_CHAT_PACKET_INPUT_SIZE = Integer.getInteger("paper.maxChatCommandInputSize", 256); // Paper - limit chat command inputs
    public static final StreamCodec<FriendlyByteBuf, ServerboundChatCommandPacket> STREAM_CODEC = Packet.codec(
        ServerboundChatCommandPacket::write, ServerboundChatCommandPacket::new
    );

    private ServerboundChatCommandPacket(final FriendlyByteBuf input) {
        this(input.readUtf(MAX_CHAT_PACKET_INPUT_SIZE)); // Paper - limit chat command inputs
    }

    private void write(final FriendlyByteBuf output) {
        output.writeUtf(this.command);
    }

    @Override
    public PacketType<ServerboundChatCommandPacket> type() {
        return GamePacketTypes.SERVERBOUND_CHAT_COMMAND;
    }

    @Override
    public void handle(final ServerGamePacketListener listener) {
        listener.handleChatCommand(this);
    }
}

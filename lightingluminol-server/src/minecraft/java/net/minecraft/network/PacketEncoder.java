package net.minecraft.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import org.slf4j.Logger;

public class PacketEncoder<T extends PacketListener> extends MessageToByteEncoder<Packet<T>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ProtocolInfo<T> protocolInfo;

    public PacketEncoder(final ProtocolInfo<T> protocolInfo) {
        this.protocolInfo = protocolInfo;
    }

    static final ThreadLocal<java.util.Locale> ADVENTURE_LOCALE = ThreadLocal.withInitial(() -> null); // Paper - adventure; set player's locale
    @Override
    protected void encode(final ChannelHandlerContext ctx, final Packet<T> packet, final ByteBuf output) throws Exception {
        PacketType<? extends Packet<? super T>> packetId = packet.type();

        try {
            ADVENTURE_LOCALE.set(ctx.channel().attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).get()); // Paper - adventure; set player's locale
            this.protocolInfo.codec().encode(output, packet);
            int writtenBytes = output.readableBytes();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    Connection.PACKET_SENT_MARKER,
                    "OUT: [{}:{}] {} -> {} bytes",
                    this.protocolInfo.id().id(),
                    packetId,
                    packet.getClass().getName(),
                    writtenBytes
                );
            }

            JvmProfiler.INSTANCE.onPacketSent(this.protocolInfo.id(), packetId, ctx.channel().remoteAddress(), writtenBytes);
        } catch (Throwable t) {
            LOGGER.error("Error sending packet {}", packetId, t);
            if (packet.isSkippable()) {
                throw new SkipPacketEncoderException(t);
            }

            throw t;
        } finally {
            // Paper start - Handle large packets disconnecting client
            int packetLength = output.readableBytes();
            if (packetLength > MAX_PACKET_SIZE || (packetLength > MAX_FINAL_PACKET_SIZE && packet.hasLargePacketFallback())) {
                throw new PacketTooLargeException(packet, packetLength);
            }
            // Paper end - Handle large packets disconnecting client
            ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
        }
    }

    // Paper start
    // packet size is encoded into 3-byte varint
    private static final int MAX_FINAL_PACKET_SIZE = (1 << 21) - 1;
    // Vanilla Max size for the encoder (before compression)
    private static final int MAX_PACKET_SIZE = 8388608;

    public static class PacketTooLargeException extends RuntimeException {
        private final Packet<?> packet;

        PacketTooLargeException(Packet<?> packet, int packetLength) {
            super("PacketTooLarge - " + packet.getClass().getSimpleName() + " is " + packetLength + ". Max is " + MAX_PACKET_SIZE);
            this.packet = packet;
        }

        public Packet<?> getPacket() {
            return this.packet;
        }
    }
    // Paper end
}

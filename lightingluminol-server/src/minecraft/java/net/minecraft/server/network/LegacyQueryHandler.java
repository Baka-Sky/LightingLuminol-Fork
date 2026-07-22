package net.minecraft.server.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.SocketAddress;
import java.util.Locale;
import net.minecraft.server.ServerInfo;
import org.slf4j.Logger;

public class LegacyQueryHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ServerInfo server;
    private ByteBuf buf; // Paper

    public LegacyQueryHandler(final ServerInfo server) {
        this.server = server;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        ByteBuf in = (ByteBuf)msg;
        // Paper start - Make legacy ping handler more reliable
        if (this.buf != null) {
            try {
                this.readLegacy1_6(ctx, in);
            } finally {
                in.release();
            }
            return;
        }
        // Paper end - Make legacy ping handler more reliable

        in.markReaderIndex();
        boolean connectNormally = true;

        try {
            try {
                if (in.readUnsignedByte() != 254) {
                    return;
                }

                SocketAddress socket = ctx.channel().remoteAddress();
                int length = in.readableBytes();
                String body = null; // Paper
                if (length == 0) {
                    LOGGER.debug("Ping: (<1.3.x) from {}", net.minecraft.server.MinecraftServer.getServer().logIPs() ? socket : "<ip address withheld>"); // Paper - Respect logIPs option
                    // Paper start - Call PaperServerListPingEvent and use results
                    com.destroystokyo.paper.event.server.PaperServerListPingEvent event = com.destroystokyo.paper.network.PaperLegacyStatusClient.processRequest(net.minecraft.server.MinecraftServer.getServer(), (java.net.InetSocketAddress) socket, 39, null);
                    if (event == null) {
                        ctx.close();
                        in.release();
                        connectNormally = false;
                        return;
                    }
                    body = String.format(Locale.ROOT, "%s§%d§%d", com.destroystokyo.paper.network.PaperLegacyStatusClient.getUnformattedMotd(event), event.getNumPlayers(), event.getMaxPlayers());
                    // Paper end - Call PaperServerListPingEvent and use results
                    sendFlushAndClose(ctx, createLegacyDisconnectPacket(ctx.alloc(), body));
                } else {
                    if (in.readUnsignedByte() != 1) {
                        return;
                    }

                    if (in.isReadable()) {
                        // Paper start - Replace below
                        if (in.readUnsignedByte() != LegacyProtocolUtils.CUSTOM_PAYLOAD_PACKET_ID) {
                            body = this.readLegacy1_6(ctx, in);
                            if (body == null) {
                                return;
                            }
                        }
                        // Paper end - Replace below
                    } else {
                        LOGGER.debug("Ping: (1.4-1.5.x) from {}", net.minecraft.server.MinecraftServer.getServer().logIPs() ? socket : "<ip address withheld>"); // Paper - Respect logIPs option
                    }

                    // Paper start - Call PaperServerListPingEvent and use results
                    if (body == null) {
                        com.destroystokyo.paper.event.server.PaperServerListPingEvent event = com.destroystokyo.paper.network.PaperLegacyStatusClient.processRequest(net.minecraft.server.MinecraftServer.getServer(), (java.net.InetSocketAddress) socket, 127, null); // Paper
                        if (event == null) {
                            ctx.close();
                            in.release();
                            connectNormally = false;
                            return;
                        }

                        // See createVersion1Response
                        body = String.format(
                            Locale.ROOT,
                            "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                            event.getProtocolVersion(), this.server.getServerVersion(),
                            event.getMotd(),
                            event.getNumPlayers(),
                            event.getMaxPlayers()
                        );
                    }
                    // Paper end - Call PaperServerListPingEvent and use results
                    sendFlushAndClose(ctx, createLegacyDisconnectPacket(ctx.alloc(), body));
                }

                in.release();
                connectNormally = false;
            } catch (RuntimeException var11) {
            }
        } finally {
            if (connectNormally) {
                in.resetReaderIndex();
                ctx.channel().pipeline().remove(this);
                ctx.fireChannelRead(msg);
            }
        }
    }

    private static boolean readCustomPayloadPacket(final ByteBuf in) {
        short packetId = in.readUnsignedByte();
        if (packetId != 250) {
            return false;
        }

        String channelId = LegacyProtocolUtils.readLegacyString(in);
        if (!"MC|PingHost".equals(channelId)) {
            return false;
        }

        int payloadSize = in.readUnsignedShort();
        if (in.readableBytes() != payloadSize) {
            return false;
        }

        short protocolVersion = in.readUnsignedByte();
        if (protocolVersion < 73) {
            return false;
        }

        String host = LegacyProtocolUtils.readLegacyString(in);
        int port = in.readInt();
        return port <= 65535;
    }

    private static String createVersion0Response(final ServerInfo server) {
        return String.format(Locale.ROOT, "%s§%d§%d", server.getMotd(), server.getPlayerCount(), server.getMaxPlayers());
    }

    private static String createVersion1Response(final ServerInfo server) {
        return String.format(
            Locale.ROOT,
            "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
            127,
            server.getServerVersion(),
            server.getMotd(),
            server.getPlayerCount(),
            server.getMaxPlayers()
        );
    }

    // Paper start
    private static @org.jspecify.annotations.Nullable String readLegacyString(final ByteBuf buf) {
        int size = buf.readShort() * Character.BYTES;
        if (!buf.isReadable(size)) {
            return null;
        }

        String result = buf.toString(buf.readerIndex(), size, java.nio.charset.StandardCharsets.UTF_16BE);
        buf.skipBytes(size); // toString doesn't increase readerIndex automatically
        return result;
    }

    private @org.jspecify.annotations.Nullable String readLegacy1_6(final ChannelHandlerContext ctx, final ByteBuf part) {
        ByteBuf buf = this.buf;

        if (buf == null) {
            this.buf = buf = ctx.alloc().buffer();
            buf.markReaderIndex();
        } else {
            buf.resetReaderIndex();
        }

        buf.writeBytes(part);

        if (!buf.isReadable(Short.BYTES + Short.BYTES + Byte.BYTES + Short.BYTES + Integer.BYTES)) {
            return null;
        }

        String string = readLegacyString(buf);
        if (string == null) {
            return null;
        }

        if (!string.equals(LegacyProtocolUtils.CUSTOM_PAYLOAD_PACKET_PING_CHANNEL)) {
            this.removeHandler(ctx);
            return null;
        }

        if (!buf.isReadable(Short.BYTES) || !buf.isReadable(buf.readShort())) {
            return null;
        }

        int protocolVersion = buf.readByte();
        String host = readLegacyString(buf);
        if (host == null) {
            this.removeHandler(ctx);
            return null;
        }

        int port = buf.readInt();
        if (buf.isReadable()) {
            this.removeHandler(ctx);
            return null;
        }

        buf.release();
        this.buf = null;

        LOGGER.debug("Ping: (1.6) from {}", net.minecraft.server.MinecraftServer.getServer().logIPs() ? ctx.channel().remoteAddress() : "<ip address withheld>"); // Paper - Respect logIPs option

        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        java.net.InetSocketAddress virtualHost = com.destroystokyo.paper.network.PaperNetworkClient.prepareVirtualHost(host, port);
        com.destroystokyo.paper.event.server.PaperServerListPingEvent event = com.destroystokyo.paper.network.PaperLegacyStatusClient.processRequest(server, (java.net.InetSocketAddress) ctx.channel().remoteAddress(), protocolVersion, virtualHost);
        if (event == null) {
            ctx.close();
            return null;
        }

        String response = String.format("§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d", event.getProtocolVersion(), event.getVersion(), com.destroystokyo.paper.network.PaperLegacyStatusClient.getMotd(event), event.getNumPlayers(), event.getMaxPlayers());
        return response;
    }

    private void removeHandler(final ChannelHandlerContext ctx) {
        ByteBuf buf = this.buf;
        this.buf = null;

        buf.resetReaderIndex();
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(buf);
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (this.buf != null) {
            this.buf.release();
            this.buf = null;
        }
    }
    // Paper end

    private static void sendFlushAndClose(final ChannelHandlerContext ctx, final ByteBuf out) {
        ctx.pipeline().firstContext().writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    }

    private static ByteBuf createLegacyDisconnectPacket(final ByteBufAllocator alloc, final String reason) {
        ByteBuf out = alloc.buffer();
        out.writeByte(255);
        LegacyProtocolUtils.writeLegacyString(out, reason);
        return out;
    }
}

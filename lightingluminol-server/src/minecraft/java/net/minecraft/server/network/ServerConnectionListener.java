package net.minecraft.server.network;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerConnectionListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final MinecraftServer server;
    public volatile boolean running;
    private volatile @Nullable UUID sessionId;
    private final List<ChannelFuture> channels = Collections.synchronizedList(Lists.newArrayList());
    private final List<Connection> connections = Collections.synchronizedList(Lists.newArrayList());

    public ServerConnectionListener(final MinecraftServer server) {
        this.server = server;
        this.running = true;
    }

    // Paper start - prevent blocking on adding a new connection while the server is ticking
    private final java.util.Queue<Connection> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final boolean disableFlushConsolidation = Boolean.getBoolean("Paper.disableFlushConsolidate"); // Paper - Optimize network

    private final void addPending() {
        Connection connection;
        while ((connection = this.pending.poll()) != null) {
            this.connections.add(connection);
            connection.isPending = false; // Paper - Optimize network
        }
    }
    // Paper end - prevent blocking on adding a new connection while the server is ticking

    public void startTcpServerListener(final @Nullable InetAddress address, final int port) throws IOException {
        // Paper start - Unix domain socket support
        this.startTcpServerListener(new java.net.InetSocketAddress(address, port));
    }

    public void startTcpServerListener(SocketAddress address) throws IOException {
        // Paper end - Unix domain socket support
        synchronized (this.channels) {
            EventLoopGroupHolder eventLoopGroupHolder = EventLoopGroupHolder.remote(address, this.server.useNativeTransport()); // Paper - Unix domain socket support
            // Paper start - Warn people with console access that HAProxy is in use.
            if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.proxyProtocol) {
                LOGGER.warn("Using HAProxy, please ensure the server port is adequately firewalled.");
            }
            // Paper end - Warn people with console access that HAProxy is in use.
            // Paper start - Use Velocity cipher
            ServerConnectionListener.LOGGER.info("Paper: Using " + com.velocitypowered.natives.util.Natives.compress.getLoadedVariant() + " compression from Velocity.");
            ServerConnectionListener.LOGGER.info("Paper: Using " + com.velocitypowered.natives.util.Natives.cipher.getLoadedVariant() + " cipher from Velocity.");
            // Paper end - Use Velocity cipher
            this.channels
                .add(
                    new ServerBootstrap()
                        .channel(eventLoopGroupHolder.serverChannelCls())
                        .childHandler(
                            new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(final Channel channel) {
                                    try {
                                        channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                                    } catch (ChannelException var5) {
                                    }

                                    if (!disableFlushConsolidation) channel.pipeline().addFirst(new io.netty.handler.flush.FlushConsolidationHandler()); // Paper - Optimize network
                                    ChannelPipeline pipeline = channel.pipeline().addLast(HandlerNames.TIMEOUT, new ReadTimeoutHandler(30));
                                    if (ServerConnectionListener.this.server.repliesToStatus()) {
                                        pipeline.addLast(HandlerNames.LEGACY_QUERY, new LegacyQueryHandler(ServerConnectionListener.this.getServer()));
                                    }

                                    Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
                                    int rateLimitPacketsPerSecond = ServerConnectionListener.this.server.getRateLimitPacketsPerSecond();
                                    Connection connection = rateLimitPacketsPerSecond > 0
                                        ? new RateKickingConnection(rateLimitPacketsPerSecond)
                                        : new Connection(PacketFlow.SERVERBOUND);
                                    // Paper start - Add support for Proxy Protocol
                                    if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.proxyProtocol) {
                                        channel.pipeline().addAfter(HandlerNames.TIMEOUT, "haproxy-decoder", new io.netty.handler.codec.haproxy.HAProxyMessageDecoder());
                                        channel.pipeline().addAfter("haproxy-decoder", "haproxy-handler", new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                                if (msg instanceof io.netty.handler.codec.haproxy.HAProxyMessage message) {
                                                    if (message.command() == io.netty.handler.codec.haproxy.HAProxyCommand.PROXY) {
                                                        String realAddress = message.sourceAddress();
                                                        int realPort = message.sourcePort();

                                                        SocketAddress socketAddr = new java.net.InetSocketAddress(realAddress, realPort);

                                                        Connection connection = (Connection) channel.pipeline().get(HandlerNames.PACKET_HANDLER);
                                                        connection.address = socketAddr;
                                                        // Paper start - Add API to get player's proxy address
                                                        final String proxyAddress = message.destinationAddress();
                                                        final int proxyPort = message.destinationPort();

                                                        connection.haProxyAddress = new java.net.InetSocketAddress(proxyAddress, proxyPort);
                                                        // Paper end - Add API to get player's proxy address
                                                    }
                                                } else {
                                                    super.channelRead(ctx, msg);
                                                }
                                            }
                                        });
                                    }
                                    // Paper end - Add support for proxy protocol
                                    // ServerConnectionListener.this.connections.add(connection); // Paper - prevent blocking on adding a new connection while the server is ticking
                                    //ServerConnectionListener.this.pending.add(connection); // Paper - prevent blocking on adding a new connection while the server is ticking // Folia - connection fixes - move down
                                    connection.configurePacketHandler(pipeline);
                                    connection.setListenerForServerboundHandshake(
                                        new ServerHandshakePacketListenerImpl(ServerConnectionListener.this.server, connection)
                                    );
                                    io.papermc.paper.network.ChannelInitializeListenerHolder.callListeners(channel); // Paper - Add Channel initialization listeners
                                    // Folia start - regionised threading
                                    io.papermc.paper.threadedregions.RegionizedServer.getInstance().addConnection(connection);
                                    // Folia end - regionised threading
                                }
                            }
                        )
                        .group(eventLoopGroupHolder.eventLoopGroup())
                        .localAddress(address) // Paper - Unix domain socket support
                        .option(ChannelOption.AUTO_READ, false) // CraftBukkit
                        .bind()
                        .syncUninterruptibly()
                );
        }
    }

    // CraftBukkit start
    public void acceptConnections() {
        synchronized (this.channels) {
            for (ChannelFuture future : this.channels) {
                future.channel().config().setAutoRead(true);
            }
        }
    }
    // CraftBukkit end

    public SocketAddress startMemoryChannel() {
        ChannelFuture newChannel;
        synchronized (this.channels) {
            newChannel = new ServerBootstrap()
                .channel(EventLoopGroupHolder.local().serverChannelCls())
                .childHandler(
                    new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel channel) {
                            Connection connection = new Connection(PacketFlow.SERVERBOUND);
                            connection.setListenerForServerboundHandshake(
                                new MemoryServerHandshakePacketListenerImpl(ServerConnectionListener.this.server, connection)
                            );
                            ServerConnectionListener.this.connections.add(connection);
                            ChannelPipeline pipeline = channel.pipeline();
                            Connection.configureInMemoryPipeline(pipeline, PacketFlow.SERVERBOUND);
                            if (SharedConstants.DEBUG_FAKE_LATENCY_MS > 0) {
                                pipeline.addLast(
                                    HandlerNames.LATENCY,
                                    new ServerConnectionListener.LatencySimulator(SharedConstants.DEBUG_FAKE_LATENCY_MS, SharedConstants.DEBUG_FAKE_JITTER_MS)
                                );
                            }

                            connection.configurePacketHandler(pipeline);
                        }
                    }
                )
                .group(EventLoopGroupHolder.local().eventLoopGroup())
                .localAddress(LocalAddress.ANY)
                .bind()
                .syncUninterruptibly();
            this.channels.add(newChannel);
        }

        return newChannel.channel().localAddress();
    }

    public void acceptChannel(final Channel channel, final UUID profileId) {
        channel.pipeline()
            .addLast(
                new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        int rateLimitPacketsPerSecond = ServerConnectionListener.this.server.getRateLimitPacketsPerSecond();
                        Connection connection = rateLimitPacketsPerSecond > 0
                            ? new RateKickingConnection(rateLimitPacketsPerSecond)
                            : new Connection(PacketFlow.SERVERBOUND);
                        ChannelPipeline pipeline = ch.pipeline().addLast(HandlerNames.TIMEOUT, new ReadTimeoutHandler(30));
                        Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
                        connection.configurePacketHandler(pipeline);
                        connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(ServerConnectionListener.this.server, connection));
                        connection.setIntendedProfileId(profileId);
                        ServerConnectionListener.this.connections.add(connection);
                    }
                }
            );
        EventLoopGroupHolder.local().eventLoopGroup().register(channel).syncUninterruptibly();
    }

    public void stop() {
        this.running = false;

        for (ChannelFuture channel : this.channels) {
            try {
                channel.channel().close().sync();
            } catch (InterruptedException ignored) {
                LOGGER.error("Interrupted whilst closing channel");
            }
        }
    }

    public void stopTcpServerListener() {
        synchronized (this.channels) {
            Iterator<ChannelFuture> iterator = this.channels.iterator();

            while (iterator.hasNext()) {
                ChannelFuture future = iterator.next();
                if (!(future.channel() instanceof LocalServerChannel)) {
                    try {
                        future.channel().close().sync();
                    } catch (InterruptedException ignored) {
                        LOGGER.error("Interrupted whilst closing TCP listener");
                    }

                    iterator.remove();
                }
            }
        }
    }

    public void tick() {
        synchronized (this.connections) {
            // Spigot start
            this.addPending(); // Paper - prevent blocking on adding a new connection while the server is ticking
            // This prevents players from 'gaming' the server, and strategically relogging to increase their position in the tick order
            if (org.spigotmc.SpigotConfig.playerShuffle > 0 && 0 % org.spigotmc.SpigotConfig.playerShuffle == 0) { // Folia - region threading
                Collections.shuffle(this.connections);
            }
            // Spigot end
            Iterator<Connection> iterator = this.connections.iterator();

            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (!connection.isConnecting()) {
                    if (connection.isConnected()) {
                        // Paper start - Force kill connection ticking
                        // also call this multiple times, doesnt matter
                        // See MC-307764
                        if (connection.handleConnectionDisconnectOnNextTick) {
                            continue;
                        }
                        // Paper end - Force kill connection ticking
                        try {
                            connection.tick();
                        } catch (Exception e) {
                            if (connection.isMemoryConnection()) {
                                throw new ReportedException(CrashReport.forThrowable(e, "Ticking memory connection"));
                            }

                            LOGGER.warn("Failed to handle packet for {}", connection.getLoggableAddress(this.server.logIPs()), e);
                            Component component = Component.literal("Internal server error");
                            connection.send(new ClientboundDisconnectPacket(component), PacketSendListener.thenRun(() -> connection.disconnect(component)));
                            connection.setReadOnly();
                        }
                    } else {
                        if (connection.preparing) continue; // Spigot - Fix a race condition where a Connection could be unregistered just before connection
                        iterator.remove();
                        connection.handleDisconnection();
                    }
                }
            }

            if (this.connections.isEmpty()) {
                synchronized (this) {
                    this.sessionId = null;
                }
            }
        }
    }
    // Paper start
    public void handleAllDisconnections() {
        synchronized (this.connections) {
            for (final Connection connection : this.connections) {
                connection.channel.close().awaitUninterruptibly();
                connection.handleDisconnection();
            }
        }
    }
    // Paper end

    public MinecraftServer getServer() {
        return this.server;
    }

    public List<Connection> getConnections() {
        return this.connections;
    }

    public UUID getSessionId() {
        UUID uuid = this.sessionId;
        if (uuid != null) {
            return uuid;
        }

        synchronized (this) {
            uuid = this.sessionId;
            if (uuid == null) {
                uuid = UUID.randomUUID();
                this.sessionId = uuid;
            }

            return uuid;
        }
    }

    private static class LatencySimulator extends ChannelInboundHandlerAdapter {
        private static final Timer TIMER = new HashedWheelTimer(new ThreadFactoryBuilder().setNameFormat("Latency Simulator #%d").setDaemon(true).build());
        private final int delay;
        private final int jitter;
        private final List<ServerConnectionListener.LatencySimulator.DelayedMessage> queuedMessages = Lists.newArrayList();

        public LatencySimulator(final int delay, final int jitter) {
            this.delay = delay;
            this.jitter = jitter;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            this.delayDownstream(ctx, msg);
        }

        private void delayDownstream(final ChannelHandlerContext ctx, final Object msg) {
            int sendDelay = this.delay + (int)(Math.random() * this.jitter);
            this.queuedMessages.add(new ServerConnectionListener.LatencySimulator.DelayedMessage(ctx, msg));
            TIMER.newTimeout(this::onTimeout, sendDelay, TimeUnit.MILLISECONDS);
        }

        private void onTimeout(final Timeout timeout) {
            ServerConnectionListener.LatencySimulator.DelayedMessage next = this.queuedMessages.remove(0);
            next.ctx.fireChannelRead(next.msg);
        }

        private record DelayedMessage(ChannelHandlerContext ctx, Object msg) {
        }
    }
}

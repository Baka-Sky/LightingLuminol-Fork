package net.minecraft.network;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.TimeoutException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Connection extends SimpleChannelInboundHandler<Packet<?>> {
    private static final float AVERAGE_PACKETS_SMOOTHING = 0.75F;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Marker ROOT_MARKER = MarkerFactory.getMarker("NETWORK");
    public static final Marker PACKET_MARKER = Util.make(MarkerFactory.getMarker("NETWORK_PACKETS"), m -> m.add(ROOT_MARKER));
    public static final Marker PACKET_RECEIVED_MARKER = Util.make(MarkerFactory.getMarker("PACKET_RECEIVED"), m -> m.add(PACKET_MARKER));
    public static final Marker PACKET_SENT_MARKER = Util.make(MarkerFactory.getMarker("PACKET_SENT"), m -> m.add(PACKET_MARKER));
    private static final ProtocolInfo<ServerHandshakePacketListener> INITIAL_PROTOCOL = HandshakeProtocols.SERVERBOUND;
    private final PacketFlow receiving;
    private volatile boolean sendLoginDisconnect = true;
    private final Queue<WrappedConsumer> pendingActions = new ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<>(); // Paper - Optimize network // Folia - region threading - connection fixes
    public Channel channel;
    public SocketAddress address;
    // Spigot start
    public java.util.UUID spoofedUUID;
    public com.mojang.authlib.properties.Property[] spoofedProfile;
    public boolean preparing = true;
    // Spigot end
    private volatile @Nullable PacketListener disconnectListener;
    private volatile @Nullable PacketListener packetListener;
    private @Nullable DisconnectionDetails disconnectionDetails;
    private final java.util.concurrent.atomic.AtomicBoolean disconnectionHandled = new java.util.concurrent.atomic.AtomicBoolean(false); // Folia - region threading - may be called concurrently during configuration stage
    private int receivedPackets;
    private int sentPackets;
    private float averageReceivedPackets;
    private float averageSentPackets;
    private int tickCount;
    private boolean handlingFault;
    private volatile @Nullable DisconnectionDetails delayedDisconnect;
    private @Nullable BandwidthDebugMonitor bandwidthDebugMonitor;
    private @Nullable UUID intendedProfileId;
    public String hostname = ""; // CraftBukkit - add field
    public boolean handleConnectionDisconnectOnNextTick = false; // Paper - Force kill connection ticking
    // Paper start - NetworkClient implementation
    public int protocolVersion;
    public java.net.InetSocketAddress virtualHost;
    private static boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush"); // Paper - Disable explicit connection flushing
    // Paper end
    // Paper start - add utility methods
    public final net.minecraft.server.level.ServerPlayer getPlayer() {
        if (this.packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl impl) {
            return impl.player;
        } else if (this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl impl) {
            return null;
        }
        return null;
    }
    // Paper end - add utility methods
    // Paper start - packet limiter
    protected final Object PACKET_LIMIT_LOCK = new Object();
    protected final io.papermc.paper.util.@Nullable IntervalledCounter allPacketCounts = io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.isEnabled() ? new io.papermc.paper.util.IntervalledCounter(
        (long)(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.interval() * 1.0e9)
    ) : null;
    protected final java.util.Map<Class<? extends net.minecraft.network.protocol.Packet<?>>, io.papermc.paper.util.IntervalledCounter> packetSpecificLimits = new java.util.HashMap<>();

    private boolean stopReadingPackets;
    private void killForPacketSpam() {
        this.sendPacket(new ClientboundDisconnectPacket(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.kickMessage)), PacketSendListener.thenRun(() -> {
            this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.kickMessage));
        }), true);
        this.setReadOnly();
        this.stopReadingPackets = true;
    }
    // Paper end - packet limiter
    @Nullable public SocketAddress haProxyAddress; // Paper - Add API to get player's proxy address
    public java.util.@Nullable Optional<net.minecraft.network.chat.Component> legacySavedLoginEventResultOverride; // Paper - playerloginevent
    public boolean handledLegacyLoginEvent; // Paper - playerloginevent
    public net.minecraft.server.level.@Nullable ServerPlayer savedPlayerForLegacyEvents; // Paper - playerloginevent & PlayerSpawnLocationEvent
    public org.bukkit.event.player.PlayerResourcePackStatusEvent.@Nullable Status resourcePackStatus; // Paper
    // Paper start - Optimize network
    public boolean isPending = true;
    public boolean queueImmunity;
    // Paper end - Optimize network

    public Connection(final PacketFlow receiving) {
        this.receiving = receiving;
    }

    // Folia start - region threading
    private volatile boolean becomeActive;

    public boolean becomeActive() {
        return this.becomeActive;
    }

    private static record DisconnectReq(DisconnectionDetails details) {}

    private final ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<DisconnectReq> disconnectReqs =
            new ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<>();

    /**
     * Safely disconnects the connection while possibly on another thread. Note: This call will not block, even if on the
     * same thread that could disconnect.
     */
    public final void disconnectSafely(DisconnectionDetails details) {
        this.disconnectReqs.add(new DisconnectReq(details));
        // We can't halt packet processing here because a plugin could cancel a kick request.
    }

    /**
     * Safely disconnects the connection while possibly on another thread. Note: This call will not block, even if on the
     * same thread that could disconnect.
     */
    public final void disconnectSafely(Component disconnectReason, io.papermc.paper.connection.DisconnectionReason cause) {
        this.disconnectReqs.add(new DisconnectReq(new DisconnectionDetails(disconnectReason, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.ofNullable(cause))));
        // We can't halt packet processing here because a plugin could cancel a kick request.
    }

    public final boolean isPlayerConnected() {
        return this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl;
    }
    // Folia end - region threading

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.channel = ctx.channel();
        this.address = this.channel.remoteAddress();
        this.preparing = false; // Spigot
        if (this.delayedDisconnect != null) {
            this.disconnect(this.delayedDisconnect);
        }
        this.becomeActive = true; // Folia - region threading
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        this.disconnect(Component.translatable("disconnect.endOfStream"));
    }

    @Override
    // Paper start - Handle large packets disconnecting client
    public void exceptionCaught(final ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof io.netty.handler.codec.EncoderException && cause.getCause() instanceof PacketEncoder.PacketTooLargeException packetTooLargeException) {
            final Packet<?> packet = packetTooLargeException.getPacket();
            if (packet.packetTooLarge(this)) {
                ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
                return;
            } else if (packet.isSkippable()) {
                Connection.LOGGER.debug("Skipping packet due to errors", cause.getCause());
                ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
                return;
            } else {
                cause = cause.getCause();
            }
        }
        // Paper end - Handle large packets disconnecting client
        if (cause instanceof SkipPacketException) {
            LOGGER.debug("Skipping packet due to errors", cause.getCause());
        } else {
            boolean isFirstFault = !this.handlingFault;
            this.handlingFault = true;
            if (this.channel.isOpen()) {
                net.minecraft.server.level.ServerPlayer player = this.getPlayer(); // Paper - Add API for quit reason
                if (cause instanceof TimeoutException) {
                    LOGGER.debug("Timeout", cause);
                    if (player != null) player.quitReason = org.bukkit.event.player.PlayerQuitEvent.QuitReason.TIMED_OUT; // Paper - Add API for quit reason
                    this.disconnect(Component.translatable("disconnect.timeout"));
                } else {
                    Component reason = Component.translatable("disconnect.genericReason", "Internal Exception: " + cause);
                    PacketListener listener = this.packetListener;
                    DisconnectionDetails details;
                    if (listener != null) {
                        details = listener.createDisconnectionInfo(reason, cause);
                    } else {
                        details = new DisconnectionDetails(reason);
                    }

                    if (player != null) player.quitReason = org.bukkit.event.player.PlayerQuitEvent.QuitReason.ERRONEOUS_STATE; // Paper - Add API for quit reason
                    if (isFirstFault) {
                        LOGGER.debug("Failed to sent packet", cause);
                        boolean doesDisconnectExist = this.packetListener.protocol() != ConnectionProtocol.STATUS && this.packetListener.protocol() != ConnectionProtocol.HANDSHAKING; // Paper
                        if (this.getSending() == PacketFlow.CLIENTBOUND && doesDisconnectExist) { // Paper
                            Packet<?> packet = this.sendLoginDisconnect
                                ? new ClientboundLoginDisconnectPacket(reason)
                                : new ClientboundDisconnectPacket(reason);
                            this.send(packet, PacketSendListener.thenRun(() -> this.disconnect(details)));
                        } else {
                            this.disconnect(details);
                        }

                        this.setReadOnly();
                    } else {
                        LOGGER.debug("Double fault", cause);
                        this.disconnect(details);
                    }
                }
            }
        }
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) io.papermc.paper.util.TraceUtil.printStackTrace(cause); // Spigot // Paper
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Packet<?> packet) {
        if (this.channel.isOpen()) {
            PacketListener packetListener = this.packetListener;
            if (packetListener == null) {
                throw new IllegalStateException("Received a packet before the packet listener was initialized");
            }
            // Paper start - packet limiter
            if (this.stopReadingPackets) {
                return;
            }
            if (this.allPacketCounts != null ||
                io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.overrides.containsKey(packet.getClass())) {
                long time = System.nanoTime();
                synchronized (PACKET_LIMIT_LOCK) {
                    if (this.allPacketCounts != null) {
                        this.allPacketCounts.updateAndAdd(1, time);
                        if (this.allPacketCounts.getRate() >= io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.maxPacketRate()) {
                            this.killForPacketSpam();
                            return;
                        }
                    }

                    for (Class<?> check = packet.getClass(); check != Object.class; check = check.getSuperclass()) {
                        io.papermc.paper.configuration.GlobalConfiguration.PacketLimiter.PacketLimit packetSpecificLimit =
                            io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.overrides.get(check);
                        if (packetSpecificLimit == null || !packetSpecificLimit.isEnabled()) {
                            continue;
                        }
                        io.papermc.paper.util.IntervalledCounter counter = this.packetSpecificLimits.computeIfAbsent((Class)check, (clazz) -> {
                            return new io.papermc.paper.util.IntervalledCounter((long)(packetSpecificLimit.interval() * 1.0e9));
                        });
                        counter.updateAndAdd(1, time);
                        if (counter.getRate() >= packetSpecificLimit.maxPacketRate()) {
                            switch (packetSpecificLimit.action()) {
                                case DROP:
                                    return;
                                case KICK:
                                    String deobfedPacketName = check.getName();

                                    String playerName;
                                    if (this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl impl) {
                                        playerName = impl.getOwner().name();
                                    } else {
                                        playerName = this.getLoggableAddress(net.minecraft.server.MinecraftServer.getServer().logIPs());
                                    }

                                    Connection.LOGGER.warn("{} kicked for packet spamming: {}", playerName, deobfedPacketName.substring(deobfedPacketName.lastIndexOf(".") + 1));
                                    this.killForPacketSpam();
                                    return;
                            }
                        }
                    }
                }
            }
            // Paper end - packet limiter
            if (packetListener.shouldHandleMessage(packet)) {
                try {
                    genericsFtw(packet, packetListener);
                } catch (RunningOnDifferentThreadException var5) {
                } catch (io.papermc.paper.util.ServerStopRejectedExecutionException ignored) { // Paper - do not prematurely disconnect players on stop
                } catch (RejectedExecutionException ignored) {
                    this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
                } catch (ClassCastException exception) {
                    LOGGER.error("Received {} that couldn't be processed", packet.getClass(), exception);
                    this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
                }

                this.receivedPackets++;
            }
        }
    }

    private static <T extends PacketListener> void genericsFtw(final Packet<T> packet, final PacketListener listener) {
        packet.handle((T)listener);
    }

    private void validateListener(final ProtocolInfo<?> protocol, final PacketListener packetListener) {
        Objects.requireNonNull(packetListener, "packetListener");
        PacketFlow listenerFlow = packetListener.flow();
        if (listenerFlow != this.receiving) {
            throw new IllegalStateException("Trying to set listener for wrong side: connection is " + this.receiving + ", but listener is " + listenerFlow);
        }

        ConnectionProtocol listenerProtocol = packetListener.protocol();
        if (protocol.id() != listenerProtocol) {
            throw new IllegalStateException("Listener protocol (" + listenerProtocol + ") does not match requested one " + protocol);
        }
    }

    private static void syncAfterConfigurationChange(final ChannelFuture future) {
        try {
            future.syncUninterruptibly();
        } catch (Exception e) {
            if (e instanceof ClosedChannelException) {
                LOGGER.info("Connection closed during protocol change");
            } else {
                throw e;
            }
        }
    }

    public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocol, final T packetListener) {
        this.validateListener(protocol, packetListener);
        if (protocol.flow() != this.getReceiving()) {
            throw new IllegalStateException("Invalid inbound protocol: " + protocol.id());
        }

        this.packetListener = packetListener;
        this.disconnectListener = null;
        UnconfiguredPipelineHandler.InboundConfigurationTask configMessage = UnconfiguredPipelineHandler.setupInboundProtocol(protocol);
        BundlerInfo bundlerInfo = protocol.bundlerInfo();
        if (bundlerInfo != null) {
            PacketBundlePacker newBundler = new PacketBundlePacker(bundlerInfo);
            configMessage = configMessage.andThen(ctx -> ctx.pipeline().addAfter(HandlerNames.DECODER, HandlerNames.BUNDLER, newBundler));
        }

        syncAfterConfigurationChange(this.channel.writeAndFlush(configMessage));
    }

    public void setupOutboundProtocol(final ProtocolInfo<?> protocol) {
        if (protocol.flow() != this.getSending()) {
            throw new IllegalStateException("Invalid outbound protocol: " + protocol.id());
        }

        UnconfiguredPipelineHandler.OutboundConfigurationTask configMessage = UnconfiguredPipelineHandler.setupOutboundProtocol(protocol);
        BundlerInfo bundlerInfo = protocol.bundlerInfo();
        if (bundlerInfo != null) {
            PacketBundleUnpacker newUnbundler = new PacketBundleUnpacker(bundlerInfo);
            configMessage = configMessage.andThen(ctx -> ctx.pipeline().addAfter(HandlerNames.ENCODER, HandlerNames.UNBUNDLER, newUnbundler));
        }

        boolean isLoginProtocol = protocol.id() == ConnectionProtocol.LOGIN;
        syncAfterConfigurationChange(this.channel.writeAndFlush(configMessage.andThen(ctx -> this.sendLoginDisconnect = isLoginProtocol)));
    }

    public void setListenerForServerboundHandshake(final PacketListener packetListener) {
        if (this.packetListener != null) {
            throw new IllegalStateException("Listener already set");
        }

        if (this.receiving == PacketFlow.SERVERBOUND && packetListener.flow() == PacketFlow.SERVERBOUND && packetListener.protocol() == INITIAL_PROTOCOL.id()) {
            this.packetListener = packetListener;
        } else {
            throw new IllegalStateException("Invalid initial listener");
        }
    }

    public void initiateServerboundStatusConnection(final String hostName, final int port, final ClientStatusPacketListener listener) {
        this.initiateServerboundConnection(hostName, port, StatusProtocols.SERVERBOUND, StatusProtocols.CLIENTBOUND, listener, ClientIntent.STATUS);
    }

    public void initiateServerboundPlayConnection(final String hostName, final int port, final ClientLoginPacketListener listener) {
        this.initiateServerboundConnection(hostName, port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, listener, ClientIntent.LOGIN);
    }

    public <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundPlayConnection(
        final String hostName, final int port, final ProtocolInfo<S> outbound, final ProtocolInfo<C> inbound, final C listener, final boolean transfer
    ) {
        this.initiateServerboundConnection(hostName, port, outbound, inbound, listener, transfer ? ClientIntent.TRANSFER : ClientIntent.LOGIN);
    }

    private <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundConnection(
        final String hostName, final int port, final ProtocolInfo<S> outbound, final ProtocolInfo<C> inbound, final C listener, final ClientIntent intent
    ) {
        if (outbound.id() != inbound.id()) {
            throw new IllegalStateException("Mismatched initial protocols");
        }

        this.disconnectListener = listener;
        this.runOnceConnected(connection -> {
            this.setupInboundProtocol(inbound, listener);
            connection.sendPacket(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(), hostName, port, intent), null, true);
            this.setupOutboundProtocol(outbound);
        });
    }

    public void send(final Packet<?> packet) {
        this.send(packet, null);
    }

    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener) {
        this.send(packet, listener, true);
    }

    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        // Paper start - Optimize network: Handle oversized packets better
        final boolean connected = this.isConnected();
        if (!connected && !this.preparing) {
            return;
        }

        packet.onPacketDispatch(this.getPlayer());
        if (false && connected && (InnerUtil.canSendImmediate(this, packet) // Folia - region threading - connection fixes
            || (io.papermc.paper.util.MCUtil.isMainThread() && packet.isReady() && this.pendingActions.isEmpty()
            && (packet.getExtraPackets() == null || packet.getExtraPackets().isEmpty())))) {
            this.sendPacket(packet, listener, flush);
        } else {
            // Write the packets to the queue, then flush - antixray hooks there already
            final java.util.List<Packet<?>> extraPackets = InnerUtil.buildExtraPackets(packet);
            final boolean hasExtraPackets = extraPackets != null && !extraPackets.isEmpty();
            if (!hasExtraPackets) {
                this.pendingActions.add(new PacketSendAction(packet, listener, flush));
            } else {
                final java.util.List<PacketSendAction> actions = new java.util.ArrayList<>(1 + extraPackets.size());
                actions.add(new PacketSendAction(packet, null, false)); // Delay the future listener until the end of the extra packets

                for (int i = 0, len = extraPackets.size(); i < len;) {
                    final Packet<?> extraPacket = extraPackets.get(i);
                    final boolean end = ++i == len;
                    actions.add(new PacketSendAction(extraPacket, end ? listener : null, end)); // Append listener to the end
                }

                this.pendingActions.addAll(actions);
            }

            this.flushQueue();
            // Paper end - Optimize network
        }
    }

    public void runOnceConnected(final Consumer<Connection> action) {
        if (false && this.isConnected()) { // Folia - region threading - connection fixes
            this.flushQueue();
            action.accept(this);
        } else {
            this.pendingActions.add(new WrappedConsumer(action)); // Paper - Optimize network
            this.flushQueue(); // Folia - region threading - connection fixes
        }
    }

    private void sendPacket(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        this.sentPackets++;
        if (this.channel.eventLoop().inEventLoop()) {
            this.doSendPacket(packet, listener, flush);
        } else {
            this.channel.eventLoop().execute(() -> this.doSendPacket(packet, listener, flush));
        }
    }

    private void doSendPacket(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        // Paper start - Optimize network
        final net.minecraft.server.level.ServerPlayer player = this.getPlayer();
        if (!this.isConnected()) {
            packet.onPacketDispatchFinish(player, null);
            return;
        }
        try {
        final ChannelFuture future;
        // Paper end - Optimize network
        if (listener != null) {
            future = flush ? this.channel.writeAndFlush(packet) : this.channel.write(packet); // Paper - Optimize network
            future.addListener(listener);
        } else if (flush) {
            future = this.channel.writeAndFlush(packet, this.channel.voidPromise()); // Paper - Optimize network
        } else {
            future = this.channel.write(packet, this.channel.voidPromise()); // Paper - Optimize network
        }

        // Paper start - Optimize network
        if (packet.hasFinishListener()) {
            future.addListener((ChannelFutureListener) f -> packet.onPacketDispatchFinish(player, f));
        }
        } catch (final Exception e) {
            LOGGER.error("NetworkException: {}", player, e);
            Component reason = Component.translatable("disconnect.genericReason", "Internal Exception: " + e.getMessage());
            this.send(new ClientboundDisconnectPacket(reason), PacketSendListener.thenRun(() -> this.disconnect(reason)));
            packet.onPacketDispatchFinish(player, null);
        }
        // Paper end - Optimize network
    }

    public void flushChannel() {
        if (false && this.isConnected()) { // Folia - region threading - connection fixes
            this.flush();
        } else {
            this.pendingActions.add(new WrappedConsumer(Connection::flush)); // Paper - Optimize network
            this.flushQueue(); // Folia - region threading - connection fixes
        }
    }

    private void flush() {
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.flush();
        } else {
            this.channel.eventLoop().execute(() -> this.channel.flush());
        }
    }

    // Paper start - Optimize network: Rewrite this to be safer if ran off main thread
    private boolean flushQueue() {
        return this.processQueue(); // Folia - region threading - connection fixes
    }

    // Folia start - region threading - connection fixes
    // allow only one thread to be flushing the queue at once to ensure packets are written in the order they are sent
    // into the queue
    private final java.util.concurrent.atomic.AtomicBoolean flushingQueue = new java.util.concurrent.atomic.AtomicBoolean();

    private static boolean canWrite(WrappedConsumer queued) {
        return queued != null && (!(queued instanceof PacketSendAction packet) || packet.packet.isReady());
    }

    private boolean canWritePackets() {
        return canWrite(this.pendingActions.peek());
    }
    // Folia end - region threading - connection fixes

    private boolean processQueue() {
        // Folia start - region threading - connection fixes
        if (!this.isConnected()) {
            return true;
        }

        while (this.canWritePackets()) {
            final boolean set = this.flushingQueue.getAndSet(true);
            try {
                if (set) {
                    // we didn't acquire the lock, break
                    return false;
                }

                ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<WrappedConsumer> queue =
                    (ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<WrappedConsumer>)this.pendingActions;
                WrappedConsumer holder;
                for (;;) {
                    // synchronise so that queue clears appear atomic
                    synchronized (queue) {
                        holder = queue.pollIf(Connection::canWrite);
                    }
                    if (holder == null) {
                        break;
                    }

                    holder.accept(this);
                }

            } finally {
                if (!set) {
                    this.flushingQueue.set(false);
                }
            }
        }

        return true;
        // Folia end - region threading - connection fixes
    }
    // Paper end - Optimize network

    private static final int MAX_PER_TICK = io.papermc.paper.configuration.GlobalConfiguration.get().misc.maxJoinsPerTick; // Paper - Buffer joins to world
    private static int joinAttemptsThisTick; // Paper - Buffer joins to world
    private static int currTick; // Paper - Buffer joins to world
    public void tick() {
        this.flushQueue();
        // Folia start - this is broken
//        // Paper start - Buffer joins to world
//        if (Connection.currTick != net.minecraft.server.MinecraftServer.currentTick) {
//            Connection.currTick = net.minecraft.server.MinecraftServer.currentTick;
//            Connection.joinAttemptsThisTick = 0;
//        }
//        // Paper end - Buffer joins to world
        // Folia end - this is broken
        // Folia start - region threading
        // handle disconnect requests, but only after flushQueue()
        DisconnectReq disconnectReq;
        while ((disconnectReq = this.disconnectReqs.poll()) != null) {
            PacketListener packetlistener = this.packetListener;

            if (packetlistener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl loginPacketListener) {
                loginPacketListener.disconnect(disconnectReq.details.reason());
                // this doesn't fail, so abort any further attempts
                return;
            } else if (packetlistener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl commonPacketListener) {
                commonPacketListener.disconnect(disconnectReq.details);
                // may be cancelled by a plugin, if not cancelled then any further calls do nothing
                continue;
            } else {
                this.disconnect(disconnectReq.details);
                this.setReadOnly();
                return;
            }
        }
        if (!this.isConnected()) {
            // disconnected from above
            this.handleDisconnection();
            return;
        }
        // Folia end - region threading
        if (this.packetListener instanceof TickablePacketListener tickable) {
            // Paper start - Buffer joins to world
            if (true) { // Folia - region threading
            // Paper start - detailed watchdog information
            net.minecraft.network.PacketProcessor.packetProcessing.push(this.packetListener);
            try {
            tickable.tick();
            } finally {
                net.minecraft.network.PacketProcessor.packetProcessing.pop();
            } // Paper end - detailed watchdog information
            } // Paper end - Buffer joins to world
        }

        if (!this.isConnected()) {// Folia - region threading - it's fine to call if it is already handled, as it no longer logs
            this.handleDisconnection();
        }

        if (this.channel != null) {
            if (enableExplicitFlush) this.channel.eventLoop().execute(() -> this.channel.flush()); // Paper - Disable explicit connection flushing; we don't need to explicit flush here, but allow opt in incase issues are found to a better version
        }

        if (this.tickCount++ % 20 == 0) {
            this.tickSecond();
        }

        if (this.bandwidthDebugMonitor != null) {
            this.bandwidthDebugMonitor.tick();
        }
    }

    protected void tickSecond() {
        this.averageSentPackets = Mth.lerp(0.75F, this.sentPackets, this.averageSentPackets);
        this.averageReceivedPackets = Mth.lerp(0.75F, this.receivedPackets, this.averageReceivedPackets);
        this.sentPackets = 0;
        this.receivedPackets = 0;
    }

    public SocketAddress getRemoteAddress() {
        return this.address;
    }

    public String getLoggableAddress(final boolean logIPs) {
        if (this.address == null) {
            return "local";
        } else {
            return logIPs ? this.address.toString() : "IP hidden";
        }
    }

    public void disconnect(final Component reason) {
        this.disconnect(new DisconnectionDetails(reason));
    }

    public void disconnect(final DisconnectionDetails details) {
        this.preparing = false; // Spigot
        this.clearPacketQueue(); // Paper - Optimize network
        if (this.channel == null) {
            this.delayedDisconnect = details;
        }

        if (this.isConnected()) {
            this.channel.close().awaitUninterruptibly();
            this.disconnectionDetails = details;
        }
        this.becomeActive = true; // Folia - region threading
    }

    public boolean isMemoryConnection() {
        return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
    }

    public PacketFlow getReceiving() {
        return this.receiving;
    }

    public PacketFlow getSending() {
        return this.receiving.getOpposite();
    }

    public static Connection connectToServer(
        final InetSocketAddress address, final EventLoopGroupHolder eventLoopGroupHolder, final @Nullable LocalSampleLogger bandwidthLogger
    ) {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        if (bandwidthLogger != null) {
            connection.setBandwidthLogger(bandwidthLogger);
        }

        ChannelFuture connect = connect(address, eventLoopGroupHolder, connection);
        connect.syncUninterruptibly();
        return connection;
    }

    public static ChannelFuture connect(final InetSocketAddress address, final EventLoopGroupHolder eventLoopGroupHolder, final Connection connection) {
        return new Bootstrap().group(eventLoopGroupHolder.eventLoopGroup()).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel channel) {
                try {
                    channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                } catch (ChannelException var3) {
                }

                ChannelPipeline pipeline = channel.pipeline().addLast(HandlerNames.TIMEOUT, new ReadTimeoutHandler(30));
                Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, connection.bandwidthDebugMonitor);
                connection.configurePacketHandler(pipeline);
            }
        }).channel(eventLoopGroupHolder.channelCls()).connect(address.getAddress(), address.getPort());
    }

    private static String outboundHandlerName(final boolean configureOutbound) {
        return configureOutbound ? HandlerNames.ENCODER : HandlerNames.OUTBOUND_CONFIG;
    }

    private static String inboundHandlerName(final boolean configureInbound) {
        return configureInbound ? HandlerNames.DECODER : HandlerNames.INBOUND_CONFIG;
    }

    public void configurePacketHandler(final ChannelPipeline pipeline) {
        pipeline.addLast("hackfix", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                super.write(ctx, msg, promise);
            }
        }).addLast(HandlerNames.PACKET_HANDLER, this);
    }

    public static void configureSerialization(
        final ChannelPipeline pipeline, final PacketFlow inboundDirection, final boolean local, final @Nullable BandwidthDebugMonitor monitor
    ) {
        PacketFlow outboundDirection = inboundDirection.getOpposite();
        boolean configureInbound = inboundDirection == PacketFlow.SERVERBOUND;
        boolean configureOutbound = outboundDirection == PacketFlow.SERVERBOUND;
        pipeline.addLast(HandlerNames.SPLITTER, createFrameDecoder(monitor, local))
            .addLast(new FlowControlHandler())
            .addLast(inboundHandlerName(configureInbound), configureInbound ? new PacketDecoder<>(INITIAL_PROTOCOL) : new UnconfiguredPipelineHandler.Inbound())
            .addLast(HandlerNames.PREPENDER, createFrameEncoder(local))
            .addLast(
                outboundHandlerName(configureOutbound), configureOutbound ? new PacketEncoder<>(INITIAL_PROTOCOL) : new UnconfiguredPipelineHandler.Outbound()
            );
    }

    private static ChannelOutboundHandler createFrameEncoder(final boolean local) {
        return local ? new LocalFrameEncoder() : new Varint21LengthFieldPrepender();
    }

    private static ChannelInboundHandler createFrameDecoder(final @Nullable BandwidthDebugMonitor monitor, final boolean local) {
        if (!local) {
            return new Varint21FrameDecoder(monitor);
        } else {
            return monitor != null ? new MonitoredLocalFrameDecoder(monitor) : new LocalFrameDecoder();
        }
    }

    public static void configureInMemoryPipeline(final ChannelPipeline pipeline, final PacketFlow packetFlow) {
        configureSerialization(pipeline, packetFlow, true, null);
    }

    public static Connection connectToLocalServer(final SocketAddress address) {
        final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        new Bootstrap().group(EventLoopGroupHolder.local().eventLoopGroup()).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                Connection.configureInMemoryPipeline(pipeline, PacketFlow.CLIENTBOUND);
                connection.configurePacketHandler(pipeline);
            }
        }).channel(EventLoopGroupHolder.local().channelCls()).connect(address).syncUninterruptibly();
        return connection;
    }

    public static Connection fromChannel(final Channel channel, final PacketFlow flow, final @Nullable LocalSampleLogger bandwidthLogger) {
        Connection connection = new Connection(flow);
        if (bandwidthLogger != null) {
            connection.setBandwidthLogger(bandwidthLogger);
        }

        ChannelPipeline pipeline = channel.pipeline().addLast(HandlerNames.TIMEOUT, new ReadTimeoutHandler(30));
        configureSerialization(pipeline, flow, false, connection.bandwidthDebugMonitor);
        connection.configurePacketHandler(pipeline);
        EventLoopGroupHolder.local().eventLoopGroup().register(channel).syncUninterruptibly();
        return connection;
    }

    // Paper start - Use Velocity cipher
    private boolean encrypted;

    public void setEncryptionKey(final javax.crypto.SecretKey key) throws net.minecraft.util.CryptException {
        if (!this.encrypted) {
            try {
                final com.velocitypowered.natives.encryption.VelocityCipher decryptionCipher = com.velocitypowered.natives.util.Natives.cipher.get().forDecryption(key);
                final com.velocitypowered.natives.encryption.VelocityCipher encryptionCipher = com.velocitypowered.natives.util.Natives.cipher.get().forEncryption(key);

                this.encrypted = true;
                this.channel.pipeline().addBefore(HandlerNames.SPLITTER, HandlerNames.DECRYPT, new CipherDecoder(decryptionCipher));
                this.channel.pipeline().addBefore(HandlerNames.PREPENDER, HandlerNames.ENCRYPT, new CipherEncoder(encryptionCipher));
            } catch (final java.security.GeneralSecurityException e) {
                throw new net.minecraft.util.CryptException(e);
            }
        }
    }
    // Paper end - Use Velocity cipher

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen();
    }

    public boolean isConnecting() {
        return this.channel == null;
    }

    public @Nullable PacketListener getPacketListener() {
        return this.packetListener;
    }

    public @Nullable DisconnectionDetails getDisconnectionDetails() {
        return this.disconnectionDetails;
    }

    public void setReadOnly() {
        if (this.channel != null) {
            this.channel.config().setAutoRead(false);
        }
    }

    // Paper start - add proper async disconnect
    public void enableAutoRead() {
        if (this.channel != null) {
            this.channel.config().setAutoRead(true);
        }
    }
    // Paper end - add proper async disconnect

    public void setupCompression(final int threshold, final boolean validateDecompressed) {
        if (threshold >= 0) {
            com.velocitypowered.natives.compression.VelocityCompressor compressor = com.velocitypowered.natives.util.Natives.compress.get().create(io.papermc.paper.configuration.GlobalConfiguration.get().misc.compressionLevel.or(-1)); // Paper - Use Velocity cipher
            if (this.channel.pipeline().get(HandlerNames.DECOMPRESS) instanceof CompressionDecoder compressionDecoder) {
                compressionDecoder.setThreshold(compressor, threshold, validateDecompressed); // Paper - Use Velocity cipher
            } else {
                this.channel.pipeline().addAfter(HandlerNames.SPLITTER, HandlerNames.DECOMPRESS, new CompressionDecoder(compressor, threshold, validateDecompressed)); // Paper - Use Velocity cipher
            }

            if (this.channel.pipeline().get(HandlerNames.COMPRESS) instanceof CompressionEncoder compressionEncoder) {
                compressionEncoder.setThreshold(threshold);
            } else {
                this.channel.pipeline().addAfter(HandlerNames.PREPENDER, HandlerNames.COMPRESS, new CompressionEncoder(compressor, threshold)); // Paper - Use Velocity cipher
            }
            this.channel.pipeline().fireUserEventTriggered(io.papermc.paper.network.ConnectionEvent.COMPRESSION_THRESHOLD_SET); // Paper - Add Channel initialization listeners
        } else {
            if (this.channel.pipeline().get(HandlerNames.DECOMPRESS) instanceof CompressionDecoder) {
                this.channel.pipeline().remove(HandlerNames.DECOMPRESS);
            }

            if (this.channel.pipeline().get(HandlerNames.COMPRESS) instanceof CompressionEncoder) {
                this.channel.pipeline().remove(HandlerNames.COMPRESS);
            }
            this.channel.pipeline().fireUserEventTriggered(io.papermc.paper.network.ConnectionEvent.COMPRESSION_DISABLED); // Paper - Add Channel initialization listeners
        }
    }

    public void handleDisconnection() {
        if (this.channel != null && !this.channel.isOpen()) {
            if (!this.disconnectionHandled.compareAndSet(false, true)) { // Folia - region threading - may be called concurrently during configuration stage
                // LOGGER.warn("handleDisconnection() called twice"); // Paper - Don't log useless message
            } else {
                //this.disconnectionHandled = true; // Folia - region threading - may be called concurrently during configuration stage - set above
                PacketListener packetListener = this.getPacketListener();
                PacketListener disconnectListener = packetListener != null ? packetListener : this.disconnectListener;
                if (disconnectListener != null) {
                    DisconnectionDetails details = Objects.requireNonNullElseGet(
                        this.getDisconnectionDetails(), () -> new DisconnectionDetails(Component.translatable("multiplayer.disconnect.generic"))
                    );
                    disconnectListener.onDisconnect(details);
                }
                this.clearPacketQueue(); // Paper - Optimize network
                // Paper start - Add PlayerConnectionCloseEvent
                if (packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl commonPacketListener) {
                    /* Player was logged in, either game listener or configuration listener */
                    final com.mojang.authlib.GameProfile profile = commonPacketListener.getOwner();
                    new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.id(),
                        profile.name(), ((InetSocketAddress) this.address).getAddress(), false).callEvent();
                } else if (packetListener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl loginListener) {
                    /* Player is login stage */
                    switch (loginListener.state) {
                        case VERIFYING:
                        case WAITING_FOR_DUPE_DISCONNECT:
                        case PROTOCOL_SWITCHING:
                        case ACCEPTED:
                            final com.mojang.authlib.GameProfile profile = loginListener.authenticatedProfile; /* Should be non-null at this stage */
                            new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.id(), profile.name(),
                                ((InetSocketAddress) this.address).getAddress(), false).callEvent();
                    }
                }
                // Paper end - Add PlayerConnectionCloseEvent
                // Folia start - region threading
                if (packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl commonPacketListener) {
                    net.minecraft.server.MinecraftServer.getServer().getPlayerList().removeConnection(
                            commonPacketListener.getOwner().name(),
                            commonPacketListener.getOwner().id(), this
                    );
                } else if (packetListener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl loginPacketListener) {
                    if (loginPacketListener.state.ordinal() >= net.minecraft.server.network.ServerLoginPacketListenerImpl.State.VERIFYING.ordinal()) {
                        net.minecraft.server.MinecraftServer.getServer().getPlayerList().removeConnection(
                                loginPacketListener.authenticatedProfile.name(),
                                loginPacketListener.authenticatedProfile.id(), this
                        );
                    }
                }
                // Folia end - region threading
            }
        }
    }

    public float getAverageReceivedPackets() {
        return this.averageReceivedPackets;
    }

    public float getAverageSentPackets() {
        return this.averageSentPackets;
    }

    public void setBandwidthLogger(final LocalSampleLogger bandwidthLogger) {
        this.bandwidthDebugMonitor = new BandwidthDebugMonitor(bandwidthLogger);
    }

    public void setIntendedProfileId(final UUID profileId) {
        this.intendedProfileId = profileId;
    }

    public @Nullable UUID getIntendedProfileId() {
        return this.intendedProfileId;
    }

    // Paper start - Optimize network
    public void clearPacketQueue() {
        final net.minecraft.server.level.ServerPlayer player = getPlayer();
        // Folia start - region threading - connection fixes
        java.util.List<Connection.PacketSendAction> queuedPackets = new java.util.ArrayList<>();
        // synchronise so that flushQueue does not poll values while the queue is being cleared
        synchronized (this.pendingActions) {
            Connection.WrappedConsumer consumer;
            while ((consumer = this.pendingActions.poll()) != null) {
                if (consumer instanceof Connection.PacketSendAction packetHolder) {
                    queuedPackets.add(packetHolder);
                }
            }
        }

        for (Connection.PacketSendAction queuedPacket : queuedPackets) {
            Packet<?> packet = queuedPacket.packet;
            if (packet.hasFinishListener()) {
                packet.onPacketDispatchFinish(player, null);
            }
        }
        // Folia end - region threading - connection fixes
    }

    private static final class InnerUtil { // Attempt to hide these methods from ProtocolLib, so it doesn't accidently pick them up.

        private static java.util.@Nullable List<Packet<?>> buildExtraPackets(final Packet<?> packet) {
            final java.util.List<Packet<?>> extra = packet.getExtraPackets();
            if (extra == null || extra.isEmpty()) {
                return null;
            }

            final java.util.List<Packet<?>> ret = new java.util.ArrayList<>(1 + extra.size());
            buildExtraPackets0(extra, ret);
            return ret;
        }

        private static void buildExtraPackets0(final java.util.List<Packet<?>> extraPackets, final java.util.List<Packet<?>> into) {
            for (final Packet<?> extra : extraPackets) {
                into.add(extra);
                final java.util.List<Packet<?>> extraExtra = extra.getExtraPackets();
                if (extraExtra != null && !extraExtra.isEmpty()) {
                    buildExtraPackets0(extraExtra, into);
                }
            }
        }

        private static boolean canSendImmediate(final Connection connection, final net.minecraft.network.protocol.Packet<?> packet) {
            return connection.isPending || connection.packetListener.protocol() != ConnectionProtocol.PLAY ||
                packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundClearTitlesPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSoundEntityPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundStopSoundPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundBossEventPacket ||
                packet instanceof net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
        }
    }

    private static class WrappedConsumer implements Consumer<Connection> {
        private final Consumer<Connection> delegate;
        private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean(false);

        private WrappedConsumer(final Consumer<Connection> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(final Connection connection) {
            this.delegate.accept(connection);
        }

        public boolean tryMarkConsumed() {
            return consumed.compareAndSet(false, true);
        }

        public boolean isConsumed() {
            return consumed.get();
        }
    }

    private static final class PacketSendAction extends WrappedConsumer {
        private final Packet<?> packet;

        private PacketSendAction(final Packet<?> packet, @Nullable final ChannelFutureListener channelFutureListener, final boolean flush) {
            super(connection -> connection.sendPacket(packet, channelFutureListener, flush));
            this.packet = packet;
        }
    }
    // Paper end - Optimize network
}

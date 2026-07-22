package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class ServerCommonPacketListenerImpl implements ServerCommonPacketListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int LATENCY_CHECK_INTERVAL = 15000;
    private static final int CLOSED_LISTENER_TIMEOUT = 15000;
    private static final Component TIMEOUT_DISCONNECTION_MESSAGE = Component.translatable("disconnect.timeout");
    static final Component DISCONNECT_UNEXPECTED_QUERY = Component.translatable("multiplayer.disconnect.unexpected_query_response");
    protected final MinecraftServer server;
    public final Connection connection; // Paper
    private final boolean transferred;
    //private long keepAliveTime; // Paper - improve keepalives
    //private boolean keepAlivePending; // Paper - improve keepalives
    //private long keepAliveChallenge; // Paper - improve keepalives
    private long closedListenerTime;
    private boolean closed = false;
    private volatile int latency; // Paper - improve keepalives - make volatile
    private final io.papermc.paper.util.KeepAlive keepAlive; // Paper - improve keepalives
    private volatile boolean suspendFlushingOnServerThread = false;
    // CraftBukkit start
    public final org.bukkit.craftbukkit.CraftServer cserver;
    // CraftBukkit end
    public final java.util.Map<java.util.UUID, net.kyori.adventure.resource.ResourcePackCallback> packCallbacks = new java.util.concurrent.ConcurrentHashMap<>(); // Paper - adventure resource pack callbacks
    private static final long KEEPALIVE_LIMIT = Long.getLong("paper.playerconnection.keepalive", 30) * 1000; // Paper - provide property to set keepalive limit
    protected static final net.minecraft.resources.Identifier MINECRAFT_BRAND = net.minecraft.resources.Identifier.withDefaultNamespace("brand"); // Paper - Brand support
    // Paper start - retain certain values
    public @Nullable String clientBrand;
    public final java.util.Set<String> pluginMessagerChannels;
    // Paper end - retain certain values

    public ServerCommonPacketListenerImpl(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
        this.server = server;
        this.connection = connection;
        //this.keepAliveTime = Util.getMillis(); // Paper - improve keepalives
        this.latency = cookie.latency();
        this.transferred = cookie.transferred();
        // Paper start
        this.clientBrand = cookie.clientBrand();
        this.cserver = server.server;
        this.pluginMessagerChannels = cookie.channels();
        this.keepAlive = cookie.keepAlive();
        // Paper end
    }

    // Paper start - configuration phase API
    public abstract io.papermc.paper.connection.PlayerCommonConnection getApiConnection();

    public abstract net.kyori.adventure.audience.Audience getAudience();
    // Paper end - configuration phase API

    private void close() {
        if (!this.closed) {
            this.closedListenerTime = Util.getMillis();
            this.closed = true;
        }
    }

    @Override
    public void onDisconnect(final DisconnectionDetails details) {
        if (this.isSingleplayerOwner()) {
            LOGGER.info("Stopping singleplayer server as player logged out");
            this.server.halt(false);
        }
    }

    @Override
    public void onPacketError(final Packet packet, final Exception e) throws ReportedException {
        ServerCommonPacketListener.super.onPacketError(packet, e);
        this.server.reportPacketHandlingException(e, packet.type());
    }

    @Override
    public void handleKeepAlive(final ServerboundKeepAlivePacket packet) {
        // Paper start - improve keepalives
        long now = System.nanoTime();
        io.papermc.paper.util.KeepAlive.PendingKeepAlive pending = this.keepAlive.pendingKeepAlives.peek();
        if (pending != null && pending.challengeId() == packet.getId()) {
            this.keepAlive.pendingKeepAlives.remove(pending);

            io.papermc.paper.util.KeepAlive.KeepAliveResponse response = new io.papermc.paper.util.KeepAlive.KeepAliveResponse(pending.txTimeNS(), now);

            this.keepAlive.pingCalculator1m.update(response);
            this.keepAlive.pingCalculator5s.update(response);

            this.latency = this.keepAlive.pingCalculator5s.getAvgLatencyMS();
            return;
        }

        for (java.util.Iterator<io.papermc.paper.util.KeepAlive.PendingKeepAlive> itr = this.keepAlive.pendingKeepAlives.iterator(); itr.hasNext();) {
            io.papermc.paper.util.KeepAlive.PendingKeepAlive ka = itr.next();
            if (ka.challengeId() == packet.getId()) {
                itr.remove();

                LOGGER.info("Disconnecting {} for sending keepalive response ({}) out-of-order!", this.playerProfile().name(), packet.getId());
                this.disconnectAsync(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT);
                return;
            }
        }

        LOGGER.info("Disconnecting {} for sending keepalive response ({}) without matching challenge!", this.playerProfile().name(), packet.getId());
        this.disconnectAsync(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT);
        // Paper end - improve keepalives
    }

    @Override
    public void handlePong(final ServerboundPongPacket serverboundPongPacket) {
    }

    // Paper start
    public static final net.minecraft.resources.Identifier CUSTOM_REGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("register");
    private static final net.minecraft.resources.Identifier CUSTOM_UNREGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("unregister");
    // Paper end

    @Override
    public void handleCustomPayload(final ServerboundCustomPayloadPacket packet) {
        // Paper start
        if (!(packet.payload() instanceof final net.minecraft.network.protocol.common.custom.DiscardedPayload discardedPayload)) {
            return;
        }

        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());

        final net.minecraft.resources.Identifier identifier = packet.payload().type().id();
        final byte[] data = discardedPayload.data();
        try {
            final boolean registerChannel = CUSTOM_REGISTER.equals(identifier);
            if (registerChannel || CUSTOM_UNREGISTER.equals(identifier)) {
                // Strings separated by zeros instead of length prefixes
                int startIndex = 0;
                for (int i = 0; i < data.length; i++) {
                    final byte b = data[i];
                    if (b != 0) {
                        continue;
                    }

                    readChannelIdentifier(data, startIndex, i, registerChannel);
                    startIndex = i + 1;
                }

                // Read the last one
                readChannelIdentifier(data, startIndex, data.length, registerChannel);
                return;
            }

            if (identifier.equals(MINECRAFT_BRAND)) {
                this.clientBrand = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)).readUtf(256);
            }

            this.cserver.getMessenger().dispatchIncomingMessage(paperConnection(), identifier.toString(), data);
        } catch (final Exception e) {
            LOGGER.error("Couldn't handle custom payload on channel {}", identifier, e);
            this.disconnect(Component.literal("Invalid custom payload payload!"), io.papermc.paper.connection.DisconnectionReason.INVALID_PAYLOAD); // Paper - kick event cause
        }
    }

    private void readChannelIdentifier(final byte[] data, final int from, final int to, final boolean register) {
        io.papermc.paper.connection.PluginMessageBridgeImpl bridge = switch (this) {
            case ServerGamePacketListenerImpl gamePacketListener -> gamePacketListener.player.getBukkitEntity();
            case ServerConfigurationPacketListenerImpl commonPacketListener -> commonPacketListener.paperConnection;
            default -> null;
        };
        if (bridge == null) {
            return;
        }


        final int length = to - from;
        if (length == 0) {
            return;
        }

        final String channel = new String(data, from, length, java.nio.charset.StandardCharsets.US_ASCII);
        if (register) {
            bridge.addChannel(channel);
        } else {
            bridge.removeChannel(channel);
        }
    // Paper end
    }

    @Override
    public void handleCustomClickAction(final ServerboundCustomClickActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        this.server.handleCustomClickAction(packet.id(), packet.payload());
        // Paper start - Implement click callbacks with custom click action
        final io.papermc.paper.event.player.PaperPlayerCustomClickEvent event = new io.papermc.paper.event.player.PaperPlayerCustomClickEvent(io.papermc.paper.adventure.PaperAdventure.asAdventure(packet.id()), this.getApiConnection(), packet.payload().orElse(null));
        event.callEvent();
        io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
        io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.ADVENTURE_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
        // Paper end - Implement click callbacks with custom click action
    }

    @Override
    public void handleResourcePackResponse(final ServerboundResourcePackPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        if (packet.action() == ServerboundResourcePackPacket.Action.DECLINED && this.server.isResourcePackRequired()) {
            LOGGER.info("Disconnecting {} due to resource pack {} rejection", this.playerProfile().name(), packet.id());
            this.disconnect(Component.translatable("multiplayer.requiredTexturePrompt.disconnect"), io.papermc.paper.connection.DisconnectionReason.RESOURCE_PACK_REJECTION); // Paper - kick event cause
        }
        // Paper start - adventure pack callbacks
        // call the callbacks before the previously-existing event so the event has final say
        final net.kyori.adventure.resource.ResourcePackCallback callback;
        if (packet.action().isTerminal()) {
            callback = this.packCallbacks.remove(packet.id());
        } else {
            callback = this.packCallbacks.get(packet.id());
        }
        if (callback != null) {
            net.kyori.adventure.audience.Audience audience = switch (this) {
                case ServerGamePacketListenerImpl serverGamePacketListener -> serverGamePacketListener.getCraftPlayer();
                case ServerConfigurationPacketListenerImpl configurationPacketListener -> configurationPacketListener.paperConnection.getAudience();
                default -> throw new IllegalStateException("Unexpected value: " + this);
            };

            callback.packEventReceived(packet.id(), net.kyori.adventure.resource.ResourcePackStatus.valueOf(packet.action().name()), audience);
        }
        // Paper end
    }

    @Override
    public void handleCookieResponse(final ServerboundCookieResponsePacket packet) {
        if (this.paperConnection().handleCookieResponse(packet)) return; // Paper
        this.disconnect(DISCONNECT_UNEXPECTED_QUERY, io.papermc.paper.connection.DisconnectionReason.INVALID_COOKIE); // Paper - kick event cause
    }

    protected void keepConnectionAlive() {
        Profiler.get().push("keepAlive");
        long now = Util.getMillis();
        // Paper start - improve keepalives
        if (this.checkIfClosed(now)) {
            long currTime = System.nanoTime();

            if ((currTime - this.keepAlive.lastKeepAliveTx) >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1L)) {
                this.keepAlive.lastKeepAliveTx = currTime;

                io.papermc.paper.util.KeepAlive.PendingKeepAlive pka = new io.papermc.paper.util.KeepAlive.PendingKeepAlive(currTime, now);
                this.keepAlive.pendingKeepAlives.add(pka);
                this.send(new ClientboundKeepAlivePacket(pka.challengeId()));
            }

            io.papermc.paper.util.KeepAlive.PendingKeepAlive oldest = this.keepAlive.pendingKeepAlives.peek();
            if (oldest != null && (currTime - oldest.txTimeNS()) > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(KEEPALIVE_LIMIT)) {
                LOGGER.info("{} was kicked due to keepalive timeout!", this.playerProfile().name());
                this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT); // Paper - kick event cause
                // Paper end - improve keepalives
            }
        }

        Profiler.get().pop();
    }

    private boolean checkIfClosed(final long now) {
        if (this.closed) {
            if (now - this.closedListenerTime >= 15000L) {
                this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT); // Paper - kick event cause
            }

            return false;
        } else {
            return true;
        }
    }

    public void suspendFlushing() {
        this.suspendFlushingOnServerThread = true;
    }

    public void resumeFlushing() {
        this.suspendFlushingOnServerThread = false;
        this.connection.flushChannel();
    }

    public void send(final Packet<?> packet) {
        this.send(packet, null);
    }

    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener) {
        // CraftBukkit start
        if (packet == null) {
            return;
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket defaultSpawnPositionPacket && this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            serverGamePacketListener.player.compassTarget = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(defaultSpawnPositionPacket.respawnData().pos(), serverGamePacketListener.getPlayer().level());
        }
        // CraftBukkit end
        if (packet.isTerminal()) {
            this.close();
        }

        boolean flush = !this.suspendFlushingOnServerThread || !this.server.isSameThread();

        try {
            this.connection.send(packet, listener, flush);
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Sending packet");
            CrashReportCategory category = report.addCategory("Packet being sent");
            category.setDetail("Packet class", () -> packet.getClass().getCanonicalName());
            throw new ReportedException(report);
        }
    }

    @Deprecated // Paper - kick event causes TODO check on update: ensure all call to this are from the configuration phase
    public void disconnect(final Component reason) {
        // Paper start - kick event causes
        this.disconnect(reason, io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
    }

    public void disconnect(Component reason, io.papermc.paper.connection.DisconnectionReason cause) {
        this.disconnect(new DisconnectionDetails(reason, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(cause)));
        // Paper end - kick event causes
    }

    public void disconnect(final DisconnectionDetails details) {
        // CraftBukkit start - fire PlayerKickEvent
        if (!this.connection.isConnected()) {
            return;
        }
        // Folia start - region threading
        boolean onMain;
        if (this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            onMain = ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(serverGamePacketListener.player);
        } else if (this instanceof ServerConfigurationPacketListenerImpl configurationPacketListener) {
            net.minecraft.server.level.ServerPlayer player = configurationPacketListener.switchToMain;
            onMain = player == null ? io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread() : ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player);
        } else {
            onMain = io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread();
        }
        if (!onMain) {
            this.connection.disconnectSafely(details); // it HAS to be delayed/async to avoid deadlock if we try to wait for another region
            // Folia end - region threading
            return;
        }

        Component reason;
        Component leaveMessage;
        if (this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            net.kyori.adventure.text.Component rawLeaveMessage = net.kyori.adventure.text.Component.translatable("multiplayer.player.left", net.kyori.adventure.text.format.NamedTextColor.YELLOW, io.papermc.paper.configuration.GlobalConfiguration.get().messages.useDisplayNameInQuitMessage ? serverGamePacketListener.player.getBukkitEntity().displayName() : net.kyori.adventure.text.Component.text(serverGamePacketListener.player.getScoreboardName())); // Paper - Adventure

            net.minecraft.server.level.ServerPlayer player = serverGamePacketListener.player;
            org.bukkit.event.player.PlayerKickEvent.Cause cause = details.disconnectionReason().orElseThrow().game().orElse(org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN);
            org.bukkit.event.player.PlayerKickEvent event = new org.bukkit.event.player.PlayerKickEvent(
                    player.getBukkitEntity(),
                    io.papermc.paper.adventure.PaperAdventure.asAdventure(details.reason()),
                    rawLeaveMessage, cause

            );

            if (this.cserver.getServer().isRunning()) {
                this.cserver.getPluginManager().callEvent(event);
            }

            if (event.isCancelled()) {
                // Do not kick the player
                return;
            }

            reason = io.papermc.paper.adventure.PaperAdventure.asVanilla(event.reason());
            leaveMessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(event.leaveMessage());
            serverGamePacketListener.player.quitReason = org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED; // Paper - Add API for quit reason
            // Log kick to console *after* event was processed.
            switch (cause) {
                case FLYING_PLAYER -> LOGGER.warn("{} was kicked for floating too long!", player.getPlainTextName());
                case FLYING_VEHICLE -> LOGGER.warn("{} was kicked for floating a vehicle too long!", player.getPlainTextName());
            }
        } else {
            // TODO: Add event for config event
            reason = details.reason();
            leaveMessage = null;
        }

        // Send the possibly modified leave message
        this.disconnect0(new DisconnectionDetails(reason, details.report(), details.bugReportLink(), java.util.Optional.ofNullable(leaveMessage), details.disconnectionReason()));
    }

    private void disconnect0(final DisconnectionDetails details) {
        this.connection.send(new ClientboundDisconnectPacket(details.reason()), PacketSendListener.thenRun(() -> this.connection.disconnect(details)));
        this.connection.setReadOnly();
        this.connection.handleConnectionDisconnectOnNextTick = true; // Paper - Force kill connection ticking. Let this close the connection
    }

    // Paper start - add proper async disconnect
    public final void disconnectAsync(Component component, io.papermc.paper.connection.DisconnectionReason reason) {
        this.disconnectAsync(new DisconnectionDetails(component, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(reason)));
    }

    @Deprecated @io.papermc.paper.annotation.DoNotUse
    public final void disconnectAsync(Component component) {
        this.disconnectAsync(component, io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
    }

    public abstract void disconnectAsync(DisconnectionDetails disconnectionInfo);

    public boolean isTransferred() {
        return this.transferred;
    }

    public abstract io.papermc.paper.connection.PaperCommonConnection<?> paperConnection();
    // Paper end - add proper async disconnect

    protected boolean isSingleplayerOwner() {
        return this.server.isSingleplayerOwner(new NameAndId(this.playerProfile()));
    }

    protected abstract GameProfile playerProfile();

    @VisibleForDebug
    public GameProfile getOwner() {
        return this.playerProfile();
    }

    public int latency() {
        return this.latency;
    }

    protected CommonListenerCookie createCookie(final ClientInformation clientInformation) {
        // Paper start - listener handoff should reset pending keepalive expectations
        return new CommonListenerCookie(
            this.playerProfile(),
            this.latency,
            clientInformation,
            this.transferred,
            this.clientBrand,
            this.pluginMessagerChannels,
            this.keepAlive.copyForListenerHandoff()
        );
        // Paper end - listener handoff should reset pending keepalive expectations
    }
}

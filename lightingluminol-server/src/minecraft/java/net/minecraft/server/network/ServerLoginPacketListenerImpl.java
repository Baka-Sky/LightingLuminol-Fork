package net.minecraft.server.network;

import com.google.common.primitives.Ints;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.logging.LogUtils;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.notifications.ServerActivityMonitor;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringUtil;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// CraftBukkit start
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;

public class ServerLoginPacketListenerImpl implements ServerLoginPacketListener, TickablePacketListener {
    private static final AtomicInteger UNIQUE_THREAD_ID = new AtomicInteger(0);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.util.concurrent.ExecutorService authenticatorPool = java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("User Authenticator #", 0).uncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER)).factory()); // Paper - Virtual authenticator threads
    private static final int MAX_TICKS_BEFORE_LOGIN = 600;
    private final byte[] challenge;
    private final MinecraftServer server;
    public final Connection connection;
    private final ServerActivityMonitor serverActivityMonitor;
    public volatile ServerLoginPacketListenerImpl.State state = ServerLoginPacketListenerImpl.State.HELLO;
    private int tick;
    public @Nullable String requestedUsername; // Paper - public
    public @Nullable GameProfile authenticatedProfile;
    private final String serverId = "";
    public final boolean transferred; // Paper
    public boolean iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation = false; // Paper - username validation overriding
    private int velocityLoginMessageId = -1; // Paper - Add Velocity IP Forwarding Support
    public java.util.@Nullable UUID requestedUuid; // Paper
    private final io.papermc.paper.connection.PaperPlayerLoginConnection paperLoginConnection; // Paper - Config API

    public ServerLoginPacketListenerImpl(final MinecraftServer minecraftserver, final Connection connection, final boolean transferred) {
        this.server = minecraftserver;
        this.connection = connection;
        this.serverActivityMonitor = this.server.getServerActivityMonitor();
        this.challenge = Ints.toByteArray(RandomSource.create().nextInt());
        this.transferred = transferred;
        this.paperLoginConnection = new io.papermc.paper.connection.PaperPlayerLoginConnection(this); // Paper
    }

    @Override
    public void tick() {
        // Paper start - login cookie API
        // Don't block the connection
        if (this.paperLoginConnection.isAwaitingCookies()) {
            this.tickTimeout(); // Ensure we tick timeout logic
            return;
        }
        // Paper end - login cookie API

        if (this.state == ServerLoginPacketListenerImpl.State.VERIFYING) {
            // Folia start - region threading - rewrite login process
            String name = this.authenticatedProfile.name();
            java.util.UUID uniqueId = this.authenticatedProfile.id();
            if (this.server.getPlayerList().pushPendingJoin(name, uniqueId, this.connection)) {
            // Folia end - region threading - rewrite login process
            this.verifyLoginAndFinishConnectionSetup(Objects.requireNonNull(this.authenticatedProfile));
            } else { --this.tick; } // Folia - region threading - rewrite login process // Folia - max concurrent logins
        }

        if (this.state == ServerLoginPacketListenerImpl.State.WAITING_FOR_DUPE_DISCONNECT
            && !this.isPlayerAlreadyInWorld(Objects.requireNonNull(this.authenticatedProfile))) {
            this.finishLoginAndWaitForClient(this.authenticatedProfile);
        }

    // Paper start - login cookie API
        this.tickTimeout();
    }
    public void tickTimeout() {
    // Paper end - login cookie API
        if (this.tick++ == 600) {
            this.disconnectAsync(Component.translatable("multiplayer.disconnect.slow_login")); // Paper
        }
    }

    // CraftBukkit start
    @Deprecated
    public void disconnect(String reason) {
        this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(reason))); // Paper - Fix hex colors not working in some kick messages
    }
    // CraftBukkit end

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    // Paper start
    public void disconnectAsync(Component reason) {
        try {
            LOGGER.info("Disconnecting {}: {}", this.getUserName(), reason.getString());
            this.connection.send(new ClientboundLoginDisconnectPacket(reason), PacketSendListener.thenRun(() -> this.connection.disconnect(reason)));
            this.connection.handleConnectionDisconnectOnNextTick = true;
        } catch (Exception var3) {
            LOGGER.error("Error whilst disconnecting player", (Throwable)var3);
        }
    }
    // Paper end
    public void disconnect(final Component component) {
        try {
            LOGGER.info("Disconnecting {}: {}", this.getUserName(), component.getString());
            this.connection.send(new ClientboundLoginDisconnectPacket(component));
            this.connection.disconnect(component);
        } catch (Exception e) {
            LOGGER.error("Error whilst disconnecting player", e);
        }
    }

    private boolean isPlayerAlreadyInWorld(final GameProfile gameProfile) {
        return this.server.getPlayerList().getPlayer(gameProfile.id()) != null;
    }

    @Override
    public void onDisconnect(final DisconnectionDetails details) {
        LOGGER.info("{} lost connection: {}", this.getUserName(), details.reason().getString());
    }

    public String getUserName() {
        String loggableAddress = this.connection.getLoggableAddress(this.server.logIPs());
        return this.requestedUsername != null ? this.requestedUsername + " (" + loggableAddress + ")" : loggableAddress;
    }

    @Override
    public void handleHello(final ServerboundHelloPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        // Paper start - Validate usernames
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode()
            && io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.performUsernameValidation
            && !this.iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation) {
            Validate.validState(StringUtil.isReasonablePlayerName(packet.name()), "Invalid characters in username");
        }
        this.requestedUuid = packet.profileId();
        // Paper end - Validate usernames
        this.requestedUsername = packet.name();
        GameProfile singleplayerProfile = this.server.getSingleplayerProfile();
        if (singleplayerProfile != null && this.requestedUsername.equalsIgnoreCase(singleplayerProfile.name())) {
            this.startClientVerification(singleplayerProfile);
        } else {
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, true));
            } else {
                // Paper start - Add Velocity IP Forwarding Support
                if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
                    this.velocityLoginMessageId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                    net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    buf.writeByte(com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
                    net.minecraft.network.protocol.login.ClientboundCustomQueryPacket packet1 = new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(this.velocityLoginMessageId, new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket.PlayerInfoChannelPayload(com.destroystokyo.paper.proxy.VelocityProxy.PLAYER_INFO_CHANNEL, buf));
                    this.connection.send(packet1);
                    return;
                }
                // Paper end - Add Velocity IP Forwarding Support
                // Paper start - Virtual authenticator threads
                authenticatorPool.execute(() -> {
                    try {
                        GameProfile gameprofile = ServerLoginPacketListenerImpl.this.createOfflineProfile(ServerLoginPacketListenerImpl.this.requestedUsername); // Spigot

                        gameprofile = ServerLoginPacketListenerImpl.this.callPlayerPreLoginEvents(gameprofile); // Paper - Add more fields to AsyncPlayerPreLoginEvent
                        ServerLoginPacketListenerImpl.LOGGER.info("UUID of player {} is {}", gameprofile.name(), gameprofile.id());
                        ServerLoginPacketListenerImpl.this.startClientVerification(gameprofile);
                    } catch (Exception ex) {
                        ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                        ServerLoginPacketListenerImpl.this.server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + ServerLoginPacketListenerImpl.this.requestedUsername, ex);
                    }
                });
                // Paper end - Virtual authenticator threads
            }
        }
    }

    private void startClientVerification(final GameProfile profile) {
        this.authenticatedProfile = profile;
        this.state = ServerLoginPacketListenerImpl.State.VERIFYING;
    }

    private void verifyLoginAndFinishConnectionSetup(final GameProfile profile) {
        PlayerList playerList = this.server.getPlayerList();
        Component error = org.bukkit.craftbukkit.event.CraftEventFactory.handleLoginResult(playerList.canPlayerLogin(this.connection.getRemoteAddress(), new NameAndId(profile), this.connection), this.paperLoginConnection, this.connection, profile, this.server, true); // Paper // Folia - region threading - add connection parameter
        if (error != null) {
            this.disconnectAsync(error); // Paper
        } else if (this.connection.getIntendedProfileId() != null && !profile.id().equals(this.connection.getIntendedProfileId())) {
            this.disconnectAsync(CommonComponents.CONNECT_FAILED); // Paper
        } else {
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                this.connection
                    .send(
                        new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()),
                        PacketSendListener.thenRun(() -> this.connection.setupCompression(this.server.getCompressionThreshold(), true))
                    );
            }

            boolean waitForDisconnection = false &&playerList.disconnectAllPlayersWithProfile(profile); // Paper - validate usernames // Folia - rewrite login process - always false here
            if (waitForDisconnection) {
                this.state = ServerLoginPacketListenerImpl.State.WAITING_FOR_DUPE_DISCONNECT;
            } else {
                this.finishLoginAndWaitForClient(profile);
            }
        }
    }

    private void finishLoginAndWaitForClient(final GameProfile gameProfile) {
        this.state = ServerLoginPacketListenerImpl.State.PROTOCOL_SWITCHING;
        this.connection.send(new ClientboundLoginFinishedPacket(gameProfile, this.server.getConnection().getSessionId()));
        this.server.services().paper().filledProfileCache().add(gameProfile); // Paper - update profile cache
    }

    @Override
    public void handleKey(final ServerboundKeyPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet");

        final String digest;
        try {
            PrivateKey serverPrivateKey = this.server.getKeyPair().getPrivate();
            if (!packet.isChallengeValid(this.challenge, serverPrivateKey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretKey = packet.getSecretKey(serverPrivateKey);
            digest = new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey)).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setEncryptionKey(secretKey); // Paper - Use Velocity cipher
        } catch (CryptException e) {
            throw new IllegalStateException("Protocol error", e);
        }

        // Paper start - Virtual authenticator threads
        authenticatorPool.execute(new Runnable() {
            @Override
            public void run() {
                String name = Objects.requireNonNull(ServerLoginPacketListenerImpl.this.requestedUsername, "Player name not initialized");

                try {
                    ProfileResult result = ServerLoginPacketListenerImpl.this.server
                        .services()
                        .sessionService()
                        .hasJoinedServer(name, digest, this.getAddress());
                    if (result != null) {
                        GameProfile profile = result.profile();
                        // CraftBukkit start - fire PlayerPreLoginEvent
                        if (!ServerLoginPacketListenerImpl.this.connection.isConnected()) {
                            return;
                        }
                        profile = ServerLoginPacketListenerImpl.this.callPlayerPreLoginEvents(profile); // Paper - Add more fields to AsyncPlayerPreLoginEvent
                        // CraftBukkit end
                        ServerLoginPacketListenerImpl.LOGGER.info("UUID of player {} is {}", profile.name(), profile.id());
                        ServerLoginPacketListenerImpl.this.serverActivityMonitor.reportLoginActivity();
                        ServerLoginPacketListenerImpl.this.startClientVerification(profile);
                    } else if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Failed to verify username but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.startClientVerification(ServerLoginPacketListenerImpl.this.createOfflineProfile(name)); // Spigot
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                        ServerLoginPacketListenerImpl.LOGGER.error("Username '{}' tried to join with an invalid session", name);
                    }
                } catch (AuthenticationUnavailableException ignored) {
                    if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.startClientVerification(ServerLoginPacketListenerImpl.this.createOfflineProfile(name)); // Spigot
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.authenticationServersDown)); // Paper - Configurable kick message
                        ServerLoginPacketListenerImpl.LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                    // CraftBukkit start - catch all exceptions
                } catch (Exception ex) {
                    ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                    ServerLoginPacketListenerImpl.LOGGER.warn("Exception verifying {}", name, ex);
                    // CraftBukkit end
                }
            }

            private @Nullable InetAddress getAddress() {
                return ServerLoginPacketListenerImpl.this.server.getPreventProxyConnections()
                        && ServerLoginPacketListenerImpl.this.connection.getRemoteAddress() instanceof InetSocketAddress inetSocketAddress
                    ? inetSocketAddress.getAddress()
                    : null;
            }
        });
        // Paper end - Virtual authenticator threads
    }

    // CraftBukkit start
    private GameProfile callPlayerPreLoginEvents(GameProfile gameprofile) throws Exception { // Paper - Add more fields to AsyncPlayerPreLoginEvent
        // Paper start - Add Velocity IP Forwarding Support
        if (ServerLoginPacketListenerImpl.this.velocityLoginMessageId == -1 && io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
            this.disconnect("This server requires you to connect with Velocity.");
            return gameprofile;
        }
        // Paper end - Add Velocity IP Forwarding Support
        String playerName = gameprofile.name();
        java.net.InetAddress address = ((java.net.InetSocketAddress) this.connection.getRemoteAddress()).getAddress();
        java.util.UUID uniqueId = gameprofile.id();
        final org.bukkit.craftbukkit.CraftServer server = ServerLoginPacketListenerImpl.this.server.server;

        // Paper start - Add more fields to AsyncPlayerPreLoginEvent
        final InetAddress rawAddress = ((InetSocketAddress) this.connection.channel.remoteAddress()).getAddress();
        com.destroystokyo.paper.profile.PlayerProfile profile = com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(gameprofile); // Paper - setPlayerProfileAPI
        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, rawAddress, uniqueId, this.transferred, profile, this.connection.hostname, this.paperLoginConnection); // Paper
        server.getPluginManager().callEvent(asyncEvent);
        profile = asyncEvent.getPlayerProfile();
        profile.complete(true); // Paper - setPlayerProfileAPI
        gameprofile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(profile);
        playerName = gameprofile.name();
        uniqueId = gameprofile.id();
        // Paper end - Add more fields to AsyncPlayerPreLoginEvent

        if (false && PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) { // Folia - region threading
            final PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                event.disallow(asyncEvent.getResult(), asyncEvent.kickMessage()); // Paper - Adventure
            }
            Waitable<PlayerPreLoginEvent.Result> waitable = new Waitable<>() {
                @Override
                protected PlayerPreLoginEvent.Result evaluate() {
                    server.getPluginManager().callEvent(event);
                    return event.getResult();
                }
            };

            ServerLoginPacketListenerImpl.this.server.processQueue.add(waitable);
            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage())); // Paper - Adventure
            }
        } else {
            if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(asyncEvent.kickMessage())); // Paper - Adventure
            }
        }
        return gameprofile; // Paper - Add more fields to AsyncPlayerPreLoginEvent
    }
    // CraftBukkit end

    @Override
    public void handleCustomQueryPacket(final ServerboundCustomQueryAnswerPacket packet) {
        // Paper start - Add Velocity IP Forwarding Support
        try {
            this.handleCustomQueryPacket0(packet);
        } finally {
            ServerboundCustomQueryAnswerPacket.QueryAnswerPayload payload = (ServerboundCustomQueryAnswerPacket.QueryAnswerPayload) packet.payload();
            if (payload != null) {
                payload.buffer.release();
            }
        }
    }
    private void handleCustomQueryPacket0(final ServerboundCustomQueryAnswerPacket packet) {
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled && packet.transactionId() == this.velocityLoginMessageId) {
            ServerboundCustomQueryAnswerPacket.QueryAnswerPayload payload = (ServerboundCustomQueryAnswerPacket.QueryAnswerPayload)packet.payload();
            if (payload == null) {
                this.disconnect("This server requires you to connect with Velocity.");
                return;
            }

            net.minecraft.network.FriendlyByteBuf buf = payload.buffer;
            if (!com.destroystokyo.paper.proxy.VelocityProxy.checkIntegrity(buf)) {
                this.disconnect("Unable to verify player details");
                return;
            }

            int version = buf.readVarInt();
            if (version > com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION) {
                throw new IllegalStateException("Unsupported forwarding version " + version + ", wanted upto " + com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
            }

            java.net.SocketAddress listening = this.connection.getRemoteAddress();
            int port = 0;
            if (listening instanceof java.net.InetSocketAddress) {
                port = ((java.net.InetSocketAddress) listening).getPort();
            }
            this.connection.address = new java.net.InetSocketAddress(com.destroystokyo.paper.proxy.VelocityProxy.readAddress(buf), port);

            this.authenticatedProfile = com.destroystokyo.paper.proxy.VelocityProxy.createProfile(buf);

            //TODO Update handling for lazy sessions, might not even have to do anything?

            // Proceed with login
            authenticatorPool.execute(() -> {
                try {
                    final GameProfile gameprofile = this.callPlayerPreLoginEvents(this.authenticatedProfile);
                    ServerLoginPacketListenerImpl.LOGGER.info("UUID of player {} is {}", gameprofile.name(), gameprofile.id());
                    ServerLoginPacketListenerImpl.this.startClientVerification(gameprofile);
                } catch (Exception ex) {
                    disconnect("Failed to verify username!");
                    server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + this.authenticatedProfile.name(), ex);
                }
            });
            return;
        }
        // Paper end - Add Velocity IP Forwarding Support
        this.disconnect(ServerCommonPacketListenerImpl.DISCONNECT_UNEXPECTED_QUERY);
    }

    @Override
    public void handleLoginAcknowledgement(final ServerboundLoginAcknowledgedPacket packet) {
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor()); // CraftBukkit
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.PROTOCOL_SWITCHING, "Unexpected login acknowledgement packet");
        // LightingLuminol start - async protocol switch
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(Objects.requireNonNull(this.authenticatedProfile), this.transferred);
        ServerConfigurationPacketListenerImpl configPacketListener = new ServerConfigurationPacketListenerImpl(this.server, this.connection, cookie);

        Runnable afterSwitch = () -> io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(configPacketListener::startConfiguration);

        if (!meow.bacteriawa.lightingluminol.config.OptimizationsConfig.UseAsyncProtocolSwitching.enabled) {
            this.connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
            this.connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, configPacketListener);
            afterSwitch.run();
            this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
            return;
        }

        this.connection.setupInboundProtocolAsync(ConfigurationProtocols.SERVERBOUND, configPacketListener, () -> {
            this.connection.setupOutboundProtocolAsync(ConfigurationProtocols.CLIENTBOUND, afterSwitch, true);
        }, false);
        this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
        // LightingLuminol end
    }

    @Override
    public void fillListenerSpecificCrashDetails(final CrashReport report, final CrashReportCategory connectionDetails) {
        connectionDetails.setDetail("Login phase", () -> this.state.toString());
    }

    @Override
    public void handleCookieResponse(final ServerboundCookieResponsePacket packet) {
        if (this.paperLoginConnection.handleCookieResponse(packet)) return; // Paper
        this.disconnect(ServerCommonPacketListenerImpl.DISCONNECT_UNEXPECTED_QUERY);
    }

    // Spigot start
    protected GameProfile createOfflineProfile(String s) {
        java.util.UUID uuid;
        if (this.connection.spoofedUUID != null) {
            uuid = this.connection.spoofedUUID;
        } else {
            uuid = UUIDUtil.createOfflinePlayerUUID(s);
        }

        final var props = com.google.common.collect.ImmutableMultimap.<String, com.mojang.authlib.properties.Property>builder();

        if (this.connection.spoofedProfile != null) {
            for (com.mojang.authlib.properties.Property property : this.connection.spoofedProfile) {
                if (!ServerHandshakePacketListenerImpl.PROP_PATTERN.matcher(property.name()).matches()) continue;
                props.put(property.name(), property);
            }
        }

        return new GameProfile(uuid, s, new com.mojang.authlib.properties.PropertyMap(props.build()));
    }
    // Spigot end

    public enum State {
        HELLO,
        KEY,
        AUTHENTICATING,
        NEGOTIATING,
        VERIFYING,
        WAITING_FOR_DUPE_DISCONNECT,
        PROTOCOL_SWITCHING,
        ACCEPTED;
    }
}

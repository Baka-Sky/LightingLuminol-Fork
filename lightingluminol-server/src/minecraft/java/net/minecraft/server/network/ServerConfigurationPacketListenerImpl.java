package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.config.JoinWorldTask;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.network.config.ServerCodeOfConductConfigurationTask;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.flag.FeatureFlags;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerConfigurationPacketListenerImpl extends ServerCommonPacketListenerImpl implements ServerConfigurationPacketListener, TickablePacketListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component DISCONNECT_REASON_INVALID_DATA = Component.translatable("multiplayer.disconnect.invalid_player_data");
    private static final Component DISCONNECT_REASON_CONFIGURATION_ERROR = Component.translatable("multiplayer.disconnect.configuration_error");
    private final GameProfile gameProfile;
    private final Queue<ConfigurationTask> configurationTasks = new ConcurrentLinkedQueue<>();
    public @Nullable ConfigurationTask currentTask;
    public ClientInformation clientInformation;
    private @Nullable SynchronizeRegistriesTask synchronizeRegistriesTask;
    private @Nullable PrepareSpawnTask prepareSpawnTask;
    public io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection; // Paper
    public net.minecraft.server.level.ServerPlayer switchToMain; // Folia - region threading - rewrite login process

    public ServerConfigurationPacketListenerImpl(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
        super(server, connection, cookie);
        this.gameProfile = cookie.gameProfile();
        this.clientInformation = cookie.clientInformation();
        this.paperConnection = new io.papermc.paper.connection.PaperPlayerConfigurationConnection(this); // Paper
    }

    // Paper start - configuration phase API
    @Override
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        return this.paperConnection;
    }

    @Override
    public net.kyori.adventure.audience.Audience getAudience() {
        return this.paperConnection.getAudience();
    }
    // Paper end - configuration phase API

    @Override
    protected GameProfile playerProfile() {
        return this.gameProfile;
    }

    @Override
    public void onDisconnect(final DisconnectionDetails details) {
        // Paper start - Debugging
        if (this.server.isDebugging()) {
            ServerConfigurationPacketListenerImpl.LOGGER.info("{} ({}) lost connection: {}, while in configuration phase {}", this.gameProfile.name(), this.gameProfile.id(), details.reason().getString(), this.currentTask != null ? this.currentTask.type().id() : "null");
        } else
        // Paper end
        LOGGER.info("{} ({}) lost connection: {}", this.gameProfile.name(), this.gameProfile.id(), details.reason().getString());
        if (this.prepareSpawnTask != null) {
            this.prepareSpawnTask.close();
            this.prepareSpawnTask = null;
        }

        super.onDisconnect(details);
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    public void startConfiguration() {
        new io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent(this.paperConnection).callEvent(); // Paper
        this.send(new ClientboundCustomPayloadPacket(new BrandPayload(this.server.getServerModName())));
        ServerLinks serverLinks = this.server.serverLinks();
        if (!serverLinks.isEmpty()) {
            // Paper start
            org.bukkit.craftbukkit.CraftServerLinks links = new org.bukkit.craftbukkit.CraftServerLinks(serverLinks);
            new org.bukkit.event.player.PlayerLinksSendEvent(this.paperConnection, links).callEvent();
            this.send(new ClientboundServerLinksPacket(links.getServerLinks().untrust()));
            // Paper end
        }

        LayeredRegistryAccess<RegistryLayer> registries = this.server.registries();
        List<KnownPack> knownPacks = this.server
            .getResourceManager()
            .listPacks()
            .flatMap(packResources -> packResources.location().knownPackInfo().stream())
            .toList();
        this.send(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(this.server.getWorldData().enabledFeatures())));
        this.synchronizeRegistriesTask = new SynchronizeRegistriesTask(knownPacks, registries);
        this.configurationTasks.add(this.synchronizeRegistriesTask);
        this.addOptionalTasks();
        this.configurationTasks.add(new io.papermc.paper.connection.PaperConfigurationTask(this)); // Paper
        this.returnToWorld();
    }

    public void returnToWorld() {
        this.prepareSpawnTask = new PrepareSpawnTask(this.server, this.gameProfile, this); // Paper - pass full GameProfile & listener for events
        this.configurationTasks.add(this.prepareSpawnTask);
        this.configurationTasks.add(new JoinWorldTask());
        this.startNextTask();
    }

    private void addOptionalTasks() {
        Map<String, String> codeOfConducts = this.server.getCodeOfConducts();
        if (!codeOfConducts.isEmpty()) {
            this.configurationTasks.add(new ServerCodeOfConductConfigurationTask(() -> {
                String codeOfConduct = codeOfConducts.get(this.clientInformation.language().toLowerCase(Locale.ROOT));
                if (codeOfConduct == null) {
                    codeOfConduct = codeOfConducts.get("en_us");
                }

                if (codeOfConduct == null) {
                    codeOfConduct = codeOfConducts.values().iterator().next();
                }

                return codeOfConduct;
            }));
        }

        this.server.getServerResourcePack().ifPresent(info -> this.configurationTasks.add(new ServerResourcePackConfigurationTask(info)));
    }

    @Override
    public void handleClientInformation(final ServerboundClientInformationPacket packet) {
        this.clientInformation = packet.information();
        this.connection.channel.attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).set(net.kyori.adventure.translation.Translator.parseLocale(packet.information().language())); // Paper
    }

    @Override
    public void handleResourcePackResponse(final ServerboundResourcePackPacket packet) {
        super.handleResourcePackResponse(packet);
        this.connection.resourcePackStatus = org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.values()[packet.action().ordinal()]; // Paper
        if (packet.action().isTerminal() && packet.id().equals(this.server.getServerResourcePack().map(MinecraftServer.ServerResourcePackInfo::id).orElse(null))) { // Paper - Ignore resource pack requests that are not vanilla
            this.finishCurrentTask(ServerResourcePackConfigurationTask.TYPE);
        }
    }

    @Override
    public void handleSelectKnownPacks(final ServerboundSelectKnownPacks packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        if (this.synchronizeRegistriesTask == null) {
            throw new IllegalStateException("Unexpected response from client: received pack selection, but no negotiation ongoing");
        }

        this.synchronizeRegistriesTask.handleResponse(packet.knownPacks(), this::send);
        this.finishCurrentTask(SynchronizeRegistriesTask.TYPE);
    }

    @Override
    public void handleAcceptCodeOfConduct(final ServerboundAcceptCodeOfConductPacket packet) {
        this.finishCurrentTask(ServerCodeOfConductConfigurationTask.TYPE);
    }

    @Override
    public void handleConfigurationFinished(final ServerboundFinishConfigurationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        this.finishCurrentTask(JoinWorldTask.TYPE);
        // LightingLuminol start - async protocol switch
        Runnable afterSwitch = () -> { // wrapped callback
        try {
            PlayerList playerList = this.server.getPlayerList();
            if (playerList.getPlayer(this.gameProfile.id()) != null) {
                this.disconnect(PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
                return;
            }

            Component loginError = org.bukkit.craftbukkit.event.CraftEventFactory.handleLoginResult(playerList.canPlayerLogin(this.connection.getRemoteAddress(), new NameAndId(this.gameProfile), this.connection), this.paperConnection, this.connection, this.gameProfile, this.server, false); // Paper - Login event logic // Folia - region threading - add connection parameter
            if (loginError != null) {
                this.disconnect(loginError);
                return;
            }

            // Folia start - region threading
            final Connection connection = this.connection;
            final CommonListenerCookie commonListenerCookie = this.createCookie(this.clientInformation);
            final net.minecraft.server.level.ServerPlayer serverPlayer = this.prepareSpawnTask.createPlayer(connection, commonListenerCookie);

            this.switchToMain = serverPlayer;
            // now the connection responsibility is transferred to the region
            final net.minecraft.server.level.ServerLevel world = this.prepareSpawnTask.getSpawnWorld();
            final net.minecraft.world.level.ChunkPos chunkPos = net.minecraft.world.level.ChunkPos.containing(net.minecraft.core.BlockPos.containing(this.prepareSpawnTask.getSpawnPosition()));
            world.moonrise$getChunkTaskScheduler().scheduleTickingState(
                    chunkPos.x(), chunkPos.z(), net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING, true,
                    ca.spottedleaf.concurrentutil.util.Priority.HIGHER,
                    (final net.minecraft.world.level.chunk.LevelChunk chunk) -> {
                        world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                                net.minecraft.server.level.TicketType.PLAYER_SPAWN, chunkPos,
                                ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.ENTITY_TICKING_TICKET_LEVEL,
                                null
                        );
                        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                                world, chunkPos.x(), chunkPos.z(), () -> {
                                    net.minecraft.server.network.ServerConfigurationPacketListenerImpl.this.prepareSpawnTask.spawnPlayer(
                                            this.connection, commonListenerCookie, serverPlayer
                                    );
                                }
                        );
                    }
            );
            // Folia end - region threading - rewrite login process
        } catch (Exception e) {
            LOGGER.error("Couldn't place player in world", e);
            this.disconnect(DISCONNECT_REASON_INVALID_DATA);
        }
        // LightingLuminol start - async protocol switch
        };
        if (!meow.bacteriawa.lightingluminol.config.OptimizationsConfig.UseAsyncProtocolSwitching.enabled) {
            this.connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess())));
            afterSwitch.run();
        } else {
            this.connection.setupOutboundProtocolAsync(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess())), () -> {
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(afterSwitch);
            }, false);
        }
        // LightingLuminol end
    }

    @Override
    public void tick() {
        this.keepConnectionAlive();
        ConfigurationTask task = this.currentTask;
        if (task != null) {
            try {
                if (task.tick()) {
                    this.finishCurrentTask(task.type());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to tick configuration task {}", task.type(), e);
                this.disconnect(DISCONNECT_REASON_CONFIGURATION_ERROR);
            }
        }

        if (this.prepareSpawnTask != null) {
            this.prepareSpawnTask.keepAlive();
        }
    }

    private void startNextTask() {
        if (this.currentTask != null) {
            throw new IllegalStateException("Task " + this.currentTask.type().id() + " has not finished yet");
        }

        if (this.isAcceptingMessages()) {
            ConfigurationTask task = this.configurationTasks.poll();
            if (task != null) {
                this.currentTask = task;

                try {
                    task.start(this::send);
                } catch (Exception e) {
                    LOGGER.error("Failed to start configuration task {}", task.type(), e);
                    this.disconnect(DISCONNECT_REASON_CONFIGURATION_ERROR);
                }
            }
        }
    }

    public void finishCurrentTask(final ConfigurationTask.Type taskTypeToFinish) {
        ConfigurationTask.Type currentTaskType = this.currentTask != null ? this.currentTask.type() : null;
        if (!taskTypeToFinish.equals(currentTaskType)) {
            throw new IllegalStateException("Unexpected request for task finish, current task: " + currentTaskType + ", requested: " + taskTypeToFinish);
        }

        this.currentTask = null;
        this.startNextTask();
    }

    // Paper start
    @Override
    public void disconnectAsync(final net.minecraft.network.DisconnectionDetails disconnectionInfo) {
        if (this.cserver.isPrimaryThread()) {
            this.disconnect(disconnectionInfo);
            return;
        }

        this.connection.setReadOnly();
        this.server.scheduleOnMain(() -> {
            this.disconnect(disconnectionInfo); // Currently you cannot cancel disconnect during the config stage
        });
    }

    @Override
    public void handleCustomPayload(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket packet) {
        super.handleCustomPayload(packet);
    }

    @Override
    public io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection() {
        return paperConnection;
    }
    // Paper end
}

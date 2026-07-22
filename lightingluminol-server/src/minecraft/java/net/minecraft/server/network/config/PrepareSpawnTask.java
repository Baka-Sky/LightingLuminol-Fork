package net.minecraft.server.network.config;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class PrepareSpawnTask implements ConfigurationTask {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("prepare_spawn");
    public static final int PREPARE_CHUNK_RADIUS = 3;
    private final MinecraftServer server;
    private final NameAndId nameAndId;
    private final LevelLoadListener loadListener;
    private PrepareSpawnTask.@Nullable State state;

    // Paper start - passthrough profile and packet listener
    private final com.mojang.authlib.GameProfile profile;
    private final net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener;
    private boolean newPlayer;

    public PrepareSpawnTask(final MinecraftServer server, final com.mojang.authlib.GameProfile profile, final net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener) {
        this.profile = profile;
        this.listener = listener;
        // Paper end - passthrough profile and packet listener
        this.server = server;
        this.nameAndId = new net.minecraft.server.players.NameAndId(profile); // Paper - passthrough profile and packet listener - create from profile
        this.loadListener = LevelLoadListener.noop(); // Paper - per level load listener - this is already a no-op on dedicated server, but we moved it to Level
    }

    @Override
    public void start(final Consumer<Packet<?>> connection) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            Optional<ValueInput> loadedData = this.server
                .getPlayerList()
                .loadPlayerData(this.nameAndId)
                .map(tag -> TagValueInput.create(reporter, this.server.registryAccess(), tag));
            // Paper start - move logic in Entity to here, to use bukkit supplied world UUID & reset to main world spawn if no valid world is found
            this.newPlayer = loadedData.isEmpty(); // New players don't have saved data!
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> resourceKey = null; // Paper
            boolean[] invalidPlayerWorld = {false};
            bukkitData: if (loadedData.isPresent()) {
                // The main way for bukkit worlds to store the world is the world UUID despite mojang adding custom worlds
                final org.bukkit.World bWorld;
                final ValueInput playerData = loadedData.get();
                // TODO maybe convert this to a codec and use compoundTag#read, we need silent variants of that method first.
                final Optional<Long> worldUUIDMost = playerData.getLong("WorldUUIDMost");
                final Optional<Long> worldUUIDLeast = playerData.getLong("WorldUUIDLeast");
                final java.util.Optional<String> worldName = playerData.getString("world");
                if (worldUUIDMost.isPresent() && worldUUIDLeast.isPresent()) {
                    bWorld = org.bukkit.Bukkit.getServer().getWorld(new java.util.UUID(worldUUIDMost.get(), worldUUIDLeast.get()));
                } else if (worldName.isPresent()) { // Paper - legacy bukkit world name
                    bWorld = org.bukkit.Bukkit.getServer().getWorld(worldName.get());
                } else {
                    break bukkitData; // if neither of the bukkit data points exist, proceed to the vanilla migration section
                }
                if (bWorld != null) {
                    resourceKey = ((org.bukkit.craftbukkit.CraftWorld) bWorld).getHandle().dimension();
                } else {
                    resourceKey = net.minecraft.world.level.Level.OVERWORLD;
                    invalidPlayerWorld[0] = true;
                }
            }
            ServerPlayer.SavedPosition loadedPosition = loadedData.<ServerPlayer.SavedPosition>flatMap(tag -> tag.read(ServerPlayer.SavedPosition.MAP_CODEC))
                .orElse(ServerPlayer.SavedPosition.EMPTY);
            LevelData.RespawnData respawnData = this.server.getWorldData().overworldData().getRespawnData();
            if (resourceKey == null) { // only run the vanilla logic if we haven't found a world from the bukkit data
                // Below is the vanilla way of getting the dimension, this is for migration from vanilla servers
                resourceKey = loadedPosition.dimension().orElse(null);
            }
            ServerLevel spawnDataLevel = this.server.getLevel(respawnData.dimension());
            if (spawnDataLevel == null) {
                spawnDataLevel = this.server.overworld();
            }
            ServerLevel spawnLevel;
            if (resourceKey == null) {
                spawnLevel = spawnDataLevel;
            } else {
                spawnLevel = this.server.getLevel(resourceKey);
                if (spawnLevel == null) {
                    LOGGER.warn("Unknown respawn dimension {}, defaulting to overworld", resourceKey);
                    spawnLevel = spawnDataLevel;
                }
            }
            // Folia start - region threading
            CompletableFuture<Vec3> spawnPosition = new java.util.concurrent.CompletableFuture<>();
            if (loadedPosition.position().isPresent()) {
                spawnPosition.complete(loadedPosition.position().get());
            } else {
                ca.spottedleaf.concurrentutil.completable.CallbackCompletable<org.bukkit.Location> spawnComplete = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();
                spawnComplete.addWaiter(
                        (final org.bukkit.Location loc, final Throwable throwable) -> {
                            if (throwable != null) {
                                spawnPosition.completeExceptionally(throwable);
                            } else {
                                spawnPosition.complete(io.papermc.paper.util.MCUtil.toVec3(loc));
                            }
                        }
                );
                ServerPlayer.fudgeSpawnLocation(spawnLevel, spawnComplete);
            }
            // Folia end - region threading
            // Paper end - move logic in Entity to here, to use bukkit supplied world UUID & reset to main world spawn if no valid world is found
            Vec2 spawnAngle = loadedPosition.rotation().orElse(new Vec2(respawnData.yaw(), respawnData.pitch()));
            this.state = new PrepareSpawnTask.Preparing(spawnLevel, spawnPosition, spawnAngle);
        }
    }

    @Override
    public boolean tick() {
        return switch (this.state) {
            case null -> false;
            case PrepareSpawnTask.Preparing preparing -> {
                PrepareSpawnTask.Ready ready = preparing.tick();
                if (ready != null) {
                    this.state = ready;
                    yield true;
                } else {
                    yield false;
                }
            }
            case PrepareSpawnTask.Ready ignored -> true;
            default -> throw new MatchException(null, null);
        };
    }

    public ServerPlayer spawnPlayer(final Connection connection, final CommonListenerCookie cookie, final ServerPlayer player) { // Folia - add ServerPlayer param
        if (this.state instanceof PrepareSpawnTask.Ready ready) {
            return ready.spawn(connection, cookie, player); // Folia - add ServerPlayer param
        } else {
            throw new IllegalStateException("Player spawn was not ready");
        }
    }

    // Folia start - region threading
    public ServerLevel getSpawnWorld() {
        if (this.state instanceof PrepareSpawnTask.Ready ready) {
            return ready.getSpawnWorld();
        } else {
            throw new IllegalStateException("Player spawn was not ready");
        }
    }

    public Vec3 getSpawnPosition() {
        if (this.state instanceof PrepareSpawnTask.Ready ready) {
            return ready.getSpawnPosition();
        } else {
            throw new IllegalStateException("Player spawn was not ready");
        }
    }

    // copy of spawnPlayer but only invokes createPlayer
    public ServerPlayer createPlayer(final Connection connection, final CommonListenerCookie cookie) {
        if (this.state instanceof PrepareSpawnTask.Ready ready) {
            return ready.createPlayer(connection, cookie);
        } else {
            throw new IllegalStateException("Player spawn was not ready");
        }
    }
    // Folia end - region threading

    public void keepAlive() {
        if (this.state instanceof PrepareSpawnTask.Ready ready) {
            ready.keepAlive();
        }
    }

    public void close() {
        if (this.state instanceof PrepareSpawnTask.Preparing preparing) {
            preparing.cancel();
        }

        this.state = null;
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }

    private final class Preparing implements PrepareSpawnTask.State {
        private ServerLevel spawnLevel; // Paper - remove final
        private CompletableFuture<Vec3> spawnPosition; // Paper - remove final
        private Vec2 spawnAngle; // Paper - remove final
        private @Nullable CompletableFuture<?> chunkLoadFuture;
        private @Nullable CompletableFuture<org.bukkit.Location> eventFuture; // Paper
        private final ChunkLoadCounter chunkLoadCounter = new ca.spottedleaf.moonrise.patches.chunk_system.MoonriseChunkLoadCounter(); // Paper - rewrite chunk system

        private Preparing(final ServerLevel spawnLevel, final CompletableFuture<Vec3> spawnPosition, final Vec2 spawnAngle) {
            this.spawnLevel = spawnLevel;
            this.spawnPosition = spawnPosition;
            this.spawnAngle = spawnAngle;
        }

        public void cancel() {
            this.spawnPosition.cancel(false);
        }

        public PrepareSpawnTask.@Nullable Ready tick() {
            if (!this.spawnPosition.isDone()) {
                return null;
            }

            Vec3 spawnPosition = this.spawnPosition.join();
            if (this.chunkLoadFuture == null) {
                // Paper start - PlayerSpawnLocationEvent
                if (false && this.eventFuture == null && org.spigotmc.event.player.PlayerSpawnLocationEvent.getHandlerList().getRegisteredListeners().length != 0) { // Folia - region threading
                    ServerPlayer player;
                    if (PrepareSpawnTask.this.listener.connection.savedPlayerForLegacyEvents != null) {
                        player = PrepareSpawnTask.this.listener.connection.savedPlayerForLegacyEvents;
                    } else {
                        player = new ServerPlayer(
                            PrepareSpawnTask.this.server,
                            PrepareSpawnTask.this.server.overworld(),
                            PrepareSpawnTask.this.profile,
                            net.minecraft.server.level.ClientInformation.createDefault()
                        );
                        PrepareSpawnTask.this.listener.connection.savedPlayerForLegacyEvents = player;
                    }
                    org.spigotmc.event.player.PlayerSpawnLocationEvent ev = new org.spigotmc.event.player.PlayerSpawnLocationEvent(
                        player.getBukkitEntity(),
                        org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPosition, this.spawnLevel, this.spawnAngle.x, this.spawnAngle.y)
                    );
                    ev.callEvent();
                    spawnPosition = io.papermc.paper.util.MCUtil.toVec3(ev.getSpawnLocation());
                    if (ev.getSpawnLocation().getWorld() != null) this.spawnLevel = ((org.bukkit.craftbukkit.CraftWorld) ev.getSpawnLocation().getWorld()).getHandle();
                    this.spawnPosition = CompletableFuture.completedFuture(spawnPosition);
                    this.spawnAngle = new Vec2(ev.getSpawnLocation().getYaw(), ev.getSpawnLocation().getPitch());
                }

                if (this.eventFuture == null && io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent.getHandlerList().getRegisteredListeners().length != 0) {
                    final Vec3 spawnPositionFinal = spawnPosition;
                    this.eventFuture = CompletableFuture.supplyAsync(() -> {
                        io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent ev = new io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent(
                            PrepareSpawnTask.this.listener.paperConnection,
                            org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPositionFinal, this.spawnLevel, this.spawnAngle.x, this.spawnAngle.y),
                            PrepareSpawnTask.this.newPlayer
                        );
                        ev.callEvent();
                        return ev.getSpawnLocation();
                    }, io.papermc.paper.connection.PaperConfigurationTask.CONFIGURATION_POOL);
                }
                if (this.eventFuture != null) {
                    if (!this.eventFuture.isDone()) {
                        return null;
                    }
                    org.bukkit.Location location = this.eventFuture.join();
                    spawnPosition = io.papermc.paper.util.MCUtil.toVec3(location);
                    this.spawnLevel = ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle();
                    this.spawnPosition = CompletableFuture.completedFuture(spawnPosition);
                    this.spawnAngle = new Vec2(location.getYaw(), location.getPitch());
                }
                // Paper end - PlayerSpawnLocationEvent
                ChunkPos spawnChunk = ChunkPos.containing(BlockPos.containing(spawnPosition));
                this.chunkLoadFuture = ((ca.spottedleaf.moonrise.patches.chunk_system.MoonriseChunkLoadCounter)this.chunkLoadCounter).trackLoadWithRadius(this.spawnLevel, spawnChunk, 3, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, ca.spottedleaf.concurrentutil.util.Priority.HIGH, () -> { Preparing.this.spawnLevel.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SPAWN, spawnChunk, 3); }); // Paper - rewrite chunk system
                PrepareSpawnTask.this.loadListener.start(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS, this.chunkLoadCounter.totalChunks());
                PrepareSpawnTask.this.loadListener.updateFocus(this.spawnLevel.dimension(), spawnChunk);
            }

            PrepareSpawnTask.this.loadListener
                .update(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS, this.chunkLoadCounter.readyChunks(), this.chunkLoadCounter.totalChunks());
            if (!this.chunkLoadFuture.isDone()) {
                return null;
            }

            PrepareSpawnTask.this.loadListener.finish(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS);
            return PrepareSpawnTask.this.new Ready(this.spawnLevel, spawnPosition, this.spawnAngle);
        }
    }

    private final class Ready implements PrepareSpawnTask.State {
        private final ServerLevel spawnLevel;
        private final Vec3 spawnPosition;
        private final Vec2 spawnAngle;

        private Ready(final ServerLevel spawnLevel, final Vec3 spawnPosition, final Vec2 spawnAngle) {
            this.spawnLevel = spawnLevel;
            this.spawnPosition = spawnPosition;
            this.spawnAngle = spawnAngle;
        }
        // Folia start - region threading
        public ServerLevel getSpawnWorld() {
            return this.spawnLevel;
        }

        public Vec3 getSpawnPosition() {
            return this.spawnPosition;
        }
        // Folia end - region threading


        public void keepAlive() {
            this.spawnLevel.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SPAWN, ChunkPos.containing(BlockPos.containing(this.spawnPosition)), 3);
        }

        // Folia start - region threading - split out createPlayer and spawn
        public ServerPlayer createPlayer(final Connection connection, final CommonListenerCookie cookie) {
            //ChunkPos spawnChunk = ChunkPos.containing(BlockPos.containing(this.spawnPosition));
            //this.spawnLevel.waitForEntities(spawnChunk, 3); // Not needed on Moonrise chunk system
            // Folia end - region threading
            // Paper start - configuration api - possibly use legacy saved server player instance
            ServerPlayer player;
            if (connection.savedPlayerForLegacyEvents != null) {
                player = connection.savedPlayerForLegacyEvents;
                connection.savedPlayerForLegacyEvents = null;
                // Update the existing instance
                player.gameProfile = cookie.gameProfile();
                player.updateOptionsNoEvents(cookie.clientInformation());
                player.setServerLevel(this.spawnLevel);
            } else {
                player = new ServerPlayer(PrepareSpawnTask.this.server, this.spawnLevel, cookie.gameProfile(), cookie.clientInformation());
            }
            // Paper end - configuration api - possibly use legacy saved server player instance
            // Folia start - region threading - split out createPlayer and spawn
            return player;
        }
        public ServerPlayer spawn(final Connection connection, final CommonListenerCookie cookie, final ServerPlayer player) {
            // Folia end - region threading - split out createPlayer and spawn

            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), PrepareSpawnTask.LOGGER)) {
                Optional<ValueInput> input = PrepareSpawnTask.this.server
                    .getPlayerList()
                    .loadPlayerData(PrepareSpawnTask.this.nameAndId)
                    // CraftBukkit start
                    .map(tag -> {
                        org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = player.getBukkitEntity();
                        // Only update first played if it is older than the one we have
                        long modified = new java.io.File(PrepareSpawnTask.this.server.getPlayerList().playerIo.getPlayerDir(), player.getStringUUID() + ".dat").lastModified();
                        if (modified < craftPlayer.getFirstPlayed()) {
                            craftPlayer.setFirstPlayed(modified);
                        }
                        return tag;
                    })
                    // CraftBukkit end
                    .map(tag -> TagValueInput.create(reporter, PrepareSpawnTask.this.server.registryAccess(), tag));
                input.ifPresent(player::load);
                // CraftBukkit start - Better rename detection
                if (input.isPresent()) {
                    player.lastKnownName = input.flatMap(t -> t.child("bukkit")).flatMap(t -> t.getString("lastKnownName")).orElse(null);
                }
                // CraftBukkit end - Better rename detection
                // Paper start - Entity#getEntitySpawnReason
                if (input.isEmpty()) {
                    player.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT; // set Player SpawnReason to DEFAULT on first login
                }
                // Paper end - Entity#getEntitySpawnReason
                player.snapTo(this.spawnPosition, this.spawnAngle.x, this.spawnAngle.y);
                PrepareSpawnTask.this.server.getPlayerList().placeNewPlayer(connection, player, cookie);
                input.ifPresent(tag -> {
                    player.loadAndSpawnEnderPearls(tag);
                    player.loadAndSpawnParentVehicle(tag);
                });
                return player;
            }
        }
    }

    private sealed interface State permits PrepareSpawnTask.Preparing, PrepareSpawnTask.Ready {
    }
}

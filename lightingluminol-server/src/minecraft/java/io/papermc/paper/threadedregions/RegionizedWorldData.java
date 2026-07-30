package io.papermc.paper.threadedregions;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.ticks.LevelTicks;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.slf4j.Logger;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RegionizedWorldData {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Entity[] EMPTY_ENTITY_ARRAY = new Entity[0];

    public static final RegionizedData.RegioniserCallback<RegionizedWorldData> REGION_CALLBACK = new RegionizedData.RegioniserCallback<>() {
        @Override
        public void merge(final RegionizedWorldData from, final RegionizedWorldData into, final long fromTickOffset) {
            // connections
            for (final Connection conn : from.connections) {
                into.connections.add(conn);
            }
            // time
            final long fromRedstoneTimeOffset = into.redstoneTime - from.redstoneTime;
            // entities
            for (final ServerPlayer player : from.localPlayers) {
                into.localPlayers.add(player);
                player.getBukkitEntity().updateRegion(into);
                into.nearbyPlayers.addPlayer(player);
            }
            for (final Entity entity : from.allEntities) {
                into.allEntities.add(entity);
                entity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
                entity.getBukkitEntity().taskScheduler.registerTo(into.entitySchedulerTickList);
            }
            for (final Entity entity : from.loadedEntities) {
                into.loadedEntities.add(entity);
            }
            for (final Iterator<Entity> iterator = from.entityTickList.unsafeIterator(); iterator.hasNext();) {
                into.entityTickList.add(iterator.next());
            }
            for (final Iterator<Mob> iterator = from.navigatingMobs.unsafeIterator(); iterator.hasNext();) {
                into.navigatingMobs.add(iterator.next());
            }
            for (final Iterator<Entity> iterator = from.trackerEntities.iterator(); iterator.hasNext();) {
                into.trackerEntities.add(iterator.next());
            }
            for (final Iterator<Entity> iterator = from.trackerUnloadedEntities.iterator(); iterator.hasNext();) {
                into.trackerUnloadedEntities.add(iterator.next());
            }
            // block ticking
            into.blockEvents.addAll(from.blockEvents);
            // ticklists use game time
            from.blockLevelTicks.merge(into.blockLevelTicks, fromRedstoneTimeOffset);
            from.fluidLevelTicks.merge(into.fluidLevelTicks, fromRedstoneTimeOffset);

            // tile entity ticking
            for (final TickingBlockEntity tileEntityWrapped : from.pendingBlockEntityTickers) {
                into.pendingBlockEntityTickers.add(tileEntityWrapped);
                final BlockEntity tileEntity = tileEntityWrapped.getTileEntity();
                if (tileEntity != null) {
                    tileEntity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
                }
            }
            for (final TickingBlockEntity tileEntityWrapped : from.blockEntityTickers) {
                into.blockEntityTickers.add(tileEntityWrapped);
                final BlockEntity tileEntity = tileEntityWrapped.getTileEntity();
                if (tileEntity != null) {
                    tileEntity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
                }
            }

            // ticking chunks
            for (final Iterator<LevelChunk> iterator = from.entityTickingChunks.iterator(); iterator.hasNext();) {
                into.entityTickingChunks.add(iterator.next());
            }
            for (final Iterator<LevelChunk> iterator = from.tickingChunks.iterator(); iterator.hasNext();) {
                into.tickingChunks.add(iterator.next());
            }
            for (final Iterator<LevelChunk> iterator = from.chunks.iterator(); iterator.hasNext();) {
                into.chunks.add(iterator.next());
            }
            // redstone torches
            if (from.redstoneUpdateInfos != null && !from.redstoneUpdateInfos.isEmpty()) {
                if (into.redstoneUpdateInfos == null) {
                    into.redstoneUpdateInfos = new ArrayDeque<>();
                }
                for (final net.minecraft.world.level.block.RedstoneTorchBlock.Toggle info : from.redstoneUpdateInfos) {
                    info.offsetTime(fromRedstoneTimeOffset);
                    into.redstoneUpdateInfos.add(info);
                }
            }
            // mob spawning
            into.catSpawnerNextTick = Math.max(from.catSpawnerNextTick, into.catSpawnerNextTick);
            into.patrolSpawnerNextTick = Math.max(from.patrolSpawnerNextTick, into.patrolSpawnerNextTick);
            into.phantomSpawnerNextTick = Math.max(from.phantomSpawnerNextTick, into.phantomSpawnerNextTick);
            // chunkHoldersToBroadcast
            for (final ChunkHolder chunkHolder : from.chunkHoldersToBroadcast) {
                into.chunkHoldersToBroadcast.add(chunkHolder);
            }
        }

        @Override
        public void split(final RegionizedWorldData from, final int chunkToRegionShift,
                          final Long2ReferenceOpenHashMap<RegionizedWorldData> regionToData,
                          final ReferenceOpenHashSet<RegionizedWorldData> dataSet) {
            // connections
            for (final Connection conn : from.connections) {
                final ServerPlayer player = conn.getPlayer();
                final ChunkPos pos = player.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift))
                    .connections.add(conn);
            }
            // entities
            for (final ServerPlayer player : from.localPlayers) {
                final ChunkPos pos = player.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift));
                into.localPlayers.add(player);
                player.getBukkitEntity().updateRegion(into);
                into.nearbyPlayers.addPlayer(player);
            }
            for (final Entity entity : from.allEntities) {
                final ChunkPos pos = entity.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift));
                into.allEntities.add(entity);
                // Note: entityTickList is a subset of allEntities
                if (from.entityTickList.contains(entity)) {
                    into.entityTickList.add(entity);
                }
                // Note: loadedEntities is a subset of allEntities
                if (from.loadedEntities.contains(entity)) {
                    into.loadedEntities.add(entity);
                }
                // Note: navigatingMobs is a subset of allEntities
                if (entity instanceof Mob mob && from.navigatingMobs.contains(mob)) {
                    into.navigatingMobs.add(mob);
                }
                if (from.trackerEntities.contains(entity)) {
                    into.trackerEntities.add(entity);
                }
                if (from.trackerUnloadedEntities.contains(entity)) {
                    into.trackerUnloadedEntities.add(entity);
                }
                entity.getBukkitEntity().taskScheduler.registerTo(into.entitySchedulerTickList);
            }
            // block ticking
            for (final BlockEventData blockEventData : from.blockEvents) {
                final BlockPos pos = blockEventData.pos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                // Unlike entities, the chunk holder is not guaranteed to exist for block events, because the block events
                // is just some list. So if it unloads, I guess it's just lost.
                if (into != null) {
                    into.blockEvents.add(blockEventData);
                }
            }

            final Long2ReferenceOpenHashMap<LevelTicks<Block>> levelTicksBlockRegionData = new Long2ReferenceOpenHashMap<>(regionToData.size(), 0.75f);
            final Long2ReferenceOpenHashMap<LevelTicks<Fluid>> levelTicksFluidRegionData = new Long2ReferenceOpenHashMap<>(regionToData.size(), 0.75f);

            for (final Iterator<Long2ReferenceMap.Entry<RegionizedWorldData>> iterator = regionToData.long2ReferenceEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ReferenceMap.Entry<RegionizedWorldData> entry = iterator.next();
                final long key = entry.getLongKey();
                final RegionizedWorldData worldData = entry.getValue();

                levelTicksBlockRegionData.put(key, worldData.blockLevelTicks);
                levelTicksFluidRegionData.put(key, worldData.fluidLevelTicks);
            }

            from.blockLevelTicks.split(chunkToRegionShift, levelTicksBlockRegionData);
            from.fluidLevelTicks.split(chunkToRegionShift, levelTicksFluidRegionData);

            // tile entity ticking
            for (final TickingBlockEntity tileEntity : from.pendingBlockEntityTickers) {
                final BlockPos pos = tileEntity.getPos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                if (into != null) {
                    into.pendingBlockEntityTickers.add(tileEntity);
                } // else: when a chunk unloads, it does not actually _remove_ the tile entity from the list, it just gets
                  //       marked as removed. So if there is no section, it's probably removed!
            }
            for (final TickingBlockEntity tileEntity : from.blockEntityTickers) {
                final BlockPos pos = tileEntity.getPos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                if (into != null) {
                    into.blockEntityTickers.add(tileEntity);
                } // else: when a chunk unloads, it does not actually _remove_ the tile entity from the list, it just gets
                  //       marked as removed. So if there is no section, it's probably removed!
            }
            // time
            for (final RegionizedWorldData regionizedWorldData : dataSet) {
                regionizedWorldData.redstoneTime = from.redstoneTime;
            }
            // ticking chunks
            for (final Iterator<LevelChunk> iterator = from.entityTickingChunks.iterator(); iterator.hasNext();) {
                final LevelChunk chunk = iterator.next();
                final ChunkPos pos = chunk.getPos();

                // Impossible for get() to return null, as the chunk is entity ticking - thus the chunk holder is loaded
                regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift))
                    .entityTickingChunks.add(chunk);
            }
            for (final Iterator<LevelChunk> iterator = from.tickingChunks.iterator(); iterator.hasNext();) {
                final LevelChunk chunk = iterator.next();
                final ChunkPos pos = chunk.getPos();

                // Impossible for get() to return null, as the chunk is entity ticking - thus the chunk holder is loaded
                regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift))
                    .tickingChunks.add(chunk);
            }
            for (final Iterator<LevelChunk> iterator = from.chunks.iterator(); iterator.hasNext();) {
                final LevelChunk chunk = iterator.next();
                final ChunkPos pos = chunk.getPos();

                // Impossible for get() to return null, as the chunk is entity ticking - thus the chunk holder is loaded
                regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift))
                    .chunks.add(chunk);
            }

            // redstone torches
            if (from.redstoneUpdateInfos != null && !from.redstoneUpdateInfos.isEmpty()) {
                for (final net.minecraft.world.level.block.RedstoneTorchBlock.Toggle info : from.redstoneUpdateInfos) {
                    final BlockPos pos = info.pos;

                    final RegionizedWorldData worldData = regionToData.get(CoordinateUtils.getChunkKey((pos.getX() >> 4) >> chunkToRegionShift, (pos.getZ() >> 4) >> chunkToRegionShift));
                    if (worldData != null) {
                        if (worldData.redstoneUpdateInfos == null) {
                            worldData.redstoneUpdateInfos = new ArrayDeque<>();
                        }
                        worldData.redstoneUpdateInfos.add(info);
                    } // else: chunk unloaded
                }
            }
            // mob spawning
            for (final RegionizedWorldData regionizedWorldData : dataSet) {
                regionizedWorldData.catSpawnerNextTick = from.catSpawnerNextTick;
                regionizedWorldData.patrolSpawnerNextTick = from.patrolSpawnerNextTick;
                regionizedWorldData.phantomSpawnerNextTick = from.phantomSpawnerNextTick;
                regionizedWorldData.villageSiegeState = new VillageSiegeState(); // just re set it, as the spawn pos will be invalid
            }
            // chunkHoldersToBroadcast
            for (final ChunkHolder chunkHolder : from.chunkHoldersToBroadcast) {
                final ChunkPos pos = chunkHolder.getPos();

                // Possible for get() to return null, as the chunk holder is not removed during unload
                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift));
                if (into != null) {
                    into.chunkHoldersToBroadcast.add(chunkHolder);
                }
            }
        }
    };

    public final ServerLevel world;

    private RegionizedServer.WorldLevelData tickData;

    // connections
    public final List<Connection> connections = new ArrayList<>();

    // misc. fields
    private boolean isHandlingTick;

    public void setHandlingTick(final boolean to) {
        this.isHandlingTick = to;
    }

    public boolean isHandlingTick() {
        return this.isHandlingTick;
    }

    // entities
    // this is copy on write to allow packet processing to iterate safely
    private final CopyOnWriteArrayList<ServerPlayer> localPlayers = new CopyOnWriteArrayList<>();
    private final NearbyPlayers nearbyPlayers;
    public final io.papermc.paper.threadedregions.EntityScheduler.EntitySchedulerTickList entitySchedulerTickList = new io.papermc.paper.threadedregions.EntityScheduler.EntitySchedulerTickList();
    private final ReferenceList<Entity> allEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY);
    private final ReferenceList<Entity> loadedEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY);
    private final IteratorSafeOrderedReferenceSet<Entity> entityTickList = new IteratorSafeOrderedReferenceSet<>();
    private final IteratorSafeOrderedReferenceSet<Mob> navigatingMobs = new IteratorSafeOrderedReferenceSet<>();
    public final ReferenceList<Entity> trackerEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY); // Moonrise - entity tracker
    public final ReferenceList<Entity> trackerUnloadedEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY); // Moonrise - entity tracker

    // block ticking
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private final LevelTicks<Block> blockLevelTicks;
    private final LevelTicks<Fluid> fluidLevelTicks;

    // tile entity ticking
    private final List<TickingBlockEntity> pendingBlockEntityTickers = new ArrayList<>();
    private final List<TickingBlockEntity> blockEntityTickers = new ArrayList<>();
    private boolean tickingBlockEntities;

    // time
    private long redstoneTime = 1L;

    public long getRedstoneGameTime() {
        return this.redstoneTime;
    }

    public void setRedstoneGameTime(final long to) {
        this.redstoneTime = to;
    }

    // ticking chunks
    private static final LevelChunk[] EMPTY_CHUNK_ARRAY = new LevelChunk[0];
    private final ReferenceList<LevelChunk> entityTickingChunks = new ReferenceList<>(EMPTY_CHUNK_ARRAY);
    private final ReferenceList<LevelChunk> tickingChunks = new ReferenceList<>(EMPTY_CHUNK_ARRAY);
    private final ReferenceList<LevelChunk> chunks = new ReferenceList<>(EMPTY_CHUNK_ARRAY);

    // Paper/CB api hook misc
    // don't bother to merge/split these, no point
    // From ServerLevel
    public boolean hasPhysicsEvent = true; // Paper
    public boolean hasEntityMoveEvent = false; // Paper
    // Paper start - Optimize Hoppers
    public boolean skipPullModeEventFire = false;
    public boolean skipPushModeEventFire = false;
    public boolean skipHopperEvents = false;
    // Paper end - Optimize Hoppers
    public long lastMidTickExecute;
    public long lastMidTickExecuteFailure;
    // From Level
    public boolean populating;
    public final CollectingNeighborUpdater neighborUpdater;
    public boolean captureBlockStates = false;
    public boolean captureTreeGeneration = false;
    public final Map<BlockPos, CraftBlockState> capturedBlockStates = new java.util.LinkedHashMap<>(); // Paper
    public final Map<BlockPos, BlockEntity> capturedTileEntities = new java.util.LinkedHashMap<>(); // Paper
    public List<ItemEntity> captureDrops;
    // Paper start
    public int wakeupInactiveRemainingAnimals;
    public int wakeupInactiveRemainingFlying;
    public int wakeupInactiveRemainingMonsters;
    public int wakeupInactiveRemainingVillagers;
    // Paper end
    public int currentPrimedTnt = 0; // Spigot
    @Nullable
    @VisibleForDebug
    public NaturalSpawner.SpawnState lastSpawnState;
    public boolean shouldSignal = true;
    public final Map<ServerExplosion.CacheKey, Float> explosionDensityCache = new HashMap<>(64, 0.25f);
    public final PathTypeCache pathTypesByPosCache = new PathTypeCache();
    public final List<LevelChunk> temporaryChunkTickList = new java.util.ArrayList<>();
    public final Set<ChunkHolder> chunkHoldersToBroadcast = new ReferenceLinkedOpenHashSet<>();

    // not transient
    public java.util.ArrayDeque<net.minecraft.world.level.block.RedstoneTorchBlock.Toggle> redstoneUpdateInfos;

    // Mob spawning
    public final ca.spottedleaf.moonrise.common.misc.PositionCountingAreaMap<ServerPlayer> spawnChunkTracker = new ca.spottedleaf.moonrise.common.misc.PositionCountingAreaMap<>();
    public int catSpawnerNextTick = 0;
    public int patrolSpawnerNextTick = 0;
    public int phantomSpawnerNextTick = 0;
    public VillageSiegeState villageSiegeState = new VillageSiegeState();
    public WanderingTraderData wanderingTraderData;

    public static final class VillageSiegeState {
        public boolean hasSetupSiege;
        public VillageSiege.State siegeState = VillageSiege.State.SIEGE_DONE;
        public int zombiesToSpawn;
        public int nextSpawnTime;
        public int spawnX;
        public int spawnY;
        public int spawnZ;
    }
    // Redstone
    public final alternate.current.wire.WireHandler wireHandler;
    public final io.papermc.paper.redstone.RedstoneWireTurbo turbo;
    // Environment attribute system
    public Reference2ObjectOpenHashMap<EnvironmentAttribute<?>, EnvironmentAttributeSystem.ValueSampler<?>> attributeSamplers;

    public final TickRegions.TickRegionData regionData;

    public RegionizedWorldData(final ServerLevel world, final TickRegions.TickRegionData regionData) {
        this.world = world;
        this.blockLevelTicks = new LevelTicks<>(world::isPositionTickingWithEntitiesLoaded, world, true);
        this.fluidLevelTicks = new LevelTicks<>(world::isPositionTickingWithEntitiesLoaded, world, false);
        this.neighborUpdater = new CollectingNeighborUpdater(world, world.neighbourUpdateMax);
        this.nearbyPlayers = new NearbyPlayers(world);
        this.wireHandler = new alternate.current.wire.WireHandler(world);
        this.turbo = new io.papermc.paper.redstone.RedstoneWireTurbo((RedStoneWireBlock)Blocks.REDSTONE_WIRE);
        this.regionData = regionData;

        // tasks may be drained before the region ticks, so we must set up the tick data early just in case
        this.updateTickData();
    }

    public void checkWorld(final Level against) {
        if (this.world != against) {
            throw new IllegalStateException("World mismatch: expected " + this.world.getWorld().getName() + " but got " + (against == null ? "null" : against.getWorld().getName()));
        }
    }

    public RegionizedServer.WorldLevelData getTickData() {
        return this.tickData;
    }

    private long lagCompensationTick;

    public long getLagCompensationTick() {
        return this.lagCompensationTick;
    }

    public void updateTickData() {
        this.tickData = this.world.tickData;
        this.hasPhysicsEvent = org.bukkit.event.block.BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0; // Paper - BlockPhysicsEvent
        this.hasEntityMoveEvent = io.papermc.paper.event.entity.EntityMoveEvent.getHandlerList().getRegisteredListeners().length > 0; // Paper - Add EntityMoveEvent
        this.skipHopperEvents = this.world.paperConfig().hopper.disableMoveEvent || org.bukkit.event.inventory.InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0; // Paper - Perf: Optimize Hoppers
        // always subtract from server init so that the tick starts at zero, allowing us to cast to int without much worry
        this.lagCompensationTick = (System.nanoTime() - MinecraftServer.SERVER_INIT) / TickRegionScheduler.TIME_BETWEEN_TICKS;
    }

    public NearbyPlayers getNearbyPlayers() {
        return this.nearbyPlayers;
    }

    private static void cleanUpConnection(final Connection conn) {
        // note: ALL connections HERE have a player
        final ServerPlayer player = conn.getPlayer();
        // now that the connection is removed, we can allow this region to die
        player.level().moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
            ServerGamePacketListenerImpl.DISCONNECT_TICKET, player.connection.disconnectPos,
            ChunkHolderManager.MAX_TICKET_LEVEL,
            player.connection.disconnectTicketId
        );
    }

    // connections
    public void tickConnections() {
        final List<Connection> connections = new ArrayList<>(this.connections);
        Collections.shuffle(connections);
        for (final Connection conn : connections) {
            if (!conn.isConnected()) {
                conn.handleDisconnection();
                // global tick thread will not remove connections not owned by it, so we need to
                RegionizedServer.getInstance().removeConnection(conn);
                this.connections.remove(conn);
                cleanUpConnection(conn);
                continue;
            }
            if (!this.connections.contains(conn)) {
                // removed by connection tick?
                continue;
            }

            try {
                conn.tick();
            } catch (final Exception exception) {
                if (conn.isMemoryConnection()) {
                    throw new ReportedException(CrashReport.forThrowable(exception, "Ticking memory connection"));
                }

                LOGGER.warn("Failed to handle packet for {}", conn.getLoggableAddress(MinecraftServer.getServer().logIPs()), exception);
                MutableComponent ichatmutablecomponent = Component.literal("Internal server error");

                conn.send(new ClientboundDisconnectPacket(ichatmutablecomponent), PacketSendListener.thenRun(() -> {
                    conn.disconnect(ichatmutablecomponent);
                }));
                conn.setReadOnly();
                continue;
            }
        }
    }

    // entities hooks
    public int getEntityCount() {
        return this.allEntities.size();
    }

    public int getPlayerCount() {
        return this.localPlayers.size();
    }

    public Iterable<Entity> getLocalEntities() {
        return this.allEntities;
    }

    public Entity[] getLocalEntitiesCopy() {
        return Arrays.copyOf(this.allEntities.getRawData(), this.allEntities.size(), Entity[].class);
    }

    public List<ServerPlayer> getLocalPlayers() {
        return this.localPlayers;
    }

    public void addLoadedEntity(final Entity entity) {
        this.loadedEntities.add(entity);
    }

    public boolean hasLoadedEntity(final Entity entity) {
        return this.loadedEntities.contains(entity);
    }

    public void removeLoadedEntity(final Entity entity) {
        this.loadedEntities.remove(entity);
    }

    public Iterable<Entity> getLoadedEntities() {
        return this.loadedEntities;
    }

    public void addEntityTickingEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        this.entityTickList.add(entity);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public boolean hasEntityTickingEntity(final Entity entity) {
        return this.entityTickList.contains(entity);
    }

    public void removeEntityTickingEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        this.entityTickList.remove(entity);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public void forEachTickingEntity(final Consumer<Entity> action) {
        final IteratorSafeOrderedReferenceSet.Iterator<Entity> iterator = this.entityTickList.iterator();
        try {
            while (iterator.hasNext()) {
                action.accept(iterator.next());
            }
        } finally {
            iterator.finishedIterating();
        }
    }

    public void addEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(this.world, entity.chunkPosition())) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        if (this.allEntities.add(entity)) {
            if (entity instanceof ServerPlayer player) {
                this.localPlayers.add(player);
                player.getBukkitEntity().updateRegion(this);
            }
            TickRegions.RegionStats.updateCurrentRegion();
        }
    }

    public boolean hasEntity(final Entity entity) {
        return this.allEntities.contains(entity);
    }

    public void removeEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        if (this.allEntities.remove(entity)) {
            if (entity instanceof ServerPlayer player) {
                this.localPlayers.remove(player);
                player.getBukkitEntity().updateRegion(null);
            }
            TickRegions.RegionStats.updateCurrentRegion();
        }
    }

    public void addNavigatingMob(final Mob mob) {
        if (!TickThread.isTickThreadFor(mob)) {
            throw new IllegalArgumentException("Entity " + mob + " is not under this region's control");
        }
        this.navigatingMobs.add(mob);
    }

    public void removeNavigatingMob(final Mob mob) {
        if (!TickThread.isTickThreadFor(mob)) {
            throw new IllegalArgumentException("Entity " + mob + " is not under this region's control");
        }
        this.navigatingMobs.remove(mob);
    }

    public Iterator<Mob> getNavigatingMobs() {
        return this.navigatingMobs.unsafeIterator();
    }

    // block ticking hooks
    // Since block event data does not require chunk holders to be created for the chunk they reside in,
    // it's not actually guaranteed that when merging / splitting data that we actually own the data...
    // Note that we can only ever not own the event data when the chunk unloads, and so I've decided to
    // make the code easier by simply discarding it in such an event
    public void pushBlockEvent(final BlockEventData blockEventData) {
        TickThread.ensureTickThread(this.world, blockEventData.pos(), "Cannot queue block even data async");
        this.blockEvents.add(blockEventData);
    }

    public void pushBlockEvents(final Collection<? extends BlockEventData> blockEvents) {
        for (final BlockEventData blockEventData : blockEvents) {
            this.pushBlockEvent(blockEventData);
        }
    }

    public void removeIfBlockEvents(final Predicate<? super BlockEventData> predicate) {
        for (final Iterator<BlockEventData> iterator = this.blockEvents.iterator(); iterator.hasNext();) {
            final BlockEventData blockEventData = iterator.next();
            if (predicate.test(blockEventData)) {
                iterator.remove();
            }
        }
    }

    public BlockEventData removeFirstBlockEvent() {
        BlockEventData ret;
        while (!this.blockEvents.isEmpty()) {
            ret = this.blockEvents.removeFirst();
            if (TickThread.isTickThreadFor(this.world, ret.pos())) {
                return ret;
            } // else: chunk must have been unloaded
        }

        return null;
    }

    public LevelTicks<Block> getBlockLevelTicks() {
        return this.blockLevelTicks;
    }

    public LevelTicks<Fluid> getFluidLevelTicks() {
        return this.fluidLevelTicks;
    }

    // tile entity ticking
    public void addBlockEntityTicker(final TickingBlockEntity ticker) {
        TickThread.ensureTickThread(this.world, ticker.getPos(), "Tile entity must be owned by current region");

        (this.tickingBlockEntities ? this.pendingBlockEntityTickers : this.blockEntityTickers).add(ticker);
    }

    public void setTickingBlockEntities(final boolean to) {
        this.tickingBlockEntities = true;
    }

    public List<TickingBlockEntity> getBlockEntityTickers() {
        return this.blockEntityTickers;
    }

    public void pushPendingTickingBlockEntities() {
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
            this.pendingBlockEntityTickers.clear();
        }
    }

    // ticking chunks
    public void addEntityTickingChunk(final LevelChunk chunk) {
        this.entityTickingChunks.add(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public void removeEntityTickingChunk(final LevelChunk chunk) {
        this.entityTickingChunks.remove(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public ReferenceList<LevelChunk> getEntityTickingChunks() {
        return this.entityTickingChunks;
    }

    public void addTickingChunk(final LevelChunk chunk) {
        this.tickingChunks.add(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public void removeTickingChunk(final LevelChunk chunk) {
        this.tickingChunks.remove(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public ReferenceList<LevelChunk> getTickingChunks() {
        return this.tickingChunks;
    }

    public void addChunk(final LevelChunk chunk) {
        this.chunks.add(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public void removeChunk(final LevelChunk chunk) {
        this.chunks.remove(chunk);
        TickRegions.RegionStats.updateCurrentRegion();
    }

    public ReferenceList<LevelChunk> getChunks() {
        return this.chunks;
    }

    public int getEntityTickingChunkCount() {
        return this.entityTickingChunks.size();
    }

    public int getChunkCount() {
        return this.chunks.size();
    }
}
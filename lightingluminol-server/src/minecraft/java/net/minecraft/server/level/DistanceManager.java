package net.minecraft.server.level;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.util.TriState;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class DistanceManager implements ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemDistanceManager, ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickDistanceManager { // Paper - rewrite chunk system // Paper - chunk tick iteration optimisation
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PLAYER_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private final Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk = new Long2ObjectOpenHashMap<>();
    // Paper - rewrite chunk system
    public final TicketStorage ticketStorage;
    // Paper - chunk tick iteration optimisation
    // Paper - rewrite chunk system

    protected DistanceManager(final TicketStorage ticketStorage, final Executor executor, final Executor mainThreadExecutor) {
        this.ticketStorage = ticketStorage;
        // Paper - rewrite chunk system
        TaskScheduler<Runnable> mainThreadTaskScheduler = TaskScheduler.wrapExecutor("player ticket throttler", mainThreadExecutor);
        this.ticketStorage.moonrise$setChunkMap(this.moonrise$getChunkMap()); // Paper - rewrite chunk system
    }

    // Paper start - rewrite chunk system
    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager moonrise$getChunkHolderManager() {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.moonrise$getChunkMap().level).moonrise$getChunkTaskScheduler().chunkHolderManager;
    }
    // Paper end - rewrite chunk system
    // Paper start - chunk tick iteration optimisation
    // Folia - move to regionized world data
    // Note: Cannot do narrow tracking on Paper due to custom spawn range

    @Override
    public final void moonrise$addPlayer(final ServerPlayer player, final SectionPos pos) {
        this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.add(player, pos.x(), pos.z(), ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE); // Folia - region threading
        // Note: Cannot do narrow tracking on Paper due to custom spawn range
    }

    @Override
    public final void moonrise$removePlayer(final ServerPlayer player, final SectionPos pos) {
        this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.remove(player); // Folia - region threading
        // Note: Cannot do narrow tracking on Paper due to custom spawn range
    }

    @Override
    public final void moonrise$updatePlayer(final ServerPlayer player,
                                            final SectionPos oldPos, final SectionPos newPos,
                                            final boolean oldIgnore, final boolean newIgnore) {
        if (newIgnore) {
            this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.remove(player); // Folia - region threading
            // Note: Cannot do narrow tracking on Paper due to custom spawn range
        } else {
            this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.addOrUpdate(player, newPos.x(), newPos.z(), ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE); // Folia - region threading
            // Note: Cannot do narrow tracking on Paper due to custom spawn range
        }
    }

    @Override
    public final boolean moonrise$hasAnyNearbyNarrow(final int chunkX, final int chunkZ) {
        throw new UnsupportedOperationException(); // Note: Cannot do narrow tracking on Paper due to custom spawn range
    }
    // Paper end - chunk tick iteration optimisation

    protected abstract boolean isChunkToRemove(final long node);

    protected abstract @Nullable ChunkHolder getChunk(final long node);

    protected abstract @Nullable ChunkHolder updateChunkScheduling(final long node, final int level, final @Nullable ChunkHolder chunk, final int oldLevel);

    public boolean runAllUpdates(final ChunkMap scheduler) {
        return this.moonrise$getChunkHolderManager().processTicketUpdates(); // Paper - rewrite chunk system
    }

    public void addPlayer(final SectionPos pos, final ServerPlayer player) {
        ChunkPos chunk = pos.chunk();
        long chunkPos = chunk.pack();
        this.playersPerChunk.computeIfAbsent(chunkPos, k -> new ObjectOpenHashSet<>()).add(player);
        // Paper - chunk tick iteration optimisation
        // Paper - rewrite chunk system
    }

    public void removePlayer(final SectionPos pos, final ServerPlayer player) {
        ChunkPos chunk = pos.chunk();
        long chunkPos = chunk.pack();
        ObjectSet<ServerPlayer> chunkPlayers = this.playersPerChunk.get(chunkPos);
        // Paper start - some state corruption happens here, don't crash, clean up gracefully
        if (chunkPlayers != null) chunkPlayers.remove(player);
        if (chunkPlayers == null || chunkPlayers.isEmpty()) {
        // Paper end - some state corruption happens here, don't crash, clean up gracefully
            this.playersPerChunk.remove(chunkPos);
            // Paper - chunk tick iteration optimisation
            // Paper - rewrite chunk system
        }
    }

    private int getPlayerTicketLevel() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public boolean inEntityTickingRange(final long key) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkHolderManager().getChunkHolder(key);
        return chunkHolder != null && chunkHolder.isEntityTickingReady();
        // Paper end - rewrite chunk system
    }

    public boolean inBlockTickingRange(final long key) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkHolderManager().getChunkHolder(key);
        return chunkHolder != null && chunkHolder.isTickingReady();
        // Paper end - rewrite chunk system
    }

    public int getChunkLevel(final long key, final boolean simulation) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkHolderManager().getChunkHolder(key);
        return chunkHolder == null ? ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL + 1 : chunkHolder.getTicketLevel();
        // Paper end - rewrite chunk system
    }

    protected void updatePlayerTickets(final int viewDistance) {
        this.moonrise$getChunkMap().setServerViewDistance(viewDistance); // Paper - rewrite chunk system
    }

    public void updateSimulationDistance(final int newDistance) {
        // Paper start - rewrite chunk system
        // note: vanilla does not clamp to 0, but we do simply because we need a min of 0
        final int clamped = net.minecraft.util.Mth.clamp(newDistance, 0, ca.spottedleaf.moonrise.common.util.MoonriseConstants.MAX_VIEW_DISTANCE);

        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.moonrise$getChunkMap().level).moonrise$getPlayerChunkLoader().setTickDistance(clamped);
        // Paper end - rewrite chunk system
    }

    public int getNaturalSpawnChunkCount() {
        return this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.getTotalPositions(); // Paper - chunk tick iteration optimisation // Folia - region threading
    }

    public TriState hasPlayersNearby(final long pos) {
        // Note: Cannot do narrow tracking on Paper due to custom spawn range // Paper - chunk tick iteration optimisation
        return this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.hasObjectsNear(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos)) ? net.minecraft.util.TriState.DEFAULT : net.minecraft.util.TriState.FALSE; // Paper - chunk tick iteration optimisation // Folia - region threading
    }

    public void forEachEntityTickingChunk(final LongConsumer consumer) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.level.chunk.LevelChunk> chunks = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.moonrise$getChunkMap().level).moonrise$getEntityTickingChunks();
        final LevelChunk[] raw = chunks.getRawDataUnchecked();
        final int size = chunks.size();

        java.util.Objects.checkFromToIndex(0, size, raw.length);
        for (int i = 0; i < size; ++i) {
            final LevelChunk chunk = raw[i];

            consumer.accept(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunk.getPos()));
        }
        // Paper end - rewrite chunk system
    }

    public LongIterator getSpawnCandidateChunks() {
        return this.moonrise$getChunkMap().level.getCurrentWorldData().spawnChunkTracker.getPositions().iterator(); // Paper - chunk tick iteration optimisation // Folia - region threading
    }

    public String getDebugStatus() {
        return "N/A"; // Paper - rewrite chunk system
    }

    public boolean hasTickets() {
        return this.ticketStorage.hasTickets();
    }

    private class FixedPlayerDistanceChunkTracker extends ChunkTracker {
        protected final Long2ByteMap chunks = new Long2ByteOpenHashMap();
        protected final int maxDistance;

        protected FixedPlayerDistanceChunkTracker(final int maxDistance) {
            super(maxDistance + 2, 16, 256);
            this.maxDistance = maxDistance;
            this.chunks.defaultReturnValue((byte)(maxDistance + 2));
        }

        @Override
        protected int getLevel(final long node) {
            return this.chunks.get(node);
        }

        @Override
        protected void setLevel(final long node, final int level) {
            byte oldLevel;
            if (level > this.maxDistance) {
                oldLevel = this.chunks.remove(node);
            } else {
                oldLevel = this.chunks.put(node, (byte)level);
            }

            this.onLevelChange(node, oldLevel, level);
        }

        protected void onLevelChange(final long node, final int oldLevel, final int level) {
        }

        @Override
        protected int getLevelFromSource(final long to) {
            return this.havePlayer(to) ? 0 : Integer.MAX_VALUE;
        }

        private boolean havePlayer(final long chunkPos) {
            ObjectSet<ServerPlayer> players = DistanceManager.this.playersPerChunk.get(chunkPos);
            return players != null && !players.isEmpty();
        }

        public void runAllUpdates() {
            this.runUpdates(Integer.MAX_VALUE);
        }
    }

/*  // Paper - rewrite chunk system
    private class PlayerTicketTracker extends DistanceManager.FixedPlayerDistanceChunkTracker {
        private int viewDistance;
        private final Long2IntMap queueLevels = Long2IntMaps.synchronize(new Long2IntOpenHashMap());
        private final LongSet toUpdate = new LongOpenHashSet();

        protected PlayerTicketTracker(final int maxDistance) {
            super(maxDistance);
            this.viewDistance = 0;
            this.queueLevels.defaultReturnValue(maxDistance + 2);
        }

        @Override
        protected void onLevelChange(final long node, final int oldLevel, final int level) {
            this.toUpdate.add(node);
        }

        public void updateViewDistance(final int viewDistance) {
            for (Entry entry : this.chunks.long2ByteEntrySet()) {
                byte level = entry.getByteValue();
                long key = entry.getLongKey();
                this.onLevelChange(key, level, this.haveTicketFor(level), level <= viewDistance);
            }

            this.viewDistance = viewDistance;
        }

        private void onLevelChange(final long key, final int level, final boolean saw, final boolean sees) {
            if (saw != sees) {
                Ticket ticket = new Ticket(TicketType.PLAYER_LOADING, DistanceManager.PLAYER_TICKET_LEVEL);
                if (sees) {
                    DistanceManager.this.ticketDispatcher.submit(() -> DistanceManager.this.mainThreadExecutor.execute(() -> {
                        if (this.haveTicketFor(this.getLevel(key))) {
                            DistanceManager.this.ticketStorage.addTicket(key, ticket);
                            DistanceManager.this.ticketsToRelease.add(key);
                        } else {
                            DistanceManager.this.ticketDispatcher.release(key, () -> {}, false);
                        }
                    }), key, () -> level);
                } else {
                    DistanceManager.this.ticketDispatcher
                        .release(
                            key,
                            () -> DistanceManager.this.mainThreadExecutor.execute(() -> DistanceManager.this.ticketStorage.removeTicket(key, ticket)),
                            true
                        );
                }
            }
        }

        @Override
        public void runAllUpdates() {
            super.runAllUpdates();
            if (!this.toUpdate.isEmpty()) {
                LongIterator iterator = this.toUpdate.iterator();

                while (iterator.hasNext()) {
                    long node = iterator.nextLong();
                    int oldLevel = this.queueLevels.get(node);
                    int level = this.getLevel(node);
                    if (oldLevel != level) {
                        DistanceManager.this.ticketDispatcher.onLevelChange(ChunkPos.unpack(node), () -> this.queueLevels.get(node), level, l -> {
                            if (l >= this.queueLevels.defaultReturnValue()) {
                                this.queueLevels.remove(node);
                            } else {
                                this.queueLevels.put(node, l);
                            }
                        });
                        this.onLevelChange(node, level, this.haveTicketFor(oldLevel), this.haveTicketFor(level));
                    }
                }

                this.toUpdate.clear();
            }
        }

        private boolean haveTicketFor(final int level) {
            return level <= this.viewDistance;
        }
    }*/  // Paper - rewrite chunk system
}

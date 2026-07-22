package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerChunkCache extends ChunkSource implements ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache { // Paper - rewrite chunk system
    private static final Logger LOGGER = LogUtils.getLogger();
    private final DistanceManager distanceManager;
    private final ServerLevel level;
    private final Thread mainThread;
    private final ThreadedLevelLightEngine lightEngine;
    public final ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    public final ChunkMap chunkMap;
    private final SavedDataStorage savedDataStorage;
    private final TicketStorage ticketStorage;
    //private long lastInhabitedUpdate; // Folia - region threading
    public boolean spawnEnemies = true;
    public boolean spawnFriendlies = true; // Paper - add back spawnFriendlies field
    private static final int CACHE_SIZE = 4;
    private final long[] lastChunkPos = new long[4];
    private final @Nullable ChunkStatus[] lastChunkStatus = new ChunkStatus[4];
    private final @Nullable ChunkAccess[] lastChunk = new ChunkAccess[4];
    // Folia - moved to regionised world data
    // Paper start
    public final ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<net.minecraft.world.level.chunk.LevelChunk> fullChunks = new ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<>();
    public int getFullChunksCount() {
        return this.fullChunks.size();
    }
    long chunkFutureAwaitCounter;
    // Paper end
    // Paper start - rewrite chunk system

    @Override
    public final void moonrise$setFullChunk(final int chunkX, final int chunkZ, final LevelChunk chunk) {
        final long key = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (chunk == null) {
            this.fullChunks.remove(key);
        } else {
            this.fullChunks.put(key, chunk);
        }
    }

    @Override
    public final LevelChunk moonrise$getFullChunkIfLoaded(final int chunkX, final int chunkZ) {
        return this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    private ChunkAccess syncLoad(final int chunkX, final int chunkZ, final ChunkStatus toStatus) {
        // Folia start - region threading
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Cannot asynchronously load chunks");
        }
        // Folia end - region threading
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final CompletableFuture<ChunkAccess> completable = new CompletableFuture<>();
        chunkTaskScheduler.scheduleChunkLoad(
            chunkX, chunkZ, toStatus, true, ca.spottedleaf.concurrentutil.util.Priority.BLOCKING,
            completable::complete
        );

        if (!completable.isDone() && chunkTaskScheduler.hasShutdown()) {
            throw new IllegalStateException(
                "Chunk system has shut down, cannot process chunk requests in world '" + ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(this.level) + "' at "
                    + "(" + chunkX + "," + chunkZ + ") status: " + toStatus
            );
        }

        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.level, chunkX, chunkZ)) {
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.pushChunkWait(this.level, chunkX, chunkZ);
            this.mainThreadProcessor.managedBlock(completable::isDone);
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.popChunkWait();
        }

        final ChunkAccess ret = completable.join();
        if (ret == null) {
            throw new IllegalStateException("Chunk not loaded when requested");
        }

        return ret;
    }

    private ChunkAccess getChunkFallback(final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                         final boolean load) {
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder currentChunk = chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));

        final ChunkAccess ifPresent = currentChunk == null ? null : currentChunk.getChunkIfPresent(toStatus);

        if (ifPresent != null && (toStatus != ChunkStatus.FULL || currentChunk.isFullChunkReady())) {
            return ifPresent;
        }

        final ca.spottedleaf.moonrise.common.PlatformHooks platformHooks = ca.spottedleaf.moonrise.common.PlatformHooks.get();

        if (platformHooks.hasCurrentlyLoadingChunk() && currentChunk != null) {
            final ChunkAccess loading = platformHooks.getCurrentlyLoadingChunk(currentChunk.vanillaChunkHolder);
            if (loading != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
                return loading;
            }
        }

        return load ? this.syncLoad(chunkX, chunkZ, toStatus) : null;
    }
    // Paper end - rewrite chunk system
    // Paper start - chunk tick iteration optimisations
    private final ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom shuffleRandom = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(0L);
    private void iterateTickingChunksFaster() {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.RANDOM_TICK); try { // Folia - profiler
        final ServerLevel world = this.level;
        final int randomTickSpeed = world.getGameRules().get(GameRules.RANDOM_TICK_SPEED);

        // TODO check on update: impl of forEachBlockTickingChunk will only iterate ENTITY ticking chunks!
        // TODO check on update: consumer just runs tickChunk
        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.level.chunk.LevelChunk> entityTickingChunks = world.getCurrentWorldData().getEntityTickingChunks(); // Folia - region threading

        // note: we can use the backing array here because:
        // 1. we do not care about new additions
        // 2. _removes_ are impossible at this stage in the tick
        final LevelChunk[] raw = entityTickingChunks.getRawDataUnchecked();
        final int size = entityTickingChunks.size(); foliaProfiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.RANDOM_CHUNK_TICK_COUNT, (long)size); // Folia - profiler

        java.util.Objects.checkFromToIndex(0, size, raw.length);
        for (int i = 0; i < size; ++i) {
            world.tickChunk(raw[i], randomTickSpeed);

            // call mid-tick tasks for chunk system
            if ((i & 7) == 0) {
                ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this.level.getServer()).moonrise$executeMidTickTasks();
                continue;
            }
        }
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.RANDOM_TICK); } // Folia - profiler
    }
    // Paper end - chunk tick iteration optimisations

    public ServerChunkCache(
        final ServerLevel level,
        final LevelStorageSource.LevelStorageAccess levelStorage,
        final DataFixer fixerUpper,
        final StructureTemplateManager structureTemplateManager,
        final Executor executor,
        final ChunkGenerator generator,
        final int viewDistance,
        final int simulationDistance,
        final boolean syncWrites,
        final ChunkStatusUpdateListener chunkStatusListener,
        final Supplier<SavedDataStorage> overworldDataStorage
        , final SavedDataStorage savedDataStorage // Paper - initialize SavedDataStorage earlier
    ) {
        this.level = level;
        this.mainThreadProcessor = new ServerChunkCache.MainThreadExecutor(level);
        this.mainThread = Thread.currentThread();
        Path dataFolder = levelStorage.getDimensionPath(level.dimension()).resolve("data");

        try {
            FileUtil.createDirectoriesSafe(dataFolder);
        } catch (IOException e) {
            LOGGER.error("Failed to create dimension data storage directory", e);
        }

        this.savedDataStorage = savedDataStorage; // Paper - initialize SavedDataStorage earlier
        this.ticketStorage = this.savedDataStorage.computeIfAbsent(TicketStorage.TYPE);
        this.chunkMap = new ChunkMap(
            level,
            levelStorage,
            fixerUpper,
            structureTemplateManager,
            executor,
            this.mainThreadProcessor,
            this,
            generator,
            chunkStatusListener,
            overworldDataStorage,
            this.ticketStorage,
            viewDistance,
            syncWrites
        );
        this.lightEngine = this.chunkMap.getLightEngine();
        this.distanceManager = this.chunkMap.getDistanceManager();
        this.distanceManager.updateSimulationDistance(simulationDistance);
        this.clearCache();
    }

    // CraftBukkit start - properly implement isChunkLoaded
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ChunkHolder chunk = this.chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        if (chunk == null) {
            return false;
        }
        return chunk.getFullChunkNow() != null;
    }
    // CraftBukkit end
    // Paper start
    // Paper - rewrite chunk system

    @Nullable
    public ChunkAccess getChunkAtImmediately(int x, int z) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        if (holder == null) {
            return null;
        }

        return holder.getLatestChunk();
    }

    public void addTicketAtLevel(TicketType ticketType, ChunkPos chunkPos, int ticketLevel) {
        this.ticketStorage.addTicket(new Ticket(ticketType, ticketLevel), chunkPos);
    }

    public void removeTicketAtLevel(TicketType ticketType, ChunkPos chunkPos, int ticketLevel) {
        this.ticketStorage.removeTicket(new Ticket(ticketType, ticketLevel), chunkPos);
    }

    // "real" get chunk if loaded
    // Note: Partially copied from the getChunkAt method below
    @Nullable
    public LevelChunk getChunkAtIfCachedImmediately(int x, int z) {
        long key = ChunkPos.pack(x, z);

        // Note: Bypass cache since we need to check ticket level, and to make this MT-Safe

        ChunkHolder playerChunk = this.getVisibleChunkIfPresent(key);
        if (playerChunk == null) {
            return null;
        }

        return playerChunk.getFullChunkNowUnchecked();
    }

    @Nullable
    public LevelChunk getChunkAtIfLoadedImmediately(int x, int z) {
        return this.fullChunks.get(ChunkPos.pack(x, z));
    }
    // Paper end

    @Override
    public ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    private @Nullable ChunkHolder getVisibleChunkIfPresent(final long key) {
        return this.chunkMap.getVisibleChunkIfPresent(key);
    }

    private void storeInCache(final long pos, final @Nullable ChunkAccess chunk, final ChunkStatus status) {
        for (int i = 3; i > 0; i--) {
            this.lastChunkPos[i] = this.lastChunkPos[i - 1];
            this.lastChunkStatus[i] = this.lastChunkStatus[i - 1];
            this.lastChunk[i] = this.lastChunk[i - 1];
        }

        this.lastChunkPos[0] = pos;
        this.lastChunkStatus[0] = status;
        this.lastChunk[0] = chunk;
    }

    @Override
    public @Nullable ChunkAccess getChunk(final int x, final int z, final ChunkStatus targetStatus, final boolean loadOrGenerate) {
        // Paper start - rewrite chunk system
        if (targetStatus == ChunkStatus.FULL) {
            final LevelChunk ret = this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(x, z));

            if (ret != null) {
                return ret;
            }

            return loadOrGenerate ? this.getChunkFallback(x, z, targetStatus, loadOrGenerate) : null;
        }

        return this.getChunkFallback(x, z, targetStatus, loadOrGenerate);
        // Paper end - rewrite chunk system
    }

    @Override
    public @Nullable LevelChunk getChunkNow(final int x, final int z) {
        // Paper start - rewrite chunk system
        final LevelChunk ret = this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(x, z));
        if (!ca.spottedleaf.moonrise.common.PlatformHooks.get().hasCurrentlyLoadingChunk()) {
            return ret;
        }

        if (ret != null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return ret;
        }

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getChunkHolder(x, z);
        if (holder == null) {
            return ret;
        }

        return ca.spottedleaf.moonrise.common.PlatformHooks.get().getCurrentlyLoadingChunk(holder.vanillaChunkHolder);
        // Paper end - rewrite chunk system
    }

    private void clearCache() {
        Arrays.fill(this.lastChunkPos, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.lastChunkStatus, null);
        Arrays.fill(this.lastChunk, null);
    }

    public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFuture(final int x, final int z, final ChunkStatus targetStatus, final boolean loadOrGenerate) {
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        boolean isMainThread = Thread.currentThread() == this.mainThread;
        CompletableFuture<ChunkResult<ChunkAccess>> serverFuture;
        if (isMainThread) {
            serverFuture = this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate);
            this.mainThreadProcessor.managedBlock(serverFuture::isDone);
        } else {
            serverFuture = CompletableFuture.<CompletableFuture<ChunkResult<ChunkAccess>>>supplyAsync(
                    () -> this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate), this.mainThreadProcessor
                )
                .thenCompose(chunk -> (CompletionStage<ChunkResult<ChunkAccess>>)chunk);
        }

        return serverFuture;
    }

    private CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(
        final int x, final int z, final ChunkStatus targetStatus, final boolean loadOrGenerate
    ) {
        // Paper start - rewrite chunk system
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, x, z, "Scheduling chunk load off-main");

        final int minLevel = ChunkLevel.byStatus(targetStatus);
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(x, z);

        final boolean needsFullScheduling = targetStatus == ChunkStatus.FULL && (chunkHolder == null || !chunkHolder.getChunkStatus().isOrAfter(FullChunkStatus.FULL));

        if ((chunkHolder == null || chunkHolder.getTicketLevel() > minLevel || needsFullScheduling) && !loadOrGenerate) {
            return ChunkHolder.UNLOADED_CHUNK_FUTURE;
        }

        final ChunkAccess ifPresent = chunkHolder == null ? null : chunkHolder.getChunkIfPresent(targetStatus);
        if (needsFullScheduling || ifPresent == null) {
            // schedule
            final CompletableFuture<ChunkResult<ChunkAccess>> ret = new CompletableFuture<>();
            final Consumer<ChunkAccess> complete = (ChunkAccess chunk) -> {
                if (chunk == null) {
                    ret.complete(ChunkHolder.UNLOADED_CHUNK);
                } else {
                    ret.complete(ChunkResult.of(chunk));
                }
            };

            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(
                x, z, targetStatus, true,
                ca.spottedleaf.concurrentutil.util.Priority.HIGHER,
                complete
            );

            return ret;
        } else {
            // can return now
            return CompletableFuture.completedFuture(ChunkResult.of(ifPresent));
        }
        // Paper end - rewrite chunk system
    }

    private boolean chunkAbsent(final @Nullable ChunkHolder chunkHolder, final int targetTicketLevel) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public boolean hasChunk(final int x, final int z) {
        return this.getChunkNow(x, z) != null; // Paper - rewrite chunk system
    }

    @Override
    public @Nullable LightChunk getChunkForLighting(final int x, final int z) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(x, z);
        if (newChunkHolder == null) {
            return null;
        }
        return newChunkHolder.getChunkIfPresentUnchecked(ChunkStatus.INITIALIZE_LIGHT.getParent());
        // Paper end - rewrite chunk system
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    public boolean pollTask() {
        return this.mainThreadProcessor.pollTask();
    }

    public boolean runDistanceManagerUpdates() {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(); // Paper - rewrite chunk system
    }

    public boolean isPositionTicking(final long chunkKey) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkKey);
        return newChunkHolder != null && newChunkHolder.isTickingReady();
        // Paper end - rewrite chunk system
    }

    public void save(final boolean flushStorage) {
        // Paper - rewrite chunk system
        this.chunkMap.saveAllChunks(flushStorage);
    }

    @Override
    public void close() throws IOException {
        // CraftBukkit start
        this.close(true);
    }

    public void close(boolean save) throws IOException {
        // Paper - rewrite chunk system
        // CraftBukkit end
        this.savedDataStorage.close();
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.close(save, true); // Paper - rewrite chunk system
    }

    // CraftBukkit start - modelled on below
    public void purgeUnload() {
        if (true) return; // Paper - rewrite chunk system
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("purge");
        this.ticketStorage.purgeStaleTickets(this.chunkMap);
        this.runDistanceManagerUpdates();
        gameprofilerfiller.popPush("unload");
        this.chunkMap.tick(() -> true);
        gameprofilerfiller.pop();
        this.clearCache();
    }
    // CraftBukkit end

    @Override
    public void tick(final BooleanSupplier haveTime, final boolean tickChunks) {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        ProfilerFiller profiler = Profiler.get();
        profiler.push("purge");
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_HOLDER_MANAGER_TICK); try { // Folia - profiler
        if (this.level.tickRateManager().runsNormally() || !tickChunks || this.level.spigotConfig.unloadFrozenChunks) { // Spigot
            this.ticketStorage.purgeStaleTickets(this.chunkMap);
        }

        this.runDistanceManagerUpdates();
        profiler.popPush("chunks");
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_HOLDER_MANAGER_TICK); } // Folia - profiler
        if (tickChunks) {
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_CHUNK_LOADER_TICK); try { // Folia - profiler
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().tick(); // Paper - rewrite chunk system
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_CHUNK_LOADER_TICK); } // Folia - profiler
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK); try { // Folia - profiler
            this.tickChunks();
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK); } // Folia - profiler
            this.chunkMap.tick();
        }

        profiler.popPush("unload");
        this.chunkMap.tick(haveTime);
        profiler.pop();
        this.clearCache();
    }

    private void tickChunks() {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        //long time = this.level.getGameTime(); // Folia - region threading
        long timeDiff = 1L; // Folia - region threading
        //this.lastInhabitedUpdate = time; // Folia - region threading
        if (!this.level.isDebug()) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("pollingChunks");
            if (this.level.tickRateManager().runsNormally() && this.level.paperConfig().unsupportedSettings.ticking.chunks) { // Paper - option to disable ticking
                profiler.push("tickingChunks");
                this.tickChunks(profiler, timeDiff);
                profiler.pop();
            }

            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BROADCAST_BLOCK_CHANGES); try { // Folia - profiler
            this.broadcastChangedChunks(profiler);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BROADCAST_BLOCK_CHANGES); } // Folia - profiler
            profiler.pop();
        }
    }

    private void broadcastChangedChunks(final ProfilerFiller profiler) {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        profiler.push("broadcast");

        for (ChunkHolder chunkHolder : regionizedWorldData.chunkHoldersToBroadcast) { // Folia - region threading - note: do not need to thread check, as getChunkToSend is only non-null when the chunkholder is loaded
            LevelChunk chunk = chunkHolder.getChunkToSend(); // Paper - rewrite chunk system
            if (chunk != null) {
                chunkHolder.broadcastChanges(chunk);
            }
        }

        regionizedWorldData.chunkHoldersToBroadcast.clear(); // Folia - region threading
        profiler.pop();
    }

    private void tickChunks(final ProfilerFiller profiler, final long timeDiff) {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        profiler.push("naturalSpawnCount");
        int chunkCount = this.distanceManager.getNaturalSpawnChunkCount();
        // Paper start - Optional per player mob spawns
        NaturalSpawner.SpawnState spawnCookie;
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MOB_SPAWN_ENTITY_COUNT); try { // Folia - profiler
        if ((this.spawnFriendlies || this.spawnEnemies) && this.level.paperConfig().entities.spawning.perPlayerMobSpawns) { // don't count mobs when animals and monsters are disabled
            // re-set mob counts
            for (ServerPlayer player : this.level.getLocalPlayers()) { // Folia - region threading
                // Paper start - per player mob spawning backoff
                for (int j = 0; j < ServerPlayer.MOBCATEGORY_TOTAL_ENUMS; j++) {
                    player.mobCounts[j] = 0;

                    int newBackoff = player.mobBackoffCounts[j] - 1; // TODO make configurable bleed // TODO use nonlinear algorithm?
                    if (newBackoff < 0) {
                        newBackoff = 0;
                    }
                    player.mobBackoffCounts[j] = newBackoff;
                }
                // Paper end - per player mob spawning backoff
            }
            spawnCookie = NaturalSpawner.createState(chunkCount, regionizedWorldData.getLoadedEntities(), this::getFullChunk, null, true); // Folia - region threading - note: function only cares about loaded entities, doesn't need all
        } else {
            spawnCookie = NaturalSpawner.createState(chunkCount, regionizedWorldData.getLoadedEntities(), this::getFullChunk, !this.level.paperConfig().entities.spawning.perPlayerMobSpawns ? new LocalMobCapCalculator(this.chunkMap) : null, false); // Folia - region threading - note: function only cares about loaded entities, doesn't need all
        }
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MOB_SPAWN_ENTITY_COUNT); } // Folia - profiler
        // Paper end - Optional per player mob spawns
        regionizedWorldData.lastSpawnState = spawnCookie; // Folia - region threading
        boolean doMobSpawning = this.level.getGameRules().get(GameRules.SPAWN_MOBS) && !this.level.getLocalPlayers().isEmpty(); // CraftBukkit // Folia - region threading
        int tickSpeed = this.level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        List<MobCategory> spawningCategories;
        if (doMobSpawning && (this.spawnEnemies || this.spawnFriendlies)) { // Paper
            // Paper start - PlayerNaturallySpawnCreaturesEvent
            for (ServerPlayer player : this.level.getLocalPlayers()) { // Folia - region threading
                int chunkRange = Math.min(level.spigotConfig.mobSpawnRange, player.getBukkitEntity().getViewDistance());
                chunkRange = Math.min(chunkRange, 8);
                player.playerNaturallySpawnedEvent = new com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent(player.getBukkitEntity(), (byte) chunkRange);
                player.playerNaturallySpawnedEvent.callEvent();
            }
            // Paper end - PlayerNaturallySpawnCreaturesEvent
            boolean spawnPersistent = this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) != 0L && this.level.getRedstoneGameTime() % this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) == 0L; // CraftBukkit // Folia - region threading
            spawningCategories = NaturalSpawner.getFilteredSpawningCategories(spawnCookie, this.spawnFriendlies, this.spawnEnemies, spawnPersistent, this.level); // CraftBukkit
        } else {
            spawningCategories = List.of();
        }

        List<LevelChunk> spawningChunks = regionizedWorldData.temporaryChunkTickList; // Folia - region threading

        try {
            profiler.popPush("filteringSpawningChunks");
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_SPAWN_COLLECT_CHUNKS); try { // Folia - profiler
            this.chunkMap.collectSpawningChunks(spawningChunks);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_SPAWN_COLLECT_CHUNKS); } // Folia - profiler
            profiler.popPush("shuffleSpawningChunks");
            // Paper start - chunk tick iteration optimisation
            this.shuffleRandom.setSeed(this.level.getRandom().nextLong());
            if (!this.level.paperConfig().entities.spawning.perPlayerMobSpawns) Util.shuffle(spawningChunks, this.level.getRandom()); // Paper - Optional per player mob spawns; do not need this when per-player is enabled
            // Paper end - chunk tick iteration optimisation
            profiler.popPush("tickSpawningChunks");

            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_TICK); try { // Folia - profiler
            foliaProfiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_CHUNK_COUNT, (long)spawningChunks.size()); // Folia - profiler
            for (LevelChunk chunk : spawningChunks) {
                this.tickSpawningChunk(chunk, timeDiff, spawningCategories, spawnCookie);
            }
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_TICK); } // Folia - profiler
        } finally {
            spawningChunks.clear();
        }

        profiler.popPush("tickTickingChunks");
        this.iterateTickingChunksFaster(); // Paper - chunk tick iteration optimizations
        if (doMobSpawning) {
            profiler.popPush("customSpawners");
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MISC_MOB_SPAWN_TICK); try { // Folia - profiler
            this.level.tickCustomSpawners(this.spawnEnemies);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MISC_MOB_SPAWN_TICK); } // Folia - profiler
        }

        profiler.pop();
    }

    private void tickSpawningChunk(
        final LevelChunk chunk, final long timeDiff, final List<MobCategory> spawningCategories, final NaturalSpawner.SpawnState spawnCookie
    ) {
        ChunkPos chunkPos = chunk.getPos();
        chunk.incrementInhabitedTime(timeDiff);
        if (true) { // Paper - rewrite chunk system
            this.level.tickThunder(chunk);
        }

        if (!spawningCategories.isEmpty()) {
            if (this.level.getWorldBorder().isWithinBounds(chunkPos)) { // Paper - rewrite chunk system
                NaturalSpawner.spawnForChunk(this.level, chunk, spawnCookie, spawningCategories);
            }
        }
    }

    private void getFullChunk(final long chunkKey, final Consumer<LevelChunk> output) {
        // Paper start - rewrite chunk system
        // note: bypass currentlyLoaded from getChunkNow
        final LevelChunk fullChunk = this.fullChunks.get(chunkKey);
        if (fullChunk != null) {
            output.accept(fullChunk);
        }
        // Paper end - rewrite chunk system
    }

    @Override
    public String gatherStats() {
        return Integer.toString(this.getLoadedChunksCount());
    }

    @VisibleForTesting
    public int getPendingTasksCount() {
        return this.mainThreadProcessor.getPendingTasksCount();
    }

    public ChunkGenerator getGenerator() {
        return this.chunkMap.generator();
    }

    public ChunkGeneratorStructureState getGeneratorState() {
        return this.chunkMap.generatorState();
    }

    public RandomState randomState() {
        return this.chunkMap.randomState();
    }

    @Override
    public int getLoadedChunksCount() {
        return this.chunkMap.size();
    }

    public void blockChanged(final BlockPos pos) {
        int xc = SectionPos.blockToSectionCoord(pos.getX());
        int zc = SectionPos.blockToSectionCoord(pos.getZ());
        ChunkHolder chunk = this.getVisibleChunkIfPresent(ChunkPos.pack(xc, zc));
        if (chunk != null && chunk.blockChanged(pos)) {
            this.level.getCurrentWorldData().chunkHoldersToBroadcast.add(chunk); // Folia - region threading
        }
    }

    @Override
    public void onLightUpdate(final LightLayer layer, final SectionPos pos) {
        Runnable run = () -> { // Folia - region threading
            ChunkHolder chunk = this.getVisibleChunkIfPresent(pos.chunk().pack());
            if (chunk != null && chunk.sectionLightChanged(layer, pos.y())) {
                ServerChunkCache.this.level.getCurrentWorldData().chunkHoldersToBroadcast.add(chunk); // Folia - region threading
            }
        }; // Folia - region threading
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(
                this.level, pos.getX(), pos.getZ(), run
        );
        // Folia end - region threading
    }

    public boolean hasActiveTickets() {
        return this.ticketStorage.shouldKeepDimensionActive();
    }

    public void addTicket(final Ticket ticket, final ChunkPos pos) {
        this.ticketStorage.addTicket(ticket, pos);
    }

    public CompletableFuture<?> addTicketAndLoadWithRadius(final TicketType type, final ChunkPos pos, final int radius) {
        // Paper start - rewrite chunk system
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAndLoadWithRadius(
            type, pos, radius, ChunkStatus.FULL, ca.spottedleaf.concurrentutil.util.Priority.NORMAL
        );
        // Paper end - rewrite chunk system
    }

    public void addTicketWithRadius(final TicketType type, final ChunkPos pos, final int radius) {
        this.ticketStorage.addTicketWithRadius(type, pos, radius);
    }

    public void removeTicketWithRadius(final TicketType type, final ChunkPos pos, final int radius) {
        this.ticketStorage.removeTicketWithRadius(type, pos, radius);
    }

    @Override
    public boolean updateChunkForced(final ChunkPos pos, final boolean forced) {
        return this.ticketStorage.updateChunkForced(pos, forced);
    }

    @Override
    public LongSet getForceLoadedChunks() {
        return this.ticketStorage.getForceLoadedChunks();
    }

    public void move(final ServerPlayer player) {
        if (!player.isRemoved()) {
            this.chunkMap.move(player);
            if (player.isReceivingWaypoints()) {
                this.level.getWaypointManager().updatePlayer(player);
            }
        }
    }

    public boolean hasEntityWithId(final int id) {
        return this.chunkMap.hasEntityWithId(id);
    }

    public void removeEntity(final Entity entity) {
        this.chunkMap.removeEntity(entity);
    }

    public void addEntity(final Entity entity) {
        this.chunkMap.addEntity(entity);
    }

    public void sendToTrackingPlayersAndSelf(final Entity entity, final Packet<? super ClientGamePacketListener> packet) {
        this.chunkMap.sendToTrackingPlayersAndSelf(entity, packet);
    }

    public void sendToTrackingPlayers(final Entity entity, final Packet<? super ClientGamePacketListener> packet) {
        this.chunkMap.sendToTrackingPlayers(entity, packet);
    }

    public void setViewDistance(final int newDistance) {
        this.chunkMap.setServerViewDistance(newDistance);
    }

    // Paper start - rewrite chunk system
    public void setSendViewDistance(int viewDistance) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().setSendDistance(viewDistance);
    }
    // Paper end - rewrite chunk system

    public void setSimulationDistance(final int simulationDistance) {
        this.distanceManager.updateSimulationDistance(simulationDistance);
    }

    @Override
    public void setSpawnSettings(final boolean spawnEnemies) {
        // CraftBukkit start
        this.setSpawnSettings(spawnEnemies, this.spawnFriendlies);
    }
    public void setSpawnSettings(final boolean spawnEnemies, final boolean spawnFriendlies) {
        this.spawnEnemies = spawnEnemies;
        this.spawnFriendlies = spawnFriendlies;
        // CraftBukkit end
    }

    public String getChunkDebugData(final ChunkPos pos) {
        return this.chunkMap.getChunkDebugData(pos);
    }

    public SavedDataStorage getDataStorage() {
        return this.savedDataStorage;
    }

    public PoiManager getPoiManager() {
        return this.chunkMap.getPoiManager();
    }

    public ChunkScanAccess chunkScanner() {
        return this.chunkMap.chunkScanner();
    }

    @VisibleForDebug
    public NaturalSpawner.@Nullable SpawnState getLastSpawnState() {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.level.getCurrentWorldData(); // Folia - region threading
        return worldData == null ? null : worldData.lastSpawnState; // Folia - region threading
    }

    public void deactivateTicketsOnClosing() {
        this.ticketStorage.deactivateTicketsOnClosing();
    }

    public void onChunkReadyToSend(final ChunkHolder chunk) {
        if (chunk.hasChangesToBroadcast()) {
            throw new UnsupportedOperationException(); // Folia - region threading
        }
    }

    public final class MainThreadExecutor extends BlockableEventLoop<Runnable> {
        private MainThreadExecutor(final Level level) {
            super("Chunk source main thread executor for " + level.dimension().identifier(), false);
        }

        @Override
        public Runnable wrapRunnable(final Runnable runnable) {
            return runnable;
        }

        @Override
        protected boolean shouldRun(final Runnable task) {
            return true;
        }

        @Override
        protected boolean scheduleExecutables() {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return ServerChunkCache.this.mainThread;
        }

        // Folia start - region threading
        @Override
        public <V> CompletableFuture<V> submit(Supplier<V> task) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            return super.submit(task);
        }

        @Override
        public CompletableFuture<Void> submit(Runnable task) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            return super.submit(task);
        }

        @Override
        public void schedule(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.schedule(runnable);
        }

        @Override
        public void executeBlocking(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.executeBlocking(runnable);
        }

        @Override
        public void execute(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.execute(runnable);
        }

        @Override
        public void executeIfPossible(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.executeIfPossible(runnable);
        }
        // Folia end - region threading

        @Override
        protected void doRunTask(final Runnable task) {
            if (true) throw new UnsupportedOperationException(); // Folia - region threading
            Profiler.get().incrementCounter("runTask");
            super.doRunTask(task);
        }

        @Override
        protected boolean pollTask() {
            // Folia start - region threading
            if (ServerChunkCache.this.level != io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().world) {
                throw new IllegalStateException("Polling tasks from non-owned region");
            }
            // Folia end - region threading
            if (ServerChunkCache.this.runDistanceManagerUpdates()) {
                return true;
            }

            // Paper - rewrite chunk system
            return io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion().getData().getTaskQueueData().executeChunkTask(); // Folia - region threading
        }
    }
}

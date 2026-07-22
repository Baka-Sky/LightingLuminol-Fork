package net.minecraft.server.level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.TriState;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ChunkMap extends SimpleRegionStorage implements ChunkHolder.PlayerProvider, GeneratingChunkMap {
    private static final ChunkResult<List<ChunkAccess>> UNLOADED_CHUNK_LIST_RESULT = ChunkResult.error("Unloaded chunks found in range");
    private static final CompletableFuture<ChunkResult<List<ChunkAccess>>> UNLOADED_CHUNK_LIST_FUTURE = CompletableFuture.completedFuture(
        UNLOADED_CHUNK_LIST_RESULT
    );
    private static final byte CHUNK_TYPE_REPLACEABLE = -1;
    private static final byte CHUNK_TYPE_UNKNOWN = 0;
    private static final byte CHUNK_TYPE_FULL = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_SAVED_PER_TICK = 200;
    private static final int CHUNK_SAVED_EAGERLY_PER_TICK = 20;
    private static final int EAGER_CHUNK_SAVE_COOLDOWN_IN_MILLIS = 10000;
    private static final int MAX_ACTIVE_CHUNK_WRITES = 128;
    public static final int MIN_VIEW_DISTANCE = 2;
    public static final int MAX_VIEW_DISTANCE = 32;
    public static final int FORCED_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    // Paper - rewrite chunk system
    public final ServerLevel level;
    private final ThreadedLevelLightEngine lightEngine;
    private final BlockableEventLoop<Runnable> mainThreadExecutor;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState chunkGeneratorState;
    private final TicketStorage ticketStorage;
    private final PoiManager poiManager;
    public final LongSet toDrop = new LongOpenHashSet();
    private boolean modified;
    // Paper - rewrite chunk system
    private final ChunkStatusUpdateListener chunkStatusListener;
    private final ChunkMap.DistanceManager distanceManager;
    private final String storageName;
    //private final PlayerMap playerMap = new PlayerMap(); // Folia - region threading
    //public final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap = new Int2ObjectOpenHashMap<>(); // Folia - region threading
    private final Long2ByteMap chunkTypeCache = new Long2ByteOpenHashMap();
    // Paper - rewrite chunk system
    public int serverViewDistance;
    public final WorldGenContext worldGenContext; // Paper - public

    // CraftBukkit start - recursion-safe executor for Chunk loadCallback() and unloadCallback()
    public final CallbackExecutor callbackExecutor = new CallbackExecutor();
    public static final class CallbackExecutor implements java.util.concurrent.Executor, Runnable {

        private final java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();

        @Override
        public void execute(Runnable runnable) {
            this.queue.add(runnable);
        }

        @Override
        public void run() {
            Runnable task;
            while ((task = this.queue.poll()) != null) {
                task.run();
            }
        }
    };
    // CraftBukkit end

    // Paper start
    public final ChunkHolder getUnloadingChunkHolder(int chunkX, int chunkZ) {
        return null; // Paper - rewrite chunk system
    }
    // Paper end

    public ChunkMap(
        final ServerLevel level,
        final LevelStorageSource.LevelStorageAccess levelStorage,
        final DataFixer dataFixer,
        final StructureTemplateManager structureManager,
        final Executor executor,
        final BlockableEventLoop<Runnable> mainThreadExecutor,
        final LightChunkGetter chunkGetter,
        final ChunkGenerator generator,
        final ChunkStatusUpdateListener chunkStatusListener,
        final Supplier<SavedDataStorage> overworldDataStorage,
        final TicketStorage ticketStorage,
        final int serverViewDistance,
        final boolean syncWrites
    ) {
        super(
            new RegionStorageInfo(levelStorage.getLevelId(), level.dimension(), "chunk"),
            levelStorage.getDimensionPath(level.dimension()).resolve("region"),
            dataFixer,
            syncWrites,
            DataFixTypes.CHUNK
        );
        Path storageFolder = levelStorage.getDimensionPath(level.dimension());
        this.storageName = storageFolder.getFileName().toString();
        this.level = level;
        RegistryAccess registryAccess = level.registryAccess();
        long levelSeed = level.getSeed();
        // CraftBukkit start - SPIGOT-7051: It's a rigged game! Use delegate for random state creation, otherwise it is not so random.
        ChunkGenerator randomGenerator = generator;
        if (randomGenerator instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator customChunkGenerator) {
            randomGenerator = customChunkGenerator.getDelegate();
        }
        if (randomGenerator instanceof NoiseBasedChunkGenerator noiseGenerator) {
            // CraftBukkit end
            this.randomState = RandomState.create(noiseGenerator.generatorSettings().value(), registryAccess.lookupOrThrow(Registries.NOISE), levelSeed);
        } else {
            this.randomState = RandomState.create(NoiseGeneratorSettings.dummy(), registryAccess.lookupOrThrow(Registries.NOISE), levelSeed);
        }

        this.chunkGeneratorState = generator.createState(registryAccess.lookupOrThrow(Registries.STRUCTURE_SET), this.randomState, levelSeed, level.spigotConfig); // Spigot
        this.mainThreadExecutor = mainThreadExecutor;
        ConsecutiveExecutor worldgen = new ConsecutiveExecutor(executor, "worldgen");
        this.chunkStatusListener = chunkStatusListener;
        ConsecutiveExecutor light = new ConsecutiveExecutor(executor, "light");
        //this.worldgenTaskDispatcher = new ChunkTaskDispatcher(worldgen, executor); // Paper - rewrite chunk system
        //this.lightTaskDispatcher = new ChunkTaskDispatcher(light, executor); // Paper - rewrite chunk system
        this.lightEngine = new ThreadedLevelLightEngine(chunkGetter, this, this.level.dimensionType().hasSkyLight(), light, null); // Paper - rewrite chunk system
        this.distanceManager = new ChunkMap.DistanceManager(ticketStorage, executor, mainThreadExecutor);
        this.ticketStorage = ticketStorage;
        this.poiManager = new PoiManager(
            new RegionStorageInfo(levelStorage.getLevelId(), level.dimension(), "poi"),
            storageFolder.resolve("poi"),
            dataFixer,
            syncWrites,
            registryAccess,
            level.getServer(),
            level
        );
        this.setServerViewDistance(serverViewDistance);
        this.worldGenContext = new WorldGenContext(level, generator, structureManager, this.lightEngine, null, this::setChunkUnsaved); // Paper - rewrite chunk system
    }

    private void setChunkUnsaved(final ChunkPos chunkPos) {
        // Paper - rewrite chunk system
    }

    // Paper start - Optional per player mob spawns
    public void updatePlayerMobTypeMap(final Entity entity) {
        if (!this.level.paperConfig().entities.spawning.perPlayerMobSpawns) {
            return;
        }

        final int index = entity.getType().getCategory().ordinal();
        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> inRange =
            this.level.moonrise$getNearbyPlayers().getPlayers(entity.chunkPosition(), ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
        if (inRange == null) {
            return;
        }

        final ServerPlayer[] backingSet = inRange.getRawDataUnchecked();
        for (int i = 0, len = inRange.size(); i < len; i++) {
            ++(backingSet[i].mobCounts[index]);
        }
    }

    // Paper start - per player mob count backoff
    public void updateFailurePlayerMobTypeMap(int chunkX, int chunkZ, net.minecraft.world.entity.MobCategory mobCategory) {
        if (!this.level.paperConfig().entities.spawning.perPlayerMobSpawns) {
            return;
        }
        int idx = mobCategory.ordinal();
        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> inRange =
            this.level.moonrise$getNearbyPlayers().getPlayersByChunk(chunkX, chunkZ, ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
        if (inRange == null) {
            return;
        }
        final ServerPlayer[] backingSet = inRange.getRawDataUnchecked();
        for (int i = 0, len = inRange.size(); i < len; i++) {
            ++(backingSet[i].mobBackoffCounts[idx]);
        }
    }
    // Paper end - per player mob count backoff
    public int getMobCountNear(final ServerPlayer player, final net.minecraft.world.entity.MobCategory mobCategory) {
        return player.mobCounts[mobCategory.ordinal()] + player.mobBackoffCounts[mobCategory.ordinal()]; // Paper - per player mob count backoff
    }
    // Paper end - Optional per player mob spawns

    protected ChunkGenerator generator() {
        return this.worldGenContext.generator();
    }

    protected ChunkGeneratorStructureState generatorState() {
        return this.chunkGeneratorState;
    }

    protected RandomState randomState() {
        return this.randomState;
    }

    public boolean isChunkTracked(final ServerPlayer player, final int chunkX, final int chunkZ) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().isChunkSent(player, chunkX, chunkZ); // Paper - rewrite chunk system
    }

    private boolean isChunkOnTrackedBorder(final ServerPlayer player, final int chunkX, final int chunkZ) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().isChunkSent(player, chunkX, chunkZ, true); // Paper - rewrite chunk system
    }

    protected ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    public @Nullable ChunkHolder getUpdatingChunkIfPresent(final long key) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(key);
        return holder == null ? null : holder.vanillaChunkHolder;
        // Paper end - rewrite chunk system
    }

    public @Nullable ChunkHolder getVisibleChunkIfPresent(final long key) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(key);
        return holder == null ? null : holder.vanillaChunkHolder;
        // Paper end - rewrite chunk system
    }

    public @Nullable ChunkStatus getLatestStatus(final long key) {
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(key);
        return chunkHolder != null ? chunkHolder.getLatestStatus() : null;
    }

    protected IntSupplier getChunkQueueLevel(final long pos) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public String getChunkDebugData(final ChunkPos pos) {
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(pos.pack());
        if (chunkHolder == null) {
            return "null";
        }

        String result = chunkHolder.getTicketLevel() + "\n";
        ChunkStatus status = chunkHolder.getLatestStatus();
        ChunkAccess chunk = chunkHolder.getLatestChunk();
        if (status != null) {
            result = result + "St: §" + status.getIndex() + status + "§r\n";
        }

        if (chunk != null) {
            result = result + "Ch: §" + chunk.getPersistedStatus().getIndex() + chunk.getPersistedStatus() + "§r\n";
        }

        FullChunkStatus fullStatus = chunkHolder.getFullStatus();
        result = result + '§' + fullStatus.ordinal() + fullStatus;
        return result + "§r";
    }

    CompletableFuture<ChunkResult<List<ChunkAccess>>> getChunkRangeFuture(
        final ChunkHolder centerChunk, final int range, final IntFunction<ChunkStatus> distanceToStatus
    ) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public ReportedException debugFuturesAndCreateReportedException(final IllegalStateException exception, final String details) {
        StringBuilder sb = new StringBuilder();
        Consumer<ChunkHolder> addToDebug = holder -> holder.getAllFutures().forEach(pair -> {
            ChunkStatus status = pair.getFirst();
            CompletableFuture<ChunkResult<ChunkAccess>> future = pair.getSecond();
            if (future != null && future.isDone() && future.join() == null) {
                sb.append(holder.getPos()).append(" - status: ").append(status).append(" future: ").append(future).append(System.lineSeparator());
            }
        });
        sb.append("Updating:").append(System.lineSeparator());
        ca.spottedleaf.moonrise.common.PlatformHooks.get().getUpdatingChunkHolders(this.level).forEach(addToDebug); // Paper
        sb.append("Visible:").append(System.lineSeparator());
        ca.spottedleaf.moonrise.common.PlatformHooks.get().getVisibleChunkHolders(this.level).forEach(addToDebug); // Paper
        CrashReport report = CrashReport.forThrowable(exception, "Chunk loading");
        CrashReportCategory category = report.addCategory("Chunk loading");
        category.setDetail("Details", details);
        category.setDetail("Futures", sb);
        return new ReportedException(report);
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareEntityTickingChunk(final ChunkHolder chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private @Nullable ChunkHolder updateChunkScheduling(final long node, final int level, @Nullable ChunkHolder chunk, final int oldLevel) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void onLevelChange(final ChunkPos pos, final IntSupplier oldLevel, final int newLevel, final IntConsumer setQueueLevel) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Use ServerChunkCache#close"); // Paper - rewrite chunk system
    }

    protected void saveAllChunks(final boolean flushStorage) {
        // Paper start - rewrite chunk system
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.saveAllChunks(
            flushStorage, false, false, false
        );
        // Paper end - rewrite chunk system
    }

    protected void tick(final BooleanSupplier haveTime) {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        ProfilerFiller profiler = Profiler.get();
        profiler.push("poi");
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.POI_MANAGER_TICK); try { // Folia - profiler
        this.poiManager.tick(haveTime);
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.POI_MANAGER_TICK); } // Folia - profiler
        profiler.popPush("chunk_unload");
        if (!this.level.noSave()) {
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PROCESS_UNLOADS); try { // Folia - profiler
            this.processUnloads(haveTime);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PROCESS_UNLOADS); } // Folia - profiler
        }

        profiler.pop();
    }

    public boolean hasWork() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void processUnloads(final BooleanSupplier haveTime) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.processUnloads(); // Paper - rewrite chunk system
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.autoSave(); // Paper - rewrite chunk system
    }

    private void saveChunksEagerly(final BooleanSupplier haveTime) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void scheduleUnload(final long pos, final ChunkHolder chunkHolder) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    protected boolean promoteChunkMap() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private CompletableFuture<ChunkAccess> scheduleChunkLoad(final ChunkPos pos) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private ChunkAccess handleChunkLoadFailure(final Throwable throwable, final ChunkPos pos) {
        Throwable unwrapped = throwable instanceof CompletionException e ? e.getCause() : throwable;
        Throwable cause = unwrapped instanceof ReportedException e ? e.getCause() : unwrapped;
        boolean alwaysThrow = cause instanceof Error;
        boolean ioException = cause instanceof IOException || cause instanceof NbtException;
        if (!alwaysThrow) {
            if (!ioException) {
            }

            this.level.getServer().reportChunkLoadFailure(cause, this.storageInfo(), pos);
            return this.createEmptyChunk(pos);
        } else {
            CrashReport report = CrashReport.forThrowable(throwable, "Exception loading chunk");
            CrashReportCategory chunkBeingLoaded = report.addCategory("Chunk being loaded");
            chunkBeingLoaded.setDetail("pos", pos);
            this.markPositionReplaceable(pos);
            throw new ReportedException(report);
        }
    }

    private ChunkAccess createEmptyChunk(final ChunkPos pos) {
        this.markPositionReplaceable(pos);
        return new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
    }

    private void markPositionReplaceable(final ChunkPos pos) {
        this.chunkTypeCache.put(pos.pack(), (byte)-1);
    }

    private byte markPosition(final ChunkPos pos, final ChunkType type) {
        return this.chunkTypeCache.put(pos.pack(), (byte)(type == ChunkType.PROTOCHUNK ? -1 : 1));
    }

    @Override
    public GenerationChunkHolder acquireGeneration(final long chunkNode) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public void releaseGeneration(final GenerationChunkHolder chunkHolder) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public CompletableFuture<ChunkAccess> applyStep(
        final GenerationChunkHolder chunkHolder, final ChunkStep step, final StaticCache2D<GenerationChunkHolder> cache
    ) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public ChunkGenerationTask scheduleGenerationTask(final ChunkStatus targetStatus, final ChunkPos pos) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void runGenerationTask(final ChunkGenerationTask task) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public void runGenerationTasks() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareTickingChunk(final ChunkHolder chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void onChunkReadyToSend(final ChunkHolder chunkHolder, final LevelChunk chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareAccessibleChunk(final ChunkHolder chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    Stream<ChunkHolder> allChunksWithAtLeastStatus(final ChunkStatus status) {
        // Paper start - rewrite chunk system
        final int i = ChunkLevel.byStatus(status);
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager
            .getOldChunkHolders()
            .stream()
            .filter(holder -> holder.getTicketLevel() <= i);
        // Paper end - rewrite chunk system
    }

    private boolean saveChunkIfNeeded(final ChunkHolder chunk, final long now) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public boolean save(final ChunkAccess chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private boolean isExistingChunkFull(final ChunkPos pos) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void setServerViewDistance(final int newViewDistance) {
        // Paper start - rewrite chunk system
        final int clamped = Mth.clamp(newViewDistance, 2, ca.spottedleaf.moonrise.common.util.MoonriseConstants.MAX_VIEW_DISTANCE);
        if (clamped == this.serverViewDistance) {
            return;
        }

        this.serverViewDistance = clamped;
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().setLoadDistance(this.serverViewDistance + 1);
        // Paper end - rewrite chunk system
    }

    private int getPlayerViewDistance(final ServerPlayer player) {
        return ca.spottedleaf.moonrise.common.PlatformHooks.get().getSendViewDistance(player); // Paper - rewrite chunk system
    }

    private void markChunkPendingToSend(final ServerPlayer player, final ChunkPos pos) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private static void markChunkPendingToSend(final ServerPlayer player, final LevelChunk chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private static void dropChunk(final ServerPlayer player, final ChunkPos pos) {
        // Paper - rewrite chunk system
    }

    // Paper start - rewrite chunk system
    @Override
    public CompletableFuture<Optional<CompoundTag>> read(final ChunkPos pos) {
        final CompletableFuture<Optional<CompoundTag>> ret = new CompletableFuture<>();

        ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.loadDataAsync(
            this.level, pos.x(), pos.z(), ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
            (final CompoundTag data, final Throwable thr) -> {
                if (thr != null) {
                    ret.completeExceptionally(thr);
                } else {
                    ret.complete(Optional.ofNullable(data));
                }
            }, false
        );

        return ret;
    }

    @Override
    public CompletableFuture<Void> write(final ChunkPos pos, final Supplier<CompoundTag> tag) {
        ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.scheduleSave(
            this.level, pos.x(), pos.z(), tag.get(),
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType.CHUNK_DATA
        );
        return null;
    }

    @Override
    public CompletableFuture<Void> synchronize(final boolean flush) {
        try {
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(this.level);
            if (flush) {
                ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flushRegionStorages(this.level);
            }
            return CompletableFuture.completedFuture(null);
        } catch (final Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
    // Paper end - rewrite chunk system

    public @Nullable LevelChunk getChunkToSend(final long key) {
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(key);
        return chunkHolder == null ? null : chunkHolder.getChunkToSend();
    }

    public int size() {
        return ca.spottedleaf.moonrise.common.PlatformHooks.get().getVisibleChunkHolderCount(this.level); // Paper
    }

    public net.minecraft.server.level.DistanceManager getDistanceManager() {
        return this.distanceManager;
    }

    void dumpChunks(final Writer output) throws IOException {
        CsvOutput csvOutput = CsvOutput.builder()
            .addColumn("x")
            .addColumn("z")
            .addColumn("level")
            .addColumn("in_memory")
            .addColumn("status")
            .addColumn("full_status")
            .addColumn("accessible_ready")
            .addColumn("ticking_ready")
            .addColumn("entity_ticking_ready")
            .addColumn("ticket")
            .addColumn("spawning")
            .addColumn("block_entity_count")
            .addColumn("ticking_ticket")
            .addColumn("ticking_level")
            .addColumn("block_ticks")
            .addColumn("fluid_ticks")
            .build(output);

        for (ChunkHolder entry : ca.spottedleaf.moonrise.common.PlatformHooks.get().getVisibleChunkHolders(this.level)) { // Paper - Moonrise
            long posKey = entry.pos.pack(); // Paper - Moonrise
            ChunkPos pos = entry.pos; // Paper - Moonrise
            ChunkHolder holder = entry; // Paper - Moonrise
            Optional<ChunkAccess> chunk = Optional.ofNullable(holder.getLatestChunk());
            Optional<LevelChunk> fullChunk = chunk.flatMap(
                chunkAccess -> chunkAccess instanceof LevelChunk levelChunk ? Optional.of(levelChunk) : Optional.empty()
            );
            csvOutput.writeRow(
                pos.x(),
                pos.z(),
                holder.getTicketLevel(),
                chunk.isPresent(),
                chunk.map(ChunkAccess::getPersistedStatus).orElse(null),
                fullChunk.map(LevelChunk::getFullStatus).orElse(null),
                printFuture(holder.getFullChunkFuture()),
                printFuture(holder.getTickingChunkFuture()),
                printFuture(holder.getEntityTickingChunkFuture()),
                this.ticketStorage.getTicketDebugString(posKey, false),
                this.anyPlayerCloseEnoughForSpawning(pos),
                fullChunk.<Integer>map(c -> c.getBlockEntities().size()).orElse(0),
                this.ticketStorage.getTicketDebugString(posKey, true),
                this.distanceManager.getChunkLevel(posKey, true),
                fullChunk.<Integer>map(levelChunk -> levelChunk.getBlockTicks().count()).orElse(0),
                fullChunk.<Integer>map(levelChunk -> levelChunk.getFluidTicks().count()).orElse(0)
            );
        }
    }

    private static String printFuture(final CompletableFuture<ChunkResult<LevelChunk>> future) {
        try {
            ChunkResult<LevelChunk> result = future.getNow(null);
            if (result != null) {
                return result.isSuccess() ? "done" : "unloaded";
            } else {
                return "not completed";
            }
        } catch (CompletionException e) {
            return "failed " + e.getCause().getMessage();
        } catch (CancellationException e) {
            return "cancelled";
        }
    }

    private CompletableFuture<Optional<CompoundTag>> readChunk(final ChunkPos pos) {
        return this.read(pos).thenApplyAsync(chunkTag -> chunkTag.map(this::upgradeChunkTag), Util.backgroundExecutor().forName("upgradeChunk"));
    }

    public CompoundTag upgradeChunkTag(final CompoundTag tag) { // Paper - rewrite chunk system - public
        return this.upgradeChunkTag(
            tag,
            -1,
            getChunkDataFixContextTag(this.level.dimension(), this.generator().getTypeNameForDataFixer(), this.level.getTypeKey()), // Paper - pass level stem
            SharedConstants.getCurrentVersion().dataVersion().version()
        );
    }

    public static CompoundTag getChunkDataFixContextTag(final ResourceKey<Level> dimension, final Optional<Identifier> generatorIdentifier, final ResourceKey<net.minecraft.world.level.dimension.LevelStem> stemKey) { // Paper - pass level stem
        CompoundTag contextTag = new CompoundTag();
        contextTag.putString("dimension", stemKey.identifier().toString()); // Paper - pass level stem as dimension instead of passed dimension to run datafixers for non minecraft worlds.
        contextTag.putString("level_identifier", dimension.identifier().toString()); // Paper - store dimension key for later lookup if needed.
        generatorIdentifier.ifPresent(identifier -> contextTag.putString("generator", identifier.toString()));
        return contextTag;
    }

    // Paper start - optimise chunk tick iteration
    private boolean isChunkNearPlayer(final ChunkMap chunkMap, final ChunkPos chunkPos, final LevelChunk levelChunk) {
        final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData chunkData = ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)levelChunk).moonrise$getChunkHolder().holderData;
        final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk nearbyPlayers = this.level.moonrise$getNearbyPlayers().getChunk(chunkPos); // Folia - region threading
        if (nearbyPlayers == null) {
            return false;
        }

        // Note: cannot use narrow on Paper due to custom spawn range

        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = nearbyPlayers.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.SPAWN_RANGE);

        if (players == null) {
            return false;
        }

        final ServerPlayer[] raw = players.getRawDataUnchecked();
        final int len = players.size();

        Objects.checkFromIndexSize(0, len, raw.length);
        for (int i = 0; i < len; ++i) {
            // Paper start - PlayerNaturallySpawnCreaturesEvent
            com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event = raw[i].playerNaturallySpawnedEvent;
            if (event == null || event.isCancelled()) {
                continue;
            }
            double blockRange = (double) ((event.getSpawnRadius() << 4) * (event.getSpawnRadius() << 4));
            if (chunkMap.playerIsCloseEnoughForSpawning(raw[i], chunkPos, blockRange)) {
                // Paper end - PlayerNaturallySpawnCreaturesEvent
                return true;
            }
        }

        return false;
    }
    // Paper end - optimise chunk tick iteration

    public void collectSpawningChunks(final List<LevelChunk> output) {
        // Paper start - optimise chunk tick iteration
        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.level.chunk.LevelChunk> tickingChunks = this.level.getCurrentWorldData().getEntityTickingChunks(); // Folia - region threading

        final LevelChunk[] raw = tickingChunks.getRawDataUnchecked();
        final int size = tickingChunks.size();

        Objects.checkFromToIndex(0, size, raw.length);
        for (int i = 0; i < size; ++i) {
            final LevelChunk levelChunk = raw[i];

            if (!this.isChunkNearPlayer((ChunkMap)(Object)this, levelChunk.getPos(), levelChunk)) {
                continue;
            }

            output.add(levelChunk);
        }
        // Paper end - optimise chunk tick iteration
    }

    public void forEachBlockTickingChunk(final Consumer<LevelChunk> tickingChunkConsumer) {
        this.distanceManager.forEachEntityTickingChunk(chunkPos -> {
            ChunkHolder holder = this.getVisibleChunkIfPresent(chunkPos); // Paper - rewrite chunk system
            if (holder != null) {
                LevelChunk chunk = holder.getTickingChunk();
                if (chunk != null) {
                    tickingChunkConsumer.accept(chunk);
                }
            }
        });
    }

    public boolean anyPlayerCloseEnoughForSpawning(final ChunkPos pos) {
        // Spigot start
        return this.anyPlayerCloseEnoughForSpawning(pos, false);
    }

    boolean anyPlayerCloseEnoughForSpawning(final ChunkPos pos, boolean reducedRange) {
        // Paper - chunk tick iteration optimisation - cannot use narrow check due to custom range
        return this.anyPlayerCloseEnoughForSpawningInternal(pos, reducedRange); // Paper - chunk tick iteration optimisation
        // Spigot end
    }

    public boolean anyPlayerCloseEnoughTo(final BlockPos pos, final int maxDistance) {
        Vec3 target = new Vec3(pos);

        for (ServerPlayer player : this.level.getLocalPlayers()) { // Folia - region threading
            if (this.playerIsCloseEnoughTo(player, target, maxDistance)) {
                return true;
            }
        }

        return false;
    }

    private boolean anyPlayerCloseEnoughForSpawningInternal(final ChunkPos pos) {
        // Spigot start
        return this.anyPlayerCloseEnoughForSpawningInternal(pos, false);
    }

    private boolean anyPlayerCloseEnoughForSpawningInternal(final ChunkPos pos, final boolean reducedRange) {
        double blockRange; // Paper - use from event
        // Spigot end
        // Paper start - chunk tick iteration optimisation
        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getNearbyPlayers().getPlayers(
            pos, ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.SPAWN_RANGE
        );
        if (players == null) {
            return false;
        }

        final ServerPlayer[] raw = players.getRawDataUnchecked();
        final int len = players.size();

        Objects.checkFromIndexSize(0, len, raw.length);
        for (int i = 0; i < len; ++i) {
            final ServerPlayer player = raw[i];
            // Paper start - PlayerNaturallySpawnCreaturesEvent
            com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event;
            blockRange = 16384.0;
            if (reducedRange) {
                event = player.playerNaturallySpawnedEvent;
                if (event == null || event.isCancelled()) continue;
                blockRange = (double) ((event.getSpawnRadius() << 4) * (event.getSpawnRadius() << 4));
            }
            if (this.playerIsCloseEnoughForSpawning(player, pos, blockRange)) {
                // Paper end - PlayerNaturallySpawnCreaturesEvent
                return true;
            }
        }

        return false;
        // Paper end - chunk tick iteration optimisation
    }

    public List<ServerPlayer> getPlayersCloseForSpawning(final ChunkPos pos) {
        // Paper start - chunk tick iteration optimisation
        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getNearbyPlayers().getPlayers(
            pos, ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.SPAWN_RANGE
        );
        if (players == null) {
            return new ArrayList<>();
        }

        List<ServerPlayer> ret = null;

        final ServerPlayer[] raw = players.getRawDataUnchecked();
        final int len = players.size();

        Objects.checkFromIndexSize(0, len, raw.length);
        for (int i = 0; i < len; ++i) {
            final ServerPlayer player = raw[i];
            if (this.playerIsCloseEnoughForSpawning(player, pos, 16384.0D)) { // Spigot
                if (ret == null) {
                    ret = new ArrayList<>(len - i);
                    ret.add(player);
                } else {
                    ret.add(player);
                }
            }
        }

        return ret == null ? new ArrayList<>() : ret;
        // Paper end - chunk tick iteration optimisation
    }

    public boolean playerIsCloseEnoughForSpawning(final ServerPlayer player, final ChunkPos pos, final double range) { // Spigot // Paper - chunk tick iteration optimisation - public
        if (player.isSpectator()) {
            return false;
        }

        double distanceToChunk = euclideanDistanceSquared(pos, player.position());
        return distanceToChunk < range; // Spigot
    }

    private boolean playerIsCloseEnoughTo(final ServerPlayer player, final Vec3 pos, final int maxDistance) {
        if (player.isSpectator()) {
            return false;
        }

        double distanceToPos = player.position().distanceTo(pos);
        return distanceToPos < maxDistance;
    }

    private static double euclideanDistanceSquared(final ChunkPos chunkPos, final Vec3 pos) {
        double xPos = SectionPos.sectionToBlockCoord(chunkPos.x(), 8);
        double zPos = SectionPos.sectionToBlockCoord(chunkPos.z(), 8);
        double xd = xPos - pos.x;
        double zd = zPos - pos.z;
        return xd * xd + zd * zd;
    }

    private boolean skipPlayer(final ServerPlayer player) {
        return player.isSpectator() && !this.level.getGameRules().get(GameRules.SPECTATORS_GENERATE_CHUNKS);
    }

    private void updatePlayerStatus(final ServerPlayer player, final boolean added) {
        boolean ignored = this.skipPlayer(player);
        //boolean wasIgnored = this.playerMap.ignoredOrUnknown(player); // Folia - region threading
        if (added) {
            //this.playerMap.addPlayer(player, ignored); // Folia - region threading
            this.updatePlayerPos(player);
            if (!ignored) {
                //this.distanceManager.addPlayer(SectionPos.of(player), player); // Folia - region threading
                ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickDistanceManager)this.distanceManager).moonrise$addPlayer(player, SectionPos.of(player)); // Paper - chunk tick iteration optimisation
            }

            player.setChunkTrackingView(ChunkTrackingView.EMPTY);
            ca.spottedleaf.moonrise.common.PlatformHooks.get().addPlayerToDistanceMaps(this.level, player); // Paper - rewrite chunk system
        } else {
            SectionPos lastPos = player.getLastSectionPos();
            //this.playerMap.removePlayer(player); // Folia - region threading
            if (true) { // Folia - region threading
                //this.distanceManager.removePlayer(lastPos, player); // Folia - region threading
                ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickDistanceManager)this.distanceManager).moonrise$removePlayer(player, SectionPos.of(player)); // Paper - chunk tick iteration optimisation
            }

            ca.spottedleaf.moonrise.common.PlatformHooks.get().removePlayerFromDistanceMaps(this.level, player); // Paper - rewrite chunk system
        }
    }

    private void updatePlayerPos(final ServerPlayer player) {
        SectionPos pos = SectionPos.of(player);
        player.setLastSectionPos(pos);
    }

    public void move(final ServerPlayer player) {
        // Paper - optimise entity tracker

        SectionPos oldSection = player.getLastSectionPos();
        SectionPos newSection = SectionPos.of(player);
        //boolean wasIgnored = this.playerMap.ignored(player); // Folia - region threading
        boolean ignored = this.skipPlayer(player);
        //boolean positionChanged = oldSection.asLong() != newSection.asLong(); // Folia - region threading
        if (true) { // Folia - region threading
            this.updatePlayerPos(player);
            ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickDistanceManager)this.distanceManager).moonrise$updatePlayer(player, oldSection, newSection, false, ignored); // Paper - chunk tick iteration optimisation // Folia - region threading
            // Folia - region threading

            // Paper - rewrite chunk system
        }
        ca.spottedleaf.moonrise.common.PlatformHooks.get().updateMaps(this.level, player); // Paper - rewrite chunk system
    }

    private void updateChunkTracking(final ServerPlayer player) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void applyChunkTrackingView(final ServerPlayer player, final ChunkTrackingView next) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public List<ServerPlayer> getPlayers(final ChunkPos pos, final boolean borderOnly) {
        // Paper start - rewrite chunk system
        final ChunkHolder holder = this.getVisibleChunkIfPresent(pos.pack());
        if (holder == null) {
            return new ArrayList<>();
        } else {
            return ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)holder).moonrise$getPlayers(borderOnly);
        }
        // Paper end - rewrite chunk system
    }

    public boolean hasEntityWithId(final int id) {
        return this.level.moonrise$getEntityLookup().hasEntity(id); // Folia - region threading
    }

    protected void addEntity(final Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
        // Paper start - ignore and warn about illegal addEntity calls instead of crashing server
        if (!entity.valid || entity.level() != this.level || entity.moonrise$getTrackedEntity() != null) { // Folia - region threading
            LOGGER.error("Illegal ChunkMap::addEntity for world " + io.papermc.paper.util.MCUtil.getLevelName(this.level)
                + ": " + entity  + (entity.moonrise$getTrackedEntity() != null ? " ALREADY CONTAINED (This would have crashed your server)" : ""), new Throwable()); // Folia - region threading
            return;
        }
        // Paper end - ignore and warn about illegal addEntity calls instead of crashing server
        if (entity instanceof ServerPlayer && ((ServerPlayer) entity).suppressTrackerForLogin) return; // Paper - Fire PlayerJoinEvent when Player is actually ready; Delay adding to tracker until after list packets
        if (!(entity instanceof EnderDragonPart)) {
            EntityType<?> type = entity.getType();
            int range = type.clientTrackingRange() * 16;
            range = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, range); // Spigot
            if (range != 0) {
                int updateInterval = type.updateInterval();
                if (entity.moonrise$getTrackedEntity() != null) { // Folia - region threading
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Entity is already tracked!"));
                }

                ChunkMap.TrackedEntity trackedEntity = new ChunkMap.TrackedEntity(entity, range, updateInterval, type.trackDeltas());
                //this.entityMap.put(entity.getId(), trackedEntity); // Folia - region threading
                // Paper start - optimise entity tracker
                if (((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity)entity).moonrise$getTrackedEntity() != null) {
                    throw new IllegalStateException("Entity is already tracked");
                }
                ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity)entity).moonrise$setTrackedEntity(trackedEntity);
                // Paper end - optimise entity tracker
                trackedEntity.updatePlayers(this.level.getLocalPlayers()); // Folia - region threading
                if (entity instanceof ServerPlayer player) {
                    this.updatePlayerStatus(player, true);

                    // Folia start - region threading
                    for (Entity possible : this.level.getCurrentWorldData().trackerEntities) {
                        ChunkMap.TrackedEntity e = possible.moonrise$getTrackedEntity();
                        if (e == null) {
                            continue;
                        }
                        // Folia end - region threading
                        if (e.entity != player) {
                            e.updatePlayer(player);
                        }
                    }
                }
            }
        }
    }

    protected void removeEntity(final Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
        if (entity instanceof ServerPlayer player) {
            this.updatePlayerStatus(player, false);

            // Folia start - region threading
            for (Entity possible : this.level.getCurrentWorldData().getLocalEntities()) {
                ChunkMap.TrackedEntity trackedEntity = possible.moonrise$getTrackedEntity();
                if (trackedEntity == null) {
                    continue;
                }
                // Folia end - region threading
                trackedEntity.removePlayer(player);
            }
            // Folia end - region threading
        }

        ChunkMap.TrackedEntity trackedEntity = entity.moonrise$getTrackedEntity(); // Folia - region threading
        if (trackedEntity != null) {
            trackedEntity.broadcastRemoved();
        }
        ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity)entity).moonrise$setTrackedEntity(null); // Paper - optimise entity tracker
    }

    // Paper start - optimise entity tracker
    private void newTrackerTick() {
        // Folia start - profiler
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler();
        final int totalEntities;
        profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TRACKER_TICK); try { // Folia - profiler
        // Folia end - profiler
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.level.getCurrentWorldData(); // Folia - region threading
        final ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup entityLookup = (ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup)((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getEntityLookup();;
        final ca.spottedleaf.moonrise.common.misc.NearbyPlayers nearbyPlayers = this.level.moonrise$getNearbyPlayers(); // Folia - region threading

        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.entity.Entity> trackerEntities = worldData.trackerEntities; // Folia - region threading
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        for (int i = 0, len = totalEntities = trackerEntities.size(); i < len; ++i) { // Folia - region threading
            final Entity entity = trackerEntitiesRaw[i];
            final ChunkMap.TrackedEntity tracker = ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity)entity).moonrise$getTrackedEntity();
            if (tracker == null) {
                continue;
            }
            ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity)tracker).moonrise$tick(nearbyPlayers.getChunk(entity.chunkPosition())); // Folia - region threading
            if (((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity)tracker).moonrise$hasPlayers()
                || ((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity)entity).moonrise$getChunkStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
                tracker.serverEntity.sendChanges();
            }
        }
        profiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.TRACKED_ENTITY_COUNTS, (long)totalEntities); // Folia - profiler
        } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TRACKER_TICK); } // Folia - profiler
    }
    // Paper end - optimise entity tracker

    protected void tick() {
        // Paper start - optimise entity tracker
        if (true) {
            this.newTrackerTick();
            return;
        }
        // Paper end - optimise entity tracker
        // Paper - rewrite chunk system

        // Folia - region threading
    }

    public void sendToTrackingPlayers(final Entity entity, final Packet<? super ClientGamePacketListener> packet) {
        ChunkMap.TrackedEntity trackedEntity = entity.moonrise$getTrackedEntity(); // Folia - region threading
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayers(packet);
        }
    }

    public void sendToTrackingPlayersFiltered(
        final Entity entity, final Packet<? super ClientGamePacketListener> packet, final Predicate<ServerPlayer> targetPredicate
    ) {
        ChunkMap.TrackedEntity trackedEntity = entity.moonrise$getTrackedEntity(); // Folia - region threading
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayersFiltered(packet, targetPredicate);
        }
    }

    protected void sendToTrackingPlayersAndSelf(final Entity entity, final Packet<? super ClientGamePacketListener> packet) {
        ChunkMap.TrackedEntity trackedEntity = entity.moonrise$getTrackedEntity(); // Folia - region threading
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayersAndSelf(packet);
        }
    }

    public boolean isTrackedByAnyPlayer(final Entity entity) {
        ChunkMap.TrackedEntity trackedEntity = entity.moonrise$getTrackedEntity(); // Folia - region threading
        return trackedEntity != null && !trackedEntity.seenBy.isEmpty();
    }

    public void forEachEntityTrackedBy(final ServerPlayer player, final Consumer<Entity> consumer) {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    public void resendBiomesForChunks(final List<ChunkAccess> chunks) {
        Map<ServerPlayer, List<LevelChunk>> chunksForPlayers = new HashMap<>();

        for (ChunkAccess chunkAccess : chunks) {
            ChunkPos pos = chunkAccess.getPos();
            LevelChunk chunk;
            if (chunkAccess instanceof LevelChunk levelChunk) {
                chunk = levelChunk;
            } else {
                chunk = this.level.getChunk(pos.x(), pos.z());
            }

            for (ServerPlayer player : this.getPlayers(pos, false)) {
                chunksForPlayers.computeIfAbsent(player, p -> new ArrayList<>()).add(chunk);
            }
        }

        chunksForPlayers.forEach((playerx, chunkList) -> playerx.connection.send(ClientboundChunksBiomesPacket.forChunks((List<LevelChunk>)chunkList)));
    }

    protected PoiManager getPoiManager() {
        return this.poiManager;
    }

    public String getStorageName() {
        return this.storageName;
    }

    void onFullChunkStatusChange(final ChunkPos pos, final FullChunkStatus status) {
        this.chunkStatusListener.onChunkStatusChange(pos, status);
    }

    public void waitForLightBeforeSending(final ChunkPos centerChunk, final int chunkRadius) {
        // Paper - rewrite chunk system
    }

    public void forEachReadyToSendChunk(final Consumer<LevelChunk> consumer) {
        for (ChunkHolder chunkHolder : ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getOldChunkHolders()) { // Paper - rewrite chunk system
            LevelChunk chunk = chunkHolder.getChunkToSend();
            if (chunk != null) {
                consumer.accept(chunk);
            }
        }
    }

    public class DistanceManager extends net.minecraft.server.level.DistanceManager implements ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemDistanceManager { // Paper - rewrite chunk system
        protected DistanceManager(final TicketStorage ticketStorage, final Executor executor, final Executor mainThreadExecutor) {
            super(ticketStorage, executor, mainThreadExecutor);
        }

        // Paper start - rewrite chunk system
        @Override
        public final ChunkMap moonrise$getChunkMap() {
            return ChunkMap.this;
        }
        // Paper end - rewrite chunk system

        @Override
        protected boolean isChunkToRemove(final long node) {
            throw new UnsupportedOperationException(); // Paper - rewrite chunk system
        }

        @Override
        protected @Nullable ChunkHolder getChunk(final long node) {
            return ChunkMap.this.getUpdatingChunkIfPresent(node);
        }

        @Override
        protected @Nullable ChunkHolder updateChunkScheduling(final long node, final int level, final @Nullable ChunkHolder chunk, final int oldLevel) {
            return ChunkMap.this.updateChunkScheduling(node, level, chunk, oldLevel);
        }
    }

    public class TrackedEntity implements ServerEntity.Synchronizer, ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity { // Paper - optimise entity tracker
        public final ServerEntity serverEntity;
        private final Entity entity;
        private final int range;
        private SectionPos lastSectionPos;
        public final Set<ServerPlayerConnection> seenBy = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(); // Paper - Perf: optimise map impl

        // Paper start - optimise entity tracker
        private long lastChunkUpdate = -1L;
        private ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk lastTrackedChunk;

        @Override
        public final void moonrise$tick(final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk chunk) {
            if (chunk == null) {
                this.moonrise$clearPlayers();
                return;
            }

            final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = chunk.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.VIEW_DISTANCE);

            if (players == null) {
                this.moonrise$clearPlayers();
                return;
            }

            final long lastChunkUpdate = this.lastChunkUpdate;
            final long currChunkUpdate = chunk.getUpdateCount();
            final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk lastTrackedChunk = this.lastTrackedChunk;
            this.lastChunkUpdate = currChunkUpdate;
            this.lastTrackedChunk = chunk;

            final ServerPlayer[] playersRaw = players.getRawDataUnchecked();

            for (int i = 0, len = players.size(); i < len; ++i) {
                final ServerPlayer player = playersRaw[i];
                this.updatePlayer(player);
            }

            if (lastChunkUpdate != currChunkUpdate || lastTrackedChunk != chunk) {
                // need to purge any players possible not in the chunk list
                for (final ServerPlayerConnection conn : new java.util.ArrayList<>(this.seenBy)) {
                    final ServerPlayer player = conn.getPlayer();
                    if (!players.contains(player)) {
                        this.removePlayer(player);
                    }
                }
            }
        }

        @Override
        public final void moonrise$removeNonTickThreadPlayers() {
            boolean foundToRemove = false;
            for (final ServerPlayerConnection conn : this.seenBy) {
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(conn.getPlayer())) {
                    foundToRemove = true;
                    break;
                }
            }

            if (!foundToRemove) {
                return;
            }

            for (final ServerPlayerConnection conn : new java.util.ArrayList<>(this.seenBy)) {
                ServerPlayer player = conn.getPlayer();
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) {
                    this.removePlayer(player);
                }
            }
        }

        @Override
        public final void moonrise$clearPlayers() {
            this.lastChunkUpdate = -1;
            this.lastTrackedChunk = null;
            if (this.seenBy.isEmpty()) {
                return;
            }
            for (final ServerPlayerConnection conn : new java.util.ArrayList<>(this.seenBy)) {
                ServerPlayer player = conn.getPlayer();
                this.removePlayer(player);
            }
        }

        @Override
        public final boolean moonrise$hasPlayers() {
            return !this.seenBy.isEmpty();
        }
        // Paper end - optimise entity tracker

        public TrackedEntity(final Entity entity, final int range, final int updateInterval, final boolean trackDelta) {
            this.serverEntity = new ServerEntity(ChunkMap.this.level, entity, updateInterval, trackDelta, this, this.seenBy); // Paper
            this.entity = entity;
            this.range = range;
            this.lastSectionPos = SectionPos.of(entity);
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof ChunkMap.TrackedEntity trackedEntity && trackedEntity.entity.getId() == this.entity.getId();
        }

        @Override
        public int hashCode() {
            return this.entity.getId();
        }

        @Override
        public void sendToTrackingPlayers(final Packet<? super ClientGamePacketListener> packet) {
            for (ServerPlayerConnection connection : this.seenBy) {
                connection.send(packet);
            }
        }

        @Override
        public void sendToTrackingPlayersAndSelf(final Packet<? super ClientGamePacketListener> packet) {
            this.sendToTrackingPlayers(packet);
            if (this.entity instanceof ServerPlayer player) {
                player.connection.send(packet);
            }
        }

        @Override
        public void sendToTrackingPlayersFiltered(final Packet<? super ClientGamePacketListener> packet, final Predicate<ServerPlayer> targetPredicate) {
            for (ServerPlayerConnection connection : this.seenBy) {
                if (targetPredicate.test(connection.getPlayer())) {
                    connection.send(packet);
                }
            }
        }

        public void broadcastRemoved() {
            for (ServerPlayerConnection connection : this.seenBy) {
                this.serverEntity.removePairing(connection.getPlayer());
            }
        }

        public void removePlayer(final ServerPlayer player) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker clear"); // Spigot
            if (this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
                if (this.seenBy.isEmpty()) {
                    ChunkMap.this.level.debugSynchronizers().dropEntity(this.entity);
                }
            }
        }

        public void updatePlayer(final ServerPlayer player) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker update"); // Spigot
            if (player != this.entity) {
                // Paper start - remove allocation of Vec3D here
                // Vec3 deltaToPlayer = player.position().subtract(this.entity.position());
                double deltaToPlayerX = player.getX() - this.entity.getX();
                double deltaToPlayerZ = player.getZ() - this.entity.getZ();
                // Paper end - remove allocation of Vec3D here
                int playerViewDistance = ChunkMap.this.getPlayerViewDistance(player);
                double visibleRange = Math.min(this.getEffectiveRange(), playerViewDistance * 16);
                double distanceSquared = deltaToPlayerX * deltaToPlayerX + deltaToPlayerZ * deltaToPlayerZ; // Paper
                double rangeSquared = visibleRange * visibleRange;
                // Paper start - Configurable entity tracking range by Y
                boolean visibleToPlayer = distanceSquared <= rangeSquared;
                if (visibleToPlayer && level.paperConfig().entities.trackingRangeY.enabled) {
                    double rangeY = level.paperConfig().entities.trackingRangeY.get(this.entity, -1);
                    if (rangeY != -1) {
                        double deltaToPlayerY = player.getY() - this.entity.getY();
                        visibleToPlayer = deltaToPlayerY * deltaToPlayerY <= rangeY * rangeY;
                    }
                }
                visibleToPlayer = visibleToPlayer
                    && this.entity.broadcastToPlayer(player)
                    && ChunkMap.this.isChunkTracked(player, this.entity.chunkPosition().x(), this.entity.chunkPosition().z());
                // Paper end - Configurable entity tracking range by Y
                // Folia start - region threading
                if (visibleToPlayer && (this.entity instanceof ServerPlayer thisEntity) && thisEntity.broadcastedDeath) {
                    visibleToPlayer = false;
                }
                // Folia end - region threading
                // CraftBukkit start - respect vanish API
                if (visibleToPlayer && (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player) || !player.getBukkitEntity().canSee(this.entity.getBukkitEntity()))) { // Paper - only consider hits // Folia - region threading
                    visibleToPlayer = false;
                }
                // CraftBukkit end
                if (visibleToPlayer) {
                    if (this.seenBy.add(player.connection)) {
                        // Paper start - entity tracking events
                        if (io.papermc.paper.event.player.PlayerTrackEntityEvent.getHandlerList().getRegisteredListeners().length == 0 || new io.papermc.paper.event.player.PlayerTrackEntityEvent(player.getBukkitEntity(), this.entity.getBukkitEntity()).callEvent()) {
                        this.serverEntity.addPairing(player);
                        if (this.seenBy.size() == 1) {
                            ChunkMap.this.level.debugSynchronizers().registerEntity(this.entity);
                        }

                        ChunkMap.this.level.debugSynchronizers().startTrackingEntity(player, this.entity);
                        }
                        // Paper end - entity tracking events
                        this.serverEntity.onPlayerAdd(); // Paper - fix desync when a player is added to the tracker
                    }
                } else {
                    this.removePlayer(player);
                }
            }
        }

        private int scaledRange(final int range) {
            return ChunkMap.this.level.getServer().getScaledTrackingDistance(range);
        }

        private int getEffectiveRange() {
            // Paper start - optimise entity tracker
            final Entity entity = this.entity;
            int range = this.range;

            if (entity.getPassengers() == ImmutableList.<Entity>of()) {
                return this.scaledRange(range);
            }

            // note: we change to List
            final List<Entity> passengers = (List<Entity>)entity.getIndirectPassengers();
            for (int i = 0, len = passengers.size(); i < len; ++i) {
                final Entity passenger = passengers.get(i);
                // note: max should be branchless
                range = Math.max(range, ca.spottedleaf.moonrise.common.PlatformHooks.get().modifyEntityTrackingRange(passenger, passenger.getType().clientTrackingRange() << 4));
            }

            return this.scaledRange(range);
            // Paper end - optimise entity tracker
        }

        public void updatePlayers(final List<ServerPlayer> players) {
            for (ServerPlayer player : players) {
                this.updatePlayer(player);
            }
        }
    }
}

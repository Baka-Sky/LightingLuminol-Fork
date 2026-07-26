package net.minecraft.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import com.mojang.jtracy.DiscontinuousFrame;
import com.mojang.jtracy.TracyClient;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ThreadInfo;
import java.net.Proxy;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.nbt.Tag;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DemoMode;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.notifications.ServerActivityMonitor;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.FileUtil;
import net.minecraft.util.ModCheck;
import net.minecraft.util.Mth;
import net.minecraft.util.NativeModuleLister;
import net.minecraft.util.PngInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.Util;
import net.minecraft.util.debug.ServerDebugSubscribers;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.util.debugchart.TpsDebugDimensions;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.util.profiling.metrics.profiling.ActiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.ServerMetricsSamplersProvider;
import net.minecraft.util.profiling.metrics.storage.MetricsPersister;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.Stopwatches;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.timers.TimerQueue;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreboardSaveData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class MinecraftServer extends ReentrantBlockableEventLoop<TickTask> implements CommandSource, ServerInfo, ChunkIOErrorReporter, ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer { // Paper - rewrite chunk system
    private static MinecraftServer SERVER; // Paper
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final net.kyori.adventure.text.logger.slf4j.ComponentLogger COMPONENT_LOGGER = net.kyori.adventure.text.logger.slf4j.ComponentLogger.logger(LOGGER.getName()); // Paper
    public static final String VANILLA_BRAND = "vanilla";
    private static final float AVERAGE_TICK_TIME_SMOOTHING = 0.8F;
    private static final int TICK_STATS_SPAN = 100;
    private static final long OVERLOADED_THRESHOLD_NANOS = 30L * TimeUtil.NANOSECONDS_PER_SECOND / 20L; // CraftBukkit
    private static final int OVERLOADED_TICKS_THRESHOLD = 20;
    private static final long OVERLOADED_WARNING_INTERVAL_NANOS = 10L * TimeUtil.NANOSECONDS_PER_SECOND;
    private static final int OVERLOADED_TICKS_WARNING_INTERVAL = 100;
    public static final long STATUS_EXPIRE_TIME_NANOS = 5L * TimeUtil.NANOSECONDS_PER_SECOND; // Folia - region threading - public
    private static final long PREPARE_LEVELS_DEFAULT_DELAY_NANOS = 10L * TimeUtil.NANOSECONDS_PER_MILLISECOND;
    private static final int MAX_STATUS_PLAYER_SAMPLE = 12;
    public static final int SPAWN_POSITION_SEARCH_RADIUS = 5;
    private static final int SERVER_ACTIVITY_MONITOR_SECONDS_BETWEEN_NOTIFICATIONS = 30;
    private static final Map<String, String> LEGACY_WORLD_NAMES_FOR_REALMS_LOG = Map.of("overworld", "world", "the_nether", "DIM-1", "the_end", "DIM1");
    private static final int AUTOSAVE_INTERVAL = 6000;
    private static final int MIMINUM_AUTOSAVE_TICKS = 100;
    private static final int MAX_TICK_LATENCY = 3;
    public static final int ABSOLUTE_MAX_WORLD_SIZE = 29999984;
    public static final LevelSettings DEMO_SETTINGS = new LevelSettings(
        "Demo World", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT
    );
    public static final Supplier<GameRules> DEFAULT_GAME_RULES = () -> new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures());
    public static final NameAndId ANONYMOUS_PLAYER_PROFILE = new NameAndId(Util.NIL_UUID, "Anonymous Player");
    public static final String SERVER_THREAD_NAME = "Server thread";
    public LevelStorageSource.LevelStorageAccess storageSource;
    protected final PlayerDataStorage playerDataStorage;
    private final SavedDataStorage savedDataStorage;
    private final List<Runnable> tickables = Lists.newArrayList();
    // Paper - per-level GameRules
    private MetricsRecorder metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    private Consumer<ProfileResults> onMetricsRecordingStopped = results -> this.stopRecordingMetrics();
    private Consumer<Path> onMetricsRecordingFinished = ignored -> {};
    private boolean willStartRecordingMetrics;
    private MinecraftServer.@Nullable TimeProfiler debugCommandProfiler;
    private boolean debugCommandProfilerDelayStart;
    private ServerConnectionListener connection;
    // Paper - per world load listener - moved LevelLoadListener to ServerLevel
    private @Nullable ServerStatus status;
    private ServerStatus.@Nullable Favicon statusIcon;
    private final RandomSource random = RandomSource.create();
    private final DataFixer fixerUpper;
    private String localIp;
    private int port = -1;
    private final LayeredRegistryAccess<RegistryLayer> registries;
    private Map<ResourceKey<Level>, ServerLevel> levels = Maps.newLinkedHashMap();
    private PlayerList playerList;
    private volatile boolean running = true;
    private volatile boolean isRestarting = false; // Paper - flag to signify we're attempting to restart
    private boolean stopped;
    // Folia - region threading
    protected final Proxy proxy;
    private boolean onlineMode;
    private boolean preventProxyConnections;
    private net.kyori.adventure.text.Component motd; // Paper - Adventure
    private int playerIdleTimeout;
    private final long[] tickTimesNanos = new long[100];
    private long aggregatedTickTimesNanos = 0L;
    private @Nullable KeyPair keyPair;
    private @Nullable GameProfile singleplayerProfile;
    private boolean isDemo;
    private volatile boolean isReady;
    private long lastOverloadWarningNanos;
    protected final Services services;
    private final NotificationManager notificationManager;
    private final ServerActivityMonitor serverActivityMonitor;
    private long lastServerStatus;
    private final Thread serverThread;
    private long lastTickNanos = Util.getNanos();
    private long taskExecutionStartNanos = Util.getNanos();
    private long idleTimeNanos;
    private long nextTickTimeNanos = Util.getNanos();
    private boolean waitingForNextTick = false;
    private long delayedTasksMaxNextTickTimeNanos;
    private boolean mayHaveDelayedTasks;
    private final PackRepository packRepository;
    private final WorldGenSettings worldGenSettings;
    private final ServerScoreboard scoreboard = new ServerScoreboard(this);
    private @Nullable Stopwatches stopwatches;
    private @Nullable CommandStorage commandStorage;
    private final CustomBossEvents customBossEvents;
    private final RandomSequences randomSequences;
    // Paper - per-level WeatherData
    private final ServerFunctionManager functionManager;
    private boolean enforceWhitelist;
    private boolean usingWhitelist;
    private float smoothedTickTimeMillis;
    private final Executor executor;
    private @Nullable String serverId;
    private MinecraftServer.ReloadableResources resources;
    private final StructureTemplateManager structureTemplateManager;
    private final ServerTickRateManager tickRateManager;
    private final ServerDebugSubscribers debugSubscribers = new ServerDebugSubscribers(this);
    protected WorldData worldData;
    private LevelData.RespawnData effectiveRespawnData = LevelData.RespawnData.DEFAULT;
    public PotionBrewing potionBrewing;
    private FuelValues fuelValues;
    private int emptyTicks;
    private volatile boolean isSaving;
    private final SuppressedExceptionCollector suppressedExceptions = new SuppressedExceptionCollector();
    private final DiscontinuousFrame tickFrame;
    private final PacketProcessor packetProcessor;
    // Paper - per-level scheduledEvents
    private final ServerClockManager clockManager;

    public static <S extends MinecraftServer> S spin(final Function<Thread, S> factory) {
        ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.init(); // Paper - rewrite data converter system
        AtomicReference<S> serverReference = new AtomicReference<>();
        Thread thread = new ca.spottedleaf.moonrise.common.util.TickThread(() -> serverReference.get().runServer(), "Server thread");
        thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught exception in server thread", e));
        thread.setPriority(Thread.NORM_PRIORITY + 2); // Paper - Perf: Boost priority
        if (Runtime.getRuntime().availableProcessors() > 4) {
            thread.setPriority(8);
        }

        S server = (S)factory.apply(thread);
        serverReference.set(server);
        thread.start();
        return server;
    }

    // CraftBukkit start
    public final WorldLoader.DataLoadContext worldLoaderContext;
    public org.bukkit.craftbukkit.CraftServer server;
    public joptsimple.OptionSet options;
    public org.bukkit.command.ConsoleCommandSender console;
    //public static int currentTick; // Paper - improve tick loop // Folia - region threading
    public java.util.Queue<Runnable> processQueue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    public int autosavePeriod;
    // Paper - don't store the vanilla dispatcher
    public boolean forceTicks;
    // CraftBukkit end
    // Spigot start
    public static final int TPS = 20;
    public static final int TICK_TIME = 1000000000 / MinecraftServer.TPS;
    // Spigot end
    public volatile boolean hasFullyShutdown; // Paper - Improved watchdog support
    public volatile boolean abnormalExit; // Paper - Improved watchdog support
    public volatile Thread shutdownThread; // Paper - Improved watchdog support
    public final io.papermc.paper.configuration.PaperConfigurations paperConfigurations; // Paper - add paper configuration files
    public boolean isIteratingOverLevels = false; // Paper - Throw exception on world create while being ticked
    private final Set<String> pluginsBlockingSleep = new java.util.HashSet<>(); // Paper - API to allow/disallow tick sleeping
    public static final long SERVER_INIT = System.nanoTime(); // Paper - Lag compensation
    // Paper start - improve tick loop
    public final ca.spottedleaf.common.time.TickData tickTimes1s  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(1L));
    public final ca.spottedleaf.common.time.TickData tickTimes5s  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(5L));
    public final ca.spottedleaf.common.time.TickData tickTimes10s = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(10L));
    public final ca.spottedleaf.common.time.TickData tickTimes15s = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(15L));
    public final ca.spottedleaf.common.time.TickData tickTimes1m  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(1L));
    public final ca.spottedleaf.common.time.TickData tickTimes5m  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(5L));
    public final ca.spottedleaf.common.time.TickData tickTimes15m = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(15L));

    private final ca.spottedleaf.common.time.Schedule tickSchedule = new ca.spottedleaf.common.time.Schedule(0L);

    private long lastTickStart;
    private long currentTickStart;
    private long scheduledTickStart;
    private long taskExecutionTime;
    /**
     * The tickCount field is not incremented exactly where and when we want for our
     * usage here.
     * <p></p>
     * There are two problems we need to fix:
     * <ol>
     *     <li>The tickCount field is not incremented when paused through integrated server.</li>
     *     <li>The tickCount field is incremented after draining tasks (during server tick).</li>
     * </ol>
     * Our goal with the tick count here is to prevent executing tasks scheduled after the start
     * of the current tick, which is marked by the task draining.
     *
     * @see #runAllTasksAtTickStart
     */
    private final java.util.concurrent.atomic.AtomicInteger tickTaskTickCount = new java.util.concurrent.atomic.AtomicInteger();
    private final Object statsLock = new Object();
    private double @Nullable [] tps;
    private ca.spottedleaf.common.time.TickData.@Nullable MSPTData msptData5s;

    private void addTickTime(final ca.spottedleaf.common.time.TickTime time) {
        synchronized (this.statsLock) {
            this.tickTimes1s.addDataFrom(time);
            this.tickTimes5s.addDataFrom(time);
            this.tickTimes10s.addDataFrom(time);
            this.tickTimes15s.addDataFrom(time);
            this.tickTimes1m.addDataFrom(time);
            this.tickTimes5m.addDataFrom(time);
            this.tickTimes15m.addDataFrom(time);
            this.clearTickTimeStatistics();
        }
    }

    private void clearTickTimeStatistics() {
        this.msptData5s = null;
        this.tps = null;
    }

    private static double getTPS(final ca.spottedleaf.common.time.TickData tickData, final long tickInterval) {
        final Double avg = tickData.getTPSAverage(null, tickInterval);
        if (avg == null) {
            return 1.0E9 / (double)tickInterval;
        }

        return avg;
    }

    public double[] getTPS() {
        synchronized (this.statsLock) {
            double @Nullable [] tps = this.tps;
            if (tps == null) {
                tps = this.computeTPS();
                this.tps = tps;
            }
            return tps.clone();
        }
    }

    public ca.spottedleaf.common.time.TickData.@Nullable MSPTData getMSPTData5s() {
        synchronized (this.statsLock) {
            if (this.msptData5s == null) {
                this.msptData5s = this.tickTimes5s.getMSPTData(null, this.tickRateManager().nanosecondsPerTick());
            }
            return this.msptData5s;
        }
    }

    public double[] computeTPS() {
        final long interval = this.tickRateManager().nanosecondsPerTick();
        return new double[] {
            getTPS(this.tickTimes1m, interval),
            getTPS(this.tickTimes5m, interval),
            getTPS(this.tickTimes15m, interval)
        };
    }
    // Paper end - improve tick loop
    // Paper start - rewrite chunk system
    private volatile Throwable chunkSystemCrash;

    @Override
    public final void moonrise$setChunkSystemCrash(final Throwable throwable) {
        this.chunkSystemCrash = throwable;
    }

    private static final long CHUNK_TASK_QUEUE_BACKOFF_MIN_TIME = 25L * 1000L; // 25us
    private static final long MAX_CHUNK_EXEC_TIME = 1000L; // 1us
    private static final long TASK_EXECUTION_FAILURE_BACKOFF = 5L * 1000L; // 5us

    // Folia - region threading - moved to regionized data

    private boolean tickMidTickTasks(final io.papermc.paper.threadedregions.RegionizedWorldData worldData) { // Folia - region threading
        return worldData.world.getChunkSource().pollTask(); // Folia - region threading
    }

    @Override
    public final void moonrise$executeMidTickTasks() {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - region threading
        final long startTime = System.nanoTime();
        if ((startTime - worldData.lastMidTickExecute) <= CHUNK_TASK_QUEUE_BACKOFF_MIN_TIME || (startTime - worldData.lastMidTickExecuteFailure) <= TASK_EXECUTION_FAILURE_BACKOFF) { // Folia - region threading
            // it's shown to be bad to constantly hit the queue (chunk loads slow to a crawl), even if no tasks are executed.
            // so, backoff to prevent this
            return;
        }

        for (;;) {
            final boolean moreTasks = this.tickMidTickTasks(worldData); // Folia - region threading
            final long currTime = System.nanoTime();
            final long diff = currTime - startTime;

            if (!moreTasks || diff >= MAX_CHUNK_EXEC_TIME) {
                if (!moreTasks) {
                    worldData.lastMidTickExecuteFailure = currTime; // Folia - region threading
                }

                // note: negative values reduce the time
                long overuse = diff - MAX_CHUNK_EXEC_TIME;
                if (overuse >= (10L * 1000L * 1000L)) { // 10ms
                    // make sure something like a GC or dumb plugin doesn't screw us over...
                    overuse = 10L * 1000L * 1000L; // 10ms
                }

                final double overuseCount = (double)overuse/(double)MAX_CHUNK_EXEC_TIME;
                final long extraSleep = (long)Math.round(overuseCount*CHUNK_TASK_QUEUE_BACKOFF_MIN_TIME);

                worldData.lastMidTickExecute = currTime + extraSleep; // Folia - region threading
                return;
            }
        }
    }

    @Override
    public final void moonrise$issueEmergencySave() {
        LOGGER.warn("Performing emergency save...");
        LOGGER.info("Saving all players...");
        this.getPlayerList().saveAll();
        LOGGER.info("Saved all players");
        LOGGER.info("Saving all worlds...");
        for (final ServerLevel world : this.getAllLevels()) {
            LOGGER.info("Saving chunks in world '" + ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(world) + "'...");
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$issueEmergencySave();
            LOGGER.info("Saved chunks in world '" + ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(world) + "'...");
        }
        LOGGER.info("Saved all worlds");
        LOGGER.warn("Performed emergency save");
    }
    // Paper end - rewrite chunk system
    // Folia start - regionised ticking
    public final io.papermc.paper.threadedregions.RegionizedServer regionizedServer = new io.papermc.paper.threadedregions.RegionizedServer();

    @Override
    public <V> CompletableFuture<V> submit(java.util.function.Supplier<V> task) {
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
    public void schedule(TickTask task) {
        if (true) {
            throw new UnsupportedOperationException();
        }
        super.schedule(task);
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
    // Folia end - regionised ticking

    public MinecraftServer(
        // CraftBukkit start
        joptsimple.OptionSet options,
        WorldLoader.DataLoadContext worldLoaderContext,
        // CraftBukkit end
        final Thread serverThread,
        final LevelStorageSource.LevelStorageAccess storageSource,
        final PackRepository packRepository,
        final WorldStem worldStem,
        final Optional<GameRules> gameRules,
        final Proxy proxy,
        final DataFixer fixerUpper,
        final Services services,
        final LevelLoadListener levelLoadListener,
        final boolean propagatesCrashes,
        final NotificationManager notificationManager
    ) {
        super("Server", propagatesCrashes);
        SERVER = this; // Paper - better singleton
        this.registries = worldStem.registries();
        if (false && !this.registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).containsKey(LevelStem.OVERWORLD)) { // CraftBukkit - initialised later
            throw new IllegalStateException("Missing Overworld dimension data");
        }

        this.savedDataStorage = new SavedDataStorage(storageSource.getLevelPath(LevelResource.ROOT).resolve(LevelResource.DATA.id()), fixerUpper, this.registries.compositeAccess()); // Paper - save in level storage
        this.worldData = worldStem.worldDataAndGenSettings().data();
        this.worldGenSettings = worldStem.worldDataAndGenSettings().genSettings();
        // this.savedDataStorage.set(WorldGenSettings.TYPE, this.worldGenSettings); // Paper - save in level storage
        this.proxy = proxy;
        this.packRepository = packRepository;
        this.resources = new MinecraftServer.ReloadableResources(worldStem.resourceManager(), worldStem.dataPackResources());
        this.services = services;
        // this.connection = new ServerConnectionListener(this); // Spigot
        this.tickRateManager = new ServerTickRateManager(this);
        // Paper - per-level load listener - move LevelLoadListener to ServerLevel
        this.storageSource = storageSource;
        this.playerDataStorage = storageSource.createPlayerStorage();
        this.randomSequences = this.savedDataStorage.computeIfAbsent(RandomSequences.TYPE);
        // Paper - per-level WeatherData
        // Paper - per-level GameRules
        this.fixerUpper = fixerUpper;
        this.functionManager = new ServerFunctionManager(this, this.resources.managers.getFunctionLibrary());
        HolderGetter<Block> blockLookup = this.registries.compositeAccess().lookupOrThrow(Registries.BLOCK).filterFeatures(this.worldData.enabledFeatures());
        this.structureTemplateManager = new StructureTemplateManager(worldStem.resourceManager(), storageSource, fixerUpper, blockLookup);
        this.serverThread = serverThread;
        this.executor = Util.backgroundExecutor();
        this.potionBrewing = PotionBrewing.bootstrap(this.worldData.enabledFeatures());
        this.resources.managers.getRecipeManager().finalizeRecipeLoading(this.worldData.enabledFeatures());
        this.fuelValues = FuelValues.vanillaBurnTimes(this.registries.compositeAccess(), this.worldData.enabledFeatures());
        this.tickFrame = TracyClient.createDiscontinuousFrame("Server Tick");
        this.notificationManager = notificationManager;
        this.serverActivityMonitor = new ServerActivityMonitor(notificationManager, 30);
        this.packetProcessor = null; // Folia - region threading
        this.clockManager = this.getDataStorage().computeIfAbsent(ServerClockManager.TYPE);
        this.clockManager.init(this);
        this.customBossEvents = this.savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE);
        // Paper - per-level scheduledEvents
        // CraftBukkit start
        this.options = options;
        this.worldLoaderContext = worldLoaderContext;
        io.papermc.paper.log.LogManagerShutdownThread.unhook(); // Paper - Improved watchdog support
        Runtime.getRuntime().addShutdownHook(new org.bukkit.craftbukkit.util.ServerShutdownThread(this));
        // CraftBukkit end
        this.paperConfigurations = services.paper().configurations(); // Paper - add paper configuration files
    }

    protected abstract boolean initServer() throws IOException;

    public ChunkLoadStatusView createChunkLoadStatusView(final int radius) {
        return new ChunkLoadStatusView() {
            private @Nullable ChunkMap chunkMap;
            private int centerChunkX;
            private int centerChunkZ;

            @Override
            public void moveTo(final ResourceKey<Level> dimension, final ChunkPos centerChunk) {
                ServerLevel level = MinecraftServer.this.getLevel(dimension);
                this.chunkMap = level != null ? level.getChunkSource().chunkMap : null;
                this.centerChunkX = centerChunk.x();
                this.centerChunkZ = centerChunk.z();
            }

            @Override
            public @Nullable ChunkStatus get(final int x, final int z) {
                return this.chunkMap == null
                    ? null
                    : this.chunkMap.getLatestStatus(ChunkPos.pack(x + this.centerChunkX - radius, z + this.centerChunkZ - radius));
            }

            @Override
            public int radius() {
                return radius;
            }
        };
    }

    protected void loadLevel(final String levelId) { // CraftBukkit
        boolean startedWorldLoadProfiling = !JvmProfiler.INSTANCE.isRunning()
            && SharedConstants.DEBUG_JFR_PROFILING_ENABLE_LEVEL_LOADING
            && JvmProfiler.INSTANCE.start(Environment.from(this));
        ProfiledDuration profiledDuration = JvmProfiler.INSTANCE.onWorldLoadedStarted();
        // Paper start - rework world loading process
        io.papermc.paper.world.PaperWorldLoader loader = io.papermc.paper.world.PaperWorldLoader.create(this, levelId);
        loader.loadInitialWorlds();
        // Paper end - rework world loading process
        if (profiledDuration != null) {
            profiledDuration.finish(true);
        }

        if (startedWorldLoadProfiling) {
            try {
                JvmProfiler.INSTANCE.stop();
            } catch (Throwable t) {
                LOGGER.warn("Failed to stop JFR profiling", t);
            }
        }
    }

    // Paper start - rework world loading process
    protected void initPostWorld() {
        // Paper start - Configurable player collision; Handle collideRule team for player collision toggle
        final ServerScoreboard scoreboard = this.getScoreboard();
        final java.util.Collection<String> toRemove = scoreboard.getPlayerTeams().stream().filter(team -> team.getName().startsWith("collideRule_")).map(net.minecraft.world.scores.PlayerTeam::getName).collect(java.util.stream.Collectors.toList());
        for (String teamName : toRemove) {
            scoreboard.removePlayerTeam(scoreboard.getPlayerTeam(teamName)); // Clean up after ourselves
        }

        if (!io.papermc.paper.configuration.GlobalConfiguration.get().collisions.enablePlayerCollisions) {
            this.getPlayerList().collideRuleTeamName = org.apache.commons.lang3.StringUtils.left("collideRule_" + java.util.concurrent.ThreadLocalRandom.current().nextInt(), 16);
            net.minecraft.world.scores.PlayerTeam collideTeam = scoreboard.addPlayerTeam(this.getPlayerList().collideRuleTeamName);
            collideTeam.setSeeFriendlyInvisibles(false); // Because we want to mimic them not being on a team at all
        }
        // Paper end - Configurable player collision; Handle collideRule team for player collision toggle
        this.server.enablePlugins(org.bukkit.plugin.PluginLoadOrder.POSTWORLD);
        this.server.spark.registerCommandBeforePlugins(this.server); // Paper - spark
        this.server.spark.enableAfterPlugins(this.server); // Paper - spark
        io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.setValid(); // Paper - reset invalid state for event fire below
        io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, io.papermc.paper.command.brigadier.PaperCommands.INSTANCE, org.bukkit.plugin.Plugin.class, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL); // Paper - call commands event for regular plugins
        this.server.getCommandMap().registerServerAliases(); // Paper - relocate initial CommandMap#registerServerAliases() call
        ((org.bukkit.craftbukkit.help.SimpleHelpMap) this.server.getHelpMap()).initializeCommands();
        this.server.getPluginManager().callEvent(new org.bukkit.event.server.ServerLoadEvent(org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP));
        this.connection.acceptConnections();

        Runnable task;
        while ((task = this.pluginEnabledCallbacks.pollOrBlockAdds()) != null) {
            task.run();
        }
    }
    // Paper end - rework world loading process
    public final ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<Runnable> pluginEnabledCallbacks = new ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue<>();

    protected void forceDifficulty() {
    }

    // Paper start - rework world loading process
    public void createLevel(
        LevelStem levelStem,
        io.papermc.paper.world.PaperWorldLoader.WorldLoadingInfoAndData loading,
        net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings worldDataAndGenSettings
    ) {
        final WorldOptions worldOptions = worldDataAndGenSettings.genSettings().options();
        final ResourceKey<Level> dimensionKey = loading.info().dimensionKey();
        final SavedDataStorage savedDataStorage = new SavedDataStorage(this.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()), this.getFixerUpper(), this.registryAccess());
        savedDataStorage.set(WorldGenSettings.TYPE, new WorldGenSettings(worldDataAndGenSettings.genSettings().options(), worldDataAndGenSettings.genSettings().dimensions()));
        long seed = worldOptions.seed();
        long biomeZoomSeed = BiomeManager.obfuscateSeed(seed);
        List<CustomSpawner> overworldCustomSpawners = ImmutableList.of(
            new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(savedDataStorage) // Paper - save to world data
        );
        final org.bukkit.generator.ChunkGenerator chunkGenerator = this.server.getGenerator(loading.data().bukkitName());
        org.bukkit.generator.BiomeProvider biomeProvider = this.server.getBiomeProvider(loading.data().bukkitName());
        final org.bukkit.generator.WorldInfo worldInfo = new org.bukkit.craftbukkit.generator.CraftWorldInfo(
            loading.data().bukkitName(),
            org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(loading.info().dimensionKey().identifier()),
            worldDataAndGenSettings.genSettings().options().seed(),
            worldDataAndGenSettings.data().enabledFeatures(),
            loading.info().environment(),
            levelStem.type().value(),
            levelStem.generator(),
            this.registryAccess(),
            loading.data().uuid()
        );
        if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(worldInfo);
        }
        ServerLevel serverLevel;
        if (loading.info().stemKey() == LevelStem.OVERWORLD) {
            serverLevel = new ServerLevel(
                this,
                this.executor,
                this.storageSource,
                worldDataAndGenSettings.genSettings(),
                dimensionKey,
                levelStem,
                worldDataAndGenSettings.data().isDebugWorld(),
                biomeZoomSeed,
                overworldCustomSpawners,
                true,
                loading.info().stemKey(),
                loading.info().environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loading.data()
            );
            this.worldData = worldDataAndGenSettings.data();
            this.worldData.setGameType(((net.minecraft.server.dedicated.DedicatedServer) this).getProperties().gameMode.get()); // From DedicatedServer.init
        this.scoreboard.load(this.savedDataStorage.computeIfAbsent(ScoreboardSaveData.TYPE).getData());
        this.commandStorage = new CommandStorage(this.savedDataStorage);
        this.stopwatches = this.savedDataStorage.computeIfAbsent(Stopwatches.TYPE);
            this.server.scoreboardManager = new org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager(this, serverLevel.getScoreboard());
        } else {
            final List<CustomSpawner> spawners;
            if (io.papermc.paper.configuration.GlobalConfiguration.get().misc.useDimensionTypeForCustomSpawners && levelStem.type().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)) {
                spawners = overworldCustomSpawners;
            } else {
                spawners = Collections.emptyList();
            }
            serverLevel = new ServerLevel(
                this,
                this.executor,
                this.storageSource,
                worldDataAndGenSettings.genSettings(),
                dimensionKey,
                levelStem,
                this.worldData.isDebugWorld(),
                biomeZoomSeed,
                spawners,
                true,
                loading.info().stemKey(),
                loading.info().environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loading.data()
            );
        }
        this.addLevel(serverLevel);
        this.initWorld(serverLevel, null);
    }
    public void initWorld(ServerLevel overworld, org.bukkit.@Nullable WorldCreator worldCreator) {
        final net.minecraft.world.level.storage.ServerLevelData levelData = overworld.serverLevelData;
        final WorldOptions worldOptions = overworld.worldGenSettings.options();
        final boolean isDebug = this.worldData.isDebugWorld();
        if (overworld.generator != null) {
            overworld.getWorld().getPopulators().addAll(overworld.generator.getDefaultPopulators(overworld.getWorld()));
        }
        overworld.getWorldBorder().world = overworld;
        overworld.getWorldBorder().setAbsoluteMaxSize(this.getAbsoluteMaxWorldSize());
        this.getPlayerList().addWorldborderListener(overworld);
        this.server.getPluginManager().callEvent(new org.bukkit.event.world.WorldInitEvent(overworld.getWorld()));
    // Paper end - rework world loading process
        if (!levelData.isInitialized()) {
            try {
                // Paper start - Allow direct setting spawn location
                if (worldCreator != null && worldCreator.forcedSpawnPosition() != null) {
                    levelData.setSpawn(LevelData.RespawnData.of(overworld.dimension(),
                        io.papermc.paper.util.MCUtil.toBlockPos(worldCreator.forcedSpawnPosition()),
                        Objects.requireNonNullElse(worldCreator.forcedSpawnYaw(), LevelData.RespawnData.DEFAULT.yaw()),
                        Objects.requireNonNullElse(worldCreator.forcedSpawnPitch(), LevelData.RespawnData.DEFAULT.pitch())
                    ));
                } else {
                    setInitialSpawn(overworld, levelData, worldOptions.generateBonusChest(), isDebug, overworld.levelLoadListener); // Paper - per world level load listener
                }
                // Paper end - Allow direct setting spawn location
                levelData.setInitialized(true);
                if (isDebug) {
                    this.setupDebugLevel(this.worldData, overworld); // Paper - per-level GameRules
                }
            } catch (Throwable t) {
                CrashReport report = CrashReport.forThrowable(t, "Exception initializing level");

                try {
                    overworld.fillReportDetails(report);
                } catch (Throwable var19) {
                }

                throw new ReportedException(report);
            }

            levelData.setInitialized(true);
        }

        GlobalPos focusPos = this.selectLevelLoadFocusPos();
        overworld.levelLoadListener.updateFocus(focusPos.dimension(), ChunkPos.containing(focusPos.pos())); // Paper - per world load listener
    }

    private static void setInitialSpawn(
        final ServerLevel level,
        final ServerLevelData levelData,
        final boolean spawnBonusChest,
        final boolean isDebug,
        final LevelLoadListener levelLoadListener
    ) {
        if (SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD && SharedConstants.DEBUG_WORLD_RECREATE) {
            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), new BlockPos(0, 64, -100), 0.0F, 0.0F));
        } else if (isDebug) {
            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), BlockPos.ZERO.above(80), 0.0F, 0.0F));
        } else {
            ServerChunkCache chunkSource = level.getChunkSource();
            // CraftBukkit start
            if (level.generator != null) {
                java.util.Random rand = new java.util.Random(level.getSeed());
                org.bukkit.Location spawn = level.generator.getFixedSpawnLocation(level.getWorld(), rand);

                if (spawn != null && spawn.getWorld() != null) {
                    if (spawn.getWorld() != level.getWorld()) {
                        throw new IllegalStateException("Cannot set spawn point for " + levelData.getLevelName() + " to be in another world (" + spawn.getWorld().key().asString() + ")");
                    } else {
                        levelData.setSpawn(
                            LevelData.RespawnData.of(
                                level.dimension(),
                                org.bukkit.craftbukkit.util.CraftLocation.toBlockPos(spawn),
                                spawn.getYaw(),
                                spawn.getPitch()
                            )
                        );
                        return;
                    }
                }
            }
            // CraftBukkit end
            ChunkPos spawnChunk = ChunkPos.containing(chunkSource.randomState().sampler().findSpawnPosition());
            levelLoadListener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, 0);
            levelLoadListener.updateFocus(level.dimension(), spawnChunk);
            int height = chunkSource.getGenerator().getSpawnHeight(level);
            if (height < level.getMinY()) {
                // Folia start - region threading
                // pre-apply offset to make the rest easier
                BlockPos worldPosition = spawnChunk.getWorldPosition().offset(8, 0, 8);
                height = level.moonrise$getChunkTaskScheduler().syncLoadNonFull(
                        worldPosition.getX() >> 4, worldPosition.getZ() >> 4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getParent()
                ).getHeight(Heightmap.Types.WORLD_SURFACE, worldPosition.getX(), worldPosition.getZ()) + 1; // add one to match LevelReader#getHeight
                // Folia end - region threading
            }

            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), spawnChunk.getWorldPosition().offset(8, height, 8), 0.0F, 0.0F));
            int xChunkOffset = 0;
            int zChunkOffset = 0;
            int dXChunk = 0;
            int dZChunk = -1;

            for (int i = 0; i < Mth.square(11); i++) {
                if (xChunkOffset >= -5 && xChunkOffset <= 5 && zChunkOffset >= -5 && zChunkOffset <= 5) {
                    BlockPos testedPos = PlayerSpawnFinder.getSpawnPosInChunk(level, new ChunkPos(spawnChunk.x() + xChunkOffset, spawnChunk.z() + zChunkOffset));
                    if (testedPos != null) {
                        levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), testedPos, 0.0F, 0.0F));
                        break;
                    }
                }

                if (xChunkOffset == zChunkOffset || xChunkOffset < 0 && xChunkOffset == -zChunkOffset || xChunkOffset > 0 && xChunkOffset == 1 - zChunkOffset) {
                    int olddx = dXChunk;
                    dXChunk = -dZChunk;
                    dZChunk = olddx;
                }

                xChunkOffset += dXChunk;
                zChunkOffset += dZChunk;
            }

            if (false && spawnBonusChest) { // Folia - region threading - too lazy to hack around this one
                level.registryAccess()
                    .lookup(Registries.CONFIGURED_FEATURE)
                    .flatMap(registry -> registry.get(MiscOverworldFeatures.BONUS_CHEST))
                    .ifPresent(feature -> feature.value().place(level, chunkSource.getGenerator(), level.getRandom(), levelData.getRespawnData().pos()));
            }

            levelLoadListener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);
        }
    }

    private void setupDebugLevel(final WorldData worldData, final ServerLevel level) { // Paper - pass level
        worldData.setDifficulty(Difficulty.PEACEFUL);
        worldData.setDifficultyLocked(true);
        ServerLevelData levelData = worldData.overworldData();
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, level); // Paper - per-level GameRules
        this.clockManager.moveToTimeMarker(this.registryAccess().getOrThrow(WorldClocks.OVERWORLD), ClockTimeMarkers.NOON);
        levelData.setGameType(GameType.SPECTATOR);
    }

    // CraftBukkit start
    public void prepareLevel(ServerLevel level) {
        this.forceTicks = true;
        // CraftBukkit end
        ChunkLoadCounter chunkLoadCounter = new ChunkLoadCounter();

        if (true) { // CraftBukkit
            //chunkLoadCounter.track(level, () -> { // Folia - region threading
                TicketStorage savedTickets = level.getDataStorage().get(TicketStorage.TYPE);
                if (savedTickets != null) {
                    savedTickets.activateAllDeactivatedTickets();
                }
            //}); // Folia - region threading
        }

        level.levelLoadListener.start(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.totalChunks()); // Paper - per world load listener

        do {
            level.levelLoadListener.update(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.readyChunks(), chunkLoadCounter.totalChunks()); // Paper - per world load listener
            //this.executeModerately(); // CraftBukkit // Folia - region threading
        } while (chunkLoadCounter.pendingChunks() > 0);

        level.levelLoadListener.finish(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS); // Paper - per world load listener
        level.setSpawnSettings(level.isSpawningMonsters()); // Paper - per level difficulty (from setDifficulty(ServerLevel, Difficulty, boolean))
        this.updateEffectiveRespawnData();
        this.forceTicks = false; // CraftBukkit
        //level.entityManager.tick(); // SPIGOT-6526: Load pending entities so they are available to the API // Paper - rewrite chunk system
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addWorld(level); // Folia - region threading
        new org.bukkit.event.world.WorldLoadEvent(level.getWorld()).callEvent(); // Paper - call WorldLoadEvent
    }

    protected GlobalPos selectLevelLoadFocusPos() {
        return this.worldData.overworldData().getRespawnData().globalPos();
    }

    public GameType getDefaultGameType() {
        return this.worldData.getGameType();
    }

    public boolean isHardcore() {
        return this.worldData.isHardcore();
    }

    public abstract LevelBasedPermissionSet operatorUserPermissions();

    public abstract PermissionSet getFunctionCompilationPermissions();

    public abstract boolean shouldRconBroadcast();

    public boolean saveAllChunks(final boolean silent, final boolean flush, final boolean force) {
        // Paper start - add close param
        return this.saveAllChunks(silent, flush, force, false);
    }
    public boolean saveAllChunks(final boolean silent, final boolean flush, final boolean force, final boolean close) {
        // Paper end - add close param
        this.saveGlobalData(flush); // Paper - move to saveGlobalData()
        boolean result = false;

        for (ServerLevel level : this.getAllLevels()) {
            if (!silent) {
                LOGGER.info("Saving chunks for level '{}'/{}", level, level.dimension().identifier());
            }

            level.save(null, flush, SharedConstants.DEBUG_DONT_SAVE_WORLD || level.noSave && !force, close); // Paper - add close param
            result = true;
        }

        // Paper - move to saveGlobalData()

        if (flush) {
            for (ServerLevel level : this.getAllLevels()) {
                String storageName = level.getChunkSource().chunkMap.getStorageName();
                LOGGER.info("ThreadedAnvilChunkStorage ({}): All chunks are saved", LEGACY_WORLD_NAMES_FOR_REALMS_LOG.getOrDefault(storageName, storageName));
            }

            LOGGER.info("ThreadedAnvilChunkStorage: All dimensions are saved");
        }

        return result;
    }

    // Paper start - split out global level data
    public void saveGlobalData(final boolean flush) {
        this.scoreboard.storeToSaveDataIfDirty(this.getDataStorage().computeIfAbsent(ScoreboardSaveData.TYPE));

        GameProfile singleplayerProfile = this.getSingleplayerProfile();
        this.storageSource.saveDataTag(this.worldData, singleplayerProfile == null ? null : singleplayerProfile.id());
        if (flush) {
            this.savedDataStorage.saveAndJoin();
        } else {
            this.savedDataStorage.scheduleSave();
        }
    }
    // Paper end - split out global level data

    public boolean saveEverything(final boolean silent, final boolean flush, final boolean force) {
        try {
            this.isSaving = true;
            this.getPlayerList().saveAll(); // Paper - Incremental chunk and player saving; diff on change
            boolean result = this.saveAllChunks(silent, flush, force);
            this.warnOnLowDiskSpace();
            return result;
        } finally {
            this.isSaving = false;
        }
    }

    @Override
    public void close() {
        this.stopServer();
    }

    // CraftBukkit start
    private boolean hasStopped = false;
    private boolean hasLoggedStop = false; // Paper - Debugging
    private final Object stopLock = new Object();
    public final boolean hasStopped() {
        synchronized (this.stopLock) {
            return this.hasStopped;
        }
    }
    // CraftBukkit end

    // Folia start - region threading
    private final java.util.concurrent.atomic.AtomicBoolean hasStartedShutdownThread = new java.util.concurrent.atomic.AtomicBoolean();

    private void haltServerRegionThreading() {
        if (this.hasStartedShutdownThread.getAndSet(true)) {
            // already started shutdown
            return;
        }
        new io.papermc.paper.threadedregions.RegionShutdownThread("Region shutdown thread").start();
    }

    public void haltCurrentRegion() {
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isShutdownThread()) {
            throw new IllegalStateException();
        }
    }
    // Folia end - region threading

    public void stopServer() { // Folia - region threading - public
        // Folia start - region threading
        // halt scheduler
        // don't wait, we may be on a scheduler thread
        io.papermc.paper.threadedregions.TickRegions.getScheduler().halt(false, 0L);
        // cannot run shutdown logic on this thread, as it may be a scheduler
        if (true) {
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isShutdownThread()) {
                this.haltServerRegionThreading();
                return;
            } // else: fall through to regular stop logic
        }
        // Folia end - region threading
        if (Thread.currentThread() == this.serverThread) this.executeAllRecentInternalTasks(); // Paper - execute tasks on stop
        // CraftBukkit start - prevent double stopping on multiple threads
        synchronized (this.stopLock) {
            if (this.hasStopped) return;
            this.hasStopped = true;
        }
        if (!this.hasLoggedStop && this.isDebugging()) io.papermc.paper.util.TraceUtil.dumpTraceForThread("Server stopped"); // Paper - Debugging
        this.shutdownThread = Thread.currentThread(); // Paper - Improved watchdog support
        org.spigotmc.WatchdogThread.doStop(); // Paper - Improved watchdog support
        // CraftBukkit end
        //this.packetProcessor.close(); // Folia - region threading
        if (this.metricsRecorder.isRecording()) {
            this.cancelRecordingMetrics();
        }

        LOGGER.info("Stopping server");
        Commands.COMMAND_SENDING_POOL.shutdownNow(); // Paper - Perf: Async command map building; Shutdown and don't bother finishing
        // CraftBukkit start
        if (this.server != null) {
            this.server.spark.disable(); // Paper - spark
            this.server.disablePlugins();
            this.server.waitForAsyncTasksShutdown(); // Paper - Wait for Async Tasks during shutdown
        }
        // CraftBukkit end
        this.getConnection().stop();
        this.isSaving = true;
        if (this.playerList != null) {
            //LOGGER.info("Saving players"); // Folia - move to shutdown thread logic
            //this.playerList.saveAll(); // Folia - move to shutdown thread logic
            this.playerList.removeAll(this.isRestarting); // Paper
            this.getConnection().handleAllDisconnections(); // Paper
            try { Thread.sleep(100); } catch (InterruptedException ex) {} // CraftBukkit - SPIGOT-625 - give server at least a chance to send packets
        }

        // Folia start - region threading
        if (true) {
            // the rest till part 2 is handled by the region shutdown thread
            return;
        }
        // Folia end - region threading

        LOGGER.info("Saving worlds");

        for (ServerLevel level : this.getAllLevels()) {
            if (level != null) {
                level.noSave = false;
            }
        }

        while (false && this.levels.values().stream().anyMatch(l -> l.getChunkSource().chunkMap.hasWork())) { // Paper - rewrite chunk system
            this.nextTickTimeNanos = Util.getNanos() + TimeUtil.NANOSECONDS_PER_MILLISECOND;

            for (ServerLevel level : this.getAllLevels()) {
                level.getChunkSource().deactivateTicketsOnClosing();
                level.getChunkSource().tick(() -> true, false);
            }

            this.waitUntilNextTick();
        }

        // Paper start - rewrite chunk system
        // note: make sure we call deactivateTicketsOnClosing
        for (final ServerLevel world : this.getAllLevels()) {
            world.getChunkSource().deactivateTicketsOnClosing();
        }
        // Paper end - rewrite chunk system

        this.saveAllChunks(false, true, false, true); // Paper - rewrite chunk system

        this.isSaving = false;
        // Folia start - region threading
        this.stopPart2();
    }
    public void stopPart2() {
        // Folia end - region threading
        this.savedDataStorage.close();
        this.resources.close();

        try {
            this.storageSource.close();
        } catch (IOException e) {
            LOGGER.error("Failed to unlock level {}", this.storageSource.getLevelId(), e);
        }
        // Spigot start
        io.papermc.paper.util.MCUtil.ASYNC_EXECUTOR.shutdown(); // Paper
        try {
            io.papermc.paper.util.MCUtil.ASYNC_EXECUTOR.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); // Paper
        } catch (InterruptedException _) {} // Paper
        if (org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly) {
            LOGGER.info("Saving usercache.json");
            this.services().nameToIdCache().save(false); // Paper - Perf: Async GameProfileCache saving
        }
        // Spigot end
        // Paper start - rewrite chunk system
        LOGGER.info("Waiting for all RegionFile I/O tasks to complete...");
        ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush((MinecraftServer)(Object)this);
        LOGGER.info("All RegionFile I/O tasks to complete");
        if ((Object)this instanceof net.minecraft.server.dedicated.DedicatedServer) {
            ca.spottedleaf.moonrise.common.util.MoonriseCommon.haltExecutors();
        }
        // Paper end - rewrite chunk system
        // Paper start - Improved watchdog support - move final shutdown items here
        Util.shutdownExecutors();
        this.onServerExit();
        // Paper end - Improved watchdog support - move final shutdown items here
    }

    public String getLocalIp() {
        return this.localIp;
    }

    public void setLocalIp(final String ip) {
        this.localIp = ip;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void halt(final boolean wait) {
        // Paper start - allow passing of the intent to restart
        this.halt(wait, false);
    }
    public void halt(final boolean wait, final boolean isRestarting) {
        this.isRestarting = isRestarting;
        this.hasLoggedStop = true; // Paper - Debugging
        if (this.isDebugging()) io.papermc.paper.util.TraceUtil.dumpTraceForThread("Server stopped"); // Paper - Debugging
        // Paper end
        this.running = false;
        this.stopServer(); // Folia - region threading
        if (wait) {
            try {
                this.serverThread.join();
            } catch (InterruptedException e) {
                LOGGER.error("Error while shutting down", e);
            }
        }
    }

    // Paper start - improve tick loop
    private void initTickSchedule() {
        final long interval;
        if (this.isPaused() || !this.tickRateManager.isSprinting()) {
            interval = this.tickRateManager.nanosecondsPerTick();
        } else {
            interval = 0L;
        }
        this.tickSchedule.setNextPeriod(this.nextTickTimeNanos, interval);
        this.lastTickStart = ca.spottedleaf.common.util.TimeUtil.DEADLINE_NOT_SET;
        this.scheduledTickStart = this.tickSchedule.getDeadline(interval);
    }

    private void recordEndOfTick() {
        final long prevStart = this.lastTickStart;
        final long currStart = this.currentTickStart;
        this.lastTickStart = this.currentTickStart;
        final long scheduledStart = this.scheduledTickStart;
        this.scheduledTickStart = this.nextTickTimeNanos; // set scheduledStart for next tick

        final long now = Util.getNanos();

        final ca.spottedleaf.common.time.TickTime time = new ca.spottedleaf.common.time.TickTime(
            prevStart,
            scheduledStart,
            currStart,
            0L,
            now,
            0L,
            this.taskExecutionTime,
            0L,
            false
        );
        this.taskExecutionTime = 0L;

        this.addTickTime(time);
    }

    private void runAllTasksAtTickStart() {
        this.startMeasuringTaskExecutionTime();

        // note: To avoid possibly spinning forever, only execute tasks that are roughly available at the beginning
        //       of this call. Packet processing and chunk system tasks are possibly always being queued.
        final ProfilerFiller profiler = Profiler.get();
        profiler.push("moonrise:run_all_tasks");

        profiler.push("moonrise:run_all_server");
        // avoid calling pollTask - we just want to execute queued tasks
        final int currentTick = this.tickTaskTickCount.incrementAndGet();
        final java.util.function.Predicate<net.minecraft.server.TickTask> taskPredicate = (final TickTask task) -> {
            // only run tasks scheduled before the current tick - which we incremented above
            return currentTick - task.getTick() > 0; // currentTick > tick accounting for overflow
        };
        while (this.runTaskIf(taskPredicate)) {
            // execute small amounts of other tasks just in case the number of tasks we are
            // draining is large - chunk system and packet processing may be latency sensitive

            ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this).moonrise$executeMidTickTasks(); // Paper - rewrite chunk system
            this.packetProcessor.executeSinglePacket();
        }
        profiler.popPush("moonrise:run_all_packets");
        while (this.packetProcessor.executeSinglePacket()) {
            // execute possibly latency sensitive chunk system tasks (see above)
            ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this).moonrise$executeMidTickTasks(); // Paper - rewrite chunk system
        }
        profiler.popPush("moonrise:run_all_chunk");
        // Paper start - rewrite chunk system
        for (final ServerLevel world : this.getAllLevels()) {
            profiler.push(world.toString() + " " + world.dimension().identifier()); // keep same formatting from regular tick, see tickChildren

            // note: legacy tasks may expect a distance manager update
            profiler.push("moonrise:distance_manager_update");
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates();
            profiler.popPush("moonrise:legacy_chunk_tasks");
            world.getChunkSource().mainThreadProcessor.executeAllRecentInternalTasks();
            profiler.popPush("moonrise:chunk_system_tasks");
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().executeAllRecentlyQueuedMainThreadTasks();
            profiler.pop();

            profiler.pop(); // world name
        }
        // Paper end - rewrite chunk system
        profiler.pop(); // moonrise:run_all_chunk
        profiler.pop(); // moonrise:run_all_tasks

        this.finishMeasuringTaskExecutionTime();
    }

    private void recordTaskExecutionTimeWhileWaiting() {
        final ProfilerFiller profiler = Profiler.get();

        profiler.push("moonrise:execute_tasks_until_tick");
        this.waitingForNextTick = true;
        // implement waitForTasks
        final boolean isLoggingEnabled = this.isTickTimeLoggingEnabled();
        try {
            final long deadline = this.nextTickTimeNanos;
            for (;;) {
                final long start = Util.getNanos();
                if (start - deadline >= 0L) {
                    // start is ahead of deadline
                    break;
                }

                // execute tasks while there are tasks and there is time left
                while (this.pollTask() && (Util.getNanos() - deadline < 0L));

                final long now = Util.getNanos();

                // record execution time
                this.taskExecutionTime += (now - start);

                // wait for unpark or deadline
                final long toWait = deadline - now;
                if (toWait > 0L) {
                    LockSupport.parkNanos("waiting for tick or tasks", toWait);
                    if (isLoggingEnabled) {
                        this.idleTimeNanos += Util.getNanos() - now;
                    }
                } else {
                    // done
                    break;
                }
            }
        } finally {
            this.waitingForNextTick = false;
        }
        profiler.pop();
    }
    // Paper end - improve tick loop

    protected void runServer() {
        try {
            if (!this.initServer()) {
                throw new IllegalStateException("Failed to initialize server");
            }

            this.nextTickTimeNanos = Util.getNanos();
            this.initTickSchedule(); // Paper - improve tick loop
            this.statusIcon = this.loadStatusIcon().orElse(null);
            this.status = this.buildServerStatus();

            this.server.spark.enableBeforePlugins(); // Paper - spark
            // Folia start - region threading
            if (true) {
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().init(); // Folia - region threading - only after loading worlds
                final long actualDoneTimeMs = System.currentTimeMillis() - org.bukkit.craftbukkit.Main.BOOT_TIME.toEpochMilli(); // Paper - Improve startup message
                LOGGER.info("Done ({})! For help, type \"help\"", String.format(java.util.Locale.ROOT, "%.3fs", actualDoneTimeMs / 1000.00D)); // Paper - Improve startup message
                for (;;) {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (final InterruptedException ex) {}
                }
            }
            // Folia end - region threading
            // Spigot start
            // Paper start
            LOGGER.info("Running delayed init tasks");
            new io.papermc.paper.threadedregions.RegionizedServerInitEvent().callEvent(); // Call Folia init event
            this.server.getScheduler().mainThreadHeartbeat(); // run all 1 tick delay tasks during init,
            // this is going to be the first thing the tick process does anyway, so move done and run it after
            // everything is init before watchdog tick.
            // anything at 3+ won't be caught here but also will trip watchdog....
            // tasks are default scheduled at -1 + delay, and first tick will tick at 1
            final long actualDoneTimeMs = System.currentTimeMillis() - org.bukkit.craftbukkit.Main.BOOT_TIME.toEpochMilli(); // Paper - Improve startup message
            LOGGER.info("Done ({})! For help, type \"help\"", String.format(java.util.Locale.ROOT, "%.3fs", actualDoneTimeMs / 1000.00D)); // Paper - Improve startup message
            org.spigotmc.WatchdogThread.tick();
            // Paper end
            org.spigotmc.WatchdogThread.hasStarted = true; // Paper
            // Paper start - Add onboarding message for initial server start
            if (io.papermc.paper.configuration.GlobalConfiguration.isFirstStart) {
                LOGGER.info("*************************************************************************************");
                LOGGER.info("This is the first time you're starting this server.");
                LOGGER.info("It's recommended you read our 'Getting Started' documentation for guidance.");
                LOGGER.info("View this and more helpful information here: https://docs.papermc.io/paper/next-steps");
                LOGGER.info("*************************************************************************************");
            }
            // Paper end - Add onboarding message for initial server start
            // Paper start - Improve outdated version checking
            if (System.getProperty("paper.disableStartupVersionCheck") == null && io.papermc.paper.configuration.GlobalConfiguration.get().updateChecker.enabled) {
                CompletableFuture.runAsync(com.destroystokyo.paper.PaperVersionFetcher::getUpdateStatusStartupMessage, io.papermc.paper.util.MCUtil.ASYNC_EXECUTOR);
            }
            // Paper end - Improve outdated version checking

            while (this.running) {
                final long tickStart = System.nanoTime(); // Paper - improve tick loop
                long thisTickNanos; // Paper - improve tick loop - diff on change, expect this to be tick interval
                if (!this.isPaused() && this.tickRateManager.isSprinting() && this.tickRateManager.checkShouldSprintThisTick()) {
                    thisTickNanos = 0L;
                    this.tickSchedule.setNextPeriod(tickStart, thisTickNanos); // Paper - improve tick loop
                } else {
                    thisTickNanos = this.tickRateManager.nanosecondsPerTick();
                    // Paper start - improve tick loop
                    // handle catchup logic
                    final long ticksBehind = Math.max(1L, this.tickSchedule.getPeriodsAhead(thisTickNanos, tickStart));
                    final long catchup = (long)Math.max(
                        1,
                        5 //ConfigHolder.getConfig().tickLoop.catchupTicks.getOrDefault(MoonriseConfig.TickLoop.DEFAULT_CATCHUP_TICKS).intValue()
                    );

                    // adjust ticksBehind so that it is not greater-than catchup
                    if (ticksBehind - catchup > 0L) {
                        final long difference = ticksBehind - catchup;
                        this.tickSchedule.advanceBy(difference, thisTickNanos);
                    }

                    // start next tick
                    this.tickSchedule.advanceBy(1L, thisTickNanos);
                    // Paper end - improve tick loop
                }

                this.nextTickTimeNanos = this.tickSchedule.getDeadline(thisTickNanos);
                this.lastOverloadWarningNanos = this.nextTickTimeNanos;

                this.currentTickStart = tickStart;
                //++MinecraftServer.currentTick; // Folia - region threading
                // Paper end - improve tick loop

                boolean sprinting = thisTickNanos == 0L;
                if (this.debugCommandProfilerDelayStart) {
                    this.debugCommandProfilerDelayStart = false;
                    //this.debugCommandProfiler = new MinecraftServer.TimeProfiler(Util.getNanos(), this.tickCount); // Folia - region threading
                }

                // Paper - improve tick loop - done above

                try (Profiler.Scope ignored = Profiler.use(this.createProfiler())) {
                    this.processPacketsAndTick(sprinting);
                    ProfilerFiller profiler = Profiler.get();
                    profiler.push("nextTickWait");
                    this.mayHaveDelayedTasks = true;
                    this.delayedTasksMaxNextTickTimeNanos = Math.max(Util.getNanos() + thisTickNanos, this.nextTickTimeNanos);
                    this.startMeasuringTaskExecutionTime();
                    this.recordTaskExecutionTimeWhileWaiting(); // Paper - improve tick loop - record task execution here on MSPT
                    this.finishMeasuringTaskExecutionTime();
                    if (sprinting) {
                        this.tickRateManager.endTickWork();
                    }

                    profiler.pop();
                    this.logFullTickTime();
                } finally {
                    this.endMetricsRecordingTick();
                }

                this.isReady = true;
                JvmProfiler.INSTANCE.onServerTick(this.smoothedTickTimeMillis);
            }
        } catch (Throwable t) {
            LOGGER.error("Encountered an unexpected exception", t);
            CrashReport report = constructOrExtractCrashReport(t);
            this.fillSystemReport(report.getSystemReport());
            Path file = this.getServerDirectory().resolve("crash-reports").resolve("crash-" + Util.getFilenameFormattedDateTime() + "-server.txt");
            if (report.saveToFile(file, ReportType.CRASH)) {
                LOGGER.error("This crash report has been saved to: {}", file.toAbsolutePath());
            } else {
                LOGGER.error("We were unable to save this crash report to disk.");
            }

            this.onServerCrash(report);
        } finally {
            try {
                this.stopped = true;
                this.stopServer();
            } catch (Throwable t) {
                LOGGER.error("Exception stopping the server", t);
            } finally {
                //this.onServerExit(); // Paper - Improved watchdog support; moved into stop
            }
        }
    }

    private void logFullTickTime() {
        long currentTime = Util.getNanos();
        if (this.isTickTimeLoggingEnabled()) {
            this.getTickTimeLogger().logSample(currentTime - this.lastTickNanos);
        }

        this.lastTickNanos = currentTime;
    }

    private void startMeasuringTaskExecutionTime() {
        if (this.isTickTimeLoggingEnabled()) {
            this.taskExecutionStartNanos = Util.getNanos();
            this.idleTimeNanos = 0L;
        }
    }

    private void finishMeasuringTaskExecutionTime() {
        if (this.isTickTimeLoggingEnabled()) {
            SampleLogger tickTimelogger = this.getTickTimeLogger();
            tickTimelogger.logPartialSample(Util.getNanos() - this.taskExecutionStartNanos - this.idleTimeNanos, TpsDebugDimensions.SCHEDULED_TASKS.ordinal());
            tickTimelogger.logPartialSample(this.idleTimeNanos, TpsDebugDimensions.IDLE.ordinal());
        }
    }

    private static CrashReport constructOrExtractCrashReport(final Throwable t) {
        ReportedException firstReported = null;

        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReportedException reportedException) {
                firstReported = reportedException;
            }
        }

        CrashReport report;
        if (firstReported != null) {
            report = firstReported.getReport();
            if (firstReported != t) {
                report.addCategory("Wrapped in").setDetailError("Wrapping exception", t);
            }
        } else {
            report = new CrashReport("Exception in server tick loop", t);
        }

        return report;
    }

    private boolean haveTime() {
        // CraftBukkit start
        return this.forceTicks || this.runningTask() || Util.getNanos() < (this.mayHaveDelayedTasks ? this.delayedTasksMaxNextTickTimeNanos : this.nextTickTimeNanos);
    }

    private void executeModerately() {
        this.runAllTasks();
        java.util.concurrent.locks.LockSupport.parkNanos("executing tasks", 1000L);
        // CraftBukkit end
    }

    public NotificationManager notificationManager() {
        return this.notificationManager;
    }

    protected void waitUntilNextTick() {
        // Paper - improve tick loop - moved to start of tick
        this.waitingForNextTick = true;

        try {
            this.managedBlock(() -> Util.getNanos() - this.nextTickTimeNanos >= 0L); // Paper - improve tick loop - do not oversleep
        } finally {
            this.waitingForNextTick = false;
        }
    }

    @Override
    protected void waitForTasks() {
        boolean shouldLogTime = this.isTickTimeLoggingEnabled();
        long waitStart = shouldLogTime ? Util.getNanos() : 0L;
        long waitNanos = this.waitingForNextTick ? this.nextTickTimeNanos - Util.getNanos() : 100000L;
        LockSupport.parkNanos("waiting for tasks", waitNanos);
        if (shouldLogTime) {
            this.idleTimeNanos = this.idleTimeNanos + (Util.getNanos() - waitStart);
        }
    }

    @Override
    // Paper start - anything that does try to post to main during watchdog crash, run on watchdog
    public TickTask wrapRunnable(Runnable runnable) {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    @Override
    protected boolean shouldRun(final TickTask task) {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    @Override
    protected boolean pollTask() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    private boolean pollTaskInternal() {
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        if (super.pollTask()) {
            this.moonrise$executeMidTickTasks(); // Paper - rewrite chunk system
            return true;
        }

        boolean ret = false; // Paper - force execution of all worlds, do not just bias the first
        if (this.tickRateManager.isSprinting() || this.shouldRunAllTasks() || this.haveTime()) {
            for (ServerLevel level : this.getAllLevels()) {
                if (level.getChunkSource().pollTask()) {
                    ret = true; // Paper - force execution of all worlds, do not just bias the first
                }
            }
        }

        return ret; // Paper - force execution of all worlds, do not just bias the first
    }

    @Override
    protected void doRunTask(final TickTask task) {
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        Profiler.get().incrementCounter("runTask");
        super.doRunTask(task);
    }

    private Optional<ServerStatus.Favicon> loadStatusIcon() {
        Optional<Path> iconPath = Optional.of(this.getFile("server-icon.png"))
            .filter(x$0 -> Files.isRegularFile(x$0))
            .or(() -> this.storageSource.getIconFile().filter(x$0 -> Files.isRegularFile(x$0)));
        return iconPath.flatMap(path -> {
            try {
                byte[] contents = Files.readAllBytes(path);
                PngInfo pngInfo = PngInfo.fromBytes(contents);
                if (pngInfo.width() == 64 && pngInfo.height() == 64) {
                    return Optional.of(new ServerStatus.Favicon(contents));
                } else {
                    throw new IllegalArgumentException("Invalid world icon size [" + pngInfo.width() + ", " + pngInfo.height() + "], but expected [64, 64]");
                }
            } catch (Exception e) {
                LOGGER.error("Couldn't load server icon", e);
                return Optional.empty();
            }
        });
    }

    public Optional<Path> getWorldScreenshotFile() {
        return this.storageSource.getIconFile();
    }

    public Path getServerDirectory() {
        return Path.of("");
    }

    public ServerActivityMonitor getServerActivityMonitor() {
        return this.serverActivityMonitor;
    }

    protected void onServerCrash(final CrashReport report) {
    }

    protected void onServerExit() {
    }

    public boolean isPaused() {
        return false;
    }

    // Folia start - region threading
    public void tickServer(final long startTime, final long scheduledEnd, final long targetBuffer,
                           final io.papermc.paper.threadedregions.TickRegions.TickRegionData region) {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        // Folia end - region threading
        org.spigotmc.WatchdogThread.tick(); // Spigot
        long nano = startTime; // Folia - region threading
        int emptyTickThreshold = this.pauseWhenEmptySeconds() * 20;
        this.removeDisabledPluginsBlockingSleep(); // Paper - API to allow/disallow tick sleeping
        if (false && emptyTickThreshold > 0) { // Folia - region threading - this is complicated to implement, and even if done correctly is messy
            if (this.playerList.getPlayerCount() == 0 && !this.tickRateManager.isSprinting() && this.pluginsBlockingSleep.isEmpty()) { // Paper - API to allow/disallow tick sleeping
                this.emptyTicks++;
            } else {
                this.emptyTicks = 0;
            }

            if (this.emptyTicks >= emptyTickThreshold) {
                this.server.spark.tickStart(); // Paper - spark
                if (this.emptyTicks == emptyTickThreshold) {
                    LOGGER.info("Server empty for {} seconds, pausing", this.pauseWhenEmptySeconds());
                    this.autoSave();
                }

                this.server.getScheduler().mainThreadHeartbeat(); // CraftBukkit
                // Paper start - avoid issues with certain tasks not processing during sleep
                Runnable task;
                while ((task = this.processQueue.poll()) != null) {
                    task.run();
                }
                for (final ServerLevel level : this.levels.values()) {
                    // process unloads
                    level.getChunkSource().tick(() -> true, false);
                }
                // Paper end - avoid issues with certain tasks not processing during sleep
                //this.server.spark.executeMainThreadTasks(); // Paper - spark // Folia - region threading
                this.tickConnection();
                this.server.spark.tickEnd(((double)(System.nanoTime() - this.currentTickStart) / 1000000D)); // Paper - spark
                return;
            }
        }


        // Folia start - region threading
        region.world.getCurrentWorldData().updateTickData();
        BooleanSupplier haveTime = () -> {
            return scheduledEnd - System.nanoTime() > targetBuffer;
        };
        // Folia end - region threading

        this.server.spark.tickStart(); // Paper - spark
        new com.destroystokyo.paper.event.server.ServerTickStartEvent((int)region.getCurrentTick()).callEvent(); // Paper - Server Tick Events // Folia - region threading
        // Folia start - region threading
        if (region != null) {
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.INTERNAL_TICK_TASKS); try { // Folia - profiler
            region.getTaskQueueData().drainTasks();
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.INTERNAL_TICK_TASKS); } // Folia - profiler
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_PACKET_PROCESSING); try { // Folia - profiler
            // run all player packets
            for (ServerPlayer player : new java.util.ArrayList<>(region.world.getCurrentWorldData().getLocalPlayers())) {
                PacketProcessor packetProcessor = ((org.bukkit.craftbukkit.entity.CraftPlayer)player.getBukkitEntity()).packetProcessor;
                while (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player) && packetProcessor.executeSinglePacket());
            }
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_PACKET_PROCESSING); } // Folia - profiler
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLUGIN_TICK_TASKS); try { // Folia - profiler
            ((io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler)org.bukkit.Bukkit.getRegionScheduler()).tick();
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLUGIN_TICK_TASKS); } // Folia - profiler
            // now run all the entity schedulers
            long tickedEntitySchedulers = 0L; // Folia - profiler
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_SCHEDULER_TICK); try { // Folia - profiler
            for (io.papermc.paper.threadedregions.EntityScheduler scheduler : region.world.getCurrentWorldData().entitySchedulerTickList.getAllSchedulers()) {
                net.minecraft.world.entity.Entity handle = scheduler.entity.getHandleRaw();
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(handle) || scheduler.isRetired()) {
                    continue;
                }

                ++tickedEntitySchedulers; // Folia - profiler
                scheduler.executeTick();
            }
            foliaProfiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_SCHEDULERS_TICKED, tickedEntitySchedulers); // Folia - profiler
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_SCHEDULER_TICK); } // Folia - profiler
            // Folia start - fix passenger desync
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PASSENGER_DESYNC_CHECK);
            try {
                for (final net.minecraft.world.entity.Entity entity : region.world.getCurrentWorldData().getLocalEntitiesCopy()) {
                    entity.fixPassengerDesync();
                }
            } finally {
                foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PASSENGER_DESYNC_CHECK);
            }
            // Folia end - fix passenger desync
        }
        // Folia end - region threading
        //this.tickCount++; // Folia - region threading
        //this.tickRateManager.tick(); // Folia - region threading
        this.tickChildren(haveTime, region); // Folia - region threading
        if (false && nano - this.lastServerStatus >= STATUS_EXPIRE_TIME_NANOS) { // Folia - region threading
            this.lastServerStatus = nano;
            this.status = this.buildServerStatus();
        }

        //this.ticksUntilAutosave--; // Folia - region threading
        // Paper start - Incremental chunk and player saving
        int playerSaveInterval = io.papermc.paper.configuration.GlobalConfiguration.get().playerAutoSave.rate;
        if (playerSaveInterval < 0) {
            playerSaveInterval = this.autosavePeriod;
        }
        Profiler.get().push("save");
        final boolean fullSave = this.autosavePeriod > 0 && io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() % this.autosavePeriod == 0; // Folia - region threading
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.AUTOSAVE); try { // Folia - profiler
        try {
            this.isSaving = true;
            if (playerSaveInterval > 0) {
                this.playerList.saveAll(playerSaveInterval);
            }
            if (region == null && fullSave) { // Folia - region threading - don't auto save level data
                this.saveGlobalData(false);
            }
            for (final ServerLevel level : (region == null ? this.getAllLevels() : Arrays.asList(region.world))) { // Folia - region threading
                if (level.paperConfig().chunks.autoSaveInterval.value() > 0) {
                    level.saveIncrementally(region == null && fullSave); // Folia - region threading - don't auto save level data
                }
            }
        } finally {
            this.isSaving = false;
        }
        Profiler.get().pop();
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.AUTOSAVE); } // Folia - profiler
        // Paper end - Incremental chunk and player saving

        ProfilerFiller profiler = Profiler.get();
        //this.server.spark.executeMainThreadTasks(); // Paper - spark // Folia - region threading
        // Paper start - Server Tick Events
        long endTime = System.nanoTime();
        long remaining = scheduledEnd - endTime; // Folia - region ticking
        new com.destroystokyo.paper.event.server.ServerTickEndEvent((int)io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(), ((double)(endTime - startTime) / 1000000D), remaining).callEvent(); // Folia - region ticking
        // Paper end - Server Tick Events
        this.server.spark.tickEnd(((double)(endTime - startTime) / 1000000D)); // Paper - spark // Folia - region threading
        // Folia - region threading
    }

    protected void processPacketsAndTick(final boolean sprinting) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("tick");
        this.tickFrame.start();
        // Paper - improve tick loop - moved into runAllTasksAtTickStart
        this.runAllTasksAtTickStart(); // Paper - improve tick loop
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        // Paper start - rewrite chunk system
        final Throwable crash = this.chunkSystemCrash;
        if (crash != null) {
            this.chunkSystemCrash = null;
            throw new RuntimeException("Chunk system crash propagated to tick()", crash);
        }
        // Paper end - rewrite chunk system
        this.tickFrame.end();
        this.recordEndOfTick(); // Paper - improve tick loop
        profiler.pop();
    }

    private void autoSave() {
        //this.ticksUntilAutosave = this.autosavePeriod; // CraftBukkit // Folia - region threading
        LOGGER.debug("Autosave started");
        ProfilerFiller profiler = Profiler.get();
        profiler.push("save");
        this.saveEverything(true, false, false);
        profiler.pop();
        LOGGER.debug("Autosave finished");
    }

    private void logTickMethodTime(final long startTime) {
        if (this.isTickTimeLoggingEnabled()) {
            this.getTickTimeLogger().logPartialSample(Util.getNanos() - startTime, TpsDebugDimensions.TICK_SERVER_METHOD.ordinal());
        }
    }

    // Folia - region threading - use absolute time instead of this

    public void onTickRateChanged() {
        // Folia - region threading - use absolute time instead of this
    }

    protected abstract SampleLogger getTickTimeLogger();

    public abstract boolean isTickTimeLoggingEnabled();

    // Folia start - region threading
    public void rebuildServerStatus() {
        this.status = this.buildServerStatus();
    }
    // Folia end - region threading

    private ServerStatus buildServerStatus() {
        ServerStatus.Players players = this.buildPlayerStatus();
        return new ServerStatus(
            io.papermc.paper.adventure.PaperAdventure.asVanilla(this.motd), // Paper - Adventure
            Optional.of(players),
            Optional.of(ServerStatus.Version.current()),
            Optional.ofNullable(this.statusIcon),
            this.enforceSecureProfile()
        );
    }

    private ServerStatus.Players buildPlayerStatus() {
        List<ServerPlayer> players = new java.util.ArrayList<>(this.playerList.getPlayers()); // Folia - region threading
        int maxPlayers = this.getMaxPlayers();
        if (this.hidesOnlinePlayers()) {
            return new ServerStatus.Players(maxPlayers, players.size(), List.of());
        }

        int sampleSize = Math.min(players.size(), org.spigotmc.SpigotConfig.playerSample); // Paper - PaperServerListPingEvent
        ObjectArrayList<NameAndId> sample = new ObjectArrayList<>(sampleSize);
        int offset = Mth.nextInt(this.random, 0, players.size() - sampleSize);

        for (int i = 0; i < sampleSize; i++) {
            ServerPlayer player = players.get(offset + i);
            sample.add(player.allowsListing() ? player.nameAndId() : ANONYMOUS_PLAYER_PROFILE);
        }

        Util.shuffle(sample, this.random);
        return new ServerStatus.Players(maxPlayers, players.size(), sample);
    }

    // Folia - region threading - moved to regionised data

    protected void tickChildren(final BooleanSupplier haveTime, final io.papermc.paper.threadedregions.TickRegions.TickRegionData region) { // Folia - region threading
        final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - regionised ticking
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        ProfilerFiller profiler = Profiler.get();
        //this.getPlayerList().getPlayers().forEach(playerx -> playerx.connection.suspendFlushing()); // Folia - region threading
        //this.server.getScheduler().mainThreadHeartbeat(); // CraftBukkit // Folia - region threading
        // Paper start - optimise Folia entity scheduler
        //((io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler)org.bukkit.Bukkit.getGlobalRegionScheduler()).tick(); // Folia - region threading - moved to global tick
        // Folia start - region threading - move to tickRegion
//        for (io.papermc.paper.threadedregions.EntityScheduler scheduler : this.entitySchedulerTickList.getAllSchedulers()) {
//            if (scheduler.isRetired()) {
//                continue;
//            }
//
//            scheduler.executeTick();
//        }
//        // Paper end - optimise Folia entity scheduler
        // Folia end - region threading - move to tickRegion
        // Paper end - Folia scheduler API
        //io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.ADVENTURE_CLICK_MANAGER.handleQueue(this.tickCount); // Paper // Folia - region threading - moved to global tick
        //io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.handleQueue(this.tickCount); // Paper // Folia - region threading - moved to global tick
        profiler.push("commandFunctions");
        //this.getFunctions().tick(); // Folia - region threading - TODO Purge functions
        profiler.pop();
        if (false && this.tickRateManager.runsNormally()) { // Folia - region threading - move to global tick
            profiler.push("clocks");
            // Paper start - per-world time
            if (io.papermc.paper.configuration.GlobalConfiguration.get().time.affectsAllWorlds) {
                this.clockManager.tick();
            } else {
                for (ServerLevel level : this.getAllLevels()) {
                    level.clockManager().tick();
                }
            }
            // Paper end - per-world time
            profiler.pop();
        }

        if (true) { // Folia - region threading
            profiler.push("timeSync");
            this.forceGameTimeSynchronization();
            profiler.pop();
        }

        // CraftBukkit start
        // Run tasks that are waiting on processing
        if (false) while (!this.processQueue.isEmpty()) { // Folia - region threading
            this.processQueue.remove().run();
        }

        profiler.push("levels");
        this.updateEffectiveRespawnData();

        //this.isIteratingOverLevels = true; // Paper - Throw exception on world create while being ticked // Folia - region threading
        if (true) { ServerLevel level = region.world; // Folia - region threading
            //level.hasPhysicsEvent = org.bukkit.event.block.BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0; // Paper - BlockPhysicsEvent // Folia - region threading
            //level.hasEntityMoveEvent = io.papermc.paper.event.entity.EntityMoveEvent.getHandlerList().getRegisteredListeners().length > 0; // Paper - Add EntityMoveEvent // Folia - region threading
            //level.updateLagCompensationTick(); // Paper - lag compensation // Folia - region threading
            //net.minecraft.world.level.block.entity.HopperBlockEntity.skipHopperEvents = level.paperConfig().hopper.disableMoveEvent || org.bukkit.event.inventory.InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0; // Paper - Perf: Optimize Hoppers // Folia - region threading
            profiler.push(() -> level + " " + level.dimension().identifier());
            // LightingLuminol start - Portal rate limiter
            regionizedWorldData.portalRateThrottler.begin();
            // LightingLuminol end
            profiler.push("tick");

            try {
                foliaProfiler.startTimer(level.tickTimerId); try { // Folia - profiler
                level.tick(haveTime, region); // Folia - region threading
                } finally { foliaProfiler.stopTimer(level.tickTimerId); } // Folia - profiler
            } catch (Throwable t) {
                CrashReport report = CrashReport.forThrowable(t, "Exception ticking world");
                level.fillReportDetails(report);
                throw new ReportedException(report);
            }

            profiler.pop();
            profiler.pop();
            regionizedWorldData.explosionDensityCache.clear(); // Paper - Optimize explosions // Folia - region threading
            // LightingLuminol start - Portal rate limiter
            regionizedWorldData.portalRateThrottler.done();
            // LightingLuminol end
        }
        //this.isIteratingOverLevels = false; // Paper - Throw exception on world create while being ticked // Folia - region threading

        profiler.popPush("connection");
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CONNECTION_TICK); try { // Folia - profiler
        regionizedWorldData.tickConnections(); // Folia - region threading
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CONNECTION_TICK); } // Folia - profiler
        profiler.popPush("players");
        //this.playerList.tick(); // Folia - region threading
        profiler.popPush("debugSubscribers");
        this.debugSubscribers.tick();
        if (this.tickRateManager.runsNormally()) {
            profiler.popPush("gameTests");
            GameTestTicker.SINGLETON.tick();
        }

        profiler.popPush("server gui refresh");

        if (false) for (Runnable tickable : this.tickables) { // Folia - region threading - TODO WTF is this?
            tickable.run();
        }

        profiler.popPush("send chunks");

        if (false) for (ServerPlayer player : this.playerList.getPlayers()) { // Folia - region threading
            player.connection.chunkSender.sendNextChunks(player);
            player.connection.resumeFlushing();
        }

        profiler.pop();
        this.serverActivityMonitor.tick();
    }

    // Paper start - per world respawn data
    public void updateEffectiveRespawnData() {
        ServerLevel respawnLevel = this.findRespawnDimension();
        LevelData.RespawnData respawnData = respawnLevel.serverLevelData.getRespawnData();
        this.worldData.overworldData().setSpawn(respawnData); // Sync back to level.dat for Paper->Vanilla spawn integrity
        // Paper end - per world respawn data
        this.effectiveRespawnData = respawnLevel.getWorldBorderAdjustedRespawnData(respawnData);
    }

    protected void tickConnection() {
        this.getConnection().tick();
    }

    public void forceGameTimeSynchronization() {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("timeSync");
        // Folia start - region threading
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        ClientboundSetTimePacket timePacket = new ClientboundSetTimePacket(worldData.world.getGameTime(), Map.of());
        for (ServerPlayer player : worldData.getLocalPlayers()) {
            if ((io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + player.getId()) % 20 == 0) {
                player.connection.send(timePacket);
            }
        }
        // Folia end - region threading
        profiler.pop();
    }

    public void addTickable(final Runnable tickable) {
        this.tickables.add(tickable);
    }

    protected void setId(final String serverId) {
        this.serverId = serverId;
    }

    public boolean isShutdown() {
        return !this.serverThread.isAlive();
    }

    public Path getFile(final String name) {
        return this.getServerDirectory().resolve(name);
    }

    public final ServerLevel overworld() {
        return this.levels.get(Level.OVERWORLD);
    }

    public @Nullable ServerLevel getLevel(final ResourceKey<Level> dimension) {
        return this.levels.get(dimension);
    }

    // CraftBukkit start
    public void addLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.put(level.dimension(), level);
        this.levels = Collections.unmodifiableMap(newLevels);
    }

    public void removeLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.remove(level.dimension());
        this.levels = Collections.unmodifiableMap(newLevels);
    }
    // CraftBukkit end

    public Set<ResourceKey<Level>> levelKeys() {
        return this.levels.keySet();
    }

    public Iterable<ServerLevel> getAllLevels() {
        return this.levels.values();
    }

    @Override
    public String getServerVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public int getPlayerCount() {
        return this.playerList.getPlayerCount();
    }

    public String[] getPlayerNames() {
        return this.playerList.getPlayerNamesArray();
    }

    public String getServerModName() {
        return meow.bacteriawa.lightingluminol.config.ServerModNameConfig.serverModName; // LightingLuminol - use config value
    }

    public ServerClockManager clockManager() {
        return this.clockManager;
    }

    public SystemReport fillSystemReport(final SystemReport systemReport) {
        systemReport.setDetail("Server Running", () -> Boolean.toString(this.running));
        if (this.playerList != null) {
            systemReport.setDetail(
                "Player Count", () -> this.playerList.getPlayerCount() + " / " + this.playerList.getMaxPlayers() + "; " + this.playerList.getPlayers()
            );
        }

        systemReport.setDetail("Active Data Packs", () -> PackRepository.displayPackList(this.packRepository.getSelectedPacks()));
        systemReport.setDetail("Available Data Packs", () -> PackRepository.displayPackList(this.packRepository.getAvailablePacks()));
        systemReport.setDetail(
            "Enabled Feature Flags",
            () -> FeatureFlags.REGISTRY.toNames(this.worldData.enabledFeatures()).stream().map(Identifier::toString).collect(Collectors.joining(", "))
        );
        systemReport.setDetail("World Generation", () -> this.worldData.worldGenSettingsLifecycle().toString());
        systemReport.setDetail("World Seed", () -> String.valueOf(this.worldGenSettings.options().seed()));
        systemReport.setDetail("Suppressed Exceptions", this.suppressedExceptions::dump);
        if (this.serverId != null) {
            systemReport.setDetail("Server Id", () -> this.serverId);
        }

        return this.fillServerSystemReport(systemReport);
    }

    public abstract SystemReport fillServerSystemReport(final SystemReport systemReport);

    public ModCheck getModdedStatus() {
        return ModCheck.identify("vanilla", this::getServerModName, "Server", MinecraftServer.class);
    }

    @Override
    public void sendSystemMessage(final Component message) {
        LOGGER.info(io.papermc.paper.adventure.PaperAdventure.ANSI_SERIALIZER.serialize(io.papermc.paper.adventure.PaperAdventure.asAdventure(message))); // Paper - Log message with colors
    }

    public KeyPair getKeyPair() {
        return Objects.requireNonNull(this.keyPair);
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public @Nullable GameProfile getSingleplayerProfile() {
        return this.singleplayerProfile;
    }

    public void setSingleplayerProfile(final @Nullable GameProfile singleplayerProfile) {
        this.singleplayerProfile = singleplayerProfile;
    }

    public boolean isSingleplayer() {
        return this.singleplayerProfile != null;
    }

    protected void initializeKeyPair() {
        LOGGER.info("Generating keypair");

        try {
            this.keyPair = Crypt.generateKeyPair();
        } catch (CryptException e) {
            throw new IllegalStateException("Failed to generate key pair", e);
        }
    }

    // Paper start - per level difficulty, WorldDifficultyChangeEvent
    public void setDifficulty(final ServerLevel level, final Difficulty difficulty, final @Nullable CommandSourceStack source, final boolean ignoreLock) {
        io.papermc.paper.world.saveddata.PaperLevelOverrides worldData = level.serverLevelData;
        if (ignoreLock || !worldData.isDifficultyLocked()) {
            new io.papermc.paper.event.world.WorldDifficultyChangeEvent(
                level.getWorld(), source, org.bukkit.craftbukkit.util.CraftDifficulty.toBukkit(difficulty)
            ).callEvent();
            worldData.setDifficulty(worldData.isHardcore() ? Difficulty.HARD : difficulty);
            level.setSpawnSettings(level.isSpawningMonsters());
            level.players().forEach(this::sendDifficultyUpdate);
            // Paper end - per level difficulty
        }
    }

    public int getScaledTrackingDistance(final int baseRange) {
        return baseRange;
    }

    public void updateMobSpawningFlags() {
        for (ServerLevel level : this.getAllLevels()) {
            level.setSpawnSettings(level.isSpawningMonsters());
        }
    }

    public void setDifficultyLocked(final boolean locked) {
        this.worldData.setDifficultyLocked(locked);
        // Paper start - set for all worlds
        for (final ServerLevel level : this.getAllLevels()) {
            level.serverLevelData.setDifficultyLocked(locked);
        }
        // Paper end - set for all worlds
        this.getPlayerList().getPlayers().forEach(this::sendDifficultyUpdate);
    }

    private void sendDifficultyUpdate(final ServerPlayer player) {
        LevelData levelData = player.level().getLevelData();
        player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
    }

    public boolean isDemo() {
        return this.isDemo;
    }

    public void setDemo(final boolean demo) {
        this.isDemo = demo;
    }

    public Map<String, String> getCodeOfConducts() {
        return Map.of();
    }

    public Optional<MinecraftServer.ServerResourcePackInfo> getServerResourcePack() {
        return Optional.empty();
    }

    public boolean isResourcePackRequired() {
        return this.getServerResourcePack().filter(MinecraftServer.ServerResourcePackInfo::isRequired).isPresent();
    }

    public abstract boolean isDedicatedServer();

    public abstract int getRateLimitPacketsPerSecond();

    public abstract int getCommandSpamThresholdSeconds();

    public abstract int getChatSpamThresholdSeconds();

    public boolean usesAuthentication() {
        return this.onlineMode;
    }

    public void setUsesAuthentication(final boolean onlineMode) {
        this.onlineMode = onlineMode;
    }

    public boolean getPreventProxyConnections() {
        return this.preventProxyConnections;
    }

    public void setPreventProxyConnections(final boolean preventProxyConnections) {
        this.preventProxyConnections = preventProxyConnections;
    }

    public abstract boolean useNativeTransport();

    public boolean allowFlight() {
        return true;
    }

    @Override
    public String getMotd() {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(this.motd); // Paper - Adventure
    }

    public void setMotd(final String motd) {
        // Paper start - Adventure
        this.motd = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserializeOr(motd, net.kyori.adventure.text.Component.empty());
    }

    public net.kyori.adventure.text.Component motd() {
        return this.motd;
    }

    public void motd(final net.kyori.adventure.text.Component motd) {
        // Paper end - Adventure
        this.motd = motd;
    }

    public boolean isStopped() {
        return this.stopped;
    }

    public PlayerList getPlayerList() {
        return this.playerList;
    }

    public void setPlayerList(final PlayerList players) {
        this.playerList = players;
    }

    public abstract boolean isPublished();

    public void setDefaultGameType(final GameType gameType) {
        this.worldData.setGameType(gameType);
    }

    public int enforceGameTypeForPlayers(final @Nullable GameType gameType) {
        if (gameType == null) {
            return 0;
        }

        int count = 0;

        for (ServerPlayer player : this.getPlayerList().getPlayers()) {
            player.getBukkitEntity().taskScheduler.schedule((ServerPlayer updatedPlayer) -> { // Folia - region threading
            // Paper start - Expand PlayerGameModeChangeEvent
            org.bukkit.event.player.PlayerGameModeChangeEvent event = updatedPlayer.setGameMode(gameType, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.DEFAULT_GAMEMODE, null); // Folia - region threading
            if (event == null || event.isCancelled()) {
                return; // Folia - region threading
            }
            }, null, 1L); // Folia - region threading
            count++;
            // Paper end - Expand PlayerGameModeChangeEvent
        }

        return count;
    }

    public ServerConnectionListener getConnection() {
        return this.connection == null ? this.connection = new ServerConnectionListener(this) : this.connection; // Spigot
    }

    public boolean isReady() {
        return this.isReady;
    }

    public boolean publishServer(final MinecraftServer.MultiplayerScope scope, final @Nullable GameType gameMode, final boolean allowCommands, final int port) {
        return false;
    }

    public boolean unpublishServer() {
        return false;
    }

    public int getTickCount() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    public boolean isUnderSpawnProtection(final ServerLevel level, final BlockPos pos, final Player player) {
        return false;
    }

    public boolean repliesToStatus() {
        return true;
    }

    public boolean hidesOnlinePlayers() {
        return false;
    }

    public Proxy getProxy() {
        return this.proxy;
    }

    public int playerIdleTimeout() {
        return this.playerIdleTimeout;
    }

    public void setPlayerIdleTimeout(final int playerIdleTimeout) {
        this.playerIdleTimeout = playerIdleTimeout;
    }

    public Services services() {
        return this.services;
    }

    public @Nullable ServerStatus getStatus() {
        return this.status;
    }

    public void invalidateStatus() {
        // Folia start - region threading
        if (true) {
            // we don't need this to notify the global tick region
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTaskWithoutNotify(() -> {
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().invalidateStatus();
            });
            return;
        }
        // Folia end - region threading
        this.lastServerStatus = 0L;
    }

    public int getAbsoluteMaxWorldSize() {
        return 29999984;
    }

    @Override
    public boolean scheduleExecutables() {
        return super.scheduleExecutables() && !this.isStopped();
    }

    @Override
    public void executeIfPossible(final Runnable command) {
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        if (this.isStopped()) {
            throw new io.papermc.paper.util.ServerStopRejectedExecutionException("Server already shutting down"); // Paper - do not prematurely disconnect players on stop
        }

        super.executeIfPossible(command);
    }

    @Override
    public Thread getRunningThread() {
        return this.serverThread;
    }

    public int getCompressionThreshold() {
        return 256;
    }

    public boolean enforceSecureProfile() {
        return false;
    }

    public long getNextTickTime() {
        return this.nextTickTimeNanos;
    }

    public DataFixer getFixerUpper() {
        return this.fixerUpper;
    }

    public ServerAdvancementManager getAdvancements() {
        return this.resources.managers.getAdvancements();
    }

    public ServerFunctionManager getFunctions() {
        return this.functionManager;
    }

    // Paper start - Add ServerResourcesReloadedEvent
    @Deprecated @io.papermc.paper.annotation.DoNotUse
    public CompletableFuture<Void> reloadResources(final Collection<String> packsToEnable) {
        return this.reloadResources(packsToEnable, io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause.PLUGIN);
    }

    public CompletableFuture<Void> reloadResources(
        final Collection<String> packsToEnable, final io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause cause
    ) {
        // Paper end - Add ServerResourcesReloadedEvent
        CompletableFuture<Void> result = CompletableFuture.<ImmutableList>supplyAsync(
                () -> packsToEnable.stream()
                    .map(this.packRepository::getPack)
                    .filter(Objects::nonNull)
                    .map(Pack::open)
                    .collect(ImmutableList.toImmutableList()),
                this
            )
            .thenCompose(
                packsToLoad -> {
                    CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packsToLoad);
                    List<Registry.PendingTags<?>> postponedTags = TagLoader.loadTagsForExistingRegistries(resources, this.registries.compositeAccess(), io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.RELOAD); // Paper - tag lifecycle - add cause
                    return ReloadableServerResources.loadResources(
                            resources,
                            this.registries,
                            postponedTags,
                            this.worldData.enabledFeatures(),
                            this.isDedicatedServer() ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED,
                            this.getFunctionCompilationPermissions(),
                            this.executor,
                            this
                        )
                        .whenComplete((unit, throwable) -> {
                            if (throwable != null) {
                                resources.close();
                            }
                        })
                        .thenApply(managers -> new MinecraftServer.ReloadableResources(resources, managers));
                }
            )
            .thenAcceptAsync(newResources -> {
                io.papermc.paper.command.brigadier.PaperBrigadier.moveBukkitCommands(this.resources.managers().getCommands(), newResources.managers().getCommands()); // Paper
                this.resources.close();
                this.resources = newResources;
                this.packRepository.setSelected(packsToEnable, false); // Paper - add pendingReload flag to determine required pack loading - false as this is *after* a reload (see above)
                WorldDataConfiguration newConfig = new WorldDataConfiguration(getSelectedPacks(this.packRepository, true), this.worldData.enabledFeatures());
                this.worldData.setDataConfiguration(newConfig);
                this.resources.managers.updateComponentsAndStaticRegistryTags();
                this.resources.managers.getRecipeManager().finalizeRecipeLoading(this.worldData.enabledFeatures());
                this.potionBrewing = this.potionBrewing.reload(this.worldData.enabledFeatures()); // Paper - Custom Potion Mixes
                if (Thread.currentThread() != this.serverThread) return; // Paper
                // Paper start - we don't need to save everything, just advancements
                // this.getPlayerList().saveAll();
                for (final ServerPlayer player : this.getPlayerList().getPlayers()) {
                    player.getAdvancements().save();
                }
                // Paper end - we don't need to save everything, just advancements
                this.getPlayerList().reloadResources();
                this.functionManager.replaceLibrary(this.resources.managers.getFunctionLibrary());
                this.structureTemplateManager.onResourceManagerReload(this.resources.resourceManager);
                this.fuelValues = FuelValues.vanillaBurnTimes(this.registries.compositeAccess(), this.worldData.enabledFeatures());
                org.bukkit.craftbukkit.block.data.CraftBlockData.reloadCache(); // Paper - cache block data strings; they can be defined by datapacks so refresh it here
                // Paper start - brigadier command API
                io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.setValid(); // reset invalid state for event fire below
                io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, io.papermc.paper.command.brigadier.PaperCommands.INSTANCE, org.bukkit.plugin.Plugin.class, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.RELOAD); // call commands event for regular plugins
                final org.bukkit.craftbukkit.help.SimpleHelpMap helpMap = (org.bukkit.craftbukkit.help.SimpleHelpMap) this.server.getHelpMap();
                helpMap.clear();
                helpMap.initializeGeneralTopics();
                helpMap.initializeCommands();
                this.server.syncCommands(); // Refresh commands after event
                // Paper end
                new io.papermc.paper.event.server.ServerResourcesReloadedEvent(cause).callEvent(); // Paper - Add ServerResourcesReloadedEvent; fire after everything has been reloaded
            }, this);
        if (this.isSameThread()) {
            this.managedBlock(result::isDone);
        }

        return result;
    }

    public static WorldDataConfiguration configurePackRepository(
        final PackRepository packRepository, final WorldDataConfiguration initialDataConfig, final boolean initMode, final boolean safeMode
    ) {
        DataPackConfig dataPackConfig = initialDataConfig.dataPacks();
        FeatureFlagSet forcedFeatures = initMode ? FeatureFlagSet.of() : initialDataConfig.enabledFeatures();
        FeatureFlagSet allowedFeatures = initMode ? FeatureFlags.REGISTRY.allFlags() : initialDataConfig.enabledFeatures();
        packRepository.reload(true); // Paper - will load resource packs
        if (safeMode) {
            return configureRepositoryWithSelection(packRepository, List.of("vanilla"), forcedFeatures, false);
        }

        Set<String> selected = Sets.newLinkedHashSet();

        for (String id : dataPackConfig.getEnabled()) {
            if (packRepository.isAvailable(id)) {
                selected.add(id);
            } else {
                LOGGER.warn("Missing data pack {}", id);
            }
        }

        for (Pack pack : packRepository.getAvailablePacks()) {
            String packId = pack.getId();
            if (!dataPackConfig.getDisabled().contains(packId)) {
                FeatureFlagSet packFeatures = pack.getRequestedFeatures();
                boolean isSelected = selected.contains(packId);
                if (!isSelected && pack.getPackSource().shouldAddAutomatically()) {
                    if (packFeatures.isSubsetOf(allowedFeatures)) {
                        LOGGER.info("Found new data pack {}, loading it automatically", packId);
                        selected.add(packId);
                    } else {
                        LOGGER.info(
                            "Found new data pack {}, but can't load it due to missing features {}",
                            packId,
                            FeatureFlags.printMissingFlags(allowedFeatures, packFeatures)
                        );
                    }
                }

                if (isSelected && !packFeatures.isSubsetOf(allowedFeatures)) {
                    LOGGER.warn(
                        "Pack {} requires features {} that are not enabled for this world, disabling pack.",
                        packId,
                        FeatureFlags.printMissingFlags(allowedFeatures, packFeatures)
                    );
                    selected.remove(packId);
                }
            }
        }

        if (selected.isEmpty()) {
            LOGGER.info("No datapacks selected, forcing vanilla");
            selected.add("vanilla");
        }

        return configureRepositoryWithSelection(packRepository, selected, forcedFeatures, true);
    }

    private static WorldDataConfiguration configureRepositoryWithSelection(
        final PackRepository packRepository, final Collection<String> selected, final FeatureFlagSet forcedFeatures, final boolean disableInactive
    ) {
        packRepository.setSelected(selected, true); // Paper - add pendingReload flag to determine required pack loading - before the initial server load
        enableForcedFeaturePacks(packRepository, forcedFeatures);
        DataPackConfig packConfig = getSelectedPacks(packRepository, disableInactive);
        FeatureFlagSet packRequestedFeatures = packRepository.getRequestedFeatureFlags().join(forcedFeatures);
        return new WorldDataConfiguration(packConfig, packRequestedFeatures);
    }

    private static void enableForcedFeaturePacks(final PackRepository packRepository, final FeatureFlagSet forcedFeatures) {
        FeatureFlagSet providedFeatures = packRepository.getRequestedFeatureFlags();
        FeatureFlagSet missingFeatures = forcedFeatures.subtract(providedFeatures);
        if (!missingFeatures.isEmpty()) {
            Set<String> selected = new ObjectArraySet<>(packRepository.getSelectedIds());

            for (Pack pack : packRepository.getAvailablePacks()) {
                if (missingFeatures.isEmpty()) {
                    break;
                }

                if (pack.getPackSource() == PackSource.FEATURE) {
                    String packId = pack.getId();
                    FeatureFlagSet packFeatures = pack.getRequestedFeatures();
                    if (!packFeatures.isEmpty() && packFeatures.intersects(missingFeatures) && packFeatures.isSubsetOf(forcedFeatures)) {
                        if (!selected.add(packId)) {
                            throw new IllegalStateException("Tried to force '" + packId + "', but it was already enabled");
                        }

                        LOGGER.info("Found feature pack ('{}') for requested feature, forcing to enabled", packId);
                        missingFeatures = missingFeatures.subtract(packFeatures);
                    }
                }
            }

            packRepository.setSelected(selected, true); // Paper - add pendingReload flag to determine required pack loading - before the initial server start
        }
    }

    private static DataPackConfig getSelectedPacks(final PackRepository packRepository, final boolean disableInactive) {
        Collection<String> selected = packRepository.getSelectedIds();
        List<String> enabled = ImmutableList.copyOf(selected);
        List<String> disabled = disableInactive ? packRepository.getAvailableIds().stream().filter(id -> !selected.contains(id)).toList() : List.of();
        return new DataPackConfig(enabled, disabled);
    }

    public void kickUnlistedPlayers() {
        if (this.isEnforceWhitelist() && this.isUsingWhitelist()) {
            PlayerList playerList = this.getPlayerList();
            UserWhiteList whiteList = playerList.getWhiteList();

            for (ServerPlayer player : Lists.newArrayList(playerList.getPlayers())) {
                if (!whiteList.isWhiteListed(player.nameAndId()) && !this.getPlayerList().isOp(player.nameAndId())) { // Paper - Fix kicking ops when whitelist is reloaded (MC-171420)
                    player.connection.disconnect(net.kyori.adventure.text.Component.text(org.spigotmc.SpigotConfig.whitelistMessage), org.bukkit.event.player.PlayerKickEvent.Cause.WHITELIST); // Paper - use configurable message & kick event cause
                }
            }
        }
    }

    public PackRepository getPackRepository() {
        return this.packRepository;
    }

    public Commands getCommands() {
        return this.resources.managers.getCommands();
    }

    public CommandSourceStack createCommandSourceStack() {
        ServerLevel level = this.findRespawnDimension();
        return new CommandSourceStack(
            this,
            Vec3.atLowerCornerOf(this.getRespawnData().pos()),
            Vec2.ZERO,
            level,
            LevelBasedPermissionSet.OWNER,
            "Server",
            Component.literal("Server"),
            this,
            null
        );
    }

    public ServerLevel findRespawnDimension() {
        ResourceKey<Level> respawnDimension = ((net.minecraft.world.level.storage.PrimaryLevelData) this.getWorldData().overworldData()).respawnDimension; // Paper - root cross-world respawn dimension selector
        ServerLevel respawnLevel = this.getLevel(respawnDimension);
        return respawnLevel != null ? respawnLevel : this.overworld();
    }

    @io.papermc.paper.annotation.DoNotUse @Deprecated(forRemoval = true) // Paper - per world respawn data - set through Level
    public void setRespawnData(final LevelData.RespawnData respawnData) {
        ServerLevelData levelData = this.worldData.overworldData();
        LevelData.RespawnData oldRespawnData = levelData.getRespawnData();
        if (!oldRespawnData.equals(respawnData)) {
            levelData.setSpawn(respawnData);
            this.getPlayerList().broadcastAll(new ClientboundSetDefaultSpawnPositionPacket(respawnData));
            this.updateEffectiveRespawnData();
        }
    }

    public LevelData.RespawnData getRespawnData() {
        return this.effectiveRespawnData;
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public abstract boolean shouldInformAdmins();

    public WorldGenSettings getWorldGenSettings() {
        return this.worldGenSettings;
    }

    public RecipeManager getRecipeManager() {
        return this.resources.managers.getRecipeManager();
    }

    public ServerScoreboard getScoreboard() {
        return this.scoreboard;
    }

    public CommandStorage getCommandStorage() {
        if (this.commandStorage == null) {
            throw new NullPointerException("Called before server init");
        } else {
            return this.commandStorage;
        }
    }

    public Stopwatches getStopwatches() {
        if (this.stopwatches == null) {
            throw new NullPointerException("Called before server init");
        } else {
            return this.stopwatches;
        }
    }

    public CustomBossEvents getCustomBossEvents() {
        return this.customBossEvents;
    }

    public RandomSource getRandomSequence(final Identifier key) {
        return this.randomSequences.get(key, this.worldGenSettings.options().seed());
    }

    public RandomSequences getRandomSequences() {
        return this.randomSequences;
    }

    // Paper start - per-level WeatherData
    public void setWeatherParameters(final ServerLevel level, final int clearTime, final int rainTime, final boolean raining, final boolean thundering) {
        WeatherData weatherData = level.getWeatherData();
        // Paper end - per-level WeatherData
        weatherData.setClearWeatherTime(clearTime);
        weatherData.setRainTime(rainTime);
        weatherData.setThunderTime(rainTime);
        weatherData.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.COMMAND); // Paper - per-level WeatherData
        weatherData.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.COMMAND); // Paper - per-level WeatherData
    }

    @Deprecated(forRemoval = true) @io.papermc.paper.annotation.DoNotUse // Paper
    public WeatherData getWeatherData() {
        throw new UnsupportedOperationException("Use ServerLevel.getWeatherData() instead"); // Paper
    }

    public boolean isEnforceWhitelist() {
        return this.enforceWhitelist;
    }

    public void setEnforceWhitelist(final boolean enforceWhitelist) {
        this.enforceWhitelist = enforceWhitelist;
    }

    public boolean isUsingWhitelist() {
        return this.usingWhitelist;
    }

    public void setUsingWhitelist(final boolean usingWhitelist) {
        this.usingWhitelist = usingWhitelist;
    }

    public float getCurrentSmoothedTickTime() {
        return this.smoothedTickTimeMillis;
    }

    public ServerTickRateManager tickRateManager() {
        return this.tickRateManager;
    }

    public long getAverageTickTimeNanos() {
        // Folia start - region threading
        if (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentTickingTask() instanceof io.papermc.paper.threadedregions.TickRegionScheduler.RegionScheduleHandle handle) {
            return (long)Math.ceil(handle.getTickReport5s(System.nanoTime()).timePerTickData().segmentAll().average());
        }
        return 0L;
        // Folia end - region threading
    }

    public long[] getTickTimesNanos() {
        return this.tickTimesNanos;
    }

    public LevelBasedPermissionSet getProfilePermissions(final NameAndId nameAndId) {
        if (this.getPlayerList().isOp(nameAndId)) {
            ServerOpListEntry opListEntry = this.getPlayerList().getOps().get(nameAndId);
            if (opListEntry != null) {
                return opListEntry.permissions();
            } else if (this.isSingleplayerOwner(nameAndId)) {
                return LevelBasedPermissionSet.OWNER;
            } else if (this.isSingleplayer()) {
                return this.getPlayerList().isAllowCommandsForAllPlayers() ? LevelBasedPermissionSet.OWNER : LevelBasedPermissionSet.ALL;
            } else {
                return this.operatorUserPermissions();
            }
        } else {
            return LevelBasedPermissionSet.ALL;
        }
    }

    public abstract boolean isSingleplayerOwner(NameAndId nameAndId);

    public void dumpServerProperties(final Path path) throws IOException {
    }

    private void saveDebugReport(final Path output) {
        Path levelsDir = output.resolve("levels");

        try {
            for (Entry<ResourceKey<Level>, ServerLevel> level : this.levels.entrySet()) {
                Identifier levelId = level.getKey().identifier();
                Path levelPath = levelId.resolveAgainst(levelsDir);
                Files.createDirectories(levelPath);
                level.getValue().saveDebugReport(levelPath);
            }

            this.dumpGameRules(output.resolve("gamerules.txt"));
            this.dumpClasspath(output.resolve("classpath.txt"));
            this.dumpMiscStats(output.resolve("stats.txt"));
            this.dumpThreads(output.resolve("threads.txt"));
            this.dumpServerProperties(output.resolve("server.properties.txt"));
            this.dumpNativeModules(output.resolve("modules.txt"));
        } catch (IOException e) {
            LOGGER.warn("Failed to save debug report", e);
        }
    }

    private void dumpMiscStats(final Path path) throws IOException {
        try (Writer output = Files.newBufferedWriter(path)) {
            output.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getPendingTasksCount()));
            output.write(String.format(Locale.ROOT, "average_tick_time: %f\n", this.getCurrentSmoothedTickTime()));
            output.write(String.format(Locale.ROOT, "tick_times: %s\n", Arrays.toString(this.tickTimesNanos)));
            output.write(String.format(Locale.ROOT, "queue: %s\n", Util.backgroundExecutor()));
        }
    }

    private void dumpGameRules(final Path path) throws IOException {
        try (Writer output = Files.newBufferedWriter(path)) {
            final List<String> entries = Lists.newArrayList();
            final GameRules gameRules = this.overworld().getGameRules(); // Paper - per-level GameRules
            gameRules.visitGameRuleTypes(new GameRuleTypeVisitor() {
                @Override
                public <T> void visit(final GameRule<T> gameRule) {
                    entries.add(String.format(Locale.ROOT, "%s=%s\n", gameRule.getIdentifier(), gameRules.getAsString(gameRule)));
                }
            });

            for (String entry : entries) {
                output.write(entry);
            }
        }
    }

    private void dumpClasspath(final Path path) throws IOException {
        try (Writer output = Files.newBufferedWriter(path)) {
            String classpath = System.getProperty("java.class.path");
            String separator = File.pathSeparator;

            for (String s : Splitter.on(separator).split(classpath)) {
                output.write(s);
                output.write("\n");
            }
        }
    }

    private void dumpThreads(final Path path) throws IOException {
        ThreadInfo[] threadInfos = Util.dumpThreadInfo();
        Arrays.sort(threadInfos, Comparator.comparing(ThreadInfo::getThreadName));

        try (Writer output = Files.newBufferedWriter(path)) {
            for (ThreadInfo threadInfo : threadInfos) {
                output.write(threadInfo.toString());
                output.write(10);
            }
        }
    }

    private void dumpNativeModules(final Path path) throws IOException {
        try (Writer output = Files.newBufferedWriter(path)) {
            List<NativeModuleLister.NativeModuleInfo> modules;
            try {
                modules = Lists.newArrayList(NativeModuleLister.listModules());
            } catch (Throwable t) {
                LOGGER.warn("Failed to list native modules", t);
                return;
            }

            modules.sort(Comparator.comparing(NativeModuleLister.NativeModuleInfo::name, String.CASE_INSENSITIVE_ORDER));

            for (NativeModuleLister.NativeModuleInfo module : modules) {
                output.write(module.toString());
                output.write(10);
            }
        }
    }

    // Paper start - rewrite chunk system
    @Override
    public boolean isSameThread() {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThread();
    }
    // Paper end - rewrite chunk system

    // CraftBukkit start
    public boolean isDebugging() {
        return false;
    }

    public static MinecraftServer getServer() {
        return SERVER;
    }
    // CraftBukkit end

    private ProfilerFiller createProfiler() {
        if (this.willStartRecordingMetrics) {
            this.metricsRecorder = ActiveMetricsRecorder.createStarted(
                new ServerMetricsSamplersProvider(Util.timeSource(), this.isDedicatedServer()),
                Util.timeSource(),
                Util.ioPool(),
                new MetricsPersister("server"),
                this.onMetricsRecordingStopped,
                reportPath -> {
                    this.executeBlocking(() -> this.saveDebugReport(reportPath.resolve("server")));
                    this.onMetricsRecordingFinished.accept(reportPath);
                }
            );
            this.willStartRecordingMetrics = false;
        }

        this.metricsRecorder.startTick();
        return SingleTickProfiler.decorateFiller(this.metricsRecorder.getProfiler(), SingleTickProfiler.createTickProfiler("Server"));
    }

    protected void endMetricsRecordingTick() {
        this.metricsRecorder.endTick();
    }

    public boolean isRecordingMetrics() {
        return this.metricsRecorder.isRecording();
    }

    public void startRecordingMetrics(final Consumer<ProfileResults> onStopped, final Consumer<Path> onFinished) {
        this.onMetricsRecordingStopped = report -> {
            this.stopRecordingMetrics();
            onStopped.accept(report);
        };
        this.onMetricsRecordingFinished = onFinished;
        this.willStartRecordingMetrics = true;
    }

    public void stopRecordingMetrics() {
        this.metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    }

    public void finishRecordingMetrics() {
        this.metricsRecorder.end();
    }

    public void cancelRecordingMetrics() {
        this.metricsRecorder.cancel();
    }

    public Path getWorldPath(final LevelResource resource) {
        return this.storageSource.getLevelPath(resource);
    }

    public boolean forceSynchronousWrites() {
        return true;
    }

    public StructureTemplateManager getStructureManager() {
        return this.structureTemplateManager;
    }

    public WorldData getWorldData() {
        return this.worldData;
    }

    public RegistryAccess.Frozen registryAccess() {
        return this.registries.compositeAccess();
    }

    public LayeredRegistryAccess<RegistryLayer> registries() {
        return this.registries;
    }

    public ReloadableServerRegistries.Holder reloadableRegistries() {
        return this.resources.managers.fullRegistries();
    }

    public TextFilter createTextFilterForPlayer(final ServerPlayer player) {
        return TextFilter.DUMMY;
    }

    public ServerPlayerGameMode createGameModeForPlayer(final ServerPlayer player) {
        return this.isDemo() ? new DemoMode(player) : new ServerPlayerGameMode(player);
    }

    public @Nullable GameType getForcedGameType() {
        return null;
    }

    public ResourceManager getResourceManager() {
        return this.resources.resourceManager;
    }

    public boolean isCurrentlySaving() {
        return this.isSaving;
    }

    public boolean isTimeProfilerRunning() {
        return this.debugCommandProfilerDelayStart || this.debugCommandProfiler != null;
    }

    public void startTimeProfiler() {
        this.debugCommandProfilerDelayStart = true;
    }

    public ProfileResults stopTimeProfiler() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    public int getMaxChainedNeighborUpdates() {
        return 1000000;
    }

    public void logChatMessage(final Component message, final ChatType.Bound chatType, final @Nullable String tag) {
        // Paper start
        net.kyori.adventure.text.Component decoratedMessage = io.papermc.paper.adventure.PaperAdventure.asAdventure(chatType.decorate(message));
        if (tag != null) {
            COMPONENT_LOGGER.info("[{}] {}", tag, decoratedMessage);
        } else {
            COMPONENT_LOGGER.info("{}", decoratedMessage);
            // Paper end
        }
    }

    public final java.util.concurrent.ExecutorService chatExecutor = java.util.concurrent.Executors.newCachedThreadPool(
        new com.google.common.util.concurrent.ThreadFactoryBuilder().setDaemon(true).setNameFormat("Async Chat Thread - #%d").setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(net.minecraft.server.MinecraftServer.LOGGER)).build()); // Paper
    public final ChatDecorator improvedChatDecorator = new io.papermc.paper.adventure.ImprovedChatDecorator(this); // Paper - adventure

    public ChatDecorator getChatDecorator() {
        return this.improvedChatDecorator; // Paper - support async chat decoration events
    }

    public boolean logIPs() {
        return true;
    }

    public void handleCustomClickAction(final Identifier id, final Optional<Tag> payload) {
        LOGGER.debug("Received custom click action {} with payload {}", id, payload.orElse(null));
    }

    @io.papermc.paper.annotation.DoNotUse @Deprecated(forRemoval = true) // Paper - per level load listener
    public LevelLoadListener getLevelLoadListener() {
        throw new UnsupportedOperationException(); // Paper - per level load listener
    }

    public boolean setAutoSave(final boolean enable) {
        boolean success = false;

        for (ServerLevel level : this.getAllLevels()) {
            if (level != null && level.noSave == enable) {
                level.noSave = !enable;
                success = true;
            }
        }

        return success;
    }

    public boolean isAutoSave() {
        for (ServerLevel level : this.getAllLevels()) {
            if (level != null && !level.noSave) {
                return true;
            }
        }

        return false;
    }

    // Paper start - per-world game rules
    public <T> void onGameRuleChanged(final ServerLevel level, final GameRule<T> rule, final T value) {
        this.notificationManager().onGameRuleChanged(level, rule, value);
        // Paper end - per-world game rules
        if (rule == GameRules.REDUCED_DEBUG_INFO) {
            byte event = (byte)((Boolean)value ? 22 : 23);

            for (ServerPlayer player : level.players()) { // Paper - per-world game rules
                player.connection.send(new ClientboundEntityEventPacket(player, event));
            }
        } else if (rule == GameRules.LIMITED_CRAFTING || rule == GameRules.IMMEDIATE_RESPAWN) {
            ClientboundGameEventPacket.Type eventType = rule == GameRules.LIMITED_CRAFTING
                ? ClientboundGameEventPacket.LIMITED_CRAFTING
                : ClientboundGameEventPacket.IMMEDIATE_RESPAWN;
            ClientboundGameEventPacket packet = new ClientboundGameEventPacket(eventType, (Boolean)value ? 1.0F : 0.0F);
            level.players().forEach(playerx -> playerx.connection.send(packet)); // Paper - per-world game rules
        } else if (rule == GameRules.LOCATOR_BAR) {
            // this.getAllLevels().forEach(level -> { // Paper - per-world game rules
                ServerWaypointManager waypointManager = level.getWaypointManager();
                waypointManager.locatorBarEnabled = (Boolean) value; // Paper - optimize ServerWaypointManager with locator bar disabled
                if ((Boolean)value) {
                    level.players().forEach(waypointManager::updatePlayer);
                } else {
                    waypointManager.breakAllConnections();
                }
            // }); // Paper - per-world game rules
        } else if (rule == GameRules.SPAWN_MONSTERS) {
            level.setSpawnSettings(level.isSpawningMonsters()); // Paper - per-world game rules
        } else if (rule == GameRules.ADVANCE_TIME) {
            level.players().forEach(player -> player.connection.send(level.clockManager().createFullSyncPacket(player))); // Paper - per-world time; per-player time
        }
    }

    @Deprecated
    public GameRules getGlobalGameRules() {
        return this.overworld().getGameRules();
    }

    public SavedDataStorage getDataStorage() {
        return this.savedDataStorage;
    }

    @Deprecated(forRemoval = true) @io.papermc.paper.annotation.DoNotUse
    public TimerQueue<MinecraftServer> getScheduledEvents() {
        throw new UnsupportedOperationException("Use ServerLevel.getScheduledEvents() instead"); // Paper
    }

    @Deprecated(forRemoval = true) @io.papermc.paper.annotation.DoNotUse // Paper
    public GameRules getGameRules() {
        throw new UnsupportedOperationException("Use ServerLevel.getGameRules() instead"); // Paper
    }

    public boolean acceptsTransfers() {
        return false;
    }

    private void storeChunkIoError(final CrashReport report, final ChunkPos pos, final RegionStorageInfo storageInfo) {
        Util.ioPool().execute(() -> {
            try {
                Path debugDir = this.getFile("debug");
                FileUtil.createDirectoriesSafe(debugDir);
                String sanitizedLevelName = FileUtil.sanitizeName(storageInfo.level());
                Path reportFile = debugDir.resolve("chunk-" + sanitizedLevelName + "-" + Util.getFilenameFormattedDateTime() + "-server.txt");
                FileStore fileStore = Files.getFileStore(debugDir);
                long remainingSpace = fileStore.getUsableSpace();
                if (remainingSpace < 8192L) {
                    LOGGER.warn("Not storing chunk IO report due to low space on drive {}", fileStore.name());
                    return;
                }

                CrashReportCategory category = report.addCategory("Chunk Info");
                category.setDetail("Level", storageInfo::level);
                category.setDetail("Dimension", () -> storageInfo.dimension().identifier().toString());
                category.setDetail("Storage", storageInfo::type);
                category.setDetail("Position", pos::toString);
                report.saveToFile(reportFile, ReportType.CHUNK_IO_ERROR);
                LOGGER.info("Saved details to {}", report.getSaveFile());
            } catch (Exception e) {
                LOGGER.warn("Failed to store chunk IO exception", e);
            }
        });
    }

    @Override
    public void reportChunkLoadFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
        LOGGER.error("Failed to load chunk {},{}", pos.x(), pos.z(), throwable);
        this.suppressedExceptions.addEntry("chunk/load", throwable);
        this.storeChunkIoError(CrashReport.forThrowable(throwable, "Chunk load failure"), pos, storageInfo);
        this.warnOnLowDiskSpace();
    }

    @Override
    public void reportChunkSaveFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
        LOGGER.error("Failed to save chunk {},{}", pos.x(), pos.z(), throwable);
        this.suppressedExceptions.addEntry("chunk/save", throwable);
        this.storeChunkIoError(CrashReport.forThrowable(throwable, "Chunk save failure"), pos, storageInfo);
        this.warnOnLowDiskSpace();
    }

    protected void warnOnLowDiskSpace() {
        if (this.storageSource.checkForLowDiskSpace()) {
            this.sendLowDiskSpaceWarning();
        }
    }

    public void sendLowDiskSpaceWarning() {
        LOGGER.warn("Low disk space! Might not be able to save the world.");
    }

    public void reportPacketHandlingException(final Throwable throwable, final PacketType<?> packetType) {
        this.suppressedExceptions.addEntry("packet/" + packetType, throwable);
    }

    public PotionBrewing potionBrewing() {
        return this.potionBrewing;
    }

    public FuelValues fuelValues() {
        return this.fuelValues;
    }

    public ServerLinks serverLinks() {
        return ServerLinks.EMPTY;
    }

    protected int pauseWhenEmptySeconds() {
        return 0;
    }

    public PacketProcessor packetProcessor() {
        return this.packetProcessor;
    }

    public ServerDebugSubscribers debugSubscribers() {
        return this.debugSubscribers;
    }

    public enum MultiplayerScope {
        OFF("off"),
        LAN("lan");

        private final Component translatable;
        private final Component tooltip;

        MultiplayerScope(final String key) {
            this.translatable = Component.translatable("menu.multiplayerOptions.network." + key);
            this.tooltip = Component.translatable("menu.multiplayerOptions.network." + key + ".tooltip");
        }

        public Component getDisplayName() {
            return this.translatable;
        }

        public Component getTooltip() {
            return this.tooltip;
        }
    }

    private record ReloadableResources(CloseableResourceManager resourceManager, ReloadableServerResources managers) implements AutoCloseable {
        @Override
        public void close() {
            this.resourceManager.close();
        }
    }

    public record ServerResourcePackInfo(UUID id, String url, String hash, boolean isRequired, @Nullable Component prompt) {
    }

    private static class TimeProfiler {
        private final long startNanos;
        private final int startTick;

        private TimeProfiler(final long startNanos, final int startTick) {
            this.startNanos = startNanos;
            this.startTick = startTick;
        }

        private ProfileResults stop(final long stopNanos, final int stopTick) {
            return new ProfileResults() {
                @Override
                public List<ResultField> getTimes(final String path) {
                    return Collections.emptyList();
                }

                @Override
                public boolean saveResults(final Path file) {
                    return false;
                }

                @Override
                public long getStartTimeNano() {
                    return TimeProfiler.this.startNanos;
                }

                @Override
                public int getStartTimeTicks() {
                    return TimeProfiler.this.startTick;
                }

                @Override
                public long getEndTimeNano() {
                    return stopNanos;
                }

                @Override
                public int getEndTimeTicks() {
                    return stopTick;
                }

                @Override
                public String getProfilerResults() {
                    return "";
                }
            };
        }
    }

    // Paper start - Add tick times API and /mspt command
    public static class TickTimes {
        private final long[] times;

        public TickTimes(int length) {
            times = new long[length];
        }

        void add(int index, long time) {
            times[index % times.length] = time;
        }

        public long[] getTimes() {
            return times.clone();
        }

        public double getAverage() {
            long total = 0L;
            for (long value : times) {
                total += value;
            }
            return ((double) total / (double) times.length) * 1.0E-6D;
        }
    }
    // Paper end - Add tick times API and /mspt command

    // Paper start - API to check if the server is sleeping
    public boolean isTickPaused() {
        return false; // Folia - region threading
    }

    public void addPluginAllowingSleep(final String pluginName, final boolean value) {
        // Folia - region threading
    }

    private void removeDisabledPluginsBlockingSleep() {
        // Folia - region threading
    }
    // Paper end - API to check if the server is sleeping
}

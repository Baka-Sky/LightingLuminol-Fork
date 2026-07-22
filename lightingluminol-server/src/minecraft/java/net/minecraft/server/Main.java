package net.minecraft.server;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.minecraft.CrashReport;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.SharedConstants;
import net.minecraft.SuppressForbidden;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.jsonrpc.JsonRpc;
import net.minecraft.server.jsonrpc.ManagementServer;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.util.worldupdate.WorldUpgrader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class Main {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SuppressForbidden(reason = "System.out needed before bootstrap")
    public static void main(final OptionSet options) { // CraftBukkit - replaces main(String[] args)
        io.papermc.paper.log.LogManagerShutdownThread.hook(); // Paper - Improved watchdog support
        SharedConstants.tryDetectVersion();
        /* CraftBukkit start - Replace everything
        OptionParser parser = new OptionParser();
        OptionSpec<Void> nogui = parser.accepts("nogui");
        OptionSpec<Void> initSettings = parser.accepts("initSettings", "Initializes 'server.properties' and 'eula.txt', then quits");
        OptionSpec<Void> demo = parser.accepts("demo");
        OptionSpec<Void> bonusChest = parser.accepts("bonusChest");
        OptionSpec<Void> forceUpgrade = parser.accepts("forceUpgrade");
        OptionSpec<Void> eraseCache = parser.accepts("eraseCache");
        OptionSpec<Void> recreateRegionFiles = parser.accepts("recreateRegionFiles");
        OptionSpec<Void> safeMode = parser.accepts("safeMode", "Loads level with vanilla datapack only");
        OptionSpec<Void> help = parser.accepts("help").forHelp();
        OptionSpec<String> universe = parser.accepts("universe").withRequiredArg().defaultsTo(".");
        OptionSpec<String> worldName = parser.accepts("world").withRequiredArg();
        OptionSpec<Integer> port = parser.accepts("port").withRequiredArg().ofType(Integer.class).defaultsTo(-1);
        OptionSpec<String> serverId = parser.accepts("serverId").withRequiredArg();
        OptionSpec<Void> jfrProfilingOption = parser.accepts("jfrProfile");
        OptionSpec<Path> pidFile = parser.accepts("pidFile").withRequiredArg().withValuesConvertedBy(new PathConverter());
        OptionSpec<String> nonOptions = parser.nonOptions();

        try {
            OptionSet options = parser.parse(args);
            if (options.has(help)) {
                parser.printHelpOn(System.err);
                return;
            }
            */ // CraftBukkit end
        try {

            Path pidFilePath = (Path) options.valueOf("pidFile"); // CraftBukkit
            if (pidFilePath != null) {
                writePidFile(pidFilePath);
            }

            CrashReport.preload();
            if (options.has("jfrProfile")) { // CraftBukkit
                JvmProfiler.INSTANCE.start(Environment.SERVER);
            }

            io.papermc.paper.plugin.PluginInitializerManager.load(options); // Paper
            Bootstrap.bootStrap();
            Bootstrap.validate();
            Util.startTimerHackThread();
            Path settingsFile = Paths.get("server.properties");
            DedicatedServerSettings settings = new DedicatedServerSettings(options); // CraftBukkit - CLI argument support
            settings.forceSave();
            // Paper start
            if (options.has("forceUpgrade") || options.has("recreateRegionFiles")) {
                LOGGER.error("World upgrade and region file recreation are not yet implemented in Paper 26.1.");
                return;
            }
            // Paper end
            RegionFileVersion.configure(settings.getProperties().regionFileComression);
            Path eulaFile = Paths.get("eula.txt");
            Eula eula = new Eula(eulaFile);
            // Paper start - load config files early for access below if needed
            org.bukkit.configuration.file.YamlConfiguration bukkitConfiguration = io.papermc.paper.configuration.PaperConfigurations.loadLegacyConfigFile((File) options.valueOf("bukkit-settings"));
            org.bukkit.configuration.file.YamlConfiguration spigotConfiguration = io.papermc.paper.configuration.PaperConfigurations.loadLegacyConfigFile((File) options.valueOf("spigot-settings"));
            // Paper end - load config files early for access below if needed
            if (options.has("initSettings")) { // CraftBukkit
                // CraftBukkit start - SPIGOT-5761: Create bukkit.yml and commands.yml if not present
                File configFile = (File) options.valueOf("bukkit-settings");
                org.bukkit.configuration.file.YamlConfiguration configuration = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
                configuration.options().copyDefaults(true);
                configuration.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Main.class.getClassLoader().getResourceAsStream("configurations/bukkit.yml"), java.nio.charset.StandardCharsets.UTF_8)));
                configuration.save(configFile);

                File commandFile = (File) options.valueOf("commands-settings");
                org.bukkit.configuration.file.YamlConfiguration commandsConfiguration = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(commandFile);
                commandsConfiguration.options().copyDefaults(true);
                commandsConfiguration.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Main.class.getClassLoader().getResourceAsStream("configurations/commands.yml"), java.nio.charset.StandardCharsets.UTF_8)));
                commandsConfiguration.save(commandFile);
                // CraftBukkit end
                LOGGER.info("Initialized '{}' and '{}'", settingsFile.toAbsolutePath(), eulaFile.toAbsolutePath());
                return;
            }

            // Paper start - eula system property
            boolean eulaAgreed = Boolean.getBoolean("com.mojang.eula.agree");
            if (eulaAgreed) {
                LOGGER.warn("You have used the Paper command line EULA agreement flag.");
                LOGGER.warn("By using this setting you are indicating your agreement to Mojang's EULA (https://aka.ms/MinecraftEULA).");
                LOGGER.warn("If you do not agree to the above EULA please stop your server and remove this flag immediately.");
            } else if (!eula.hasAgreedToEULA()) {
                // Paper end - eula system property
                LOGGER.info("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
                return;
            }

            // Paper start - Detect headless JRE
            String awtException = io.papermc.paper.util.ServerEnvironment.awtDependencyCheck();
            if (awtException != null) {
                LOGGER.error("You are using a headless JRE distribution.");
                LOGGER.error("This distribution is missing certain graphic libraries that the Minecraft server needs to function.");
                LOGGER.error("For instructions on how to install the non-headless JRE, see https://docs.papermc.io/misc/java-install");
                LOGGER.error("");
                LOGGER.error(awtException);
                return;
            }
            // Paper end - Detect headless JRE

            org.spigotmc.SpigotConfig.disabledAdvancements = spigotConfiguration.getStringList("advancements.disabled"); // Paper - fix SPIGOT-5885, must be set early in init

            // Paper start - fix SPIGOT-5824
            File universePath;
            File userCacheFile = new File(Services.USERID_CACHE_FILE);
            if (options.has("universe")) {
                universePath = (File) options.valueOf("universe"); // CraftBukkit
                userCacheFile = new File(universePath, Services.USERID_CACHE_FILE);
            } else {
                universePath = new File(bukkitConfiguration.getString("settings.world-container", "."));
            }
            // Paper end - fix SPIGOT-5824
            Services services = Services.create(new com.destroystokyo.paper.profile.PaperAuthenticationService(Proxy.NO_PROXY), universePath, userCacheFile, options); // Paper - pass OptionSet to load paper config files; override authentication service; fix world-container
            NotificationManager notificationManager = new NotificationManager();
            ManagementServer jsonRpcServer = JsonRpc.create(settings, notificationManager);
            String levelName = Optional.ofNullable((String) options.valueOf("world")).orElse(settings.getProperties().levelName); // CraftBukkit
            LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(universePath.toPath());
            LevelStorageSource.LevelStorageAccess access = levelStorageSource.validateAndCreateAccess(levelName);
            Dynamic<?> levelDataTag;
            if (access.hasWorldData()) {
                Dynamic<?> levelDataUnfixed;
                try {
                    levelDataUnfixed = access.getUnfixedDataTagWithFallback();
                } catch (IOException | NbtException | ReportedNbtException ex) {
                    LOGGER.error("Failed to load world data. World files may be corrupted. Shutting down.", ex);
                    return;
                }

                LevelSummary summary = access.fixAndGetSummaryFromTag(levelDataUnfixed);
                if (summary.requiresManualConversion()) {
                    LOGGER.info("This world must be opened in an older version (like 1.6.4) to be safely converted");
                    return;
                }

                if (!summary.isCompatible()) {
                    LOGGER.info("This world was created by an incompatible version.");
                    return;
                }

                levelDataTag = DataFixers.getFileFixer().fix(access, levelDataUnfixed, new UpgradeProgress());
            } else {
                levelDataTag = null;
            }
            io.papermc.paper.world.migration.WorldFolderMigration.didInitialLoad = true; // Paper

            boolean safeModeEnabled = options.has("safeMode"); // CraftBukkit
            if (safeModeEnabled) {
                LOGGER.warn("Safe mode active, only vanilla datapack will be loaded");
            }

            PackRepository packRepository = ServerPacksSource.createPackRepository(access);
            // CraftBukkit start
            File bukkitDataPackFolder = new File(access.getLevelPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR).toFile(), "bukkit");
            if (!bukkitDataPackFolder.exists()) {
                bukkitDataPackFolder.mkdirs();
            }
            File mcMeta = new File(bukkitDataPackFolder, "pack.mcmeta");
            try {
                final var major = SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA).major();
                final var minor = SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA).minor();
                com.google.common.io.Files.asCharSink(mcMeta, java.nio.charset.StandardCharsets.UTF_8).write("""
                    {
                        "pack": {
                            "description": "Data pack for resources provided by Bukkit plugins",
                            "min_format": [%d, %d],
                            "max_format": [%d, %d]
                        }
                    }
                    """.formatted(major, minor, major, minor)
                );
            } catch (java.io.IOException ex) {
                throw new RuntimeException("Could not initialize Bukkit datapack", ex);
            }
            java.util.concurrent.atomic.AtomicReference<WorldLoader.DataLoadContext> worldLoader = new java.util.concurrent.atomic.AtomicReference<>();
            // CraftBukkit end

            WorldStem worldStem;
            try {
                WorldLoader.InitConfig worldLoadConfig = loadOrCreateConfig(settings.getProperties(), levelDataTag, safeModeEnabled, packRepository);
                worldStem = Util.<WorldStem>blockUntilDone(
                        executor -> WorldLoader.load(
                            worldLoadConfig,
                            context -> {
                                worldLoader.set(context); // CraftBukkit
                                Registry<LevelStem> datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
                                if (levelDataTag != null) {
                                    // Paper start - migrate startup world
                                    try {
                                        io.papermc.paper.world.migration.WorldFolderMigration.migrateStartupWorld(
                                            access,
                                            context.datapackWorldgen(),
                                            levelName,
                                            LevelStem.OVERWORLD,
                                            Registries.levelStemToLevel(LevelStem.OVERWORLD)
                                        );
                                    } catch (final IOException ex) {
                                        throw new UncheckedIOException("Failed to migrate world storage for " + LevelStem.OVERWORLD.identifier(), ex);
                                    }
                                    // Paper end - migrate startup world
                                    LevelDataAndDimensions worldData = LevelStorageSource.getLevelDataAndDimensions(
                                        access, levelDataTag, context.dataConfiguration(), datapackDimensions, context.datapackWorldgen(), net.minecraft.world.level.Level.OVERWORLD // Paper
                                    );
                                    return new WorldLoader.DataLoadOutput<>(
                                        worldData.worldDataAndGenSettings(), worldData.dimensions().dimensionsRegistryAccess()
                                    );
                                } else {
                                    LOGGER.info("No existing world data, creating new world");
                                    return createNewWorldData(settings, context, datapackDimensions, options.has("demo"), options.has("bonusChest")); // CraftBukkit
                                }
                            },
                            WorldStem::new,
                            Util.backgroundExecutor(),
                            executor
                        )
                    )
                    .get();
            } catch (Exception e) {
                LOGGER.warn(
                    "Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode", e
                );
                return;
            }

            /*
            RegistryAccess.Frozen registryHolder = worldStem.registries().compositeAccess();
            WorldData data = worldStem.worldDataAndGenSettings().data();
            boolean recreateRegionFilesValue = options.has(recreateRegionFiles);
            if (options.has(forceUpgrade) || recreateRegionFilesValue) {
                forceUpgrade(access, DataFixers.getDataFixer(), options.has(eraseCache), () -> true, registryHolder, recreateRegionFilesValue);
            }

            access.saveDataTag(data);
            */
            final DedicatedServer dedicatedServer = MinecraftServer.spin(
                thread -> {
                    DedicatedServer server = new DedicatedServer(
                        options, // CraftBukkit
                        worldLoader.get(), // CraftBukkit
                        thread,
                        access,
                        packRepository,
                        worldStem,
                        Optional.empty(),
                        settings,
                        DataFixers.getDataFixer(),
                        services,
                        jsonRpcServer,
                        notificationManager
                    );
                    notificationManager.setServer(server);
                    /*
                    server.setPort(options.valueOf(port));
                     */
                    // Paper start
                    if (options.has("serverId")) {
                        server.setId((String) options.valueOf("serverId"));
                    }
                    server.setDemo(options.has("demo"));
                    // Paper end
                    /*
                    server.setId(options.valueOf(serverId));
                     */
                    boolean gui = !options.has("nogui") && !options.nonOptionArguments().contains("nogui");
                    if (gui && !GraphicsEnvironment.isHeadless()) {
                        server.showGui();
                    }
                    // Paper start
                    if (options.has("port")) {
                        int port = (Integer) options.valueOf("port");
                        if (port > 0) {
                            server.setPort(port);
                        }
                    }
                    // Paper end

                    return server;
                }
            );
            /* CraftBukkit start
            Thread shutdownThread = new Thread("Server Shutdown Thread") {
                @Override
                public void run() {
                    dedicatedServer.halt(true);
                }
            };
            shutdownThread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
            Runtime.getRuntime().addShutdownHook(shutdownThread);
            */ // CraftBukkit end
        } catch (Throwable t) {
            LOGGER.error(LogUtils.FATAL_MARKER, "Failed to start the minecraft server", t);
        }
    }

    public static WorldLoader.DataLoadOutput<LevelDataAndDimensions.WorldDataAndGenSettings> createNewWorldData( // Paper - public
        final DedicatedServerSettings settings,
        final WorldLoader.DataLoadContext context,
        final Registry<LevelStem> datapackDimensions,
        final boolean demoMode,
        final boolean bonusChest
    ) {
        LevelSettings createLevelSettings;
        WorldOptions worldOptions;
        WorldDimensions dimensions;
        if (demoMode) {
            createLevelSettings = MinecraftServer.DEMO_SETTINGS;
            worldOptions = WorldOptions.DEMO_OPTIONS;
            dimensions = WorldPresets.createNormalWorldDimensions(context.datapackWorldgen());
        } else {
            DedicatedServerProperties properties = settings.getProperties();
            createLevelSettings = new LevelSettings(
                properties.levelName,
                properties.gameMode.get(),
                new LevelSettings.DifficultySettings(properties.difficulty.get(), properties.hardcore, false),
                false,
                context.dataConfiguration()
            );
            worldOptions = bonusChest ? properties.worldOptions.withBonusChest(true) : properties.worldOptions;
            dimensions = properties.createDimensions(context.datapackWorldgen());
        }

        WorldDimensions.Complete finalDimensions = dimensions.bake(datapackDimensions);
        Lifecycle lifecycle = finalDimensions.lifecycle().add(context.datapackWorldgen().allRegistriesLifecycle());
        PrimaryLevelData primaryLevelData = new PrimaryLevelData(createLevelSettings, finalDimensions.specialWorldProperty(), lifecycle);
        return new WorldLoader.DataLoadOutput<>(
            new LevelDataAndDimensions.WorldDataAndGenSettings(primaryLevelData, new WorldGenSettings(worldOptions, dimensions)),
            finalDimensions.dimensionsRegistryAccess()
        );
    }

    private static void writePidFile(final Path path) {
        try {
            long pid = ProcessHandle.current().pid();
            Files.writeString(path, Long.toString(pid));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WorldLoader.InitConfig loadOrCreateConfig(
        final DedicatedServerProperties properties, final @Nullable Dynamic<?> levelDataTag, final boolean safeModeEnabled, final PackRepository packRepository
    ) {
        boolean initMode;
        WorldDataConfiguration dataConfigToUse;
        if (levelDataTag != null) {
            WorldDataConfiguration storedConfiguration = LevelStorageSource.readDataConfig(levelDataTag);
            initMode = false;
            dataConfigToUse = storedConfiguration;
        } else {
            initMode = true;
            dataConfigToUse = new WorldDataConfiguration(properties.initialDataPackConfiguration, FeatureFlags.DEFAULT_FLAGS);
        }

        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packRepository, dataConfigToUse, safeModeEnabled, initMode);
        return new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED, properties.functionPermissions);
    }

    public static void forceUpgrade(
        final LevelStorageSource.LevelStorageAccess storageSource,
        final DataFixer fixerUpper,
        final boolean eraseCache,
        final BooleanSupplier isRunning,
        final RegistryAccess registryAccess,
        final boolean recreateRegionFiles
    ) {
        throw new UnsupportedOperationException(
            "World upgrade and region file recreation are not yet implemented in Paper 26.1."
        );
        /*
        LOGGER.info("Forcing world upgrade! {}", storageSource.getLevelId()); // CraftBukkit

        try (WorldUpgrader upgrader = new WorldUpgrader(storageSource, fixerUpper, registryAccess, eraseCache, recreateRegionFiles)) {
            Component lastStatus = null;

            while (!upgrader.isFinished()) {
                Component status = upgrader.getStatus();
                if (lastStatus != status) {
                    lastStatus = status;
                    LOGGER.info(upgrader.getStatus().getString());
                }

                int totalChunks = upgrader.getTotalChunks();
                if (totalChunks > 0) {
                    int done = upgrader.getConverted() + upgrader.getSkipped();
                    LOGGER.info("{}% completed ({} / {} chunks)...", Mth.floor((float)done / totalChunks * 100.0F), done, totalChunks);
                }

                if (!isRunning.getAsBoolean()) {
                    upgrader.cancel();
                } else {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException var12) {
                    }
                }
            }
        }
        */
    }
}

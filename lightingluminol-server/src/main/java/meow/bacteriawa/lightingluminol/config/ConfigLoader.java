package meow.bacteriawa.lightingluminol.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class ConfigLoader {
    private static final Logger LOGGER = Logger.getLogger(ConfigLoader.class.getName());
    private static final String CONFIG_DIR = "luminol_config";
    private static final String CONFIG_FILE = "luminol_global_config.toml";

    private ConfigLoader() {
    }

    public static void load() {
        Path configDir = Paths.get(CONFIG_DIR);
        Path configPath = configDir.resolve(CONFIG_FILE);
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            LOGGER.info("Luminol config file not found at: " + configFile.getAbsolutePath() + ", creating default config...");
            try {
                Files.createDirectories(configDir);
                createDefaultConfig(configPath);
                LOGGER.info("Successfully created default Luminol config");
            } catch (IOException e) {
                LOGGER.severe("Failed to create default Luminol config: " + e.getMessage());
                return;
            }
        }

        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            parseConfig(lines);
            LOGGER.info("Successfully loaded Luminol config");
        } catch (IOException e) {
            LOGGER.severe("Failed to load Luminol config: " + e.getMessage());
        }

        // LightingLuminol start - SIMD detection
        try {
            gg.pufferfish.pufferfish.simd.SIMDDetection.isEnabled = gg.pufferfish.pufferfish.simd.SIMDDetection.canEnable(com.mojang.logging.LogUtils.getLogger());
        } catch (NoClassDefFoundError | Exception ignored) {
        }
        if (gg.pufferfish.pufferfish.simd.SIMDDetection.isEnabled) {
            LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
        } else if (OptimizationsConfig.UseSimd.enabled) {
            LOGGER.warning("SIMD operations are not available. Add \"--add-modules=jdk.incubator.vector\" to your startup flags.");
        }
        // LightingLuminol end - SIMD detection
    }

    private static void createDefaultConfig(Path configPath) throws IOException {
        String defaultConfig = "[unsupported.folia_scheduler_compatibility]\n" +
                "\t#Override auto detection: plugin names that should always use the real Folia scheduler.\n" +
                "\tforce_folia_scheduler_plugins = []\n" +
                "\t#Automatically choose scheduler by the folia-supported flag parsed from each plugin jar.\n" +
                "\t#Plugins marked as folia-supported use the real Folia scheduler; other plugins use the Bukkit/global compatibility scheduler.\n" +
                "\t#This improves compatibility for some legacy plugins, but it does not make unsafe world/entity access region-thread safe.\n" +
                "\t#\n" +
                "\tenabled = true\n" +
                "\t#Override auto detection: plugin names that should always use the Bukkit/global compatibility scheduler.\n" +
                "\tforce_bukkit_scheduler_plugins = []\n" +
                "\n" +
                "[experiment.disable_entity_exception_catchers]\n" +
                "\t#If this config enabled, the server will crash directly when entity ticking has some errors instead of removing the entity to keep server running.\n" +
                "\t#It could prevent entity disappearing but may cause more server crashes.\n" +
                "\t#DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!\n" +
                "\tenabled = false\n" +
                "\n" +
                "[experiment.command]\n" +
                "\tenable_data_command = false\n" +
                "\t#Force to enable command blocks.\n" +
                "\t#ATTENTION: WOULD CAUSE SERVER CRASHING AS SOME THREADING ISSUE!!!\n" +
                "\t#DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!\n" +
                "\t#\n" +
                "\tenable_command_block = true\n" +
                "\t#Enable waypoint and waypoint command.\n" +
                "\t#WARN: Still under testing\n" +
                "\t#\n" +
                "\tenable_waypoints_and_waypoint_command = false\n" +
                "\t#Only freeze/unfreeze/step/query command is allowed if you enabled it.\n" +
                "\t#WARN: This should disabled in production environment!\n" +
                "\t#\n" +
                "\tenable_tick_command = true\n" +
                "\n" +
                "[experiment.disable_async_catchers]\n" +
                "\t#Disable async catcher to prevent some crashes caused by some plugins which supports folia but has issuable logics.\n" +
                "\t#ATTENTION: Would cause region deadlock when getChunkAt was incorrectly called!\n" +
                "\t#           See: https://github.com/PaperMC/Folia/issues/280 which is resolved in folia(https://github.com/PaperMC/Folia/commit/2e7bc0721af95196c85500c7bb136aeea0bc12ce)\n" +
                "\t#DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!\n" +
                "\t#\n" +
                "\tenabled = false\n" +
                "\n" +
                "[fixes.poi_range_fixes]\n" +
                "\t#Do not compete POI if it's unloaded\n" +
                "\t#Related with https://github.com/PaperMC/Folia/issues/292\n" +
                "\t#\n" +
                "\tdo_not_compete_poi_if_unloaded = false\n" +
                "\n" +
                "[fixes.allow_unsafe_teleportation]\n" +
                "\t#Allow non player entities enter end portals if enabled.\n" +
                "\t#If you want to use sand duping,please turn on this.\n" +
                "\t#Warning: This would cause some unsafe issues, you could learn more on : https://github.com/PaperMC/Folia/issues/297\n" +
                "\tenabled = true\n" +
                "\n" +
                "#This config is a temporary fix for those incorrect owned data in the memory of each mob, for more you can see https://github.com/PaperMC/Folia/issues/203\n" +
                "[fixes.force_cleanup_drop_non_owned_entity_memory_module]\n" +
                "\t#When enabled, the entity's brain will clean the memory which is typed of entity and not belong to current tickregion\n" +
                "\tenabled_for_entity = false\n" +
                "\t#When enabled, the entity's brain will clean the memory which is typed of position_tracker and not belong to current tickregion\n" +
                "\tenabled_for_position_tracker = false\n" +
                "\t#When enabled, the entity's brain will clean the memory which is typed of block_pos and not belong to current tickregion\n" +
                "\tenabled_for_block_pos = false\n" +
                "\n" +
                "[fixes.collision_behavior]\n" +
                "\t#Decides which collision logics will be used(Moonrise and Paper modified this for optimization but would also break some vanilla behaviours at the same time).\n" +
                "\t#Would be useful for fixing improper behaviours of some huge redstone machines\n" +
                "\t#Available Value:\n" +
                "\t#VANILLA\n" +
                "\t#BLOCK_SHAPE_VANILLA\n" +
                "\t#PAPER\n" +
                "\tmode = \"VANILLA\"\n" +
                "\n" +
                "[fixes.prevent_incorrect_teleport_async_calls_during_move_event]\n" +
                "\tthrow_when_caught = true\n" +
                "\t#When enabled, the server would reject some incorrect teleportAsync calls during move events.\n" +
                "\t#And this will reduce the crashes which caused by plugins(Residence etc.)\n" +
                "\t#But you should notice that it might break the compatibility with some plugins.\n" +
                "\tenabled = false\n" +
                "\n" +
                "[fixes.item_multitask]\n" +
                "\t#Prevent the server from interrupting the state of items\n" +
                "\t#during block interactions or hotbar slot changes.\n" +
                "\tenabled = true\n" +
                "\n" +
                "[fixes.pathfinding_fixes]\n" +
                "\t#Recompute path or stop pathfinding when it's touching the blocks out of current tick region\n" +
                "\tbreak_down_pathfinding_when_out_of_region = false\n" +
                "\t#Skip pathfinding target when it's out of current tick region\n" +
                "\tdo_not_pathfind_to_not_owned_targets = false\n" +
                "\n" +
                "[fixes.fix_high_velocity_issue]\n" +
                "\t#A simple fix of an issue on folia\n" +
                "\t#(Sometimes the entity would\n" +
                "\t#have a large moment that cross the\n" +
                "\t#different tick regions, and it would\n" +
                "\t#make the server crashed) but sometimes it might doesn't work\n" +
                "\tenabled = true\n" +
                "\twarn_on_detected = false\n" +
                "\n" +
                "[fixes.use_vanilla_random_source]\n" +
                "\t#Related with RNG cracks\n" +
                "\tenable_for_player_entity = true\n" +
                "\n" +
                "[function.regionbar]\n" +
                "\tformat = \"<gray>Util<yellow>:</yellow> <util> Chunks<yellow>:</yellow> <green><chunks></green> Players<yellow>:</yellow> <green><players></green> Entities<yellow>:</yellow> <green><entities></green>\"\n" +
                "\tenabled = false\n" +
                "\tutil_color_list = [\"GREEN\", \"YELLOW\", \"RED\", \"PURPLE\"]\n" +
                "\t#Available displays: BOSS_BAR, ACTION_BAR, TAB_LIST\n" +
                "\tdisplay = \"BOSS_BAR\"\n" +
                "\tupdate_interval_ticks = 15\n" +
                "\n" +
                "[function.tripwire_dupe]\n" +
                "\tenabled = true\n" +
                "\t#Available Value:\n" +
                "\t#VANILLA20\n" +
                "\t#VANILLA21\n" +
                "\t#MIXED\n" +
                "\tbehavior_mode = \"VANILLA21\"\n" +
                "\n" +
                "[function.portal_rate_limit]\n" +
                "\t#If the fixed limit is not enough for use, you could define your own expression to dynamically limit the\n" +
                "\t#portal rate.\n" +
                "\t#\n" +
                "\t#Available variables(all is of current tickregion): e (ticking_entity_count)\n" +
                "\t#                     c (ticking_chunk_count)\n" +
                "\t#                     p (player_count)\n" +
                "\t#Example: 50 * (1 + sqrt(x/1000) + c/200 + p/5)\n" +
                "\t#\n" +
                "\tmaximum_portal_teleports_per_tick_expression = \"50 * (1 + sqrt(e/1000) + c/200 + p/5)\"\n" +
                "\t#Whether or not to limit the portal rate when entity goes into portals\n" +
                "\tenable = true\n" +
                "\t#Decides how much portal teleportation should be handled within a tick in a single tick region,when exceed,\n" +
                "\t#the portal teleportation will be pushed into the next tick\n" +
                "\t#\n" +
                "\t#Note: set to -1 to use custom expressions\n" +
                "\tmaximum_portal_teleports_per_tick = 200\n" +
                "\n" +
                "[function.membar]\n" +
                "\tformat = \"<gray>Memory usage <yellow>:</yellow> <used>MB<yellow>/</yellow><available>MB\"\n" +
                "\tmemory_color_list = [\"GREEN\", \"YELLOW\", \"RED\", \"PURPLE\"]\n" +
                "\tenabled = false\n" +
                "\t#Available displays: BOSS_BAR, ACTION_BAR, TAB_LIST\n" +
                "\tdisplay = \"BOSS_BAR\"\n" +
                "\tupdate_interval_ticks = 15\n" +
                "\n" +
                "[function.region_format]\n" +
                "\t#Decides the compression level of the region file(Only works for LINEAR_V2 and B_LINEAR)\n" +
                "\tlinear_compression_level = 1\n" +
                "\t#Decides when it will be flushed to the region file when it has been marked to save for n(default is 100) milliseconds(Only works for LINEAR_V2)\n" +
                "\tlinear_io_flush_delay_ms = 100\n" +
                "\t#Decides when it will be flushed to the region file when there has been no write operations for n(default is 3000) milliseconds(Only works for B_LINEAR)\n" +
                "\tblinear_io_flush_delay_ms = 3000\n" +
                "\t#Decides the worker thread count of linear(Only works for LINEAR_V2)\n" +
                "\tlinear_io_thread_count = 6\n" +
                "\t#Decides the worker thread count of buffered linear(Only works for B_LINEAR)\n" +
                "\tblinear_io_thread_count = 6\n" +
                "\t#Available choices: MCA, B_LINEAR, LINEAR_V2\n" +
                "\tformat = \"MCA\"\n" +
                "\t#Decides if it could use virtual threads for linear format(Only works for LINEAR_V2)\n" +
                "\tlinear_use_virtual_thread = true\n" +
                "\n" +
                "[function.tpsbar]\n" +
                "\tping_color_list = [\"GREEN\", \"YELLOW\", \"RED\", \"PURPLE\"]\n" +
                "\t#Example(if mspt is 20.00000000)(value -> result): 2 -> 20.00, 1 -> 20.0\n" +
                "\tprecision_of_mspt_value = 2\n" +
                "\t#Example(if tps is 20.00000000)(value -> result): 2 -> 20.00, 1 -> 20.0\n" +
                "\tprecision_of_tps_value = 2\n" +
                "\tchunkhot_color_list = [\"GREEN\", \"YELLOW\", \"RED\", \"PURPLE\"]\n" +
                "\t#Available displays: BOSS_BAR, ACTION_BAR, TAB_LIST\n" +
                "\tdisplay = \"BOSS_BAR\"\n" +
                "\tformat = \"<gray>TPS<yellow>:</yellow> <tps> MSPT<yellow>:</yellow> <mspt> Ping<yellow>:</yellow> <ping>ms ChunkHot<yellow>:</yellow> <chunkhot>\"\n" +
                "\ttps_color_list = [\"GREEN\", \"YELLOW\", \"RED\", \"PURPLE\"]\n" +
                "\tenabled = false\n" +
                "\tupdate_interval_ticks = 15\n" +
                "\n" +
                "[function.secure_seed]\n" +
                "\t#Version 1: Blake2b (insecure, reversible with a GPU/ASIC cluster in minutes with enough entropy)\n" +
                "\t#Version 2: Blake3 with salt key derivation (recommended, irreversible)\n" +
                "\t#***** WARN: Switching versions will cause chunk errors! *****\n" +
                "\tversion = 1\n" +
                "\t#         Once you enable secure seed, all ores and structures are generated with 1024-bit seed\n" +
                "\t#         instead of using 64-bit seed in vanilla, making traditional seed cracking impossible.\n" +
                "\t#Note: If you use V1 it will be vulnerable to terrain elevation attacks.\n" +
                "\t#         ***** WARN: You need keep it enabled if your old world are also using secure seed! Or it will kill your save *****\n" +
                "\tenabled = false\n" +
                "\t#Auto-generated 256-bit salt for V2 cryptographic operations.\n" +
                "\t#Generated once on first startup - DO NOT SHARE THIS OR MODIFY (MODIFYING THIS WILL CAUSE CHUNK ERRORS)!\n" +
                "\t#Used with Blake3 keyed hash to make seed irreversible.\n" +
                "\tsalt = \"iq7baabmIDSxYEDskJEl8on3TkabfGrcgUGwXnik7vA=\"\n" +
                "\n" +
                "[optimizations.cpu_affinity]\n" +
                "\t#Using this you could pin the threads of tick region scheduler to cpu cores listed in the config 'tickregion_affinity' following,\n" +
                "\t#which is useful for those CPU with P and E cores (such as 12/13/14 gen Intel Core CPUs and so on.)\n" +
                "\tenabled = false\n" +
                "\t#The core number you want the tick region threads to bind on\n" +
                "\ttickregion_affinity = [\"0\", \"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\", \"9\", \"10\", \"11\", \"12\", \"13\", \"14\", \"15\", \"16\", \"17\", \"18\", \"19\", \"20\", \"21\", \"22\", \"23\", \"24\", \"25\", \"26\", \"27\", \"28\", \"29\", \"30\", \"31\"]\n" +
                "\n" +
                "#Throttles the AI goal selector in entity inactive ticks.\n" +
                "#This can improve performance by a few percent, but has minor gameplay implications.\n" +
                "[optimizations.throttle_goal_selector_tick_in_inactive_tick]\n" +
                "\tenabled = false\n" +
                "\n" +
                "[optimizations.use_simd]\n" +
                "\tenabled = true\n" +
                "\n" +
                "#Lobotomizes the villager if it cannot move (Does not disable trading)\n" +
                "[optimizations.lobotomize_villager]\n" +
                "\t#The interval in ticks to check if a villager is lobotomized\n" +
                "\tcheck_interval = 100\n" +
                "\t#Wait until a villager has been traded with before lobotomizing\n" +
                "\twait_until_trade_locked = false\n" +
                "\tenabled = false\n" +
                "\n" +
                "[optimizations.use_async_protocol_switching]\n" +
                "\t#Uses async protocol preparation for mc.\n" +
                "\t#Warn: Due to the packet sequence was changed by this optimization, it might be\n" +
                "\t# uncompatible with some plugins(ViaVersion etc.)\n" +
                "\tenabled = false\n" +
                "\n" +
                "[optimizations.lithium_sleeping_block_entity]\n" +
                "\t#Use sleeping blocking optimizations from lithium,\n" +
                "\t# on luminol the hopper optimizations of paper were totally removed and replaced by those of lithium\n" +
                "\t#and it's turned on by default\n" +
                "\tenabled = true\n" +
                "\n" +
                "[optimizations.end_dragon]\n" +
                "\toptimized_dragon_respawn = false\n" +
                "\n" +
                "[optimizations.variable_entity_waking_up]\n" +
                "\t#If this value is set to any value > 0, waking up inactive entities happens spread over time, instead of many entities at once. This makes entities feel and behave more natural.\n" +
                "\t#This setting is the coefficient of variation, or σ / μ (the ratio of the standard deviation to the mean) of the inactivity duration.\n" +
                "\t#\n" +
                "\t#In other words, this setting is the value σ, so that the regular inactivity duration will be multiplied by a factor normal_distribution(μ = 1, σ).\n" +
                "\t#If a value ≤ 0 is given, variable entity wake-up is disabled.\n" +
                "\tentity_wakeup_duration_ratio_standard_deviation = 0.2\n" +
                "\n" +
                "[optimizations.projectile]\n" +
                "\t#Controls how many chunks a projectile can load in its lifetime before it gets automatically removed.\n" +
                "\tmax-loads-per-projectile = 0\n" +
                "\t#Controls how many chunks are allowed to be sync loaded by projectiles in a tick.\n" +
                "\tmax-loads-per-tick = 0\n" +
                "\n" +
                "#When it is enabled, it will delete the line of sight cache less often and use a faster nearby comparison.\n" +
                "[optimizations.reduce_sensor_work]\n" +
                "\t#The interval of each entity to drop the cache(in ticks)\n" +
                "\tdelay_ticks = 10\n" +
                "\tenabled = true\n" +
                "\n" +
                "[misc.disable_warning]\n" +
                "\t#Disable heightmap-check's warning\n" +
                "\tdisable_heightmap_warning = false\n" +
                "\t#Disable offline warns popped in the log when starting the server\n" +
                "\tdisable_offline_mode_warning = false\n" +
                "\t#Disable wrongly move warns and checks\n" +
                "\tdisable_moved_wrongly_threshold_warning = false\n" +
                "\n" +
                "[misc.server_mod_name]\n" +
                "\t#Decides the server mod name shown in your F3 debug screen.\n" +
                "\tname = \"LightingLuminol\"\n" +
                "\t#Ignore any plugin's modification and server mod name set in this config block, only force sending brand name of vanilla\n" +
                "\tvanilla_spoof = false\n" +
                "\n" +
                "#Checks GitHub Releases for newer LightingLuminol jars on a schedule.\n" +
                "#Downloads are staged under auto_update/lightingluminol and written to auto_update/core.path,\n" +
                "#which Hyacinthusclip can consume on the next restart.\n" +
                "#If target_jar_path is set, Luminol will also try to replace that launcher jar directly.\n" +
                "[misc.auto_update]\n" +
                "\t#Optional launcher jar path to replace after a successful download.\n" +
                "\t#Leave this blank to keep the downloaded jar staged in auto_update/lightingluminol\n" +
                "\t#and let Hyacinthusclip switch to it through auto_update/core.path on restart.\n" +
                "\ttarget_jar_path = \"\"\n" +
                "\t#Whether prerelease GitHub releases are allowed when selecting an update.\n" +
                "\tallow_prerelease = false\n" +
                "\t#List of daily check times in HH:mm, based on the server's local time zone.\n" +
                "\tcheck_times = [\"06:00\"]\n" +
                "\t#Whether LightingLuminol should check for updates automatically.\n" +
                "\tenabled = false\n" +
                "\n" +
                "[misc.username_checks]\n" +
                "\t#Decide whether the username checks are enabled,\n" +
                "\t# you could disable it if your players are using Chinese username but also notification any security impacts caused by disabling it\n" +
                "\tenabled = true\n" +
                "\n" +
                "[misc.folia_watchdog]\n" +
                "\t#Decides the interval of the watchdog prints the threads dumps of tickregions in stuck\n" +
                "\ttick_region_time_out_ms = 5000\n" +
                "\n" +
                "[misc.save_portal_tickets]\n" +
                "\t#whether or not to save the portal tickets when server stopping, this would make it acts like mc before 1.21.5, and won't auto active the portal chunk loader when server started again.\n" +
                "\tdo_save = true\n" +
                "\n" +
                "[misc.mojang_out_of_order_chat_check]\n" +
                "\tenabled = true\n" +
                "\n" +
                "#Force and fully disable all packet limiters of Paper, which is used to prevent from kicking by using some quick crafting mods but\n" +
                "#has negative impacts on security\n" +
                "[misc.force_disable_packet_limiter_of_paper]\n" +
                "\tforce_disable = false\n" +
                "\n" +
                "#Only verify the public key in online mode, could be useful when using plugins like MultiLogin with custom auth server configured\n" +
                "[misc.verify_publickey_only_in_online_mode]\n" +
                "\tenabled = false\n" +
                "\n" +
                "[misc.sentry]\n" +
                "\t# Logs with a level higher than or equal to this level will be recorded.\n" +
                "\tlog_level = \"WARN\"\n" +
                "\t# Only log with a Throwable will be recorded after enabling this.\n" +
                "\tonly_log_thrown = true\n" +
                "\tdsn = \"\"";

        Files.writeString(configPath, defaultConfig, StandardCharsets.UTF_8);
    }

    private static void parseConfig(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        String currentSection = "";

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1);
                continue;
            }

            int equalsIndex = line.indexOf('=');
            if (equalsIndex > 0) {
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                String fullKey = currentSection.isEmpty() ? key : currentSection + "." + key;
                values.put(fullKey, value);
            }
        }

        FoliaSchedulerCompatibilityConfig.enabled = parseBoolean(values.get("unsupported.folia_scheduler_compatibility.enabled"), true);
        FoliaSchedulerCompatibilityConfig.forceFoliaSchedulerPlugins = parseList(values.get("unsupported.folia_scheduler_compatibility.force_folia_scheduler_plugins"));
        FoliaSchedulerCompatibilityConfig.forceBukkitSchedulerPlugins = parseList(values.get("unsupported.folia_scheduler_compatibility.force_bukkit_scheduler_plugins"));

        ExperimentConfig.DisableEntityExceptionCatchers.enabled = parseBoolean(values.get("experiment.disable_entity_exception_catchers.enabled"), false);
        ExperimentConfig.Command.enableDataCommand = parseBoolean(values.get("experiment.command.enable_data_command"), false);
        ExperimentConfig.Command.enableCommandBlock = parseBoolean(values.get("experiment.command.enable_command_block"), true);
        ExperimentConfig.Command.enableWaypointsAndWaypointCommand = parseBoolean(values.get("experiment.command.enable_waypoints_and_waypoint_command"), false);
        ExperimentConfig.Command.enableTickCommand = parseBoolean(values.get("experiment.command.enable_tick_command"), true);
        ExperimentConfig.DisableAsyncCatchers.enabled = parseBoolean(values.get("experiment.disable_async_catchers.enabled"), false);

        FixesConfig.PoiRangeFixes.doNotCompetePoiIfUnloaded = parseBoolean(values.get("fixes.poi_range_fixes.do_not_compete_poi_if_unloaded"), false);
        FixesConfig.AllowUnsafeTeleportation.enabled = parseBoolean(values.get("fixes.allow_unsafe_teleportation.enabled"), true);
        FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForEntity = parseBoolean(values.get("fixes.force_cleanup_drop_non_owned_entity_memory_module.enabled_for_entity"), false);
        FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForPositionTracker = parseBoolean(values.get("fixes.force_cleanup_drop_non_owned_entity_memory_module.enabled_for_position_tracker"), false);
        FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForBlockPos = parseBoolean(values.get("fixes.force_cleanup_drop_non_owned_entity_memory_module.enabled_for_block_pos"), false);
        FixesConfig.CollisionBehavior.mode = parseString(values.get("fixes.collision_behavior.mode"), "VANILLA");
        FixesConfig.PreventIncorrectTeleportAsyncCallsDuringMoveEvent.throwWhenCaught = parseBoolean(values.get("fixes.prevent_incorrect_teleport_async_calls_during_move_event.throw_when_caught"), true);
        FixesConfig.PreventIncorrectTeleportAsyncCallsDuringMoveEvent.enabled = parseBoolean(values.get("fixes.prevent_incorrect_teleport_async_calls_during_move_event.enabled"), false);
        FixesConfig.ItemMultitask.enabled = parseBoolean(values.get("fixes.item_multitask.enabled"), true);
        FixesConfig.PathfindingFixes.breakDownPathfindingWhenOutOfRegion = parseBoolean(values.get("fixes.pathfinding_fixes.break_down_pathfinding_when_out_of_region"), false);
        FixesConfig.PathfindingFixes.doNotPathfindToNotOwnedTargets = parseBoolean(values.get("fixes.pathfinding_fixes.do_not_pathfind_to_not_owned_targets"), false);
        FixesConfig.FixHighVelocityIssue.enabled = parseBoolean(values.get("fixes.fix_high_velocity_issue.enabled"), true);
        FixesConfig.FixHighVelocityIssue.warnOnDetected = parseBoolean(values.get("fixes.fix_high_velocity_issue.warn_on_detected"), false);
        FixesConfig.UseVanillaRandomSource.enabled = parseBoolean(values.get("fixes.use_vanilla_random_source.enabled"), false);

        FunctionConfig.Regionbar.format = parseString(values.get("function.regionbar.format"), "<gray>Util<yellow>:</yellow> <util> Chunks<yellow>:</yellow> <green><chunks></green> Players<yellow>:</yellow> <green><players></green> Entities<yellow>:</yellow> <green><entities></green>");
        FunctionConfig.Regionbar.enabled = parseBoolean(values.get("function.regionbar.enabled"), false);
        FunctionConfig.Regionbar.utilColorList = parseList(values.get("function.regionbar.util_color_list"));
        FunctionConfig.Regionbar.display = parseString(values.get("function.regionbar.display"), "BOSS_BAR");
        FunctionConfig.Regionbar.updateIntervalTicks = parseInt(values.get("function.regionbar.update_interval_ticks"), 15);
        FunctionConfig.TripwireDupe.enabled = parseBoolean(values.get("function.tripwire_dupe.enabled"), true);
        FunctionConfig.TripwireDupe.behaviorMode = parseString(values.get("function.tripwire_dupe.behavior_mode"), "VANILLA21");
        FunctionConfig.PortalRateLimit.maximumPortalTeleportsPerTickExpression = parseString(values.get("function.portal_rate_limit.maximum_portal_teleports_per_tick_expression"), "50 * (1 + sqrt(e/1000) + c/200 + p/5)");
        FunctionConfig.PortalRateLimit.enable = parseBoolean(values.get("function.portal_rate_limit.enable"), true);
        FunctionConfig.PortalRateLimit.maximumPortalTeleportsPerTick = parseInt(values.get("function.portal_rate_limit.maximum_portal_teleports_per_tick"), 200);
        FunctionConfig.Membar.format = parseString(values.get("function.membar.format"), "<gray>Memory usage <yellow>:</yellow> <used>MB<yellow>/</yellow><available>MB");
        FunctionConfig.Membar.memoryColorList = parseList(values.get("function.membar.memory_color_list"));
        FunctionConfig.Membar.enabled = parseBoolean(values.get("function.membar.enabled"), false);
        FunctionConfig.Membar.display = parseString(values.get("function.membar.display"), "BOSS_BAR");
        FunctionConfig.Membar.updateIntervalTicks = parseInt(values.get("function.membar.update_interval_ticks"), 15);
        FunctionConfig.RegionFormat.linearCompressionLevel = parseInt(values.get("function.region_format.linear_compression_level"), 1);
        FunctionConfig.RegionFormat.linearIoFlushDelayMs = parseInt(values.get("function.region_format.linear_io_flush_delay_ms"), 100);
        FunctionConfig.RegionFormat.blinearIoFlushDelayMs = parseInt(values.get("function.region_format.blinear_io_flush_delay_ms"), 3000);
        FunctionConfig.RegionFormat.linearIoThreadCount = parseInt(values.get("function.region_format.linear_io_thread_count"), 6);
        FunctionConfig.RegionFormat.blinearIoThreadCount = parseInt(values.get("function.region_format.blinear_io_thread_count"), 6);
        FunctionConfig.RegionFormat.format = parseString(values.get("function.region_format.format"), "MCA");
        FunctionConfig.RegionFormat.linearUseVirtualThread = parseBoolean(values.get("function.region_format.linear_use_virtual_thread"), true);
        FunctionConfig.Tpsbar.pingColorList = parseList(values.get("function.tpsbar.ping_color_list"));
        FunctionConfig.Tpsbar.precisionOfMsptValue = parseInt(values.get("function.tpsbar.precision_of_mspt_value"), 2);
        FunctionConfig.Tpsbar.precisionOfTpsValue = parseInt(values.get("function.tpsbar.precision_of_tps_value"), 2);
        FunctionConfig.Tpsbar.chunkhotColorList = parseList(values.get("function.tpsbar.chunkhot_color_list"));
        FunctionConfig.Tpsbar.display = parseString(values.get("function.tpsbar.display"), "BOSS_BAR");
        FunctionConfig.Tpsbar.format = parseString(values.get("function.tpsbar.format"), "<gray>TPS<yellow>:</yellow> <tps> MSPT<yellow>:</yellow> <mspt> Ping<yellow>:</yellow> <ping>ms ChunkHot<yellow>:</yellow> <chunkhot>");
        FunctionConfig.Tpsbar.tpsColorList = parseList(values.get("function.tpsbar.tps_color_list"));
        FunctionConfig.Tpsbar.enabled = parseBoolean(values.get("function.tpsbar.enabled"), false);
        FunctionConfig.Tpsbar.updateIntervalTicks = parseInt(values.get("function.tpsbar.update_interval_ticks"), 15);
        FunctionConfig.SecureSeed.version = parseInt(values.get("function.secure_seed.version"), 1);
        FunctionConfig.SecureSeed.enabled = parseBoolean(values.get("function.secure_seed.enabled"), false);
        FunctionConfig.SecureSeed.salt = parseString(values.get("function.secure_seed.salt"), "iq7baabmIDSxYEDskJEl8on3TkabfGrcgUGwXnik7vA=");

        OptimizationsConfig.CpuAffinity.enabled = parseBoolean(values.get("optimizations.cpu_affinity.enabled"), false);
        OptimizationsConfig.CpuAffinity.tickregionAffinity = parseList(values.get("optimizations.cpu_affinity.tickregion_affinity"));
        OptimizationsConfig.ThrottleGoalSelectorTickInInactiveTick.enabled = parseBoolean(values.get("optimizations.throttle_goal_selector_tick_in_inactive_tick.enabled"), false);
        OptimizationsConfig.UseSimd.enabled = parseBoolean(values.get("optimizations.use_simd.enabled"), true);
        OptimizationsConfig.LobotomizeVillager.checkInterval = parseInt(values.get("optimizations.lobotomize_villager.check_interval"), 100);
        OptimizationsConfig.LobotomizeVillager.waitUntilTradeLocked = parseBoolean(values.get("optimizations.lobotomize_villager.wait_until_trade_locked"), false);
        OptimizationsConfig.LobotomizeVillager.enabled = parseBoolean(values.get("optimizations.lobotomize_villager.enabled"), false);
        OptimizationsConfig.UseAsyncProtocolSwitching.enabled = parseBoolean(values.get("optimizations.use_async_protocol_switching.enabled"), false);
        OptimizationsConfig.LithiumSleepingBlockEntity.enabled = parseBoolean(values.get("optimizations.lithium_sleeping_block_entity.enabled"), true);
        OptimizationsConfig.EndDragon.optimizedDragonRespawn = parseBoolean(values.get("optimizations.end_dragon.optimized_dragon_respawn"), false);
        OptimizationsConfig.VariableEntityWakingUp.entityWakeupDurationRatioStandardDeviation = parseDouble(values.get("optimizations.variable_entity_waking_up.entity_wakeup_duration_ratio_standard_deviation"), 0.2);
        OptimizationsConfig.Projectile.maxLoadsPerProjectile = parseInt(values.get("optimizations.projectile.max-loads-per-projectile"), 0);
        OptimizationsConfig.Projectile.maxLoadsPerTick = parseInt(values.get("optimizations.projectile.max-loads-per-tick"), 0);
        OptimizationsConfig.ReduceSensorWork.delayTicks = parseInt(values.get("optimizations.reduce_sensor_work.delay_ticks"), 10);
        OptimizationsConfig.ReduceSensorWork.enabled = parseBoolean(values.get("optimizations.reduce_sensor_work.enabled"), true);

        MiscConfig.DisableWarning.disableHeightmapWarning = parseBoolean(values.get("misc.disable_warning.disable_heightmap_warning"), false);
        MiscConfig.DisableWarning.disableOfflineModeWarning = parseBoolean(values.get("misc.disable_warning.disable_offline_mode_warning"), false);
        MiscConfig.DisableWarning.disableMovedWronglyThresholdWarning = parseBoolean(values.get("misc.disable_warning.disable_moved_wrongly_threshold_warning"), false);

        ServerModNameConfig.serverModName = parseString(values.get("misc.server_mod_name.name"), "LightingLuminol");
        ServerModNameConfig.fakeVanilla = parseBoolean(values.get("misc.server_mod_name.vanilla_spoof"), false);

        LOGGER.info("ServerModNameConfig - serverModName: '" + ServerModNameConfig.serverModName + "', fakeVanilla: " + ServerModNameConfig.fakeVanilla);
    }

    private static String parseString(String value, String defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        } else if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim().toLowerCase());
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static List<String> parseList(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return result;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return result;
        }

        String content = trimmed.substring(1, trimmed.length() - 1);
        String[] items = content.split(",");
        for (String item : items) {
            String trimmedItem = item.trim();
            if (!trimmedItem.isEmpty()) {
                if (trimmedItem.startsWith("\"") && trimmedItem.endsWith("\"")) {
                    trimmedItem = trimmedItem.substring(1, trimmedItem.length() - 1);
                } else if (trimmedItem.startsWith("'") && trimmedItem.endsWith("'")) {
                    trimmedItem = trimmedItem.substring(1, trimmedItem.length() - 1);
                }
                result.add(trimmedItem);
            }
        }
        return result;
    }
}
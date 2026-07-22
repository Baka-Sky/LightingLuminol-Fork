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
        Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            LOGGER.info("Luminol config file not found at: " + configFile.getAbsolutePath());
            return;
        }

        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            parseConfig(lines);
            LOGGER.info("Successfully loaded Luminol config");
        } catch (IOException e) {
            LOGGER.severe("Failed to load Luminol config: " + e.getMessage());
        }
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
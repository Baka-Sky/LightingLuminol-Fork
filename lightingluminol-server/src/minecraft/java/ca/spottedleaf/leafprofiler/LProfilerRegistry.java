package ca.spottedleaf.leafprofiler;

import ca.spottedleaf.profiler.ProfilerRegistry;
import ca.spottedleaf.profiler.ProfilerRegistry.ProfileType;

public final class LProfilerRegistry {

    public static final ProfilerRegistry GLOBAL_REGISTRY = new ProfilerRegistry();
    public static final int TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Full Tick");
    public static final int IN_BETWEEN_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "In Between Tick");
    public static final int INTERNAL_TICK_TASKS = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Internal Tick Tasks");
    public static final int PLAYER_PACKET_PROCESSING = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Player Packet Processing");
    public static final int PLUGIN_TICK_TASKS = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Plugin Tick Tasks");
    public static final int ENTITY_SCHEDULER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Entity Scheduler Tick");
    public static final int ENTITY_SCHEDULERS_TICKED = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Entity Schedulers Ticked");
    public static final int CONNECTION_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Connection Tick");
    public static final int AUTOSAVE = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Autosave");
    public static final int PLAYER_SAVE = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Player Save");
    public static final int CHUNK_SAVE = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Chunk Save");
    public static final int BLOCK_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Block Tick");
    public static final int FLUID_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Fluid Tick");
    public static final int BLOCK_OR_FLUID_TICK_COUNT = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Block/Fluid Tick Count");
    public static final int RAIDS_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Raids Tick");
    public static final int CHUNK_PROVIDER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Chunk Source Tick");
    public static final int CHUNK_HOLDER_MANAGER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Chunk Holder Manager Tick");
    public static final int TICKET_LEVEL_UPDATE_PROCESSING = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Ticket Level Update Processing");
    public static final int PLAYER_CHUNK_LOADER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Player Chunk Loader Tick");
    public static final int CHUNK_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Chunk Ticks");
    public static final int CHUNK_SPAWN_COLLECT_CHUNKS = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Collect Spawning Chunks");
    public static final int MOB_SPAWN_ENTITY_COUNT = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Mob Spawn Entity Count");
    public static final int SPAWN_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Spawn Entities");
    public static final int RANDOM_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Random Tick");
    public static final int SPAWN_CHUNK_COUNT = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Entity Spawn Chunk Count");
    public static final int RANDOM_CHUNK_TICK_COUNT = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Random Chunk Tick Count");
    public static final int MISC_MOB_SPAWN_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Misc Mob Spawn Tick");
    public static final int BROADCAST_BLOCK_CHANGES = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Broadcast Block Changes");
    public static final int ENTITY_TRACKER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Entity Tracker Tick");
    public static final int TRACKED_ENTITY_COUNTS = GLOBAL_REGISTRY.createType(ProfileType.COUNTER, "Total Tracked Entities");
    public static final int POI_MANAGER_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "POI Manager Tick");
    public static final int PROCESS_UNLOADS = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Process Unloads");
    public static final int BLOCK_EVENT_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Block Event Tick");
    public static final int ACTIVATE_ENTITIES = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Activate Entities");
    public static final int ENTITY_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Entity Tick");
    public static final int DRAGON_FIGHT_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Dragon Fight Tick");
    public static final int TILE_ENTITY = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Tile Entities");
    public static final int TILE_ENTITY_PENDING = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Tile Entity Handle Pending");
    public static final int TILE_ENTITY_TICK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Tile Entity Tick");
    public static final int PASSENGER_DESYNC_CHECK = GLOBAL_REGISTRY.createType(ProfileType.TIMER, "Check Passenger Desync"); // Folia - fix passenger desync
}

package net.minecraft.world.entity.npc.wanderingtrader;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.Nullable;

public class WanderingTraderSpawner implements CustomSpawner {
    private static final int DEFAULT_TICK_DELAY = 1200;
    public static final int DEFAULT_SPAWN_DELAY = 24000;
    public static final int MIN_SPAWN_CHANCE = 25;
    private static final int MAX_SPAWN_CHANCE = 75;
    private static final int SPAWN_CHANCE_INCREASE = 25;
    private static final int SPAWN_ONE_IN_X_CHANCE = 10;
    private static final int NUMBER_OF_SPAWN_ATTEMPTS = 10;
    private final RandomSource random = RandomSource.create();
    private final SavedDataStorage savedDataStorage;
    // Folia - moved to global data

    public WanderingTraderSpawner(final SavedDataStorage savedDataStorage) {
        this.savedDataStorage = savedDataStorage;
        // Folia - moved to global data
    }

    @Override
    public void tick(final ServerLevel level, final boolean spawnEnemies) {
        WanderingTraderData data = this.getTraderData(); // Folia - moved up
        // Paper start - Add Wandering Trader spawn rate config options
        if (data.tickDelay == Integer.MIN_VALUE) { // Folia - moved to WanderingTraderData
            data.tickDelay = level.paperConfig().entities.spawning.wanderingTrader.spawnMinuteLength; // Folia - moved to WanderingTraderData
            // Folia - move up
            data.setSpawnDelay(level.paperConfig().entities.spawning.wanderingTrader.spawnDayLength);
            data.setSpawnChance(level.paperConfig().entities.spawning.wanderingTrader.spawnChanceMin);
        }
        if (level.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
            if (data.tickDelay - 1 <= 0) { // Paper - Prevent tickDelay going below 0 // Folia - moved to WanderingTraderData
                data.tickDelay = level.paperConfig().entities.spawning.wanderingTrader.spawnMinuteLength; // Folia - moved to WanderingTraderData
                // Folia - move up
                int spawnDelay = data.spawnDelay() - level.paperConfig().entities.spawning.wanderingTrader.spawnMinuteLength;
                data.setSpawnDelay(spawnDelay);
                if (spawnDelay <= 0) {
                    data.setSpawnDelay(level.paperConfig().entities.spawning.wanderingTrader.spawnDayLength);
                    int chanceToSpawn = data.spawnChance();
                    int newSpawnChance = Mth.clamp(chanceToSpawn + level.paperConfig().entities.spawning.wanderingTrader.spawnChanceFailureIncrement, level.paperConfig().entities.spawning.wanderingTrader.spawnChanceMin, level.paperConfig().entities.spawning.wanderingTrader.spawnChanceMax);
                    data.setSpawnChance(newSpawnChance);
                    if (this.random.nextInt(100) <= chanceToSpawn) {
                        if (this.spawn(level)) {
                            data.setSpawnChance(level.paperConfig().entities.spawning.wanderingTrader.spawnChanceMin);
                            // Paper end - Add Wandering Trader spawn rate config options
                        }
                    }
                }
            } else { data.tickDelay--; } // Paper - Prevent tickDelay going below 0 // Folia - moved to WanderingTraderData
        }
    }

    private WanderingTraderData getTraderData() {
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - region threading
        if (worldData.wanderingTraderData == null) { // Folia - region threading
            worldData.wanderingTraderData = new net.minecraft.world.level.saveddata.WanderingTraderData(); // Folia - region threading
        }

        return worldData.wanderingTraderData; // Folia - region threading
    }

    private boolean spawn(final ServerLevel level) {
        Player player = level.getRandomLocalPlayer(); // Folia - region threading
        if (player == null) {
            return true;
        }

        if (this.random.nextInt(10) != 0) {
            return false;
        }

        BlockPos playerPos = player.blockPosition();
        int radius = 48;
        PoiManager poiManager = level.getPoiManager();
        Optional<BlockPos> poiPos = poiManager.find(p -> p.is(PoiTypes.MEETING), p -> true, playerPos, 48, PoiManager.Occupancy.ANY);
        BlockPos referencePos = poiPos.orElse(playerPos);
        BlockPos spawnPosition = this.findSpawnPositionNear(level, referencePos, 48);
        if (spawnPosition != null && this.hasEnoughSpace(level, spawnPosition)) {
            if (level.getBiome(spawnPosition).is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
                return false;
            }

            WanderingTrader trader = EntityTypes.WANDERING_TRADER.spawn(level, wanderingTrader -> wanderingTrader.setDespawnDelay(48000), spawnPosition, EntitySpawnReason.EVENT, false, false, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL); // CraftBukkit // Paper - set despawnTimer before spawn events called
            if (trader != null) {
                for (int i = 0; i < 2; i++) {
                    this.tryToSpawnLlamaFor(level, trader, 4);
                }

                // trader.setDespawnDelay(48000); // Paper - moved above, modifiable by plugins on CreatureSpawnEvent
                trader.setWanderTarget(referencePos);
                trader.setHomeTo(referencePos, 16);
                return true;
            }
        }

        return false;
    }

    private void tryToSpawnLlamaFor(final ServerLevel level, final WanderingTrader trader, final int radius) {
        BlockPos spawnPosition = this.findSpawnPositionNear(level, trader.blockPosition(), radius);
        if (spawnPosition != null) {
            TraderLlama llama = EntityTypes.TRADER_LLAMA.spawn(level, spawnPosition, EntitySpawnReason.EVENT, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL); // CraftBukkit
            if (llama != null) {
                llama.setLeashedTo(trader, true);
            }
        }
    }

    private @Nullable BlockPos findSpawnPositionNear(final LevelReader level, final BlockPos referencePosition, final int radius) {
        BlockPos spawnPosition = null;
        SpawnPlacementType wanderingTraderSpawnType = SpawnPlacements.getPlacementType(EntityTypes.WANDERING_TRADER);

        for (int i = 0; i < 10; i++) {
            int xPosition = referencePosition.getX() + this.random.nextInt(radius * 2) - radius;
            int zPosition = referencePosition.getZ() + this.random.nextInt(radius * 2) - radius;
            int yPosition = level.getHeight(SpawnPlacements.getHeightmapType(EntityTypes.WANDERING_TRADER), xPosition, zPosition);
            BlockPos spawnPos = new BlockPos(xPosition, yPosition, zPosition);
            if (wanderingTraderSpawnType.isSpawnPositionOk(level, spawnPos, EntityTypes.WANDERING_TRADER)) {
                spawnPosition = spawnPos;
                break;
            }
        }

        return spawnPosition;
    }

    private boolean hasEnoughSpace(final BlockGetter level, final BlockPos spawnPos) {
        for (BlockPos pos : BlockPos.betweenClosed(spawnPos, spawnPos.offset(1, 2, 1))) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }

        return true;
    }
}

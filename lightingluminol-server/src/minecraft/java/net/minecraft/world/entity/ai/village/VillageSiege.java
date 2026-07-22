package net.minecraft.world.entity.ai.village;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class VillageSiege implements CustomSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Folia - region threading

    @Override
    public void tick(final ServerLevel level, final boolean spawnEnemies) {
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        // Folia start - region threading
        // check if the spawn pos is no longer owned by this region
        if (worldData.villageSiegeState.siegeState != VillageSiege.State.SIEGE_DONE
                && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, worldData.villageSiegeState.spawnX >> 4, worldData.villageSiegeState.spawnZ >> 4, 8)) {
            // can't spawn here, just re-set
            worldData.villageSiegeState = new io.papermc.paper.threadedregions.RegionizedWorldData.VillageSiegeState();
        }
        // Folia end - region threading
        if (!level.isBrightOutside() && spawnEnemies) {
            Optional<Holder<WorldClock>> defaultClock = level.dimensionType().defaultClock();
            if (defaultClock.isPresent() && level.clockManager().isAtTimeMarker(defaultClock.get(), ClockTimeMarkers.ROLL_VILLAGE_SIEGE)) {
                worldData.villageSiegeState.siegeState = level.getRandom().nextInt(10) == 0 ? VillageSiege.State.SIEGE_TONIGHT : VillageSiege.State.SIEGE_DONE; // Folia - region threading
            }

            if (worldData.villageSiegeState.siegeState != VillageSiege.State.SIEGE_DONE) { // Folia - region threading
                if (!worldData.villageSiegeState.hasSetupSiege) { // Folia - region threading
                    if (!this.tryToSetupSiege(level)) {
                        return;
                    }

                    worldData.villageSiegeState.hasSetupSiege = true; // Folia - region threading
                }

                if (worldData.villageSiegeState.nextSpawnTime > 0) { // Folia - region threading
                    worldData.villageSiegeState.nextSpawnTime--; // Folia - region threading
                } else {
                    worldData.villageSiegeState.nextSpawnTime = 2; // Folia - region threading
                    if (worldData.villageSiegeState.zombiesToSpawn > 0) { // Folia - region threading
                        this.trySpawn(level);
                        worldData.villageSiegeState.zombiesToSpawn--; // Folia - region threading
                    } else {
                        worldData.villageSiegeState.siegeState = VillageSiege.State.SIEGE_DONE; // Folia - region threading
                    }
                }
            }
        } else {
            worldData.villageSiegeState.siegeState = VillageSiege.State.SIEGE_DONE; // Folia - region threading
            worldData.villageSiegeState.hasSetupSiege = false; // Folia - region threading
        }
    }

    private boolean tryToSetupSiege(final ServerLevel level) {
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        RandomSource random = level.getRandom();

        for (Player player : level.getLocalPlayers()) { // Folia - region threading
            if (!player.isSpectator()) {
                BlockPos center = player.blockPosition();
                if (level.isVillage(center) && !level.getBiome(center).is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
                    for (int i = 0; i < 10; i++) {
                        float angle = random.nextFloat() * (float) (Math.PI * 2);
                        worldData.villageSiegeState.spawnX = center.getX() + Mth.floor(Mth.cos(angle) * 32.0F); // Folia - region threading
                        worldData.villageSiegeState.spawnY = center.getY(); // Folia - region threading
                        worldData.villageSiegeState.spawnZ = center.getZ() + Mth.floor(Mth.sin(angle) * 32.0F); // Folia - region threading
                        if (this.findRandomSpawnPos(level, new BlockPos(worldData.villageSiegeState.spawnX, worldData.villageSiegeState.spawnY, worldData.villageSiegeState.spawnZ)) != null) { // Folia - region threading
                            worldData.villageSiegeState.nextSpawnTime = 0; // Folia - region threading
                            worldData.villageSiegeState.zombiesToSpawn = 20; // Folia - region threading
                            break;
                        }
                    }

                    return true;
                }
            }
        }

        return false;
    }

    private void trySpawn(final ServerLevel level) {
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        Vec3 spawnPos = this.findRandomSpawnPos(level, new BlockPos(worldData.villageSiegeState.spawnX, worldData.villageSiegeState.spawnY, worldData.villageSiegeState.spawnZ)); // Folia - region threading
        if (spawnPos != null) {
            Zombie zombie;
            try {
                zombie = new Zombie(level);
                zombie.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, level.getRandom().nextFloat() * 360.0F, 0.0F); // Folia - region threading - move up
                zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()), EntitySpawnReason.EVENT, null);
            } catch (Exception e) {
                LOGGER.warn("Failed to create zombie for village siege at {}", spawnPos, e);
                com.destroystokyo.paper.exception.ServerInternalException.reportInternalException(e); // Paper - ServerExceptionEvent
                return;
            }

            //zombie.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, level.getRandom().nextFloat() * 360.0F, 0.0F); // Folia - region threading - move up
            level.addFreshEntityWithPassengers(zombie, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION); // CraftBukkit
        }
    }

    private @Nullable Vec3 findRandomSpawnPos(final ServerLevel level, final BlockPos pos) {
        RandomSource random = level.getRandom();

        for (int i = 0; i < 10; i++) {
            int x = pos.getX() + random.nextInt(16) - 8;
            int z = pos.getZ() + random.nextInt(16) - 8;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos offset = new BlockPos(x, y, z);
            if (level.isVillage(offset) && Monster.checkMonsterSpawnRules(EntityTypes.ZOMBIE, level, EntitySpawnReason.EVENT, offset, random)) {
                return Vec3.atBottomCenterOf(offset);
            }
        }

        return null;
    }

    public enum State { // Folia - region threading - public
        SIEGE_CAN_ACTIVATE,
        SIEGE_TONIGHT,
        SIEGE_DONE;
    }
}

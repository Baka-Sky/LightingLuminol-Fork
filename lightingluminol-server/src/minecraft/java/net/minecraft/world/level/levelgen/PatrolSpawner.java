package net.minecraft.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

public class PatrolSpawner implements CustomSpawner {
    //private int nextTick; // Folia - region threading

    @Override
    public void tick(final ServerLevel level, final boolean spawnEnemies) {
        if (level.paperConfig().entities.behavior.pillagerPatrols.disable || level.paperConfig().entities.behavior.pillagerPatrols.spawnChance == 0) return; // Paper - Add option to disable pillager patrols & Pillager patrol spawn settings and per player options
        if (spawnEnemies) {
            if (level.getGameRules().get(GameRules.SPAWN_PATROLS)) {
                RandomSource random = level.getRandom();
                final io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
                // this.nextTick--;
                // Paper start - Pillager patrol spawn settings and per player options
                int playerCount = level.getLocalPlayers().size(); // Folia - region threading
                if (playerCount < 1) {
                    return;
                }

                net.minecraft.server.level.ServerPlayer player = level.getLocalPlayers().get(random.nextInt(playerCount)); // Folia - region threading
                if (player.isSpectator()) {
                    return;
                }

                int patrolSpawnDelay;
                if (level.paperConfig().entities.behavior.pillagerPatrols.spawnDelay.perPlayer) {
                    --player.patrolSpawnDelay;
                    patrolSpawnDelay = player.patrolSpawnDelay;
                } else {
                    worldData.patrolSpawnerNextTick--; // Folia - region threading
                    patrolSpawnDelay = worldData.patrolSpawnerNextTick; // Folia - region threading
                }
                if (patrolSpawnDelay <= 0) {
                    long dayCount;
                    if (level.paperConfig().entities.behavior.pillagerPatrols.start.perPlayer) {
                        dayCount = player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME)) / net.minecraft.SharedConstants.TICKS_PER_GAME_DAY; // PLAY_TIME is counting in ticks
                    } else {
                        dayCount = level
                            .registryAccess()
                            .get(net.minecraft.world.timeline.Timelines.OVERWORLD_DAY)
                            .map(timeline -> timeline.value().getPeriodCount(level.clockManager()))
                            .orElse(0);
                    }
                    if (level.paperConfig().entities.behavior.pillagerPatrols.spawnDelay.perPlayer) {
                        player.patrolSpawnDelay += level.paperConfig().entities.behavior.pillagerPatrols.spawnDelay.ticks + random.nextInt(1200);
                    } else {
                        worldData.patrolSpawnerNextTick += level.paperConfig().entities.behavior.pillagerPatrols.spawnDelay.ticks + random.nextInt(1200); // Folia - region threading
                    }

                    if (dayCount >= level.paperConfig().entities.behavior.pillagerPatrols.start.day && level.isBrightOutside()) {
                        if (random.nextDouble() < level.paperConfig().entities.behavior.pillagerPatrols.spawnChance) {
                            // Paper end - Pillager patrol spawn settings and per player options
                            if (playerCount >= 1) {
                                if (!player.isSpectator()) {
                                    if (!level.isCloseToVillage(player.blockPosition(), 2)) {
                                        int x = (24 + random.nextInt(24)) * (random.nextBoolean() ? -1 : 1);
                                        int z = (24 + random.nextInt(24)) * (random.nextBoolean() ? -1 : 1);
                                        BlockPos.MutableBlockPos spawnPos = player.blockPosition().mutable().move(x, 0, z);
                                        int delta = 10;
                                        if (level.hasChunksAt(spawnPos.getX() - 10, spawnPos.getZ() - 10, spawnPos.getX() + 10, spawnPos.getZ() + 10)) {
                                            if (level.environmentAttributes().getValue(EnvironmentAttributes.CAN_PILLAGER_PATROL_SPAWN, spawnPos)) {
                                                int groupSize = (int)Math.ceil(level.getCurrentDifficultyAt(spawnPos).getEffectiveDifficulty()) + 1;

                                                for (int i = 0; i < groupSize; i++) {
                                                    spawnPos.setY(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnPos).getY());
                                                    if (i == 0) {
                                                        if (!this.spawnPatrolMember(level, spawnPos, random, true)) {
                                                            break;
                                                        }
                                                    } else {
                                                        this.spawnPatrolMember(level, spawnPos, random, false);
                                                    }

                                                    spawnPos.setX(spawnPos.getX() + random.nextInt(5) - random.nextInt(5));
                                                    spawnPos.setZ(spawnPos.getZ() + random.nextInt(5) - random.nextInt(5));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean spawnPatrolMember(final ServerLevel level, final BlockPos pos, final RandomSource random, final boolean isLeader) {
        BlockState state = level.getBlockState(pos);
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, pos, state, state.getFluidState(), EntityTypes.PILLAGER)) {
            return false;
        }

        if (!PatrollingMonster.checkPatrollingMonsterSpawnRules(EntityTypes.PILLAGER, level, EntitySpawnReason.PATROL, pos, random)) {
            return false;
        }

        PatrollingMonster mob = EntityTypes.PILLAGER.create(level, EntitySpawnReason.PATROL);
        if (mob != null) {
            if (isLeader) {
                mob.setPatrolLeader(true);
                mob.findPatrolTarget();
            }

            mob.setPos(pos.getX(), pos.getY(), pos.getZ());
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.PATROL, null);
            level.addFreshEntityWithPassengers(mob, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.PATROL); // CraftBukkit
            return true;
        } else {
            return false;
        }
    }
}

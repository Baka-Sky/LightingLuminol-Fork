package net.minecraft.world.level;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class BaseSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SPAWNER_ENTITY_ID = -1;
    public static final EntityProcessor SET_DISPLAY_ENTITY_ID = e -> {
        e.setId(-1);
        return e;
    };
    public static final String SPAWN_DATA_TAG = "SpawnData";
    private static final int EVENT_SPAWN = 1;
    private static final int DEFAULT_SPAWN_DELAY = 20;
    private static final int DEFAULT_MIN_SPAWN_DELAY = 200;
    private static final int DEFAULT_MAX_SPAWN_DELAY = 800;
    private static final int DEFAULT_SPAWN_COUNT = 4;
    private static final int DEFAULT_MAX_NEARBY_ENTITIES = 6;
    private static final int DEFAULT_REQUIRED_PLAYER_RANGE = 16;
    private static final int DEFAULT_SPAWN_RANGE = 4;
    public int spawnDelay = 20;
    public WeightedList<SpawnData> spawnPotentials = WeightedList.of();
    public @Nullable SpawnData nextSpawnData;
    private double spin;
    private double oSpin;
    public int minSpawnDelay = 200;
    public int maxSpawnDelay = 800;
    public int spawnCount = 4;
    private @Nullable Entity displayEntity;
    public int maxNearbyEntities = 6;
    public int requiredPlayerRange = 16;
    public int spawnRange = 4;
    private int tickDelay = 0; // Paper - Configurable mob spawner tick rate

    public void setEntityId(final EntityType<?> type, final @Nullable Level level, final RandomSource random, final BlockPos pos) {
        this.getOrCreateNextSpawnData(level, random, pos).getEntityToSpawn().putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        this.spawnPotentials = WeightedList.of(); // CraftBukkit - SPIGOT-3496, MC-92282
    }

    public boolean isNearPlayer(final Level level, final BlockPos pos) {
        return level.hasNearbyAlivePlayerThatAffectsSpawning(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.requiredPlayerRange); // Paper - Affects Spawning API
    }

    public void clientTick(final Level level, final BlockPos pos) {
        if (!this.isNearPlayer(level, pos)) {
            this.oSpin = this.spin;
        } else if (this.displayEntity != null) {
            RandomSource random = level.getRandom();
            double xP = pos.getX() + random.nextDouble();
            double yP = pos.getY() + random.nextDouble();
            double zP = pos.getZ() + random.nextDouble();
            level.addParticle(ParticleTypes.SMOKE, xP, yP, zP, 0.0, 0.0, 0.0);
            level.addParticle(ParticleTypes.FLAME, xP, yP, zP, 0.0, 0.0, 0.0);
            if (this.spawnDelay > 0) {
                this.spawnDelay--;
            }

            this.oSpin = this.spin;
            this.spin = (this.spin + 1000.0F / (this.spawnDelay + 200.0F)) % 360.0;
        }
    }

    public void serverTick(final ServerLevel level, final BlockPos pos) {
        if (spawnCount <= 0 || maxNearbyEntities <= 0) return; // Paper - Ignore impossible spawn tick
        // Paper start - Configurable mob spawner tick rate
        if (spawnDelay > 0 && --tickDelay > 0) return;
        tickDelay = level.paperConfig().tickRates.mobSpawner;
        if (tickDelay == -1) { return; } // If disabled
        // Paper end - Configurable mob spawner tick rate
        if (this.isNearPlayer(level, pos) && level.isSpawnerBlockEnabled()) {
            if (this.spawnDelay < -tickDelay) { // Paper - Configurable mob spawner tick rate
                this.delay(level, pos);
            }

            if (this.spawnDelay > 0) {
                this.spawnDelay -= tickDelay; // Paper - Configurable mob spawner tick rate
            } else {
                boolean delay = false;
                RandomSource random = level.getRandom();
                SpawnData nextSpawnData = this.getOrCreateNextSpawnData(level, random, pos);

                for (int c = 0; c < this.spawnCount; c++) {
                    try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(this::toString, LOGGER)) {
                        ValueInput input = TagValueInput.create(reporter, level.registryAccess(), nextSpawnData.getEntityToSpawn());
                        Optional<EntityType<?>> entityType = EntityType.by(input);
                        if (entityType.isEmpty()) {
                            this.delay(level, pos);
                            return;
                        }

                        Vec3 spawnPos = input.read("Pos", Vec3.CODEC)
                            .orElseGet(
                                () -> new Vec3(
                                    pos.getX() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5,
                                    pos.getY() + random.nextInt(3) - 1,
                                    pos.getZ() + (random.nextDouble() - random.nextDouble()) * this.spawnRange + 0.5
                                )
                            );
                        if (level.noCollision(entityType.get().getSpawnAABB(spawnPos.x, spawnPos.y, spawnPos.z))) {
                            BlockPos spawnBlockPos = BlockPos.containing(spawnPos);
                            if (nextSpawnData.getCustomSpawnRules().isPresent()) {
                                if (!entityType.get().getCategory().isFriendly() && level.getDifficulty() == Difficulty.PEACEFUL) {
                                    continue;
                                }

                                SpawnData.CustomSpawnRules customSpawnRules = nextSpawnData.getCustomSpawnRules().get();
                                if (!customSpawnRules.isValidPosition(spawnBlockPos, level)) {
                                    continue;
                                }
                            } else if (!SpawnPlacements.checkSpawnRules(entityType.get(), level, EntitySpawnReason.SPAWNER, spawnBlockPos, level.getRandom())) {
                                continue;
                            }

                            // Paper start - PreCreatureSpawnEvent
                            com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent event = new com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent(
                                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPos, level),
                                org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(entityType.get()),
                                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level)
                            );
                            if (!event.callEvent()) {
                                delay = true;
                                if (event.shouldAbortSpawn()) {
                                    break;
                                }
                                continue;
                            }
                            // Paper end - PreCreatureSpawnEvent

                            Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.SPAWNER, e -> {
                                e.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, e.getYRot(), e.getXRot());
                                return e;
                            });
                            if (entity == null) {
                                this.delay(level, pos);
                                return;
                            }

                            int nearBy = level.getEntities(
                                    EntityTypeTest.forExactClass(entity.getClass()),
                                    new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1).inflate(this.spawnRange),
                                    EntitySelector.NO_SPECTATORS
                                )
                                .size();
                            if (nearBy >= this.maxNearbyEntities) {
                                this.delay(level, pos);
                                return;
                            }

                            entity.snapTo(entity.getX(), entity.getY(), entity.getZ(), random.nextFloat() * 360.0F, 0.0F);
                            if (entity instanceof Mob mob) {
                                if (nextSpawnData.getCustomSpawnRules().isEmpty() && !mob.checkSpawnRules(level, EntitySpawnReason.SPAWNER)
                                    || !mob.checkSpawnObstruction(level)) {
                                    continue;
                                }

                                boolean hasNoConfiguration = nextSpawnData.getEntityToSpawn().size() == 1
                                    && nextSpawnData.getEntityToSpawn().getString("id").isPresent();
                                if (hasNoConfiguration) {
                                    ((Mob)entity).finalizeSpawn(level, level.getCurrentDifficultyAt(entity.blockPosition()), EntitySpawnReason.SPAWNER, null);
                                }

                                nextSpawnData.getEquipment().ifPresent(mob::equip);
                                // Spigot start
                                if (mob.level().spigotConfig.nerfSpawnerMobs) {
                                    mob.aware = false;
                                }
                                // Spigot end
                            }

                            // Paper start
                            entity.spawnedViaMobSpawner = true;
                            entity.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
                            delay = true;
                            if (org.bukkit.craftbukkit.event.CraftEventFactory.callSpawnerSpawnEvent(entity, pos).isCancelled()) {
                                continue;
                            }
                            if (!level.tryAddFreshEntityWithPassengers(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER)) {
                                // Paper end
                                this.delay(level, pos);
                                return;
                            }

                            level.levelEvent(LevelEvent.PARTICLES_MOBBLOCK_SPAWN, pos, 0);
                            level.gameEvent(entity, GameEvent.ENTITY_PLACE, spawnBlockPos);
                            if (entity instanceof Mob mob) {
                                mob.spawnAnim();
                            }

                            // delay = true; // Paper - moved up above cancellable event
                        }
                    }
                }

                if (delay) {
                    this.delay(level, pos);
                }

                return;
            }
        }
    }

    public void delay(final Level level, final BlockPos pos) {
        RandomSource random = level.random;
        if (this.maxSpawnDelay <= this.minSpawnDelay) {
            this.spawnDelay = this.minSpawnDelay;
        } else {
            this.spawnDelay = this.minSpawnDelay + random.nextInt(this.maxSpawnDelay - this.minSpawnDelay);
        }

        this.spawnPotentials.getRandom(random).ifPresent(entry -> this.setNextSpawnData(level, pos, entry));
        this.broadcastEvent(level, pos, EVENT_SPAWN);
    }

    public void load(final @Nullable Level level, final BlockPos pos, final ValueInput input) {
        this.spawnDelay = input.getIntOr("Paper.Delay", input.getShortOr("Delay", (short) 20)); // Paper - use int if set
        input.read("SpawnData", SpawnData.CODEC).ifPresent(nextSpawnData -> this.setNextSpawnData(level, pos, nextSpawnData));
        this.spawnPotentials = input.read("SpawnPotentials", SpawnData.LIST_CODEC)
            .orElseGet(() -> WeightedList.of(this.nextSpawnData != null ? this.nextSpawnData : new SpawnData()));
        // Paper start - use int if set
        this.minSpawnDelay = input.getIntOr("Paper.MinSpawnDelay", input.getIntOr("MinSpawnDelay", 200));
        this.maxSpawnDelay = input.getIntOr("Paper.MaxSpawnDelay", input.getIntOr("MaxSpawnDelay", 800));
        // Paper end - use int if set
        this.spawnCount = input.getIntOr("SpawnCount", 4);
        this.maxNearbyEntities = input.getIntOr("MaxNearbyEntities", 6);
        this.requiredPlayerRange = input.getIntOr("RequiredPlayerRange", 16);
        this.spawnRange = input.getIntOr("SpawnRange", 4);
        this.displayEntity = null;
    }

    public void save(final ValueOutput output) {
        // Paper start
        if (this.spawnDelay > Short.MAX_VALUE) {
            output.putInt("Paper.Delay", this.spawnDelay);
        }
        output.putShort("Delay", (short) Math.min(Short.MAX_VALUE, this.spawnDelay));

        if (this.minSpawnDelay > Short.MAX_VALUE || this.maxSpawnDelay > Short.MAX_VALUE) {
            output.putInt("Paper.MinSpawnDelay", this.minSpawnDelay);
            output.putInt("Paper.MaxSpawnDelay", this.maxSpawnDelay);
        }
        output.putShort("MinSpawnDelay", (short) Math.min(Short.MAX_VALUE, this.minSpawnDelay));
        output.putShort("MaxSpawnDelay", (short) Math.min(Short.MAX_VALUE, this.maxSpawnDelay));
        // Paper end
        output.putShort("SpawnCount", (short)this.spawnCount);
        output.putShort("MaxNearbyEntities", (short)this.maxNearbyEntities);
        output.putShort("RequiredPlayerRange", (short)this.requiredPlayerRange);
        output.putShort("SpawnRange", (short)this.spawnRange);
        output.storeNullable("SpawnData", SpawnData.CODEC, this.nextSpawnData);
        output.store("SpawnPotentials", SpawnData.LIST_CODEC, this.spawnPotentials);
    }

    public @Nullable Entity getOrCreateDisplayEntity(final Level level, final BlockPos pos) {
        if (this.displayEntity == null) {
            CompoundTag entityToSpawn = this.getOrCreateNextSpawnData(level, level.getRandom(), pos).getEntityToSpawn();
            if (entityToSpawn.getString("id").isEmpty()) {
                return null;
            }

            this.displayEntity = EntityType.loadEntityRecursive(
                entityToSpawn, level, new EntitySpawnRequest(EntitySpawnReason.SPAWNER, true), SET_DISPLAY_ENTITY_ID
            );
            if (entityToSpawn.size() == 1 && this.displayEntity instanceof Mob) {
            }
        }

        return this.displayEntity;
    }

    public boolean onEventTriggered(final Level level, final int id) {
        if (id == EVENT_SPAWN) {
            if (level.isClientSide()) {
                this.spawnDelay = this.minSpawnDelay;
            }

            return true;
        } else {
            return false;
        }
    }

    public void setNextSpawnData(final @Nullable Level level, final BlockPos pos, final SpawnData nextSpawnData) {
        this.nextSpawnData = nextSpawnData;
    }

    private SpawnData getOrCreateNextSpawnData(final @Nullable Level level, final RandomSource random, final BlockPos pos) {
        if (this.nextSpawnData != null) {
            return this.nextSpawnData;
        }

        this.setNextSpawnData(level, pos, this.spawnPotentials.getRandom(random).orElseGet(SpawnData::new));
        return this.nextSpawnData;
    }

    public abstract void broadcastEvent(final Level level, final BlockPos pos, int id);

    public double getSpin() {
        return this.spin;
    }

    public double getOSpin() {
        return this.oSpin;
    }
}

package io.papermc.paper.entity.activation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spigotmc.SpigotWorldConfig;
import java.util.List;
import java.util.Set;

public final class ActivationRange {

    private ActivationRange() {
    }

    static Activity[] VILLAGER_PANIC_IMMUNITIES = {
        Activity.HIDE,
        Activity.PRE_RAID,
        Activity.RAID,
        Activity.PANIC
    };

    private static int checkInactiveWakeup(final Entity entity) {
        final Level world = entity.level();
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = world.getCurrentWorldData(); // Folia - threaded regions
        final SpigotWorldConfig config = world.spigotConfig;
        final long inactiveFor = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() - entity.activatedTick; // Folia - threaded regions
        if (entity.activationType == ActivationType.VILLAGER) {
            if (inactiveFor > config.wakeUpInactiveVillagersEvery && worldData.wakeupInactiveRemainingVillagers > 0) { // Folia - threaded regions
                worldData.wakeupInactiveRemainingVillagers--; // Folia - threaded regions
                return getWakeUpDurationWithVariance(entity, config.wakeUpInactiveVillagersFor);
            }
        } else if (entity.activationType == ActivationType.ANIMAL) {
            if (inactiveFor > config.wakeUpInactiveAnimalsEvery && worldData.wakeupInactiveRemainingAnimals > 0) { // Folia - threaded regions
                worldData.wakeupInactiveRemainingAnimals--; // Folia - threaded regions
                return getWakeUpDurationWithVariance(entity, config.wakeUpInactiveAnimalsFor);
            }
        } else if (entity.activationType == ActivationType.FLYING_MONSTER) {
            if (inactiveFor > config.wakeUpInactiveFlyingEvery && worldData.wakeupInactiveRemainingFlying > 0) { // Folia - threaded regions
                worldData.wakeupInactiveRemainingFlying--; // Folia - threaded regions
                return getWakeUpDurationWithVariance(entity, config.wakeUpInactiveFlyingFor);
            }
        } else if (entity.activationType == ActivationType.MONSTER || entity.activationType == ActivationType.RAIDER) {
            if (inactiveFor > config.wakeUpInactiveMonstersEvery && worldData.wakeupInactiveRemainingMonsters > 0) { // Folia - threaded regions
                worldData.wakeupInactiveRemainingMonsters--; // Folia - threaded regions
                return getWakeUpDurationWithVariance(entity, config.wakeUpInactiveMonstersFor);
            }
        }
        return -1;
    }

    // LightingLuminol start - variable entity wake-up duration
    private static final java.util.concurrent.ThreadLocalRandom wakeUpDurationRandom = java.util.concurrent.ThreadLocalRandom.current();

    private static int getWakeUpDurationWithVariance(Entity entity, int wakeUpDuration) {
        double deviation = meow.bacteriawa.lightingluminol.config.OptimizationsConfig.VariableEntityWakingUp.entityWakeupDurationRatioStandardDeviation;
        if (deviation <= 0) {
            return wakeUpDuration;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.round(wakeUpDuration * wakeUpDurationRandom.nextGaussian(1, deviation))));
    }
    // LightingLuminol end - variable entity wake-up duration

    //static AABB maxBB = new AABB(0, 0, 0, 0, 0, 0); // Folia - threaded regions - replaced by local variable

    /**
     * These entities are excluded from Activation range checks.
     *
     * @param entity
     * @param config
     * @return boolean If it should always tick.
     */
    public static boolean initializeEntityActivationState(final Entity entity, final SpigotWorldConfig config) {
        return (entity.activationType == ActivationType.MISC && config.miscActivationRange <= 0)
            || (entity.activationType == ActivationType.RAIDER && config.raiderActivationRange <= 0)
            || (entity.activationType == ActivationType.ANIMAL && config.animalActivationRange <= 0)
            || (entity.activationType == ActivationType.MONSTER && config.monsterActivationRange <= 0)
            || (entity.activationType == ActivationType.VILLAGER && config.villagerActivationRange <= 0)
            || (entity.activationType == ActivationType.WATER && config.waterActivationRange <= 0)
            || (entity.activationType == ActivationType.FLYING_MONSTER && config.flyingMonsterActivationRange <= 0)
            || entity instanceof EyeOfEnder
            || entity instanceof Player
            || entity instanceof ThrowableProjectile
            || entity instanceof EnderDragon
            || entity instanceof EnderDragonPart
            || entity instanceof WitherBoss
            || entity instanceof AbstractHurtingProjectile
            || entity instanceof LightningBolt
            || entity instanceof PrimedTnt
            || entity instanceof FallingBlockEntity
            || entity instanceof AbstractMinecart
            || entity instanceof AbstractBoat
            || entity instanceof EndCrystal
            || entity instanceof FireworkRocketEntity
            || entity instanceof ThrownTrident;
    }

    /**
     * Find what entities are in range of the players in the world and set
     * active if in range.
     *
     * @param world
     */
    public static void activateEntities(final Level world) {
        final int miscActivationRange = world.spigotConfig.miscActivationRange;
        final int raiderActivationRange = world.spigotConfig.raiderActivationRange;
        final int animalActivationRange = world.spigotConfig.animalActivationRange;
        final int monsterActivationRange = world.spigotConfig.monsterActivationRange;
        final int waterActivationRange = world.spigotConfig.waterActivationRange;
        final int flyingActivationRange = world.spigotConfig.flyingMonsterActivationRange;
        final int villagerActivationRange = world.spigotConfig.villagerActivationRange;
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = world.getCurrentWorldData(); // Folia - threaded regions
        worldData.wakeupInactiveRemainingAnimals = Math.min(worldData.wakeupInactiveRemainingAnimals + 1, world.spigotConfig.wakeUpInactiveAnimals); // Folia - threaded regions
        worldData.wakeupInactiveRemainingVillagers = Math.min(worldData.wakeupInactiveRemainingVillagers + 1, world.spigotConfig.wakeUpInactiveVillagers); // Folia - threaded regions
        worldData.wakeupInactiveRemainingMonsters = Math.min(worldData.wakeupInactiveRemainingMonsters + 1, world.spigotConfig.wakeUpInactiveMonsters); // Folia - threaded regions
        worldData.wakeupInactiveRemainingFlying = Math.min(worldData.wakeupInactiveRemainingFlying + 1, world.spigotConfig.wakeUpInactiveFlying); // Folia - threaded regions

        int maxRange = Math.max(monsterActivationRange, animalActivationRange);
        maxRange = Math.max(maxRange, raiderActivationRange);
        maxRange = Math.max(maxRange, miscActivationRange);
        maxRange = Math.max(maxRange, flyingActivationRange);
        maxRange = Math.max(maxRange, waterActivationRange);
        maxRange = Math.max(maxRange, villagerActivationRange);
        maxRange = Math.min((world.spigotConfig.simulationDistance << 4) - 8, maxRange);

        for (final Player player : world.getLocalPlayers()) { // Folia - region threading
            player.activatedTick = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(); // Folia - region threading
            if (world.spigotConfig.ignoreSpectatorActivation && player.isSpectator()) {
                continue;
            }

            final int worldHeight = world.getHeight();
            final AABB maxBB = player.getBoundingBox().inflate(maxRange, worldHeight, maxRange); // Folia - threaded regions
            final AABB[] bbByType = new AABB[ActivationType.values().length]; // Folia - threaded regions
            bbByType[ActivationType.MISC.ordinal()] = player.getBoundingBox().inflate(miscActivationRange, worldHeight, miscActivationRange); // Folia - threaded regions
            bbByType[ActivationType.RAIDER.ordinal()] = player.getBoundingBox().inflate(raiderActivationRange, worldHeight, raiderActivationRange); // Folia - threaded regions
            bbByType[ActivationType.ANIMAL.ordinal()] = player.getBoundingBox().inflate(animalActivationRange, worldHeight, animalActivationRange); // Folia - threaded regions
            bbByType[ActivationType.MONSTER.ordinal()] = player.getBoundingBox().inflate(monsterActivationRange, worldHeight, monsterActivationRange); // Folia - threaded regions
            bbByType[ActivationType.WATER.ordinal()] = player.getBoundingBox().inflate(waterActivationRange, worldHeight, waterActivationRange); // Folia - threaded regions
            bbByType[ActivationType.FLYING_MONSTER.ordinal()] = player.getBoundingBox().inflate(flyingActivationRange, worldHeight, flyingActivationRange); // Folia - threaded regions
            bbByType[ActivationType.VILLAGER.ordinal()] = player.getBoundingBox().inflate(villagerActivationRange, worldHeight, villagerActivationRange); // Folia - threaded regions

            final java.util.List<Entity> entities = new java.util.ArrayList<>();  // Folia - region ticking - bypass getEntities thread check, we perform a check on the entities later
            ((net.minecraft.server.level.ServerLevel)world).moonrise$getEntityLookup().getEntities((Entity)null, maxBB, entities, null); // Folia - region ticking - bypass getEntities thread check, we perform a check on the entities later
            final boolean tickMarkers = world.paperConfig().entities.markers.tick;
            for (final Entity entity : entities) {
                // Folia start - region ticking
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) {
                    continue;
                }
                // Folia end - region ticking
                if (!tickMarkers && entity instanceof Marker) {
                    continue;
                }

                ActivationRange.activateEntity(entity, bbByType); // Folia - threaded regions
            }
        }
    }

    /**
     * Tries to activate an entity.
     *
     * @param entity
     */
    private static void activateEntity(final Entity entity, final AABB[] bbByType) { // Folia - threaded regions
        if (io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() > entity.activatedTick) { // Folia - threaded regions
            if (entity.defaultActivationState) {
                entity.activatedTick = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(); // Folia - threaded regions
                return;
            }
            if (bbByType[entity.activationType.ordinal()].intersects(entity.getBoundingBox())) { // Folia - threaded regions
                entity.activatedTick = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(); // Folia - threaded regions
            }
        }
    }

    /**
     * If an entity is not in range, do some more checks to see if we should
     * give it a shot.
     *
     * @param entity
     * @return
     */
    public static int checkEntityImmunities(final Entity entity) { // return # of ticks to get immunity
        final SpigotWorldConfig config = entity.level().spigotConfig;
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = entity.level().getCurrentWorldData(); // Folia - threaded regions
        final int inactiveWakeUpImmunity = checkInactiveWakeup(entity);
        if (inactiveWakeUpImmunity > -1) {
            return inactiveWakeUpImmunity;
        }
        if (entity.getRemainingFireTicks() > 0) {
            return 2;
        }
        if (entity.activatedImmunityTick >= io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick()) { // Folia - threaded regions
            return 1;
        }
        final long inactiveFor = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() - entity.activatedTick; // Folia - threaded regions
        if ((entity.activationType != ActivationType.WATER && entity.isInWater() && entity.isPushedByFluid())) {
            return 100;
        }
        if (!entity.onGround() || entity.getDeltaMovement().horizontalDistanceSqr() > 9.999999747378752E-6D) {
            return 100;
        }
        if (!(entity instanceof final AbstractArrow arrow)) {
            if ((!entity.onGround() && !isEntityThatFlies(entity))) {
                return 10;
            }
        } else if (!arrow.isInGround()) {
            return 1;
        }
        // special cases.
        if (entity instanceof final LivingEntity living) {
            if (living.blockPosition().equals(living.getLastClimbablePos().orElse(null)) || living.isJumping() || living.hurtTime > 0 || !living.getActiveEffectsMap().isEmpty() || living.isFreezing()) {
                return 1;
            }
            if (entity instanceof final Mob mob && mob.getTarget() != null) {
                return 20;
            }
            if (entity instanceof final Bee bee) {
                final BlockPos movingTarget = bee.getMovingTarget();
                if (bee.isAngry() ||
                    (bee.getHivePos() != null && bee.getHivePos().equals(movingTarget)) ||
                    (bee.getSavedFlowerPos() != null && bee.getSavedFlowerPos().equals(movingTarget))
                ) {
                    return 20;
                }
            }
            if (entity instanceof final Villager villager) {
                final Brain<Villager> behaviorController = villager.getBrain();

                if (config.villagersActiveForPanic) {
                    for (final Activity activity : VILLAGER_PANIC_IMMUNITIES) {
                        if (behaviorController.isActive(activity)) {
                            return 20 * 5;
                        }
                    }
                }

                if (config.villagersWorkImmunityAfter > 0 && inactiveFor >= config.villagersWorkImmunityAfter) {
                    if (behaviorController.isActive(Activity.WORK)) {
                        return config.villagersWorkImmunityFor;
                    }
                }
            }
            if (entity instanceof final Llama llama && llama.inCaravan()) {
                return 1;
            }
            if (entity instanceof final Animal animal) {
                if (animal.isBaby() || animal.isInLove()) {
                    return 5;
                }
                if (entity instanceof final Sheep sheep && sheep.isSheared()) {
                    return 1;
                }
            }
            if (entity instanceof final Creeper creeper && creeper.isIgnited()) { // isExplosive
                return 20;
            }
            if (entity instanceof final Mob mob && mob.targetSelector.hasTasks()) {
                return 0;
            }
            if (entity instanceof final Pillager pillager) {
                // TODO:?
            }
        }
        // SPIGOT-6644: Otherwise the target refresh tick will be missed
        if (entity instanceof ExperienceOrb) {
            return 20;
        }
        return -1;
    }

    /**
     * Checks if the entity is active for this tick.
     *
     * @param entity
     * @return
     */
    public static boolean checkIfActive(final Entity entity) {
        // Never safe to skip fireworks or item gravity
        if (entity instanceof FireworkRocketEntity || (entity instanceof ItemEntity && (entity.tickCount + entity.getId()) % 4 == 0)) { // Needed for item gravity, see ItemEntity tick
            return true;
        }
        // special case always immunities
        // immunize brand-new entities, dead entities, and portal scenarios
        if (entity.defaultActivationState || entity.tickCount < 20 * 10 || !entity.isAlive() || (entity.portalProcess != null && !entity.portalProcess.hasExpired()) || entity.getPortalCooldown() > 0) {
            return true;
        }
        // immunize leashed entities
        if (entity instanceof final Mob mob && mob.getLeashHolder() instanceof Player) {
            return true;
        }

        boolean isActive = entity.activatedTick >= io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(); // Folia - threaded regions
        entity.isTemporarilyActive = false;

        // Should this entity tick?
        if (!isActive) {
            if ((io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() - entity.activatedTick - 1) % 20 == 0) { // Folia - threaded regions
                // Check immunities every 20 ticks.
                final int immunity = checkEntityImmunities(entity);
                if (immunity >= 0) {
                    entity.activatedTick = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + immunity; // Folia - threaded regions
                } else {
                    entity.isTemporarilyActive = true;
                }
                isActive = true;
            }
        }
        // removed the original's dumb tick skipping for active entities
        return isActive;
    }

    private static Set<EntityType<?>> ENTITIES_THAT_FLY = Set.of(
        EntityTypes.GHAST,
        EntityTypes.HAPPY_GHAST,
        EntityTypes.PHANTOM
    );

    private static boolean isEntityThatFlies(final Entity entity) {
        return ENTITIES_THAT_FLY.contains(entity.getType());
    }
}

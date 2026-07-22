package net.minecraft.world.level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public interface EntityGetter extends ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemEntityGetter { // Paper - rewrite chunk system
    List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector);

    <T extends Entity> List<T> getEntities(final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector);

    default <T extends Entity> List<T> getEntitiesOfClass(final Class<T> baseClass, final AABB bb, final Predicate<? super T> selector) {
        return this.getEntities(EntityTypeTest.forClass(baseClass), bb, selector);
    }

    // Folia start - region threading
    default List<? extends Player> getLocalPlayers() {
        return java.util.Collections.emptyList();
    }
    // Folia end - region threading

    List<? extends Player> players();

    default List<Entity> getEntities(final @Nullable Entity except, final AABB bb) {
        return this.getEntities(except, bb, EntitySelector.NO_SPECTATORS);
    }

    // Paper start - rewrite chunk system
    @Override
    default List<Entity> moonrise$getHardCollidingEntities(final Entity entity, final AABB box, final Predicate<? super Entity> predicate) {
        return this.getEntities(entity, box, predicate);
    }
    // Paper end - rewrite chunk system

    default boolean isUnobstructed(final @Nullable Entity source, final VoxelShape shape) {
        if (shape.isEmpty()) {
            return true;
        }

        // Paper start - optimise collisions
        final AABB singleAABB = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape).moonrise$getSingleAABBRepresentation();
        final List<Entity> entities = this.getEntities(
            source,
            singleAABB == null ? shape.bounds() : singleAABB.inflate(-ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)
        );

        for (int i = 0, len = entities.size(); i < len; ++i) {
            final Entity otherEntity = entities.get(i);

            if (otherEntity.isRemoved() || !otherEntity.blocksBuilding || (source != null && otherEntity.isPassengerOfSameVehicle(source))) {
                continue;
            }

            if (singleAABB == null) {
                final AABB entityBB = otherEntity.getBoundingBox();
                if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isEmpty(entityBB) || !ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersectNoEmpty(shape, entityBB)) {
                    continue;
                }
            }

            return false;
        }
        // Paper end - optimise collisions

        return true;
    }

    default <T extends Entity> List<T> getEntitiesOfClass(final Class<T> baseClass, final AABB bb) {
        return this.getEntitiesOfClass(baseClass, bb, EntitySelector.NO_SPECTATORS);
    }

    default List<VoxelShape> getEntityCollisions(final @Nullable Entity source, AABB testArea) { // Paper - optimise collisions - remove final
        // Paper start - optimise collisions
        // first behavior change is to correctly check for empty AABB
        if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isEmpty(testArea)) {
            // reduce indirection by always returning type with same class
            return new java.util.ArrayList<>();
        }

        // to comply with vanilla intersection rules, expand by -epsilon so that we only get stuff we definitely collide with.
        // Vanilla for hard collisions has this backwards, and they expand by +epsilon but this causes terrible problems
        // specifically with boat collisions.
        testArea = testArea.inflate(-ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON);

        final List<Entity> entities;
        if (source != null && ((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity)source).moonrise$isHardColliding()) {
            entities = this.getEntities(source, testArea, null);
        } else {
            entities = ((ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemEntityGetter)this).moonrise$getHardCollidingEntities(source, testArea, null);
        }

        final List<VoxelShape> ret = new java.util.ArrayList<>(Math.min(25, entities.size()));

        for (int i = 0, len = entities.size(); i < len; ++i) {
            final Entity otherEntity = entities.get(i);

            if (otherEntity.isSpectator()) {
                continue;
            }

            if ((source == null && otherEntity.canBeCollidedWith(source)) || (source != null && source.canCollideWith(otherEntity))) {
                ret.add(Shapes.create(otherEntity.getBoundingBox()));
            }
        }

        return ret;
        // Paper end - optimise collisions
    }

    // Paper start - Affects Spawning API
    default @Nullable Player findNearbyPlayer(Entity entity, double range, @Nullable Predicate<Entity> predicate) {
        return this.getNearestPlayer(entity.getX(), entity.getY(), entity.getZ(), range, predicate);
    }
    // Paper end - Affects Spawning API

    default @Nullable Player getNearestPlayer(final double x, final double y, final double z, final double range, final @Nullable Predicate<Entity> predicate) {
        double best = -1.0;
        Player result = null;

        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (predicate == null || predicate.test(player)) {
                double dist = player.distanceToSqr(x, y, z);
                if ((range < 0.0 || dist < range * range) && (best == -1.0 || dist < best)) {
                    best = dist;
                    result = player;
                }
            }
        }

        return result;
    }

    // Paper start
    default List<org.bukkit.entity.HumanEntity> findNearbyBukkitPlayers(double x, double y, double z, double range, @Nullable Predicate<Entity> predicate) {
        ImmutableList.Builder<org.bukkit.entity.HumanEntity> players = ImmutableList.builder();

        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (predicate == null || predicate.test(player)) {
                double dist = player.distanceToSqr(x, y, z);

                if (range < 0.0 || dist < range * range) {
                    players.add(player.getBukkitEntity());
                }
            }
        }

        return players.build();
    }
    // Paper end

    default @Nullable Player getNearestPlayer(final Entity source, final double maxDist) {
        return this.getNearestPlayer(source.getX(), source.getY(), source.getZ(), maxDist, false);
    }

    default @Nullable Player getNearestPlayer(final double x, final double y, final double z, final double maxDist, final boolean filterOutCreative) {
        Predicate<Entity> predicate = filterOutCreative ? EntitySelector.NO_CREATIVE_OR_SPECTATOR : EntitySelector.NO_SPECTATORS;
        return this.getNearestPlayer(x, y, z, maxDist, predicate);
    }

    // Paper start - Affects Spawning API
    default @Nullable Player getNearestPlayerThatAffectsSpawning(double x, double y, double z, double maxDist, boolean filterOutCreative) {
        Predicate<Entity> predicate = filterOutCreative ? EntitySelector.NO_CREATIVE_OR_SPECTATOR : EntitySelector.NO_SPECTATORS;
        return this.getNearestPlayer(x, y, z, maxDist, predicate.and(entity -> ((Player) entity).affectsSpawning));
    }

    default boolean hasNearbyAlivePlayerThatAffectsSpawning(double x, double y, double z, double range) {
        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (EntitySelector.PLAYER_AFFECTS_SPAWNING.test(player)) { // combines NO_SPECTATORS and LIVING_ENTITY_STILL_ALIVE with an "affects spawning" check
                double playerDist = player.distanceToSqr(x, y, z);
                if (range < 0.0 || playerDist < range * range) {
                    return true;
                }
            }
        }
        return false;
    }
    // Paper end - Affects Spawning API

    default boolean hasNearbyAlivePlayer(final double x, final double y, final double z, final double range) {
        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (EntitySelector.NO_SPECTATORS.test(player) && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(player)) {
                double playerDist = player.distanceToSqr(x, y, z);
                if (range < 0.0 || playerDist < range * range) {
                    return true;
                }
            }
        }

        return false;
    }

    default @Nullable Player getPlayerByUUID(final UUID uuid) {
        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (uuid.equals(player.getUUID())) {
                return player;
            }
        }

        return null;
    }

    // Paper start - check global player list where appropriate
    @Nullable
    default Player getGlobalPlayerByUUID(UUID uuid) {
        return this.getPlayerByUUID(uuid);
    }
    // Paper end - check global player list where appropriate
}

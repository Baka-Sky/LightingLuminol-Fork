package net.minecraft.world.level.entity;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public interface EntityAccess extends UniquelyIdentifyable {
    int getId();

    BlockPos blockPosition();

    AABB getBoundingBox();

    void setLevelCallback(EntityInLevelCallback callback);

    Stream<? extends EntityAccess> getSelfAndPassengers();

    Stream<? extends EntityAccess> getPassengersAndSelf();

    // CraftBukkit start - add Bukkit remove cause
    default void setRemoved(Entity.RemovalReason removalReason) {
        this.setRemoved(removalReason, null);
    }

    void setRemoved(Entity.RemovalReason removalReason, org.bukkit.event.entity.EntityRemoveEvent.@org.jspecify.annotations.Nullable Cause eventCause);
    // CraftBukkit end - add Bukkit remove cause

    boolean shouldBeSaved();

    boolean isAlwaysTicking();
}

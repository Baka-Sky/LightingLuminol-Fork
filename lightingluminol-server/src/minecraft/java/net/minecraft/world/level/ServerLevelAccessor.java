package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;

public interface ServerLevelAccessor extends LevelAccessor {
    ServerLevel getLevel();

    // Folia start - region threading
    default public StructureManager structureManager() {
        throw new UnsupportedOperationException();
    }
    // Folia end - region threading

    DifficultyInstance getCurrentDifficultyAt(BlockPos pos);

    default void addFreshEntityWithPassengers(final Entity entity) {
        // CraftBukkit start
        this.addFreshEntityWithPassengers(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    default void addFreshEntityWithPassengers(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        entity.getSelfAndPassengers().forEach((e) -> this.addFreshEntity(e, reason));
    }

    @Override
    default ServerLevel getMinecraftWorld() {
        return this.getLevel();
    }
    // CraftBukkit end
}

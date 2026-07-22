package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public interface LevelWriter {
    boolean setBlock(BlockPos pos, BlockState blockState, @Block.UpdateFlags int updateFlags, int updateLimit);

    default boolean setBlock(final BlockPos pos, final BlockState blockState, final @Block.UpdateFlags int updateFlags) {
        return this.setBlock(pos, blockState, updateFlags, Block.UPDATE_LIMIT);
    }

    boolean removeBlock(BlockPos pos, boolean movedByPiston);

    default boolean destroyBlock(final BlockPos pos, final boolean dropResources) {
        return this.destroyBlock(pos, dropResources, null);
    }

    default boolean destroyBlock(final BlockPos pos, final boolean dropResources, final @Nullable Entity breaker) {
        return this.destroyBlock(pos, dropResources, breaker, Block.UPDATE_LIMIT);
    }

    boolean destroyBlock(BlockPos pos, boolean dropResources, @Nullable Entity breaker, int updateLimit);

    default boolean addFreshEntity(final Entity entity) {
        return false;
    }

    // CraftBukkit start
    default boolean addFreshEntity(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason reason) {
        return false;
    }
    // CraftBukkit end
}

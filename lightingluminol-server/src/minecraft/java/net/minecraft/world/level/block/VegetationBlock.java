package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

public abstract class VegetationBlock extends Block {
    protected VegetationBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected abstract MapCodec<? extends VegetationBlock> codec();

    protected boolean mayPlaceOn(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return state.is(BlockTags.SUPPORTS_VEGETATION);
    }

    @Override
    protected BlockState updateShape(
        final BlockState state,
        final LevelReader level,
        final ScheduledTickAccess ticks,
        final BlockPos pos,
        final Direction directionToNeighbour,
        final BlockPos neighbourPos,
        final BlockState neighbourState,
        final RandomSource random
    ) {
        // CraftBukkit start
        if (!state.canSurvive(level, pos)) {
            // Suppress during worldgen
            if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel && serverLevel.getCurrentWorldData().hasPhysicsEvent) || !org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPhysicsEvent(serverLevel, pos).isCancelled()) { // Paper // Folia - region threading
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
        // CraftBukkit end
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        BlockPos below = pos.below();
        return this.mayPlaceOn(level.getBlockState(below), level, below);
    }

    @Override
    protected boolean propagatesSkylightDown(final BlockState state) {
        return state.getFluidState().isEmpty();
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return type == PathComputationType.AIR && !this.hasCollision || super.isPathfindable(state, type);
    }
}

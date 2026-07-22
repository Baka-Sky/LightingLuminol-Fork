package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Redstone;

public interface SignalGetter extends BlockGetter {
    Direction[] DIRECTIONS = Direction.values();

    default int getDirectSignal(final BlockPos pos, final Direction direction) {
        return this.getBlockState(pos).getDirectSignal(this, pos, direction);
    }

    default int getDirectSignalTo(final BlockPos pos) {
        int result = 0;
        result = Math.max(result, this.getDirectSignal(pos.below(), Direction.DOWN));
        if (result >= Redstone.SIGNAL_MAX) {
            return result;
        }

        result = Math.max(result, this.getDirectSignal(pos.above(), Direction.UP));
        if (result >= Redstone.SIGNAL_MAX) {
            return result;
        }

        result = Math.max(result, this.getDirectSignal(pos.north(), Direction.NORTH));
        if (result >= Redstone.SIGNAL_MAX) {
            return result;
        }

        result = Math.max(result, this.getDirectSignal(pos.south(), Direction.SOUTH));
        if (result >= Redstone.SIGNAL_MAX) {
            return result;
        }

        result = Math.max(result, this.getDirectSignal(pos.west(), Direction.WEST));
        if (result >= Redstone.SIGNAL_MAX) {
            return result;
        }

        result = Math.max(result, this.getDirectSignal(pos.east(), Direction.EAST));
        return result >= Redstone.SIGNAL_MAX ? result : result;
    }

    default int getControlInputSignal(final BlockPos pos, final Direction direction, final boolean onlyDiodes) {
        BlockState blockState = this.getBlockState(pos);
        if (onlyDiodes) {
            return DiodeBlock.isDiode(blockState) ? this.getDirectSignal(pos, direction) : Redstone.SIGNAL_MIN;
        } else if (blockState.is(Blocks.REDSTONE_BLOCK)) {
            return Redstone.SIGNAL_MAX;
        } else if (blockState.is(Blocks.REDSTONE_WIRE)) {
            return blockState.getValue(RedStoneWireBlock.POWER);
        } else {
            return blockState.isSignalSource() ? this.getDirectSignal(pos, direction) : Redstone.SIGNAL_MIN;
        }
    }

    default boolean hasSignal(final BlockPos pos, final Direction direction) {
        return this.getSignal(pos, direction) > 0;
    }

    default int getSignal(final BlockPos pos, final Direction direction) {
        BlockState state = this.getBlockState(pos);
        int signal = state.getSignal(this, pos, direction);
        return state.isRedstoneConductor(this, pos) ? Math.max(signal, this.getDirectSignalTo(pos)) : signal;
    }

    default int getBestOwnOrNeighbourSignal(final BlockPos pos) {
        BlockState blockState = this.getBlockState(pos);
        return Math.max(this.getBestNeighborSignal(pos), blockState.isSignalSource() ? blockState.getOwnSignal(this, pos) : 0);
    }

    default boolean hasNeighborSignal(final BlockPos blockPos) {
        return this.getSignal(blockPos.below(), Direction.DOWN) > 0
            || this.getSignal(blockPos.above(), Direction.UP) > 0
            || this.getSignal(blockPos.north(), Direction.NORTH) > 0
            || this.getSignal(blockPos.south(), Direction.SOUTH) > 0
            || this.getSignal(blockPos.west(), Direction.WEST) > 0
            || this.getSignal(blockPos.east(), Direction.EAST) > 0;
    }

    default int getBestNeighborSignal(final BlockPos pos) {
        int best = Redstone.SIGNAL_MIN;

        for (Direction direction : DIRECTIONS) {
            int signal = this.getSignal(pos.relative(direction), direction);
            if (signal >= Redstone.SIGNAL_MAX) {
                return Redstone.SIGNAL_MAX;
            }

            if (signal > best) {
                best = signal;
            }
        }

        return best;
    }
}

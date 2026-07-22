package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

public class SpongeBlock extends Block {
    public static final MapCodec<SpongeBlock> CODEC = simpleCodec(SpongeBlock::new);
    public static final int MAX_DEPTH = 6;
    public static final int MAX_COUNT = 64;
    private static final Direction[] ALL_DIRECTIONS = Direction.values();

    @Override
    public MapCodec<SpongeBlock> codec() {
        return CODEC;
    }

    protected SpongeBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            this.tryAbsorbWater(level, pos);
        }
    }

    @Override
    protected void neighborChanged(
        final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston
    ) {
        this.tryAbsorbWater(level, pos);
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
    }

    protected void tryAbsorbWater(final Level level, final BlockPos pos) {
        if (this.removeWaterBreadthFirstSearch(level, pos)) {
            level.setBlock(pos, Blocks.WET_SPONGE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    private boolean removeWaterBreadthFirstSearch(final Level level, final BlockPos startPos) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(level); // CraftBukkit - Use BlockStateListPopulator
        BlockPos.breadthFirstTraversal(startPos, 6, 65, (pos, consumer) -> {
            for (Direction direction : ALL_DIRECTIONS) {
                consumer.accept(pos.relative(direction));
            }
        }, pos -> {
            if (pos.equals(startPos)) {
                return BlockPos.TraversalNodeStatus.ACCEPT;
            } else {
                // CraftBukkit start
                BlockState blockState = blockList.getBlockState(pos);
                FluidState fluidState = blockList.getFluidState(pos);
                // CraftBukkit end
                if (!fluidState.is(FluidTags.WATER)) {
                    return BlockPos.TraversalNodeStatus.SKIP;
                } else if (blockState.getBlock() instanceof BucketPickup bucketPickup
                    && !bucketPickup.pickupBlock(null, blockList, pos, blockState).isEmpty()) { // CraftBukkit
                    return BlockPos.TraversalNodeStatus.ACCEPT;
                } else {
                    if (blockState.getBlock() instanceof LiquidBlock) {
                        blockList.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
                    } else {
                        if (!blockState.is(Blocks.KELP) && !blockState.is(Blocks.KELP_PLANT) && !blockState.is(Blocks.SEAGRASS) && !blockState.is(Blocks.TALL_SEAGRASS)) {
                            return BlockPos.TraversalNodeStatus.SKIP;
                        }

                        // CraftBukkit start
                        // BlockEntity blockEntity = blockState.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                        // dropResources(blockState, level, pos, blockEntity);
                        blockList.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        // CraftBukkit end
                    }

                    return BlockPos.TraversalNodeStatus.ACCEPT;
                }
            }
        });
        // CraftBukkit start
        java.util.List<org.bukkit.craftbukkit.block.CraftBlockState> snapshots = blockList.getSnapshotBlocks(); // Is a clone
        if (!snapshots.isEmpty()) {
            final org.bukkit.block.Block sponge = org.bukkit.craftbukkit.block.CraftBlock.at(level, startPos);

            org.bukkit.event.block.SpongeAbsorbEvent event = new org.bukkit.event.block.SpongeAbsorbEvent(sponge, (java.util.List<org.bukkit.block.BlockState>) (java.util.List) snapshots);
            if (!event.callEvent()) {
                return false;
            }

            for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : snapshots) {
                BlockPos blockPos = snapshot.getPosition();
                BlockState blockState = level.getBlockState(blockPos);
                FluidState fluidState = level.getFluidState(blockPos);

                if (!fluidState.is(FluidTags.WATER)) {
                } else if (blockState.getBlock() instanceof BucketPickup bucketPickup && !bucketPickup.pickupBlock(null, level, blockPos, blockState).isEmpty()) {
                } else if (blockState.getBlock() instanceof LiquidBlock) {
                } else if (blockState.is(Blocks.KELP) || blockState.is(Blocks.KELP_PLANT) || blockState.is(Blocks.SEAGRASS) || blockState.is(Blocks.TALL_SEAGRASS)) {
                    BlockEntity blockEntity = blockState.hasBlockEntity() ? level.getBlockEntity(blockPos) : null;

                    if (snapshot.getHandle().isAir()) {
                        dropResources(blockState, level, blockPos, blockEntity);
                    }
                }
                snapshot.place(snapshot.getFlags());
            }

            return true;
        }
        return false;
        // CraftBukkit end
    }
}

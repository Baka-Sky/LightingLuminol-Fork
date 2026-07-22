package net.minecraft.world.level.block.piston;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class PistonBaseBlock extends DirectionalBlock {
    public static final MapCodec<PistonBaseBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(Codec.BOOL.fieldOf("sticky").forGetter(b -> b.isSticky), propertiesCodec()).apply(i, PistonBaseBlock::new)
    );
    public static final BooleanProperty EXTENDED = BlockStateProperties.EXTENDED;
    public static final int TRIGGER_EXTEND = 0;
    public static final int TRIGGER_CONTRACT = 1;
    public static final int TRIGGER_DROP = 2;
    public static final int PLATFORM_THICKNESS = 4;
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateAll(Block.boxZ(16.0, 4.0, 16.0));
    private final boolean isSticky;

    @Override
    public MapCodec<PistonBaseBlock> codec() {
        return CODEC;
    }

    public PistonBaseBlock(final boolean isSticky, final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(EXTENDED, false));
        this.isSticky = isSticky;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return state.getValue(EXTENDED) ? SHAPES.get(state.getValue(FACING)) : Shapes.block();
    }

    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity by, final ItemStack itemStack) {
        if (!level.isClientSide()) {
            this.checkIfExtend(level, pos, state);
        }
    }

    @Override
    protected void neighborChanged(
        final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston
    ) {
        if (!level.isClientSide()) {
            this.checkIfExtend(level, pos, state);
        }
    }

    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            if (!level.isClientSide() && level.getBlockEntity(pos) == null) {
                this.checkIfExtend(level, pos, state);
            }
        }
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite()).setValue(EXTENDED, false);
    }

    private void checkIfExtend(final Level level, final BlockPos pos, final BlockState state) {
        Direction direction = state.getValue(FACING);
        boolean extend = this.getNeighborSignal(level, pos, direction);
        if (extend && !state.getValue(EXTENDED)) {
            if (new PistonStructureResolver(level, pos, direction, true).resolve()) {
                level.blockEvent(pos, this, TRIGGER_EXTEND, direction.get3DDataValue());
            }
        } else if (!extend && state.getValue(EXTENDED)) {
            BlockPos pushedPos = pos.relative(direction, 2);
            BlockState pushedState = level.getBlockState(pushedPos);
            int event = TRIGGER_CONTRACT;
            if (pushedState.is(Blocks.MOVING_PISTON)
                && pushedState.getValue(FACING) == direction
                && level.getBlockEntity(pushedPos) instanceof PistonMovingBlockEntity pistonEntity
                && pistonEntity.isExtending()
                && (pistonEntity.getProgress(0.0F) < 0.5F || level.getRedstoneGameTime() == pistonEntity.getLastTicked() || ((ServerLevel)level).isHandlingTick())) { // Folia - region threading
                event = 2;
            }

            level.blockEvent(pos, this, event, direction.get3DDataValue());
        }
    }

    private boolean getNeighborSignal(final SignalGetter level, final BlockPos pos, final Direction pushDirection) {
        for (Direction direction : Direction.values()) {
            if (direction != pushDirection && level.hasSignal(pos.relative(direction), direction)) {
                return true;
            }
        }

        if (level.hasSignal(pos, Direction.DOWN)) {
            return true;
        }

        BlockPos above = pos.above();

        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && level.hasSignal(above.relative(direction), direction)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean triggerEvent(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1) {
        Direction direction = state.getValue(FACING);
        // Paper start - Protect Bedrock and End Portal/Frames from being destroyed; prevent retracting when we're facing the wrong way (we were replaced before retraction could occur)
        Direction directionQueuedAs = Direction.from3DDataValue(b1 & 7); // Paper - copied from below
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits && direction != directionQueuedAs) {
            return false;
        }
        // Paper end - Protect Bedrock and End Portal/Frames from being destroyed
        BlockState extendedState = state.setValue(EXTENDED, true);
        if (!level.isClientSide()) {
            boolean extend = this.getNeighborSignal(level, pos, direction);
            if (extend && (b0 == TRIGGER_CONTRACT || b0 == TRIGGER_DROP)) {
                level.setBlock(pos, extendedState, Block.UPDATE_CLIENTS);
                return false;
            }

            if (!extend && b0 == 0) {
                return false;
            }
        }

        RandomSource random = level.getRandom();
        if (b0 == 0) {
            if (!this.moveBlocks(level, pos, direction, true)) {
                return false;
            }

            level.setBlock(pos, extendedState, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, random.nextFloat() * 0.25F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_ACTIVATE, pos, GameEvent.Context.of(extendedState));
        } else if (b0 == TRIGGER_CONTRACT || b0 == TRIGGER_DROP) {
            if (level.getBlockEntity(pos.relative(direction)) instanceof PistonMovingBlockEntity pistonMovingBlockEntity) {
                pistonMovingBlockEntity.finalTick();
            }

            BlockState movingPistonState = Blocks.MOVING_PISTON
                .defaultBlockState()
                .setValue(MovingPistonBlock.FACING, direction)
                .setValue(MovingPistonBlock.TYPE, this.isSticky ? PistonType.STICKY : PistonType.DEFAULT);
            // Paper start - Fix sticky pistons and BlockPistonRetractEvent; Move empty piston retract call to fix multiple event fires
            if (!this.isSticky) {
                if (!new org.bukkit.event.block.BlockPistonRetractEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), java.util.Collections.emptyList(), org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(direction)).callEvent()) {
                    return false;
                }
            }
            // Paper end - Fix sticky pistons and BlockPistonRetractEvent
            level.setBlock(pos, movingPistonState, Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE);
            level.setBlockEntity(
                MovingPistonBlock.newMovingBlockEntity(
                    pos, movingPistonState, this.defaultBlockState().setValue(FACING, Direction.from3DDataValue(b1 & 7)), direction, false, true // Paper - Protect Bedrock and End Portal/Frames from being destroyed; diff on change
                )
            );
            level.updateNeighborsAt(pos, movingPistonState.getBlock());
            movingPistonState.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            if (this.isSticky) {
                BlockPos twoPos = pos.offset(direction.getStepX() * 2, direction.getStepY() * 2, direction.getStepZ() * 2);
                BlockState movingState = level.getBlockState(twoPos);
                boolean pistonPiece = false;
                if (movingState.is(Blocks.MOVING_PISTON)
                    && level.getBlockEntity(twoPos) instanceof PistonMovingBlockEntity entity
                    && entity.getDirection() == direction
                    && entity.isExtending()) {
                    entity.finalTick();
                    pistonPiece = true;
                }

                if (!pistonPiece) {
                    if (b0 != TRIGGER_CONTRACT
                        || movingState.isAir()
                        || !isPushable(movingState, level, twoPos, direction.getOpposite(), false, direction)
                        || movingState.getPistonPushReaction() != PushReaction.NORMAL
                            && !movingState.is(Blocks.PISTON)
                            && !movingState.is(Blocks.STICKY_PISTON)) {
                        // Paper start - Fix sticky pistons and BlockPistonRetractEvent; fire BlockPistonRetractEvent for sticky pistons retracting nothing (air)
                        if (b0 == TRIGGER_CONTRACT && movingState.isAir()) {
                            if (!new org.bukkit.event.block.BlockPistonRetractEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), java.util.Collections.emptyList(), org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(direction)).callEvent()) {
                                return false;
                            }
                        }
                        // Paper end - Fix sticky pistons and BlockPistonRetractEvent
                        level.removeBlock(pos.relative(direction), false);
                    } else {
                        this.moveBlocks(level, pos, direction, false);
                    }
                }
            } else {
                // Paper start - Protect Bedrock and End Portal/Frames from being destroyed; fix headless pistons breaking blocks
                BlockPos headPos = pos.relative(direction);
                if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits || level.getBlockState(headPos) == Blocks.PISTON_HEAD.defaultBlockState().setValue(FACING, direction)) { // double check to make sure we're not a headless piston
                    level.removeBlock(headPos, false);
                } else {
                    ((ServerLevel) level).getChunkSource().blockChanged(headPos); // ... fix client desync
                }
                // Paper end - Protect Bedrock and End Portal/Frames from being destroyed
            }

            level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, random.nextFloat() * 0.15F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_DEACTIVATE, pos, GameEvent.Context.of(movingPistonState));
        }

        return true;
    }

    public static boolean isPushable(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Direction direction,
        final boolean allowDestroyable,
        final Direction connectionDirection
    ) {
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY() || !level.getWorldBorder().isWithinBounds(pos) || !level.getWorldBorder().isWithinBounds(pos.relative(direction))) { // Paper - Fix piston world border check
            return false;
        }

        if (state.isAir()) {
            return true;
        }

        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            return false;
        }

        if (direction == Direction.DOWN && pos.getY() == level.getMinY()) {
            return false;
        }

        if (direction == Direction.UP && pos.getY() == level.getMaxY()) {
            return false;
        }

        if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
            if (state.getDestroySpeed(level, pos) == -1.0F) {
                return false;
            }

            switch (state.getPistonPushReaction()) {
                case BLOCK:
                    return false;
                case DESTROY:
                    return allowDestroyable;
                case PUSH_ONLY:
                    return direction == connectionDirection;
            }
        } else if (state.getValue(EXTENDED)) {
            return false;
        }

        return !state.hasBlockEntity();
    }

    private boolean moveBlocks(final Level level, final BlockPos pistonPos, final Direction direction, final boolean extending) {
        BlockPos armPos = pistonPos.relative(direction);
        if (!extending && level.getBlockState(armPos).is(Blocks.PISTON_HEAD)) {
            level.setBlock(armPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE);
        }

        PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, direction, extending);
        if (!resolver.resolve()) {
            return false;
        }

        Map<BlockPos, BlockState> deleteAfterMove = Maps.newHashMap();
        List<BlockPos> toPush = resolver.getToPush();
        List<BlockState> toPushShapes = Lists.newArrayList();

        for (BlockPos pos : toPush) {
            BlockState state = level.getBlockState(pos);
            toPushShapes.add(state);
            deleteAfterMove.put(pos, state);
        }

        List<BlockPos> toDestroy = resolver.getToDestroy();
        BlockState[] toUpdate = new BlockState[toPush.size() + toDestroy.size()];
        Direction pushDirection = extending ? direction : direction.getOpposite();
        int updateIndex = 0;
        // CraftBukkit start
        final org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, pistonPos);

        final List<BlockPos> moved = resolver.getToPush();
        final List<BlockPos> broken = resolver.getToDestroy();

        List<org.bukkit.block.Block> blocks = new java.util.AbstractList<>() {

            @Override
            public int size() {
                return moved.size() + broken.size();
            }

            @Override
            public org.bukkit.block.Block get(int index) {
                if (index >= this.size() || index < 0) {
                    throw new ArrayIndexOutOfBoundsException(index);
                }

                BlockPos pos = index < moved.size() ? moved.get(index) : broken.get(index - moved.size());
                return org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
            }
        };

        final org.bukkit.event.block.BlockPistonEvent event;
        if (extending) {
            event = new org.bukkit.event.block.BlockPistonExtendEvent(
                bukkitBlock, blocks, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(pushDirection)
            );
        } else {
            event = new org.bukkit.event.block.BlockPistonRetractEvent(
                bukkitBlock, blocks, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(pushDirection)
            );
        }
        if (!event.callEvent()) {
            for (BlockPos brokenPos : broken) {
                level.sendBlockUpdated(brokenPos, Blocks.AIR.defaultBlockState(), level.getBlockState(brokenPos), Block.UPDATE_ALL);
            }
            for (BlockPos movedPos : moved) {
                level.sendBlockUpdated(movedPos, Blocks.AIR.defaultBlockState(), level.getBlockState(movedPos), Block.UPDATE_ALL);
                movedPos = movedPos.relative(pushDirection);
                level.sendBlockUpdated(movedPos, Blocks.AIR.defaultBlockState(), level.getBlockState(movedPos), Block.UPDATE_ALL);
            }
            return false;
        }
        // CraftBukkit end

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockPos pos = toDestroy.get(i);
            BlockState state = level.getBlockState(pos);
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            dropResources(state, level, pos, blockEntity, pistonPos); // Paper - Add BlockBreakBlockEvent
            if (!state.is(BlockTags.FIRE) && level.isClientSide()) {
                level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, getId(state));
            }

            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));
            toUpdate[updateIndex++] = state;
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            // Paper start - fix a variety of piston desync dupes
            boolean allowDesync = io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPistonDuplication;
            BlockPos oldPos = toPush.get(i);
            BlockPos pos = oldPos;
            BlockState blockState = allowDesync ? level.getBlockState(oldPos) : null;
            // Paper end - fix a variety of piston desync dupes
            pos = pos.relative(pushDirection);
            deleteAfterMove.remove(pos);
            BlockState actualState = Blocks.MOVING_PISTON.defaultBlockState().setValue(FACING, direction);
            level.setBlock(pos, actualState, Block.UPDATE_NONE | Block.UPDATE_MOVE_BY_PISTON);
            // Paper start - fix a variety of piston desync dupes
            if (!allowDesync) {
                blockState = level.getBlockState(oldPos);
                deleteAfterMove.replace(oldPos, blockState);
            }
            level.setBlockEntity(
                MovingPistonBlock.newMovingBlockEntity(pos, actualState, allowDesync ? toPushShapes.get(i) : blockState, direction, extending, false)
            );
            if (!allowDesync) {
                level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_SKIP_ON_PLACE); // set air to prevent later physics updates from seeing this block
            }
            // Paper end - fix a variety of piston desync dupes
            toUpdate[updateIndex++] = blockState;
        }

        if (extending) {
            PistonType type = this.isSticky ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState state = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, direction).setValue(PistonHeadBlock.TYPE, type);
            BlockState blockState = Blocks.MOVING_PISTON
                .defaultBlockState()
                .setValue(MovingPistonBlock.FACING, direction)
                .setValue(MovingPistonBlock.TYPE, this.isSticky ? PistonType.STICKY : PistonType.DEFAULT);
            deleteAfterMove.remove(armPos);
            level.setBlock(armPos, blockState, Block.UPDATE_NONE | Block.UPDATE_MOVE_BY_PISTON);
            level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(armPos, blockState, state, direction, true, true));
        }

        BlockState air = Blocks.AIR.defaultBlockState();

        for (BlockPos pos : deleteAfterMove.keySet()) {
            level.setBlock(pos, air, Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        for (Entry<BlockPos, BlockState> entry : deleteAfterMove.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState oldState = entry.getValue();
            oldState.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            air.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            air.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
        }

        Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, resolver.getPushDirection(), null);
        updateIndex = 0;

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockState state = toUpdate[updateIndex++];
            BlockPos pos = toDestroy.get(i);
            if (level instanceof ServerLevel serverLevel) {
                state.affectNeighborsAfterRemoval(serverLevel, pos, false);
            }

            state.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(pos, state.getBlock(), orientation);
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            level.updateNeighborsAt(toPush.get(i), toUpdate[updateIndex++].getBlock(), orientation);
        }

        if (extending) {
            level.updateNeighborsAt(armPos, Blocks.PISTON_HEAD, orientation);
        }

        return true;
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, EXTENDED);
    }

    @Override
    protected boolean useShapeForLightOcclusion(final BlockState state) {
        return state.getValue(EXTENDED);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }
}

package net.minecraft.world.level.block;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.Redstone;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class RedStoneWireBlock extends Block {
    public static final MapCodec<RedStoneWireBlock> CODEC = simpleCodec(RedStoneWireBlock::new);
    public static final EnumProperty<RedstoneSide> NORTH = BlockStateProperties.NORTH_REDSTONE;
    public static final EnumProperty<RedstoneSide> EAST = BlockStateProperties.EAST_REDSTONE;
    public static final EnumProperty<RedstoneSide> SOUTH = BlockStateProperties.SOUTH_REDSTONE;
    public static final EnumProperty<RedstoneSide> WEST = BlockStateProperties.WEST_REDSTONE;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    public static final Map<Direction, EnumProperty<RedstoneSide>> PROPERTY_BY_DIRECTION = ImmutableMap.copyOf(
        Maps.newEnumMap(Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH, Direction.WEST, WEST))
    );
    private static final int[] COLORS = Util.make(new int[16], list -> {
        for (int i = 0; i <= 15; i++) {
            float power = i / 15.0F;
            float red = power * 0.6F + (power > 0.0F ? 0.4F : 0.3F);
            float green = Mth.clamp(power * power * 0.7F - 0.5F, 0.0F, 1.0F);
            float blue = Mth.clamp(power * power * 0.6F - 0.7F, 0.0F, 1.0F);
            list[i] = ARGB.colorFromFloat(1.0F, red, green, blue);
        }
    });
    private static final float PARTICLE_DENSITY = 0.2F;
    private final Function<BlockState, VoxelShape> shapes;
    private final BlockState crossState;
    private final RedstoneWireEvaluator evaluator = new DefaultRedstoneWireEvaluator(this);
    //public boolean shouldSignal = true; // Folia - region threading - move to regionised world data

    @Override
    public MapCodec<RedStoneWireBlock> codec() {
        return CODEC;
    }

    public RedStoneWireBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(
            this.stateDefinition
                .any()
                .setValue(NORTH, RedstoneSide.NONE)
                .setValue(EAST, RedstoneSide.NONE)
                .setValue(SOUTH, RedstoneSide.NONE)
                .setValue(WEST, RedstoneSide.NONE)
                .setValue(POWER, 0)
        );
        this.shapes = this.makeShapes();
        this.crossState = this.defaultBlockState()
            .setValue(NORTH, RedstoneSide.SIDE)
            .setValue(EAST, RedstoneSide.SIDE)
            .setValue(SOUTH, RedstoneSide.SIDE)
            .setValue(WEST, RedstoneSide.SIDE);
    }

    private Function<BlockState, VoxelShape> makeShapes() {
        int height = 1;
        int width = 10;
        VoxelShape dot = Block.column(10.0, 0.0, 1.0);
        Map<Direction, VoxelShape> floor = Shapes.rotateHorizontal(Block.boxZ(10.0, 0.0, 1.0, 0.0, 8.0));
        Map<Direction, VoxelShape> up = Shapes.rotateHorizontal(Block.boxZ(10.0, 16.0, 0.0, 1.0));
        return this.getShapeForEachState(state -> {
            VoxelShape shape = dot;

            for (Entry<Direction, EnumProperty<RedstoneSide>> entry : PROPERTY_BY_DIRECTION.entrySet()) {
                shape = switch ((RedstoneSide)state.getValue(entry.getValue())) {
                    case UP -> Shapes.or(shape, floor.get(entry.getKey()), up.get(entry.getKey()));
                    case SIDE -> Shapes.or(shape, floor.get(entry.getKey()));
                    case NONE -> shape;
                };
            }

            return shape;
        }, POWER);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return this.shapes.apply(state);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.getConnectionState(context.getLevel(), this.crossState, context.getClickedPos());
    }

    private BlockState getConnectionState(final BlockGetter level, BlockState state, final BlockPos pos) {
        boolean wasDot = isDot(state);
        state = this.getMissingConnections(level, this.defaultBlockState().setValue(POWER, state.getValue(POWER)), pos);
        if (wasDot && isDot(state)) {
            return state;
        }

        boolean north = state.getValue(NORTH).isConnected();
        boolean south = state.getValue(SOUTH).isConnected();
        boolean east = state.getValue(EAST).isConnected();
        boolean west = state.getValue(WEST).isConnected();
        boolean northSouthEmpty = !north && !south;
        boolean eastWestEmpty = !east && !west;
        if (!west && northSouthEmpty) {
            state = state.setValue(WEST, RedstoneSide.SIDE);
        }

        if (!east && northSouthEmpty) {
            state = state.setValue(EAST, RedstoneSide.SIDE);
        }

        if (!north && eastWestEmpty) {
            state = state.setValue(NORTH, RedstoneSide.SIDE);
        }

        if (!south && eastWestEmpty) {
            state = state.setValue(SOUTH, RedstoneSide.SIDE);
        }

        return state;
    }

    private BlockState getMissingConnections(final BlockGetter level, BlockState state, final BlockPos pos) {
        boolean canConnectUp = !level.getBlockState(pos.above()).isRedstoneConductor(level, pos);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!state.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected()) {
                RedstoneSide sideConnection = this.getConnectingSide(level, pos, direction, canConnectUp);
                state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), sideConnection);
            }
        }

        return state;
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
        if (directionToNeighbour == Direction.DOWN) {
            return !this.canSurviveOn(level, neighbourPos, neighbourState) ? Blocks.AIR.defaultBlockState() : state;
        }

        if (directionToNeighbour == Direction.UP) {
            return this.getConnectionState(level, state, pos);
        }

        RedstoneSide sideConnection = this.getConnectingSide(level, pos, directionToNeighbour);
        return sideConnection.isConnected() == state.getValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour)).isConnected() && !isCross(state)
            ? state.setValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour), sideConnection)
            : this.getConnectionState(
                level, this.crossState.setValue(POWER, state.getValue(POWER)).setValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour), sideConnection), pos
            );
    }

    private static boolean isCross(final BlockState state) {
        return state.getValue(NORTH).isConnected()
            && state.getValue(SOUTH).isConnected()
            && state.getValue(EAST).isConnected()
            && state.getValue(WEST).isConnected();
    }

    private static boolean isDot(final BlockState state) {
        return !state.getValue(NORTH).isConnected()
            && !state.getValue(SOUTH).isConnected()
            && !state.getValue(EAST).isConnected()
            && !state.getValue(WEST).isConnected();
    }

    @Override
    protected void updateIndirectNeighbourShapes(
        final BlockState state, final LevelAccessor level, final BlockPos pos, final @Block.UpdateFlags int updateFlags, final int updateLimit
    ) {
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState currState; blockPos.setWithOffset(pos, direction); // Folia - block updates in unloaded chunks
            RedstoneSide value = state.getValue(PROPERTY_BY_DIRECTION.get(direction));
            if (value != RedstoneSide.NONE && (currState = (level instanceof net.minecraft.server.level.ServerLevel serverLevel && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(serverLevel, blockPos, 25) ? null : level.getBlockStateIfLoaded(blockPos))) != null && !currState.is(this)) { // Folia - block updates in unloaded chunks
                blockPos.move(Direction.DOWN);
                BlockState blockStateDown = level.getBlockState(blockPos);
                if (blockStateDown.is(this)) {
                    BlockPos neighborPos = blockPos.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), blockPos, neighborPos, level.getBlockState(neighborPos), updateFlags, updateLimit);
                }

                blockPos.setWithOffset(pos, direction).move(Direction.UP);
                BlockState blockStateUp = level.getBlockState(blockPos);
                if (blockStateUp.is(this)) {
                    BlockPos neighborPos = blockPos.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), blockPos, neighborPos, level.getBlockState(neighborPos), updateFlags, updateLimit);
                }
            }
        }
    }

    private RedstoneSide getConnectingSide(final BlockGetter level, final BlockPos pos, final Direction direction) {
        return this.getConnectingSide(level, pos, direction, !level.getBlockState(pos.above()).isRedstoneConductor(level, pos));
    }

    private RedstoneSide getConnectingSide(final BlockGetter level, final BlockPos pos, final Direction direction, final boolean canConnectUp) {
        BlockPos relativePos = pos.relative(direction);
        BlockState relativeState = level.getBlockState(relativePos);
        if (canConnectUp) {
            boolean isPlaceableAbove = relativeState.getBlock() instanceof TrapDoorBlock || this.canSurviveOn(level, relativePos, relativeState);
            if (isPlaceableAbove && shouldConnectTo(level.getBlockState(relativePos.above()))) {
                if (relativeState.isFaceSturdy(level, relativePos, direction.getOpposite())) {
                    return RedstoneSide.UP;
                }

                return RedstoneSide.SIDE;
            }
        }

        return !shouldConnectTo(relativeState, direction)
                && (relativeState.isRedstoneConductor(level, relativePos) || !shouldConnectTo(level.getBlockState(relativePos.below())))
            ? RedstoneSide.NONE
            : RedstoneSide.SIDE;
    }

    @Override
    public boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return this.canSurviveOn(level, below, belowState);
    }

    private boolean canSurviveOn(final BlockGetter level, final BlockPos relativePos, final BlockState relativeState) {
        return relativeState.isFaceSturdy(level, relativePos, Direction.UP) || relativeState.is(Blocks.HOPPER);
    }

    // Paper start - Optimize redstone (Eigencraft)
    // The bulk of the new functionality is found in RedstoneWireTurbo.java
    io.papermc.paper.redstone.RedstoneWireTurbo turbo = new io.papermc.paper.redstone.RedstoneWireTurbo(this);
    // Folia start - region threading
    private io.papermc.paper.redstone.RedstoneWireTurbo getTurbo(Level world) {
        return world.getCurrentWorldData().turbo;
    }
    // Folia end - region threading

    /*
     * Modified version of pre-existing updateSurroundingRedstone, which is called from
     * this.neighborChanged and a few other methods in this class.
     * Note: Added 'source' argument so as to help determine direction of information flow
     */
    private void updateSurroundingRedstone(Level worldIn, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
        if (worldIn.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.EIGENCRAFT) {
            // since 24w33a the source pos is no longer given, but instead an Orientation parameter
            // when this is not null, it can be used to find the source pos, which the turbo uses
            // to find the direction of information flow
            BlockPos source = null;
            if (orientation != null) {
                source = pos.relative(orientation.getFront().getOpposite());
            }
            getTurbo(worldIn).updateSurroundingRedstone(worldIn, pos, state, source); // Folia - region threading
            return;
        }
        updatePowerStrength(worldIn, pos, state, orientation, blockAdded);
    }

    /*
     * This method computes a wire's target strength and updates the given block state.
     * It uses the DefaultRedstoneWireEvaluator for this, which is identical to code
     * that was present in this class prior to the introduction of the experimental redstone
     * changes in 24w33a.
     * The previous implementation of this method in this patch had optimizations that have
     * not been relevant since 1.13, thus it has been greatly simplified.
     */
    public BlockState calculateCurrentChanges(Level level, BlockPos pos, BlockState state) {
        int oldPower = state.getValue(POWER);
        int newPower = ((DefaultRedstoneWireEvaluator) evaluator).calculateTargetStrength(level, pos);
        if (oldPower != newPower) {
            if (level.getBlockState(pos) == state) {
                newPower = org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(level, pos, oldPower, newPower).getNewCurrent();
                state = state.setValue(POWER, newPower);
                // [Space Walker] suppress shape updates and emit those manually to
                // bypass the new neighbor update stack.
                if (level.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS)) {
                    this.getTurbo(level).updateNeighborShapes(level, pos, state); // Folia - region threading
                }
            }
        }
        return state;
    }
    // Paper end

    private void updatePowerStrength(
        final Level level,
        final BlockPos pos,
        final BlockState state,
        final @Nullable Orientation orientation,
        final boolean shapeUpdateWiresAroundInitialPosition
    ) {
        if (useExperimentalEvaluator(level)) {
            new ExperimentalRedstoneWireEvaluator(this).updatePowerStrength(level, pos, state, orientation, shapeUpdateWiresAroundInitialPosition);
        } else {
            this.evaluator.updatePowerStrength(level, pos, state, orientation, shapeUpdateWiresAroundInitialPosition);
        }
    }

    public int getBlockSignal(final Level level, final BlockPos pos) {
        io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal = false; // Folia - region threading
        int blockSignal = level.getBestNeighborSignal(pos);
        io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal = true; // Folia - region threading
        return blockSignal;
    }

    private void checkCornerChangeAt(final Level level, final BlockPos pos) {
        if (level.getBlockState(pos).is(this)) {
            level.updateNeighborsAt(pos, this);

            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), this);
            }
        }
    }

    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        if (!oldState.is(state.getBlock()) && !level.isClientSide()) {
            // Paper start - optimize redstone - replace call to updatePowerStrength
            if (level.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                level.getWireHandler().onWireAdded(pos, state); // Alternate Current
            } else {
                this.updateSurroundingRedstone(level, pos, state, null, true); // Vanilla/Eigencraft
            }
            // Paper end - optimize redstone

            for (Direction direction : Direction.Plane.VERTICAL) {
                level.updateNeighborsAt(pos.relative(direction), this);
            }

            this.updateNeighborsOfNeighboringWires(level, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston) {
        if (!movedByPiston) {
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), this);
            }

            // Paper start - optimize redstone - replace call to updatePowerStrength
            if (level.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                level.getWireHandler().onWireRemoved(pos, state); // Alternate Current
            } else {
                this.updateSurroundingRedstone(level, pos, state, null, false); // Vanilla/Eigencraft
            }
            // Paper end - optimize redstone
            this.updateNeighborsOfNeighboringWires(level, pos);
        }
    }

    private void updateNeighborsOfNeighboringWires(final Level level, final BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            this.checkCornerChangeAt(level, pos.relative(direction));
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = pos.relative(direction);
            if (level.getBlockState(target).isRedstoneConductor(level, target)) {
                this.checkCornerChangeAt(level, target.above());
            } else {
                this.checkCornerChangeAt(level, target.below());
            }
        }
    }

    @Override
    protected void neighborChanged(
        final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston
    ) {
        if (!level.isClientSide()) {
            // Paper start - optimize redstone (Alternate Current)
            // Alternate Current handles breaking of redstone wires in the WireHandler.
            if (level.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                level.getWireHandler().onWireUpdated(pos, state, orientation);
            } else
                // Paper end - optimize redstone (Alternate Current)
            if (block != this || !useExperimentalEvaluator(level)) {
                if (state.canSurvive(level, pos)) {
                    this.updateSurroundingRedstone(level, pos, state, orientation, false); // Paper - Optimize redstone (Eigencraft)
                } else {
                    dropResources(state, level, pos);
                    level.removeBlock(pos, false);
                }
            }
        }
    }

    private static boolean useExperimentalEvaluator(final Level level) {
        return level.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS);
    }

    @Override
    protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
        return !io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal ? Redstone.SIGNAL_MIN : state.getSignal(level, pos, direction); // Folia - region threading
    }

    @Override
    protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
        if (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal && direction != Direction.DOWN) { // Folia - region threading
            int power = this.ownSignal(state, level, pos);
            if (power == 0) {
                return Redstone.SIGNAL_MIN;
            } else {
                return direction != Direction.UP
                        && !this.getConnectionState(level, state, pos).getValue(PROPERTY_BY_DIRECTION.get(direction.getOpposite())).isConnected()
                    ? Redstone.SIGNAL_MIN
                    : power;
            }
        } else {
            return Redstone.SIGNAL_MIN;
        }
    }

    @Override
    protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return state.getValue(POWER);
    }

    protected static boolean shouldConnectTo(final BlockState blockState) {
        return shouldConnectTo(blockState, null);
    }

    protected static boolean shouldConnectTo(final BlockState blockState, final @Nullable Direction direction) {
        if (blockState.is(Blocks.REDSTONE_WIRE)) {
            return true;
        } else if (blockState.is(Blocks.REPEATER)) {
            Direction repeaterDirection = blockState.getValue(RepeaterBlock.FACING);
            return repeaterDirection == direction || repeaterDirection.getOpposite() == direction;
        } else {
            return blockState.is(Blocks.OBSERVER) ? direction == blockState.getValue(ObserverBlock.FACING) : blockState.isSignalSource() && direction != null;
        }
    }

    @Override
    protected boolean isSignalSource(final BlockState state) {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        return worldData == null || worldData.shouldSignal;
        // Folia end - region threading
    }

    public static int getColorForPower(final int power) {
        return COLORS[power];
    }

    private static void spawnParticlesAlongLine(
        final Level level,
        final RandomSource random,
        final BlockPos pos,
        final int color,
        final Direction side,
        final Direction along,
        final float from,
        final float to
    ) {
        float span = to - from;
        if (!(random.nextFloat() >= 0.2F * span)) {
            float sideOfBlock = 0.4375F;
            float positionOnLine = from + span * random.nextFloat();
            double x = 0.5 + 0.4375F * side.getStepX() + positionOnLine * along.getStepX();
            double y = 0.5 + 0.4375F * side.getStepY() + positionOnLine * along.getStepY();
            double z = 0.5 + 0.4375F * side.getStepZ() + positionOnLine * along.getStepZ();
            level.addParticle(new DustParticleOptions(color, 1.0F), pos.getX() + x, pos.getY() + y, pos.getZ() + z, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
        int power = state.getValue(POWER);
        if (power != 0) {
            for (Direction horizontal : Direction.Plane.HORIZONTAL) {
                RedstoneSide connection = state.getValue(PROPERTY_BY_DIRECTION.get(horizontal));
                switch (connection) {
                    case UP:
                        spawnParticlesAlongLine(level, random, pos, COLORS[power], horizontal, Direction.UP, -0.5F, 0.5F);
                    case SIDE:
                        spawnParticlesAlongLine(level, random, pos, COLORS[power], Direction.DOWN, horizontal, 0.0F, 0.5F);
                        break;
                    case NONE:
                    default:
                        spawnParticlesAlongLine(level, random, pos, COLORS[power], Direction.DOWN, horizontal, 0.0F, 0.3F);
                }
            }
        }
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_180 -> (BlockState)state.setValue(NORTH, state.getValue(SOUTH))
                .setValue(EAST, state.getValue(WEST))
                .setValue(SOUTH, state.getValue(NORTH))
                .setValue(WEST, state.getValue(EAST));
            case COUNTERCLOCKWISE_90 -> (BlockState)state.setValue(NORTH, state.getValue(EAST))
                .setValue(EAST, state.getValue(SOUTH))
                .setValue(SOUTH, state.getValue(WEST))
                .setValue(WEST, state.getValue(NORTH));
            case CLOCKWISE_90 -> (BlockState)state.setValue(NORTH, state.getValue(WEST))
                .setValue(EAST, state.getValue(NORTH))
                .setValue(SOUTH, state.getValue(EAST))
                .setValue(WEST, state.getValue(SOUTH));
            default -> state;
        };
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT:
                return state.setValue(NORTH, state.getValue(SOUTH)).setValue(SOUTH, state.getValue(NORTH));
            case FRONT_BACK:
                return state.setValue(EAST, state.getValue(WEST)).setValue(WEST, state.getValue(EAST));
            default:
                return super.mirror(state, mirror);
        }
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, POWER);
    }

    @Override
    protected InteractionResult useWithoutItem(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult
    ) {
        if (!player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }

        if (isCross(state) || isDot(state)) {
            BlockState newState = isCross(state) ? this.defaultBlockState() : this.crossState;
            newState = newState.setValue(POWER, state.getValue(POWER));
            newState = this.getConnectionState(level, newState, pos);
            if (newState != state) {
                level.setBlock(pos, newState, Block.UPDATE_ALL);
                this.updatesOnShapeChange(level, pos, state, newState);
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    private void updatesOnShapeChange(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, null, Direction.UP);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = pos.relative(direction);
            if (oldState.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected() != newState.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected()
                && level.getBlockState(relativePos).isRedstoneConductor(level, relativePos)) {
                level.updateNeighborsAtExceptFromFacing(
                    relativePos, newState.getBlock(), direction.getOpposite(), ExperimentalRedstoneUtils.withFront(orientation, direction)
                );
            }
        }
    }
}

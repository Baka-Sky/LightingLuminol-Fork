package net.minecraft.world.level.material;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class FlowingFluid extends Fluid {
    public static final BooleanProperty FALLING = BlockStateProperties.FALLING;
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL_FLOWING;
    private static final int CACHE_SIZE = 200;
    private static final ThreadLocal<Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>> OCCLUSION_CACHE = ThreadLocal.withInitial(() -> {
        Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey> map = new Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>(200) {
            @Override
            protected void rehash(final int newN) {
            }
        };
        map.defaultReturnValue((byte)127);
        return map;
    });
    private final Map<FluidState, VoxelShape> shapes = Maps.newIdentityHashMap();

    // Paper start - fluid method optimisations
    private FluidState sourceFalling;
    private FluidState sourceNotFalling;

    private static final int TOTAL_FLOWING_STATES = FALLING.getPossibleValues().size() * LEVEL.getPossibleValues().size();
    private static final int MIN_LEVEL = LEVEL.getPossibleValues().stream().sorted().findFirst().get().intValue();

    // index = (falling ? 1 : 0) + level*2
    private FluidState[] flowingLookUp;
    private volatile boolean init;

    private static final int COLLISION_OCCLUSION_CACHE_SIZE = 2048;
    private static final ThreadLocal<ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[]> COLLISION_OCCLUSION_CACHE = ThreadLocal.withInitial(() -> new ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[COLLISION_OCCLUSION_CACHE_SIZE]);


    /**
     * Due to init order, we need to use callbacks to initialise our state
     */
    private void init() {
        synchronized (this) {
            if (this.init) {
                return;
            }
            this.flowingLookUp = new FluidState[TOTAL_FLOWING_STATES];
            final FluidState defaultFlowState = this.getFlowing().defaultFluidState();
            for (int i = 0; i < TOTAL_FLOWING_STATES; ++i) {
                final int falling = i & 1;
                final int level = (i >>> 1) + MIN_LEVEL;

                this.flowingLookUp[i] = defaultFlowState.setValue(FALLING, falling == 1 ? Boolean.TRUE : Boolean.FALSE)
                    .setValue(LEVEL, Integer.valueOf(level));
            }

            final FluidState defaultFallState = this.getSource().defaultFluidState();
            this.sourceFalling = defaultFallState.setValue(FALLING, Boolean.TRUE);
            this.sourceNotFalling = defaultFallState.setValue(FALLING, Boolean.FALSE);

            this.init = true;
        }
    }
    // Paper end - fluid method optimisations

    @Override
    protected void createFluidStateDefinition(final StateDefinition.Builder<Fluid, FluidState> builder) {
        builder.add(FALLING);
    }

    @Override
    public Vec3 getFlow(final BlockGetter level, final BlockPos pos, final FluidState fluidState) {
        double flowX = 0.0;
        double flowZ = 0.0;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            blockPos.setWithOffset(pos, direction);
            FluidState neighbourFluid = level.getFluidState(blockPos);
            if (this.affectsFlow(neighbourFluid)) {
                float neighborHeight = neighbourFluid.getOwnHeight();
                float distance = 0.0F;
                if (neighborHeight == 0.0F) {
                    if (!level.getBlockState(blockPos).blocksMotion()) {
                        BlockPos neighborPos = blockPos.below();
                        FluidState belowNeighborState = level.getFluidState(neighborPos);
                        if (this.affectsFlow(belowNeighborState)) {
                            neighborHeight = belowNeighborState.getOwnHeight();
                            if (neighborHeight > 0.0F) {
                                distance = fluidState.getOwnHeight() - (neighborHeight - 0.8888889F);
                            }
                        }
                    }
                } else if (neighborHeight > 0.0F) {
                    distance = fluidState.getOwnHeight() - neighborHeight;
                }

                if (distance != 0.0F) {
                    flowX += direction.getStepX() * distance;
                    flowZ += direction.getStepZ() * distance;
                }
            }
        }

        Vec3 flow = new Vec3(flowX, 0.0, flowZ);
        if (fluidState.getValue(FALLING)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                blockPos.setWithOffset(pos, direction);
                if (this.isSolidFace(level, blockPos, direction) || this.isSolidFace(level, blockPos.above(), direction)) {
                    flow = flow.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }

        return flow.normalize();
    }

    private boolean affectsFlow(final FluidState neighbourFluid) {
        return neighbourFluid.isEmpty() || neighbourFluid.getType().isSame(this);
    }

    protected boolean isSolidFace(final BlockGetter level, final BlockPos pos, final Direction direction) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);
        return !fluidState.getType().isSame(this)
            && (direction == Direction.UP || !(state.getBlock() instanceof IceBlock) && state.isFaceSturdy(level, pos, direction));
    }

    protected void spread(final ServerLevel level, final BlockPos pos, final BlockState state, final FluidState fluidState) {
        if (!fluidState.isEmpty()) {
            BlockPos belowPos = pos.below();
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = belowState.getFluidState();
            if (this.canMaybePassThrough(level, pos, state, Direction.DOWN, belowPos, belowState, belowFluid)) {
                FluidState newBelowFluid = this.getNewLiquid(level, belowPos, belowState);
                Fluid newBelowFluidType = newBelowFluid.getType();
                if (belowFluid.canBeReplacedWith(level, belowPos, newBelowFluidType, Direction.DOWN)
                    && canHoldSpecificFluid(level, belowPos, belowState, newBelowFluidType)) {
                    // CraftBukkit start
                    org.bukkit.block.Block source = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
                    org.bukkit.event.block.BlockFromToEvent event = new org.bukkit.event.block.BlockFromToEvent(source, org.bukkit.block.BlockFace.DOWN);
                    level.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return;
                    }
                    // CraftBukkit end
                    this.spreadTo(level, belowPos, belowState, Direction.DOWN, newBelowFluid);
                    if (this.sourceNeighborCount(level, pos) >= 3) {
                        this.spreadToSides(level, pos, fluidState, state);
                    }

                    return;
                }
            }

            if (fluidState.isSource() || !this.isWaterHole(level, pos, state, belowPos, belowState)) {
                this.spreadToSides(level, pos, fluidState, state);
            }
        }
    }

    private void spreadToSides(final ServerLevel level, final BlockPos pos, final FluidState fluidState, final BlockState state) {
        int neighbor = fluidState.getAmount() - this.getDropOff(level);
        if (fluidState.getValue(FALLING)) {
            neighbor = 7;
        }

        if (neighbor > 0) {
            Map<Direction, FluidState> spreads = this.getSpread(level, pos, state);

            for (Entry<Direction, FluidState> entry : spreads.entrySet()) {
                Direction spread = entry.getKey();
                FluidState newNeighborFluid = entry.getValue();
                BlockPos neighborPos = pos.relative(spread);
                final BlockState neighborState = level.getBlockStateIfLoaded(neighborPos); // Paper - Prevent chunk loading from fluid flowing
                if (neighborState == null) continue; // Paper - Prevent chunk loading from fluid flowing
                // CraftBukkit start
                org.bukkit.block.Block source = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
                org.bukkit.event.block.BlockFromToEvent event = new org.bukkit.event.block.BlockFromToEvent(source, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(spread));
                level.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    continue;
                }
                // CraftBukkit end
                this.spreadTo(level, neighborPos, neighborState, spread, newNeighborFluid); // Paper - Prevent chunk loading from fluid flowing
            }
        }
    }

    protected FluidState getNewLiquid(final ServerLevel level, final BlockPos pos, final BlockState state) {
        int highestNeighbor = 0;
        int neighbourSources = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = mutablePos.setWithOffset(pos, direction);
            BlockState blockState = level.getBlockStateIfLoaded(relativePos); // Paper - Prevent chunk loading from fluid flowing
            if (blockState == null) continue; // Paper - Prevent chunk loading from fluid flowing
            FluidState fluidState = blockState.getFluidState();
            if (fluidState.getType().isSame(this) && canPassThroughWall(direction, level, pos, state, relativePos, blockState)) {
                if (fluidState.isSource()) {
                    neighbourSources++;
                }

                highestNeighbor = Math.max(highestNeighbor, fluidState.getAmount());
            }
        }

        if (neighbourSources >= 2 && this.canConvertToSource(level)) {
            BlockState belowState = level.getBlockState(mutablePos.setWithOffset(pos, Direction.DOWN));
            FluidState belowFluid = belowState.getFluidState();
            if (belowState.isSolid() || this.isSourceBlockOfThisType(belowFluid)) {
                return this.getSource(false);
            }
        }

        BlockPos abovePos = mutablePos.setWithOffset(pos, Direction.UP);
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = aboveState.getFluidState();
        if (!aboveFluid.isEmpty() && aboveFluid.getType().isSame(this) && canPassThroughWall(Direction.UP, level, pos, state, abovePos, aboveState)) {
            return this.getFlowing(8, true);
        }

        int amount = highestNeighbor - this.getDropOff(level);
        return amount <= 0 ? Fluids.EMPTY.defaultFluidState() : this.getFlowing(amount, false);
    }

    // Paper start - fluid method optimisations
    private static boolean canPassThroughWall(final Direction direction, final BlockGetter level,
                                              final BlockPos fromPos, final BlockState fromState,
                                              final BlockPos toPos, final BlockState toState) {
        if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$emptyCollisionShape() & ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$emptyCollisionShape()) {
            // don't even try to cache simple cases
            return true;
        }

        if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$occludesFullBlock() | ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$occludesFullBlock()) {
            // don't even try to cache simple cases
            return false;
        }

        final ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[] cache = ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$hasCache() & ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$hasCache() ?
                COLLISION_OCCLUSION_CACHE.get() : null;

        final int keyIndex
                = (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$uniqueId1() ^ ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$uniqueId2() ^ ((ca.spottedleaf.moonrise.patches.collisions.util.CollisionDirection)(Object)direction).moonrise$uniqueId())
                & (COLLISION_OCCLUSION_CACHE_SIZE - 1);

        if (cache != null) {
            final ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey cached = cache[keyIndex];
            if (cached != null && cached.first() == fromState && cached.second() == toState && cached.direction() == direction) {
                return cached.result();
            }
        }

        final VoxelShape shape1 = fromState.getCollisionShape(level, fromPos);
        final VoxelShape shape2 = toState.getCollisionShape(level, toPos);

        final boolean result = !Shapes.mergedFaceOccludes(shape1, shape2, direction);

        if (cache != null) {
            // we can afford to replace in-use keys more often due to the excessive caching the collision patch does in mergedFaceOccludes
            cache[keyIndex] = new ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey(fromState, toState, direction, result);
        }

        return result;
    }
    // Paper end - fluid method optimisations


    public abstract Fluid getFlowing();

    public FluidState getFlowing(final int amount, final boolean falling) {
        // Paper start - fluid method optimisations
        if (!this.init) {
            this.init();
        }
        final int index = (falling ? 1 : 0) | ((amount - MIN_LEVEL) << 1);
        return this.flowingLookUp[index];
        // Paper end - fluid method optimisations
    }

    public abstract Fluid getSource();

    public FluidState getSource(final boolean falling) {
        // Paper start - fluid method optimisations
        if (!this.init) {
            this.init();
        }
        return falling ? this.sourceFalling : this.sourceNotFalling;
        // Paper end - fluid method optimisations
    }

    protected abstract boolean canConvertToSource(ServerLevel level);

    protected void spreadTo(final LevelAccessor level, final BlockPos pos, final BlockState state, final Direction direction, final FluidState target) {
        if (state.getBlock() instanceof LiquidBlockContainer container) {
            container.placeLiquid(level, pos, state, target);
        } else {
            if (!state.isAir()) {
                this.beforeDestroyingBlock(level, pos, state, pos.relative(direction.getOpposite())); // Paper - Add BlockBreakBlockEvent
            }

            level.setBlock(pos, target.createLegacyBlock(), Block.UPDATE_ALL);
        }
    }

    protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state, BlockPos source) { beforeDestroyingBlock(level, pos, state); } // Paper - Add BlockBreakBlockEvent

    protected abstract void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state);

    protected int getSlopeDistance(
        final LevelReader level, final BlockPos pos, final int pass, final Direction from, final BlockState state, final FlowingFluid.SpreadContext context
    ) {
        int lowest = 1000;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction != from) {
                BlockPos testPos = pos.relative(direction);
                BlockState testState = context.getBlockStateIfLoaded(testPos); // Paper - Prevent chunk loading from fluid flowing
                if (testState == null) continue; // Paper - Prevent chunk loading from fluid flowing
                FluidState testFluidState = testState.getFluidState();
                if (this.canPassThrough(level, this.getFlowing(), pos, state, direction, testPos, testState, testFluidState)) {
                    if (context.isHole(testPos)) {
                        return pass;
                    }

                    if (pass < this.getSlopeFindDistance(level)) {
                        int v = this.getSlopeDistance(level, testPos, pass + 1, direction.getOpposite(), testState, context);
                        if (v < lowest) {
                            lowest = v;
                        }
                    }
                }
            }
        }

        return lowest;
    }

    private boolean isWaterHole(
        final BlockGetter level, final BlockPos topPos, final BlockState topState, final BlockPos bottomPos, final BlockState bottomState
    ) {
        return canPassThroughWall(Direction.DOWN, level, topPos, topState, bottomPos, bottomState)
            && (bottomState.getFluidState().getType().isSame(this) || canHoldFluid(level, bottomPos, bottomState, this.getFlowing()));
    }

    private boolean canPassThrough(
        final BlockGetter level,
        final Fluid fluid,
        final BlockPos sourcePos,
        final BlockState sourceState,
        final Direction direction,
        final BlockPos testPos,
        final BlockState testState,
        final FluidState testFluidState
    ) {
        return this.canMaybePassThrough(level, sourcePos, sourceState, direction, testPos, testState, testFluidState)
            && canHoldSpecificFluid(level, testPos, testState, fluid);
    }

    private boolean canMaybePassThrough(
        final BlockGetter level,
        final BlockPos sourcePos,
        final BlockState sourceState,
        final Direction direction,
        final BlockPos testPos,
        final BlockState testState,
        final FluidState testFluidState
    ) {
        return !this.isSourceBlockOfThisType(testFluidState)
            && canHoldAnyFluid(testState)
            && canPassThroughWall(direction, level, sourcePos, sourceState, testPos, testState);
    }

    private boolean isSourceBlockOfThisType(final FluidState state) {
        return state.getType().isSame(this) && state.isSource();
    }

    protected abstract int getSlopeFindDistance(LevelReader level);

    private int sourceNeighborCount(final LevelReader level, final BlockPos pos) {
        int count = 0;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos testPos = pos.relative(direction);
            FluidState testFluidState = level.getFluidState(testPos);
            if (this.isSourceBlockOfThisType(testFluidState)) {
                count++;
            }
        }

        return count;
    }

    protected Map<Direction, FluidState> getSpread(final ServerLevel level, final BlockPos pos, final BlockState state) {
        int lowest = 1000;
        Map<Direction, FluidState> result = Maps.newEnumMap(Direction.class);
        FlowingFluid.SpreadContext context = null;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos testPos = pos.relative(direction);
            BlockState testState = level.getBlockStateIfLoaded(testPos); // Paper - Prevent chunk loading from fluid flowing
            if (testState == null) continue; // Paper - Prevent chunk loading from fluid flowing
            FluidState testFluidState = testState.getFluidState();
            if (this.canMaybePassThrough(level, pos, state, direction, testPos, testState, testFluidState)) {
                FluidState newFluid = this.getNewLiquid(level, testPos, testState);
                if (canHoldSpecificFluid(level, testPos, testState, newFluid.getType())) {
                    if (context == null) {
                        context = new FlowingFluid.SpreadContext(level, pos);
                    }

                    int distance;
                    if (context.isHole(testPos)) {
                        distance = 0;
                    } else {
                        distance = this.getSlopeDistance(level, testPos, 1, direction.getOpposite(), testState, context);
                    }

                    if (distance < lowest) {
                        result.clear();
                    }

                    if (distance <= lowest) {
                        if (testFluidState.canBeReplacedWith(level, testPos, newFluid.getType(), direction)) {
                            result.put(direction, newFluid);
                        }

                        lowest = distance;
                    }
                }
            }
        }

        return result;
    }

    private static boolean canHoldAnyFluid(final BlockState state) {
        Block block = state.getBlock();
        return block instanceof LiquidBlockContainer
            || !state.blocksMotion()
                && !(block instanceof DoorBlock)
                && !state.is(BlockTags.SIGNS)
                && !state.is(Blocks.LADDER)
                && !state.is(Blocks.SUGAR_CANE)
                && !state.is(Blocks.BUBBLE_COLUMN)
                && !state.is(Blocks.NETHER_PORTAL)
                && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY)
                && !state.is(Blocks.STRUCTURE_VOID);
    }

    private static boolean canHoldFluid(final BlockGetter level, final BlockPos pos, final BlockState state, final Fluid newFluid) {
        return canHoldAnyFluid(state) && canHoldSpecificFluid(level, pos, state, newFluid);
    }

    private static boolean canHoldSpecificFluid(final BlockGetter level, final BlockPos pos, final BlockState state, final Fluid newFluid) {
        return !(state.getBlock() instanceof LiquidBlockContainer container) || container.canPlaceLiquid(null, level, pos, state, newFluid);
    }

    protected abstract int getDropOff(LevelReader level);

    protected int getSpreadDelay(final Level level, final BlockPos pos, final FluidState oldFluidState, final FluidState newFluidState) {
        return this.getTickDelay(level);
    }

    @Override
    public void tick(final ServerLevel level, final BlockPos pos, BlockState blockState, FluidState fluidState) {
        if (!fluidState.isSource()) {
            FluidState newFluidState = this.getNewLiquid(level, pos, level.getBlockState(pos));
            int tickDelay = this.getSpreadDelay(level, pos, fluidState, newFluidState);
            if (newFluidState.isEmpty()) {
                fluidState = newFluidState;
                blockState = Blocks.AIR.defaultBlockState();
                // CraftBukkit start
                org.bukkit.event.block.FluidLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFluidLevelChangeEvent(level, pos, blockState);
                if (event.isCancelled()) {
                    return;
                }
                blockState = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getNewData()).getState();
                // CraftBukkit end
                level.setBlock(pos, blockState, Block.UPDATE_ALL);
            } else if (newFluidState != fluidState) {
                fluidState = newFluidState;
                blockState = fluidState.createLegacyBlock();
                // CraftBukkit start
                org.bukkit.event.block.FluidLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFluidLevelChangeEvent(level, pos, blockState);
                if (event.isCancelled()) {
                    return;
                }
                blockState = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getNewData()).getState();
                // CraftBukkit end
                level.setBlock(pos, blockState, Block.UPDATE_ALL);
                level.scheduleTick(pos, fluidState.getType(), tickDelay);
            }
        }

        this.spread(level, pos, blockState, fluidState);
    }

    protected static int getLegacyLevel(final FluidState fluidState) {
        return fluidState.isSource() ? 0 : 8 - Math.min(fluidState.getAmount(), 8) + (fluidState.getValue(FALLING) ? 8 : 0);
    }

    private static boolean hasSameAbove(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        return fluidState.getType().isSame(level.getFluidState(pos.above()).getType());
    }

    @Override
    public float getHeight(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        return hasSameAbove(fluidState, level, pos) ? 1.0F : fluidState.getOwnHeight();
    }

    @Override
    public float getOwnHeight(final FluidState fluidState) {
        return fluidState.getAmount() / 9.0F;
    }

    @Override
    public abstract int getAmount(final FluidState fluidState);

    @Override
    public VoxelShape getShape(final FluidState state, final BlockGetter level, final BlockPos pos) {
        return state.getAmount() == 9 && hasSameAbove(state, level, pos)
            ? Shapes.block()
            : this.shapes.computeIfAbsent(state, fluidState -> Shapes.box(0.0, 0.0, 0.0, 1.0, fluidState.getHeight(level, pos), 1.0));
    }

    private record BlockStatePairKey(BlockState first, BlockState second, Direction direction) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof FlowingFluid.BlockStatePairKey that
                && this.first == that.first
                && this.second == that.second
                && this.direction == that.direction;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(this.first);
            result = 31 * result + System.identityHashCode(this.second);
            return 31 * result + this.direction.hashCode();
        }
    }

    protected class SpreadContext {
        private final BlockGetter level;
        private final BlockPos origin;
        private final Short2ObjectMap<BlockState> stateCache = new Short2ObjectOpenHashMap<>();
        private final Short2BooleanMap holeCache = new Short2BooleanOpenHashMap();

        private SpreadContext(final BlockGetter level, final BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        public BlockState getBlockState(final BlockPos pos) {
            return this.getBlockState(pos, this.getCacheKey(pos));
        }

        // Paper start - Prevent chunk loading from fluid flowing
        public @org.jspecify.annotations.Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
            return this.getBlockState(pos, this.getCacheKey(pos), false);
        }
        // Paper end - Prevent chunk loading from fluid flowing

        private BlockState getBlockState(final BlockPos pos, final short key) {
            // Paper start - Prevent chunk loading from fluid flowing
            return this.getBlockState(pos, key, true);
        }

        private @org.jspecify.annotations.Nullable BlockState getBlockState(final BlockPos pos, final short key, final boolean load) {
            BlockState blockState = this.stateCache.get(key);
            if (blockState == null) {
                blockState = load ? this.level.getBlockState(pos) : this.level.getBlockStateIfLoaded(pos);
                if (blockState != null) {
                    this.stateCache.put(key, blockState);
                }
            }
            return blockState;
            // Paper end - Prevent chunk loading from fluid flowing
        }

        public boolean isHole(final BlockPos pos) {
            return this.holeCache.computeIfAbsent(this.getCacheKey(pos), key -> {
                BlockState state = this.getBlockState(pos, key);
                BlockPos below = pos.below();
                BlockState belowState = this.level.getBlockState(below);
                return FlowingFluid.this.isWaterHole(this.level, pos, state, below, belowState);
            });
        }

        private short getCacheKey(final BlockPos pos) {
            int relativeX = pos.getX() - this.origin.getX();
            int relativeZ = pos.getZ() - this.origin.getZ();
            return (short)((relativeX + 128 & 0xFF) << 8 | relativeZ + 128 & 0xFF);
        }
    }
}

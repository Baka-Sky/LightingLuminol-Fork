package net.minecraft.world.level.portal;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jspecify.annotations.Nullable;

public class PortalShape {
    private static final int MIN_WIDTH = 2;
    public static final int MAX_WIDTH = 21;
    private static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 21;
    private static final BlockBehaviour.StatePredicate FRAME = (state, level, pos) -> state.is(Blocks.OBSIDIAN);
    private static final float SAFE_TRAVEL_MAX_ENTITY_XY = 4.0F;
    private static final double SAFE_TRAVEL_MAX_VERTICAL_DELTA = 1.0;
    private final Direction.Axis axis;
    private final Direction rightDir;
    private final int numPortalBlocks;
    private final BlockPos bottomLeft;
    private final int height;
    private final int width;
    // CraftBukkit start - add field
    private final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks;

    private PortalShape(
        final Direction.Axis axis, final int portalBlockCount, final Direction rightDir, final BlockPos bottomLeft, final int width, final int height, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks
    ) {
        this.blocks = blocks;
        // CraftBukkit end
        this.axis = axis;
        this.numPortalBlocks = portalBlockCount;
        this.rightDir = rightDir;
        this.bottomLeft = bottomLeft;
        this.width = width;
        this.height = height;
    }

    public static Optional<PortalShape> findEmptyPortalShape(final LevelAccessor level, final BlockPos pos, final Direction.Axis preferredAxis) {
        return findPortalShape(level, pos, shape -> shape.isValid() && shape.numPortalBlocks == 0, preferredAxis);
    }

    public static Optional<PortalShape> findPortalShape(
        final LevelAccessor level, final BlockPos pos, final Predicate<PortalShape> isValid, final Direction.Axis preferredAxis
    ) {
        Optional<PortalShape> firstAxis = Optional.of(findAnyShape(level, pos, preferredAxis)).filter(isValid);
        if (firstAxis.isPresent()) {
            return firstAxis;
        }

        Direction.Axis otherAxis = preferredAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        return Optional.of(findAnyShape(level, pos, otherAxis)).filter(isValid);
    }

    public static PortalShape findAnyShape(final BlockGetter level, final BlockPos pos, final Direction.Axis axis) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blocks = new org.bukkit.craftbukkit.util.BlockStateListPopulator(((LevelAccessor) level).getMinecraftWorld()); // CraftBukkit
        Direction rightDir = axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
        BlockPos bottomLeft = calculateBottomLeft(level, rightDir, pos, blocks); // CraftBukkit
        if (bottomLeft == null) {
            return new PortalShape(axis, 0, rightDir, pos, 0, 0, blocks); // CraftBukkit
        }

        int width = calculateWidth(level, bottomLeft, rightDir, blocks); // CraftBukkit
        if (width == 0) {
            return new PortalShape(axis, 0, rightDir, bottomLeft, 0, 0, blocks); // CraftBukkit
        }

        MutableInt portalBlockCountOutput = new MutableInt();
        int height = calculateHeight(level, bottomLeft, rightDir, width, portalBlockCountOutput, blocks); // CraftBukkit
        return new PortalShape(axis, portalBlockCountOutput.intValue(), rightDir, bottomLeft, width, height, blocks); // CraftBukkit
    }

    private static @Nullable BlockPos calculateBottomLeft(final BlockGetter level, final Direction rightDir, BlockPos pos, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks) { // CraftBukkit
        int minY = Math.max(level.getMinY(), pos.getY() - 21);

        while (pos.getY() > minY && isEmpty(level.getBlockState(pos.below()))) {
            pos = pos.below();
        }

        Direction leftDir = rightDir.getOpposite();
        int edge = getDistanceUntilEdgeAboveFrame(level, pos, leftDir, blocks) - 1; // CraftBukkit
        return edge < 0 ? null : pos.relative(leftDir, edge);
    }

    private static int calculateWidth(final BlockGetter level, final BlockPos bottomLeft, final Direction rightDir, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks) { // CraftBukkit
        int width = getDistanceUntilEdgeAboveFrame(level, bottomLeft, rightDir, blocks); // CraftBukkit
        return width >= 2 && width <= 21 ? width : 0;
    }

    private static int getDistanceUntilEdgeAboveFrame(final BlockGetter level, final BlockPos pos, final Direction direction, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks) { // CraftBukkit
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (int width = 0; width <= 21; width++) {
            blockPos.set(pos).move(direction, width);
            BlockState blockState = level.getBlockState(blockPos);
            if (!isEmpty(blockState)) {
                if (FRAME.test(blockState, level, blockPos)) {
                    blocks.setBlock(blockPos, blockState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); // CraftBukkit - lower left / right
                    return width;
                }
                break;
            }

            BlockState belowState = level.getBlockState(blockPos.move(Direction.DOWN));
            if (!FRAME.test(belowState, level, blockPos)) {
                break;
            }
            blocks.setBlock(blockPos, belowState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); // CraftBukkit - bottom row
        }

        return 0;
    }

    private static int calculateHeight(
        final BlockGetter level, final BlockPos bottomLeft, final Direction rightDir, final int width, final MutableInt portalBlockCount, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks // CraftBukkit
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int height = getDistanceUntilTop(level, bottomLeft, rightDir, pos, width, portalBlockCount, blocks); // CraftBukkit
        return height >= 3 && height <= 21 && hasTopFrame(level, bottomLeft, rightDir, pos, width, height, blocks) ? height : 0; // CraftBukkit
    }

    private static boolean hasTopFrame(
        final BlockGetter level, final BlockPos bottomLeft, final Direction rightDir, final BlockPos.MutableBlockPos pos, final int width, final int height, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks // CraftBukkit
    ) {
        for (int i = 0; i < width; i++) {
            BlockPos.MutableBlockPos framePos = pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, i);
            if (!FRAME.test(level.getBlockState(framePos), level, framePos)) {
                return false;
            }
            blocks.setBlock(framePos, level.getBlockState(framePos), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); // CraftBukkit - upper row
        }

        return true;
    }

    private static int getDistanceUntilTop(
        final BlockGetter level,
        final BlockPos bottomLeft,
        final Direction rightDir,
        final BlockPos.MutableBlockPos pos,
        final int width,
        final MutableInt portalBlockCount, final org.bukkit.craftbukkit.util.BlockStateListPopulator blocks // CraftBukkit
    ) {
        for (int height = 0; height < 21; height++) {
            pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, -1);
            if (!FRAME.test(level.getBlockState(pos), level, pos)) {
                return height;
            }

            pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, width);
            if (!FRAME.test(level.getBlockState(pos), level, pos)) {
                return height;
            }

            for (int i = 0; i < width; i++) {
                pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, i);
                BlockState state = level.getBlockState(pos);
                if (!isEmpty(state)) {
                    return height;
                }

                if (state.is(Blocks.NETHER_PORTAL)) {
                    portalBlockCount.increment();
                }
            }
            // CraftBukkit start - left and right
            blocks.setBlock(pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, -1), level.getBlockState(pos), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            blocks.setBlock(pos.set(bottomLeft).move(Direction.UP, height).move(rightDir, width), level.getBlockState(pos), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            // CraftBukkit end
        }

        return 21;
    }

    private static boolean isEmpty(final BlockState state) {
        return state.isAir() || state.is(BlockTags.FIRE) || state.is(Blocks.NETHER_PORTAL);
    }

    public boolean isValid() {
        return this.width >= 2 && this.width <= 21 && this.height >= 3 && this.height <= 21;
    }

    // CraftBukkit start - return boolean, add entity
    public boolean createPortalBlocks(final LevelAccessor level, final Entity entity) {
        org.bukkit.World bworld = level.getMinecraftWorld().getWorld();
        // Copy below for loop
        BlockState portalState = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, this.axis);
        BlockPos.betweenClosed(this.bottomLeft, this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1))
            .forEach(pos -> this.blocks.setBlock(pos, portalState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        org.bukkit.event.world.PortalCreateEvent event = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) this.blocks.getSnapshotBlocks(), bworld, (entity == null) ? null : entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.FIRE);
        level.getMinecraftWorld().getServer().server.getPluginManager().callEvent(event); // todo the list is not really mutable here unlike other call and the portal frame is included

        if (event.isCancelled()) {
            return false;
        }
        // CraftBukkit end
        BlockPos.betweenClosed(this.bottomLeft, this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1))
            .forEach(pos -> level.setBlock(pos, portalState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        return true; // CraftBukkit
    }

    public boolean isComplete() {
        return this.isValid() && this.numPortalBlocks == this.width * this.height;
    }

    public static Vec3 getRelativePosition(
        final BlockUtil.FoundRectangle largestRectangleAround, final Direction.Axis axis, final Vec3 position, final EntityDimensions dimensions
    ) {
        double width = (double)largestRectangleAround.axis1Size - dimensions.width();
        double height = (double)largestRectangleAround.axis2Size - dimensions.height();
        BlockPos bottomMin = largestRectangleAround.minCorner;
        double relativeRight;
        if (width > 0.0) {
            double bottomStart = bottomMin.get(axis) + dimensions.width() / 2.0;
            relativeRight = Mth.clamp(Mth.inverseLerp(position.get(axis) - bottomStart, 0.0, width), 0.0, 1.0);
        } else {
            relativeRight = 0.5;
        }

        double relativeUp;
        if (height > 0.0) {
            Direction.Axis heightAxis = Direction.Axis.Y;
            relativeUp = Mth.clamp(Mth.inverseLerp(position.get(heightAxis) - bottomMin.get(heightAxis), 0.0, height), 0.0, 1.0);
        } else {
            relativeUp = 0.0;
        }

        Direction.Axis forwardAxis = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        double relativeForward = position.get(forwardAxis) - (bottomMin.get(forwardAxis) + 0.5);
        return new Vec3(relativeRight, relativeUp, relativeForward);
    }

    public static Vec3 findCollisionFreePosition(final Vec3 bottomCenter, final ServerLevel serverLevel, final Entity entity, final EntityDimensions dimensions) {
        if (!(dimensions.width() > 4.0F) && !(dimensions.height() > 4.0F)) {
            double halfHeight = dimensions.height() / 2.0;
            Vec3 center = bottomCenter.add(0.0, halfHeight, 0.0);
            VoxelShape allowedCenters = Shapes.create(
                AABB.ofSize(center, dimensions.width(), 0.0, dimensions.width()).expandTowards(0.0, 1.0, 0.0).inflate(1.0E-6)
            );
            Optional<Vec3> collisionFreePosition = serverLevel.findFreePosition(
                entity, allowedCenters, center, dimensions.width(), dimensions.height(), dimensions.width()
            );
            Optional<Vec3> collisionFreeBottomCenter = collisionFreePosition.map(vec -> vec.subtract(0.0, halfHeight, 0.0));
            return collisionFreeBottomCenter.orElse(bottomCenter);
        } else {
            return bottomCenter;
        }
    }
}

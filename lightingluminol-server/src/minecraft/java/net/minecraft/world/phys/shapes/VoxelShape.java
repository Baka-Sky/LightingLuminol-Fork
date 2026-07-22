package net.minecraft.world.phys.shapes;

import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jspecify.annotations.Nullable;

public abstract class VoxelShape implements ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape { // Paper - optimise collisions
    public final DiscreteVoxelShape shape; // Paper - optimise collisions - public
    private @Nullable VoxelShape @Nullable [] faces;

    // Paper start - optimise collisions
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private AABB singleAABBRepresentation;
    private double[] rootCoordinatesX;
    private double[] rootCoordinatesY;
    private double[] rootCoordinatesZ;
    private ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData cachedShapeData;
    private boolean isEmpty;
    private ca.spottedleaf.moonrise.patches.collisions.shape.CachedToAABBs cachedToAABBs;
    private AABB cachedBounds;
    private Boolean isFullBlock;
    private Boolean occludesFullBlock;

    // must be power of two
    private static final int MERGED_CACHE_SIZE = 16;
    private ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache[] mergedORCache;

    @Override
    public final double moonrise$offsetX() {
        return this.offsetX;
    }

    @Override
    public final double moonrise$offsetY() {
        return this.offsetY;
    }

    @Override
    public final double moonrise$offsetZ() {
        return this.offsetZ;
    }

    @Override
    public final AABB moonrise$getSingleAABBRepresentation() {
        return this.singleAABBRepresentation;
    }

    @Override
    public final double[] moonrise$rootCoordinatesX() {
        return this.rootCoordinatesX;
    }

    @Override
    public final double[] moonrise$rootCoordinatesY() {
        return this.rootCoordinatesY;
    }

    @Override
    public final double[] moonrise$rootCoordinatesZ() {
        return this.rootCoordinatesZ;
    }

    private static double[] extractRawArray(final DoubleList list) {
        if (list instanceof it.unimi.dsi.fastutil.doubles.DoubleArrayList rawList) {
            final double[] raw = rawList.elements();
            final int expected = rawList.size();
            if (raw.length == expected) {
                return raw;
            } else {
                return java.util.Arrays.copyOf(raw, expected);
            }
        } else {
            return list.toDoubleArray();
        }
    }

    @Override
    public final void moonrise$initCache() {
        this.cachedShapeData = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionDiscreteVoxelShape)this.shape).moonrise$getOrCreateCachedShapeData();
        this.isEmpty = this.cachedShapeData.isEmpty();

        final DoubleList xList = this.getCoords(Direction.Axis.X);
        final DoubleList yList = this.getCoords(Direction.Axis.Y);
        final DoubleList zList = this.getCoords(Direction.Axis.Z);

        if (xList instanceof OffsetDoubleList offsetDoubleList) {
            this.offsetX = offsetDoubleList.offset;
            this.rootCoordinatesX = extractRawArray(offsetDoubleList.delegate);
        } else {
            this.rootCoordinatesX = extractRawArray(xList);
        }

        if (yList instanceof OffsetDoubleList offsetDoubleList) {
            this.offsetY = offsetDoubleList.offset;
            this.rootCoordinatesY = extractRawArray(offsetDoubleList.delegate);
        } else {
            this.rootCoordinatesY = extractRawArray(yList);
        }

        if (zList instanceof OffsetDoubleList offsetDoubleList) {
            this.offsetZ = offsetDoubleList.offset;
            this.rootCoordinatesZ = extractRawArray(offsetDoubleList.delegate);
        } else {
            this.rootCoordinatesZ = extractRawArray(zList);
        }

        if (this.cachedShapeData.hasSingleAABB()) {
            this.singleAABBRepresentation = new AABB(
                this.rootCoordinatesX[0] + this.offsetX, this.rootCoordinatesY[0] + this.offsetY, this.rootCoordinatesZ[0] + this.offsetZ,
                this.rootCoordinatesX[1] + this.offsetX, this.rootCoordinatesY[1] + this.offsetY, this.rootCoordinatesZ[1] + this.offsetZ
            );
            this.cachedBounds = this.singleAABBRepresentation;
        }
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData moonrise$getCachedVoxelData() {
        return this.cachedShapeData;
    }

    private VoxelShape[] faceShapeClampedCache;

    @Override
    public final VoxelShape moonrise$getFaceShapeClamped(final Direction direction) {
        if (this.isEmpty) {
            return (VoxelShape)(Object)this;
        }
        if ((VoxelShape)(Object)this == Shapes.block()) {
            return (VoxelShape)(Object)this;
        }

        VoxelShape[] cache = this.faceShapeClampedCache;
        if (cache != null) {
            final VoxelShape ret = cache[direction.ordinal()];
            if (ret != null) {
                return ret;
            }
        }


        if (cache == null) {
            this.faceShapeClampedCache = cache = new VoxelShape[6];
        }

        final Direction.Axis axis = direction.getAxis();

        final VoxelShape ret;

        if (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            if (DoubleMath.fuzzyEquals(this.max(axis), 1.0, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) {
                ret = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.sliceShape((VoxelShape)(Object)this, axis, this.shape.getSize(axis) - 1);
            } else {
                ret = Shapes.empty();
            }
        } else {
            if (DoubleMath.fuzzyEquals(this.min(axis), 0.0, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) {
                ret = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.sliceShape((VoxelShape)(Object)this, axis, 0);
            } else {
                ret = Shapes.empty();
            }
        }

        cache[direction.ordinal()] = ret;

        return ret;
    }

    private boolean computeOccludesFullBlock() {
        if (this.isEmpty) {
            this.occludesFullBlock = Boolean.FALSE;
            return false;
        }

        if (this.moonrise$isFullBlock()) {
            this.occludesFullBlock = Boolean.TRUE;
            return true;
        }

        final AABB singleAABB = this.singleAABBRepresentation;
        if (singleAABB != null) {
            // check if the bounding box encloses the full cube
            final boolean ret =
                (singleAABB.minY <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && singleAABB.maxY >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) &&
                    (singleAABB.minX <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && singleAABB.maxX >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) &&
                    (singleAABB.minZ <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && singleAABB.maxZ >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON));
            this.occludesFullBlock = Boolean.valueOf(ret);
            return ret;
        }

        final boolean ret = !Shapes.joinIsNotEmpty(Shapes.block(), ((VoxelShape)(Object)this), BooleanOp.ONLY_FIRST);
        this.occludesFullBlock = Boolean.valueOf(ret);
        return ret;
    }

    @Override
    public final boolean moonrise$occludesFullBlock() {
        final Boolean ret = this.occludesFullBlock;
        if (ret != null) {
            return ret.booleanValue();
        }

        return this.computeOccludesFullBlock();
    }

    @Override
    public final boolean moonrise$occludesFullBlockIfCached() {
        final Boolean ret = this.occludesFullBlock;
        return ret != null ? ret.booleanValue() : false;
    }

    private static int hash(final VoxelShape key) {
        return it.unimi.dsi.fastutil.HashCommon.mix(System.identityHashCode(key));
    }

    @Override
    public final VoxelShape moonrise$orUnoptimized(final VoxelShape other) {
        // don't cache simple cases
        if (((VoxelShape)(Object)this) == other) {
            return other;
        }

        if (this.isEmpty) {
            return other;
        }

        if (other.isEmpty()) {
            return (VoxelShape)(Object)this;
        }

        // try this cache first
        final int thisCacheKey = hash(other) & (MERGED_CACHE_SIZE - 1);
        final ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache cached = this.mergedORCache == null ? null : this.mergedORCache[thisCacheKey];
        if (cached != null && cached.key() == other) {
            return cached.result();
        }

        // try other cache
        final int otherCacheKey = hash((VoxelShape)(Object)this) & (MERGED_CACHE_SIZE - 1);
        final ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache otherCache = ((VoxelShape)(Object)other).mergedORCache == null ? null : ((VoxelShape)(Object)other).mergedORCache[otherCacheKey];
        if (otherCache != null && otherCache.key() == (VoxelShape)(Object)this) {
            return otherCache.result();
        }

        // note: unsure if joinUnoptimized(1, 2, OR) == joinUnoptimized(2, 1, OR) for all cases
        final VoxelShape result = Shapes.joinUnoptimized((VoxelShape)(Object)this, other, BooleanOp.OR);

        if (cached != null && otherCache == null) {
            // try to use second cache instead of replacing an entry in this cache
            if (((VoxelShape)(Object)other).mergedORCache == null) {
                ((VoxelShape)(Object)other).mergedORCache = new ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache[MERGED_CACHE_SIZE];
            }
            ((VoxelShape)(Object)other).mergedORCache[otherCacheKey] = new ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache((VoxelShape)(Object)this, result);
        } else {
            // line is not occupied or other cache line is full
            // always bias to replace this cache, as this cache is the first we check
            if (this.mergedORCache == null) {
                this.mergedORCache = new ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache[MERGED_CACHE_SIZE];
            }
            this.mergedORCache[thisCacheKey] = new ca.spottedleaf.moonrise.patches.collisions.shape.MergedORCache(other, result);
        }

        return result;
    }

    private static DoubleList offsetList(final double[] src, final double by) {
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList wrap = it.unimi.dsi.fastutil.doubles.DoubleArrayList.wrap(src);
        if (by == 0.0) {
            return wrap;
        }
        return new OffsetDoubleList(wrap, by);
    }

    private List<AABB> toAabbsUncached() {
        final List<AABB> ret;
        if (this.singleAABBRepresentation != null) {
            ret = new java.util.ArrayList<>(1);
            ret.add(this.singleAABBRepresentation);
        } else {
            ret = new java.util.ArrayList<>();
            final double[] coordsX = this.rootCoordinatesX;
            final double[] coordsY = this.rootCoordinatesY;
            final double[] coordsZ = this.rootCoordinatesZ;

            final double offX = this.offsetX;
            final double offY = this.offsetY;
            final double offZ = this.offsetZ;

            this.shape.forAllBoxes((final int minX, final int minY, final int minZ,
                                    final int maxX, final int maxY, final int maxZ) -> {
                ret.add(new AABB(
                    coordsX[minX] + offX,
                    coordsY[minY] + offY,
                    coordsZ[minZ] + offZ,


                    coordsX[maxX] + offX,
                    coordsY[maxY] + offY,
                    coordsZ[maxZ] + offZ
                ));
            }, true);
        }

        // cache result
        this.cachedToAABBs = new ca.spottedleaf.moonrise.patches.collisions.shape.CachedToAABBs(ret, false, 0.0, 0.0, 0.0);

        return ret;
    }

    private boolean computeFullBlock() {
        Boolean ret;
        if (this.isEmpty) {
            ret = Boolean.FALSE;
        } else if ((VoxelShape)(Object)this == Shapes.block()) {
            ret = Boolean.TRUE;
        } else {
            final AABB singleAABB = this.singleAABBRepresentation;
            if (singleAABB == null) {
                final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData shapeData = this.cachedShapeData;
                final int sMinX = shapeData.minFullX();
                final int sMinY = shapeData.minFullY();
                final int sMinZ = shapeData.minFullZ();

                final int sMaxX = shapeData.maxFullX();
                final int sMaxY = shapeData.maxFullY();
                final int sMaxZ = shapeData.maxFullZ();

                if (Math.abs(this.rootCoordinatesX[sMinX] + this.offsetX) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                    Math.abs(this.rootCoordinatesY[sMinY] + this.offsetY) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                    Math.abs(this.rootCoordinatesZ[sMinZ] + this.offsetZ) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&

                    Math.abs(1.0 - (this.rootCoordinatesX[sMaxX] + this.offsetX)) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                    Math.abs(1.0 - (this.rootCoordinatesY[sMaxY] + this.offsetY)) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                    Math.abs(1.0 - (this.rootCoordinatesZ[sMaxZ] + this.offsetZ)) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) {

                    // index = z + y*sizeZ + x*(sizeZ*sizeY)

                    final int sizeY = shapeData.sizeY();
                    final int sizeZ = shapeData.sizeZ();

                    final long[] bitset = shapeData.voxelSet();

                    ret = Boolean.TRUE;

                    check_full:
                    for (int x = sMinX; x < sMaxX; ++x) {
                        for (int y = sMinY; y < sMaxY; ++y) {
                            final int baseIndex = y*sizeZ + x*(sizeZ*sizeY);
                            if (!ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.isRangeSet(bitset, baseIndex + sMinZ, baseIndex + sMaxZ)) {
                                ret = Boolean.FALSE;
                                break check_full;
                            }
                        }
                    }
                } else {
                    ret = Boolean.FALSE;
                }
            } else {
                ret = Boolean.valueOf(
                    Math.abs(singleAABB.minX) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                        Math.abs(singleAABB.minY) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                        Math.abs(singleAABB.minZ) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&

                        Math.abs(1.0 - singleAABB.maxX) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                        Math.abs(1.0 - singleAABB.maxY) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON &&
                        Math.abs(1.0 - singleAABB.maxZ) <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON
                );
            }
        }

        this.isFullBlock = ret;

        return ret.booleanValue();
    }

    @Override
    public final boolean moonrise$isFullBlock() {
        final Boolean ret = this.isFullBlock;

        if (ret != null) {
            return ret.booleanValue();
        }

        return this.computeFullBlock();
    }

    private static BlockHitResult clip(final AABB aabb, final Vec3 from, final Vec3 to, final BlockPos offset) {
        final double[] minDistanceArr = new double[] { 1.0 };
        final double diffX = to.x - from.x;
        final double diffY = to.y - from.y;
        final double diffZ = to.z - from.z;

        final Direction direction = AABB.getDirection(aabb.move(offset), from, minDistanceArr, null, diffX, diffY, diffZ);

        if (direction == null) {
            return null;
        }

        final double minDistance = minDistanceArr[0];
        return new BlockHitResult(from.add(minDistance * diffX, minDistance * diffY, minDistance * diffZ), direction, offset, false);
    }

    private VoxelShape calculateFaceDirect(final Direction direction, final Direction.Axis axis, final double[] coords, final double offset) {
        if (coords.length == 2 &&
            DoubleMath.fuzzyEquals(coords[0] + offset, 0.0, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) &&
            DoubleMath.fuzzyEquals(coords[1] + offset, 1.0, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) {
            return (VoxelShape)(Object)this;
        }

        final boolean positiveDir = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;

        // see findIndex
        final int index = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.findFloor(
            coords, offset, (positiveDir ? (1.0 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) : (0.0 + ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)),
            0, coords.length - 1
        );

        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.sliceShape(
            (VoxelShape)(Object)this, axis, index
        );
    }
    // Paper end - optimise collisions

    protected VoxelShape(final DiscreteVoxelShape shape) {
        this.shape = shape;
    }

    public double min(final Direction.Axis axis) {
        // Paper start - optimise collisions
        final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData shapeData = this.cachedShapeData;
        switch (axis) {
            case X: {
                final int idx = shapeData.minFullX();
                return idx >= shapeData.sizeX() ? Double.POSITIVE_INFINITY : (this.rootCoordinatesX[idx] + this.offsetX);
            }
            case Y: {
                final int idx = shapeData.minFullY();
                return idx >= shapeData.sizeY() ? Double.POSITIVE_INFINITY : (this.rootCoordinatesY[idx] + this.offsetY);
            }
            case Z: {
                final int idx = shapeData.minFullZ();
                return idx >= shapeData.sizeZ() ? Double.POSITIVE_INFINITY : (this.rootCoordinatesZ[idx] + this.offsetZ);
            }
            default: {
                // should never get here
                return Double.POSITIVE_INFINITY;
            }
        }
        // Paper end - optimise collisions
    }

    public double max(final Direction.Axis axis) {
        // Paper start - optimise collisions
        final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData shapeData = this.cachedShapeData;
        switch (axis) {
            case X: {
                final int idx = shapeData.maxFullX();
                return idx <= 0 ? Double.NEGATIVE_INFINITY : (this.rootCoordinatesX[idx] + this.offsetX);
            }
            case Y: {
                final int idx = shapeData.maxFullY();
                return idx <= 0 ? Double.NEGATIVE_INFINITY : (this.rootCoordinatesY[idx] + this.offsetY);
            }
            case Z: {
                final int idx = shapeData.maxFullZ();
                return idx <= 0 ? Double.NEGATIVE_INFINITY : (this.rootCoordinatesZ[idx] + this.offsetZ);
            }
            default: {
                // should never get here
                return Double.NEGATIVE_INFINITY;
            }
        }
        // Paper end - optimise collisions
    }

    public AABB bounds() {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            throw Util.pauseInIde(new UnsupportedOperationException("No bounds for empty shape."));
        }
        AABB cached = this.cachedBounds;
        if (cached != null) {
            return cached;
        }

        final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData shapeData = this.cachedShapeData;

        final double[] coordsX = this.rootCoordinatesX;
        final double[] coordsY = this.rootCoordinatesY;
        final double[] coordsZ = this.rootCoordinatesZ;

        final double offX = this.offsetX;
        final double offY = this.offsetY;
        final double offZ = this.offsetZ;

        // note: if not empty, then there is one full AABB so no bounds checks are needed on the minFull/maxFull indices
        cached = new AABB(
            coordsX[shapeData.minFullX()] + offX,
            coordsY[shapeData.minFullY()] + offY,
            coordsZ[shapeData.minFullZ()] + offZ,

            coordsX[shapeData.maxFullX()] + offX,
            coordsY[shapeData.maxFullY()] + offY,
            coordsZ[shapeData.maxFullZ()] + offZ
        );

        this.cachedBounds = cached;
        return cached;
        // Paper end - optimise collisions
    }

    public VoxelShape singleEncompassing() {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            return Shapes.empty();
        }
        return Shapes.create(this.bounds());
        // Paper end - optimise collisions
    }

    protected double get(final Direction.Axis axis, final int i) {
        // Paper start - optimise collisions
        final int idx = i;
        switch (axis) {
            case X: {
                return this.rootCoordinatesX[idx] + this.offsetX;
            }
            case Y: {
                return this.rootCoordinatesY[idx] + this.offsetY;
            }
            case Z: {
                return this.rootCoordinatesZ[idx] + this.offsetZ;
            }
            default: {
                throw new IllegalStateException("Unknown axis: " + axis);
            }
        }
        // Paper end - optimise collisions
    }

    public abstract DoubleList getCoords(final Direction.Axis axis);

    public boolean isEmpty() {
        return this.isEmpty; // Paper - optimise collisions
    }

    public VoxelShape move(final Vec3 delta) {
        return this.move(delta.x, delta.y, delta.z);
    }

    public VoxelShape move(final Vec3i delta) {
        return this.move(delta.getX(), delta.getY(), delta.getZ());
    }

    public VoxelShape move(final double dx, final double dy, final double dz) {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            return Shapes.empty();
        }

        final ArrayVoxelShape ret = new ArrayVoxelShape(
            this.shape,
            offsetList(this.rootCoordinatesX, this.offsetX + dx),
            offsetList(this.rootCoordinatesY, this.offsetY + dy),
            offsetList(this.rootCoordinatesZ, this.offsetZ + dz)
        );

        final ca.spottedleaf.moonrise.patches.collisions.shape.CachedToAABBs cachedToAABBs = this.cachedToAABBs;
        if (cachedToAABBs != null) {
            ((VoxelShape)(Object)ret).cachedToAABBs = ca.spottedleaf.moonrise.patches.collisions.shape.CachedToAABBs.offset(cachedToAABBs, dx, dy, dz);
        }

        return ret;
        // Paper end - optimise collisions
    }

    public VoxelShape optimize() {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            return Shapes.empty();
        }

        if (this.singleAABBRepresentation != null) {
            // note: the isFullBlock() is fuzzy, and Shapes.create() is also fuzzy which would return block()
            return this.moonrise$isFullBlock() ? Shapes.block() : (VoxelShape)(Object)this;
        }

        final List<AABB> aabbs = this.toAabbs();

        if (aabbs.isEmpty()) {
            // We are a SliceShape, which does not properly fill isEmpty for every case
            return Shapes.empty();
        }

        if (aabbs.size() == 1) {
            final AABB singleAABB = aabbs.get(0);
            final VoxelShape ret = Shapes.create(singleAABB);

            // forward AABB cache
            if (((VoxelShape)(Object)ret).cachedToAABBs == null) {
                ((VoxelShape)(Object)ret).cachedToAABBs = this.cachedToAABBs;
            }

            return ret;
        } else {
            // reduce complexity of joins by splitting the merges (old complexity: n^2, new: nlogn)

            // set up flat array so that this merge is done in-place
            final VoxelShape[] tmp = new VoxelShape[aabbs.size()];

            // initialise as unmerged
            for (int i = 0, len = aabbs.size(); i < len; ++i) {
                tmp[i] = Shapes.create(aabbs.get(i));
            }

            int size = aabbs.size();
            while (size > 1) {
                int newSize = 0;
                for (int i = 0; i < size; i += 2) {
                    final int next = i + 1;
                    if (next >= size) {
                        // nothing to merge with, so leave it for next iteration
                        tmp[newSize++] = tmp[i];
                        break;
                    } else {
                        // merge with adjacent
                        final VoxelShape first = tmp[i];
                        final VoxelShape second = tmp[next];

                        tmp[newSize++] = Shapes.joinUnoptimized(first, second, BooleanOp.OR);
                    }
                }
                size = newSize;
            }

            final VoxelShape ret = tmp[0];

            // forward AABB cache
            if (((VoxelShape)(Object)ret).cachedToAABBs == null) {
                ((VoxelShape)(Object)ret).cachedToAABBs = this.cachedToAABBs;
            }

            return ret;
        }
        // Paper end - optimise collisions
    }

    public void forAllEdges(final Shapes.DoubleLineConsumer consumer) {
        this.shape
            .forAllEdges(
                (xi1, yi1, zi1, xi2, yi2, zi2) -> consumer.consume(
                    this.get(Direction.Axis.X, xi1),
                    this.get(Direction.Axis.Y, yi1),
                    this.get(Direction.Axis.Z, zi1),
                    this.get(Direction.Axis.X, xi2),
                    this.get(Direction.Axis.Y, yi2),
                    this.get(Direction.Axis.Z, zi2)
                ),
                true
            );
    }

    public void forAllBoxes(final Shapes.DoubleLineConsumer consumer) {
        DoubleList xCoords = this.getCoords(Direction.Axis.X);
        DoubleList yCoords = this.getCoords(Direction.Axis.Y);
        DoubleList zCoords = this.getCoords(Direction.Axis.Z);
        this.shape
            .forAllBoxes(
                (xi1, yi1, zi1, xi2, yi2, zi2) -> consumer.consume(
                    xCoords.getDouble(xi1),
                    yCoords.getDouble(yi1),
                    zCoords.getDouble(zi1),
                    xCoords.getDouble(xi2),
                    yCoords.getDouble(yi2),
                    zCoords.getDouble(zi2)
                ),
                true
            );
    }

    public List<AABB> toAabbs() {
        // Paper start - optimise collisions
        ca.spottedleaf.moonrise.patches.collisions.shape.CachedToAABBs cachedToAABBs = this.cachedToAABBs;
        if (cachedToAABBs != null) {
            if (!cachedToAABBs.isOffset()) {
                return cachedToAABBs.aabbs();
            }

            // all we need to do is offset the cache
            cachedToAABBs = cachedToAABBs.removeOffset();
            // update cache
            this.cachedToAABBs = cachedToAABBs;

            return cachedToAABBs.aabbs();
        }

        // make new cache
        return this.toAabbsUncached();
        // Paper end - optimise collisions
    }

    public double min(final Direction.Axis aAxis, final double b, final double c) {
        Direction.Axis bAxis = AxisCycle.FORWARD.cycle(aAxis);
        Direction.Axis cAxis = AxisCycle.BACKWARD.cycle(aAxis);
        int bi = this.findIndex(bAxis, b);
        int ci = this.findIndex(cAxis, c);
        int i = this.shape.firstFull(aAxis, bi, ci);
        return i >= this.shape.getSize(aAxis) ? Double.POSITIVE_INFINITY : this.get(aAxis, i);
    }

    public double max(final Direction.Axis aAxis, final double b, final double c) {
        Direction.Axis bAxis = AxisCycle.FORWARD.cycle(aAxis);
        Direction.Axis cAxis = AxisCycle.BACKWARD.cycle(aAxis);
        int bi = this.findIndex(bAxis, b);
        int ci = this.findIndex(cAxis, c);
        int i = this.shape.lastFull(aAxis, bi, ci);
        return i <= 0 ? Double.NEGATIVE_INFINITY : this.get(aAxis, i);
    }

    protected int findIndex(final Direction.Axis axis, final double coord) {
        // Paper start - optimise collisions
        final double value = coord;
        switch (axis) {
            case X: {
                final double[] values = this.rootCoordinatesX;
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.findFloor(
                    values, this.offsetX, value, 0, values.length - 1
                );
            }
            case Y: {
                final double[] values = this.rootCoordinatesY;
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.findFloor(
                    values, this.offsetY, value, 0, values.length - 1
                );
            }
            case Z: {
                final double[] values = this.rootCoordinatesZ;
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.findFloor(
                    values, this.offsetZ, value, 0, values.length - 1
                );
            }
            default: {
                throw new IllegalStateException("Unknown axis: " + axis);
            }
        }
        // Paper end - optimise collisions
    }

    public @Nullable BlockHitResult clip(final Vec3 from, final Vec3 to, final BlockPos pos) {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            return null;
        }

        final Vec3 directionOpposite = to.subtract(from);
        if (directionOpposite.lengthSqr() < ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) {
            return null;
        }

        final Vec3 fromBehind = from.add(directionOpposite.scale(0.001));
        final double fromBehindOffsetX = fromBehind.x - (double) pos.getX();
        final double fromBehindOffsetY = fromBehind.y - (double) pos.getY();
        final double fromBehindOffsetZ = fromBehind.z - (double) pos.getZ();

        final AABB singleAABB = this.singleAABBRepresentation;
        if (singleAABB != null) {
            if (singleAABB.contains(fromBehindOffsetX, fromBehindOffsetY, fromBehindOffsetZ)) {
                return new BlockHitResult(fromBehind, Direction.getApproximateNearest(directionOpposite.x, directionOpposite.y, directionOpposite.z).getOpposite(), pos, true);
            }
            return clip(singleAABB, from, to, pos);
        }

        if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.strictlyContains((VoxelShape) (Object) this, fromBehindOffsetX, fromBehindOffsetY, fromBehindOffsetZ)) {
            return new BlockHitResult(fromBehind, Direction.getApproximateNearest(directionOpposite.x, directionOpposite.y, directionOpposite.z).getOpposite(), pos, true);
        }

        return AABB.clip(((VoxelShape) (Object) this).toAabbs(), from, to, pos);
        // Paper end - optimise collisions
    }

    public Optional<Vec3> closestPointTo(final Vec3 point) {
        // Paper start - optimise collisions
        if (this.isEmpty) {
            return Optional.empty();
        }

        Vec3 ret = null;
        double retDistance = Double.MAX_VALUE;

        final List<AABB> aabbs = this.toAabbs();
        for (int i = 0, len = aabbs.size(); i < len; ++i) {
            final AABB aabb = aabbs.get(i);
            final double x = Mth.clamp(point.x, aabb.minX, aabb.maxX);
            final double y = Mth.clamp(point.y, aabb.minY, aabb.maxY);
            final double z = Mth.clamp(point.z, aabb.minZ, aabb.maxZ);

            double dist = point.distanceToSqr(x, y, z);
            if (dist < retDistance) {
                ret = new Vec3(x, y, z);
                retDistance = dist;
            }
        }

        return Optional.ofNullable(ret);
        // Paper end - optimise collisions
    }

    public VoxelShape getFaceShape(final Direction direction) {
        if (!this.isEmpty() && this != Shapes.block()) {
            if (this.faces != null) {
                VoxelShape face = this.faces[direction.ordinal()];
                if (face != null) {
                    return face;
                }
            } else {
                this.faces = new VoxelShape[6];
            }

            VoxelShape face = this.calculateFace(direction);
            this.faces[direction.ordinal()] = face;
            return face;
        } else {
            return this;
        }
    }

    private VoxelShape calculateFace(final Direction direction) {
        // Paper start - optimise collisions
        final Direction.Axis axis = direction.getAxis();
        switch (axis) {
            case X: {
                return this.calculateFaceDirect(direction, axis, this.rootCoordinatesX, this.offsetX);
            }
            case Y: {
                return this.calculateFaceDirect(direction, axis, this.rootCoordinatesY, this.offsetY);
            }
            case Z: {
                return this.calculateFaceDirect(direction, axis, this.rootCoordinatesZ, this.offsetZ);
            }
            default: {
                throw new IllegalStateException("Unknown axis: " + axis);
            }
        }
        // Paper end - optimise collisions
    }

    protected boolean isCubeLike() {
        for (Direction.Axis axis : Direction.Axis.VALUES) {
            if (!this.isCubeLikeAlong(axis)) {
                return false;
            }
        }

        return true;
    }

    private boolean isCubeLikeAlong(final Direction.Axis axis) {
        DoubleList coords = this.getCoords(axis);
        return coords.size() == 2 && DoubleMath.fuzzyEquals(coords.getDouble(0), 0.0, 1.0E-7) && DoubleMath.fuzzyEquals(coords.getDouble(1), 1.0, 1.0E-7);
    }

    // Paper start - optimise collisions
    public double collide(final Direction.Axis axis, final AABB source, final double source_move) {
        if (this.isEmpty) {
            return source_move;
        }
        if (Math.abs(source_move) < ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) {
            return 0.0;
        }
        switch (axis) {
            case X: {
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.collideX((VoxelShape) (Object) this, source, source_move);
            }
            case Y: {
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.collideY((VoxelShape) (Object) this, source, source_move);
            }
            case Z: {
                return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.collideZ((VoxelShape) (Object) this, source, source_move);
            }
            default: {
                throw new RuntimeException("Unknown axis: " + axis);
            }
        }
    }
    // Paper end - optimise collisions

    protected double collideX(final AxisCycle transform, final AABB moving, double distance) {
        if (this.isEmpty()) {
            return distance;
        }

        if (Math.abs(distance) < 1.0E-7) {
            return 0.0;
        }

        AxisCycle inverse = transform.inverse();
        Direction.Axis aAxis = inverse.cycle(Direction.Axis.X);
        Direction.Axis bAxis = inverse.cycle(Direction.Axis.Y);
        Direction.Axis cAxis = inverse.cycle(Direction.Axis.Z);
        double maxA = moving.max(aAxis);
        double minA = moving.min(aAxis);
        int aMin = this.findIndex(aAxis, minA + 1.0E-7);
        int aMax = this.findIndex(aAxis, maxA - 1.0E-7);
        int bMin = Math.max(0, this.findIndex(bAxis, moving.min(bAxis) + 1.0E-7));
        int bMax = Math.min(this.shape.getSize(bAxis), this.findIndex(bAxis, moving.max(bAxis) - 1.0E-7) + 1);
        int cMin = Math.max(0, this.findIndex(cAxis, moving.min(cAxis) + 1.0E-7));
        int cMax = Math.min(this.shape.getSize(cAxis), this.findIndex(cAxis, moving.max(cAxis) - 1.0E-7) + 1);
        int aSize = this.shape.getSize(aAxis);
        if (distance > 0.0) {
            for (int a = aMax + 1; a < aSize; a++) {
                for (int b = bMin; b < bMax; b++) {
                    for (int c = cMin; c < cMax; c++) {
                        if (this.shape.isFullWide(inverse, a, b, c)) {
                            double newDistance = this.get(aAxis, a) - maxA;
                            if (newDistance >= -1.0E-7) {
                                distance = Math.min(distance, newDistance);
                            }

                            return distance;
                        }
                    }
                }
            }
        } else if (distance < 0.0) {
            for (int a = aMin - 1; a >= 0; a--) {
                for (int b = bMin; b < bMax; b++) {
                    for (int c = cMin; c < cMax; c++) {
                        if (this.shape.isFullWide(inverse, a, b, c)) {
                            double newDistance = this.get(aAxis, a + 1) - minA;
                            if (newDistance <= 1.0E-7) {
                                distance = Math.max(distance, newDistance);
                            }

                            return distance;
                        }
                    }
                }
            }
        }

        return distance;
    }

    @Override
    public boolean equals(final Object obj) {
        return super.equals(obj);
    }

    @Override
    public String toString() {
        return this.isEmpty() ? "EMPTY" : "VoxelShape[" + this.bounds() + "]";
    }
}

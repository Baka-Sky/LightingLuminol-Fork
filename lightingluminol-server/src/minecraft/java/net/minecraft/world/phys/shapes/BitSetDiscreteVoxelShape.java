package net.minecraft.world.phys.shapes;

import java.util.BitSet;
import net.minecraft.core.Direction;

public final class BitSetDiscreteVoxelShape extends DiscreteVoxelShape {
    public final BitSet storage; // Paper - optimise collisions - public
    public int xMin; // Paper - optimise collisions - public
    public int yMin; // Paper - optimise collisions - public
    public int zMin; // Paper - optimise collisions - public
    public int xMax; // Paper - optimise collisions - public
    public int yMax; // Paper - optimise collisions - public
    public int zMax; // Paper - optimise collisions - public

    public BitSetDiscreteVoxelShape(final int xSize, final int ySize, final int zSize) {
        super(xSize, ySize, zSize);
        this.storage = new BitSet(xSize * ySize * zSize);
        this.xMin = xSize;
        this.yMin = ySize;
        this.zMin = zSize;
    }

    public static BitSetDiscreteVoxelShape withFilledBounds(
        final int xSize, final int ySize, final int zSize, final int xMin, final int yMin, final int zMin, final int xMax, final int yMax, final int zMax
    ) {
        BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(xSize, ySize, zSize);
        shape.xMin = xMin;
        shape.yMin = yMin;
        shape.zMin = zMin;
        shape.xMax = xMax;
        shape.yMax = yMax;
        shape.zMax = zMax;

        for (int x = xMin; x < xMax; x++) {
            for (int y = yMin; y < yMax; y++) {
                for (int z = zMin; z < zMax; z++) {
                    shape.fillUpdateBounds(x, y, z, false);
                }
            }
        }

        return shape;
    }

    public BitSetDiscreteVoxelShape(final DiscreteVoxelShape voxelShape) {
        super(voxelShape.xSize, voxelShape.ySize, voxelShape.zSize);
        if (voxelShape instanceof BitSetDiscreteVoxelShape bitSetDiscreteVoxelShape) {
            this.storage = (BitSet)bitSetDiscreteVoxelShape.storage.clone();
        } else {
            this.storage = new BitSet(this.xSize * this.ySize * this.zSize);

            for (int x = 0; x < this.xSize; x++) {
                for (int y = 0; y < this.ySize; y++) {
                    for (int z = 0; z < this.zSize; z++) {
                        if (voxelShape.isFull(x, y, z)) {
                            this.storage.set(this.getIndex(x, y, z));
                        }
                    }
                }
            }
        }

        this.xMin = voxelShape.firstFull(Direction.Axis.X);
        this.yMin = voxelShape.firstFull(Direction.Axis.Y);
        this.zMin = voxelShape.firstFull(Direction.Axis.Z);
        this.xMax = voxelShape.lastFull(Direction.Axis.X);
        this.yMax = voxelShape.lastFull(Direction.Axis.Y);
        this.zMax = voxelShape.lastFull(Direction.Axis.Z);
    }

    private int getIndex(final int x, final int y, final int z) {
        return (x * this.ySize + y) * this.zSize + z;
    }

    @Override
    public boolean isFull(final int x, final int y, final int z) {
        return this.storage.get(this.getIndex(x, y, z));
    }

    private void fillUpdateBounds(final int x, final int y, final int z, final boolean updateBounds) {
        this.storage.set(this.getIndex(x, y, z));
        if (updateBounds) {
            this.xMin = Math.min(this.xMin, x);
            this.yMin = Math.min(this.yMin, y);
            this.zMin = Math.min(this.zMin, z);
            this.xMax = Math.max(this.xMax, x + 1);
            this.yMax = Math.max(this.yMax, y + 1);
            this.zMax = Math.max(this.zMax, z + 1);
        }
    }

    @Override
    public void fill(final int x, final int y, final int z) {
        this.fillUpdateBounds(x, y, z, true);
    }

    @Override
    public boolean isEmpty() {
        return this.storage.isEmpty();
    }

    @Override
    public int firstFull(final Direction.Axis axis) {
        return axis.choose(this.xMin, this.yMin, this.zMin);
    }

    @Override
    public int lastFull(final Direction.Axis axis) {
        return axis.choose(this.xMax, this.yMax, this.zMax);
    }

    public static BitSetDiscreteVoxelShape join(
        final DiscreteVoxelShape first,
        final DiscreteVoxelShape second,
        final IndexMerger xMerger,
        final IndexMerger yMerger,
        final IndexMerger zMerger,
        final BooleanOp op
    ) {
        BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(xMerger.size() - 1, yMerger.size() - 1, zMerger.size() - 1);
        int[] bounds = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        xMerger.forMergedIndexes((x1, x2, xr) -> {
            boolean[] updatedSlice = new boolean[]{false};
            yMerger.forMergedIndexes((y1, y2, yr) -> {
                boolean[] updatedColumn = new boolean[]{false};
                zMerger.forMergedIndexes((z1, z2, zr) -> {
                    if (op.apply(first.isFullWide(x1, y1, z1), second.isFullWide(x2, y2, z2))) {
                        shape.storage.set(shape.getIndex(xr, yr, zr));
                        bounds[2] = Math.min(bounds[2], zr);
                        bounds[5] = Math.max(bounds[5], zr);
                        updatedColumn[0] = true;
                    }

                    return true;
                });
                if (updatedColumn[0]) {
                    bounds[1] = Math.min(bounds[1], yr);
                    bounds[4] = Math.max(bounds[4], yr);
                    updatedSlice[0] = true;
                }

                return true;
            });
            if (updatedSlice[0]) {
                bounds[0] = Math.min(bounds[0], xr);
                bounds[3] = Math.max(bounds[3], xr);
            }

            return true;
        });
        shape.xMin = bounds[0];
        shape.yMin = bounds[1];
        shape.zMin = bounds[2];
        shape.xMax = bounds[3] + 1;
        shape.yMax = bounds[4] + 1;
        shape.zMax = bounds[5] + 1;
        return shape;
    }

    // Paper start - optimise collisions
    public static void forAllBoxes(final DiscreteVoxelShape shape, final DiscreteVoxelShape.IntLineConsumer consumer, final boolean mergeAdjacent) {
        // Paper - remove debug
        // called with the shape of a VoxelShape, so we can expect the cache to exist
        final ca.spottedleaf.moonrise.patches.collisions.shape.CachedShapeData cache = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionDiscreteVoxelShape) shape).moonrise$getOrCreateCachedShapeData();

        final int sizeX = cache.sizeX();
        final int sizeY = cache.sizeY();
        final int sizeZ = cache.sizeZ();

        int indexX;
        int indexY = 0;
        int indexZ;

        int incY = sizeZ;
        int incX = sizeZ * sizeY;

        long[] bitset = cache.voxelSet();

        // index = z + y*size_z + x*(size_z*size_y)

        if (!mergeAdjacent) {
            // due to the odd selection of loop order (which does affect behavior, unfortunately) we can't simply
            // increment an index in the Z loop, and have to perform this trash (keeping track of 3 counters) to avoid
            // the multiplication
            for (int y = 0; y < sizeY; ++y, indexY += incY) {
                indexX = indexY;
                for (int x = 0; x < sizeX; ++x, indexX += incX) {
                    indexZ = indexX;
                    for (int z = 0; z < sizeZ; ++z, ++indexZ) {
                        if ((bitset[indexZ >>> 6] & (1L << indexZ)) != 0L) {
                            consumer.consume(x, y, z, x + 1, y + 1, z + 1);
                        }
                    }
                }
            }
        } else {
            // same notes about loop order as the above
            // this branch is actually important to optimise, as it affects uncached toAabbs() (which affects optimize())

            // only clone when we may write to it
            bitset = ca.spottedleaf.moonrise.common.util.MixinWorkarounds.clone(bitset);

            for (int y = 0; y < sizeY; ++y, indexY += incY) {
                indexX = indexY;
                for (int x = 0; x < sizeX; ++x, indexX += incX) {
                    for (int zIdx = indexX, endIndex = indexX + sizeZ; zIdx < endIndex; ) {
                        final int firstSetZ = ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.firstSet(bitset, zIdx, endIndex);

                        if (firstSetZ == -1) {
                            break;
                        }

                        int lastSetZ = ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.firstClear(bitset, firstSetZ, endIndex);
                        if (lastSetZ == -1) {
                            lastSetZ = endIndex;
                        }

                        ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.clearRange(bitset, firstSetZ, lastSetZ);

                        // try to merge neighbouring on the X axis
                        int endX = x + 1; // exclusive
                        for (int neighbourIdxStart = firstSetZ + incX, neighbourIdxEnd = lastSetZ + incX;
                             endX < sizeX && ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.isRangeSet(bitset, neighbourIdxStart, neighbourIdxEnd);
                             neighbourIdxStart += incX, neighbourIdxEnd += incX) {

                            ++endX;
                            ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.clearRange(bitset, neighbourIdxStart, neighbourIdxEnd);
                        }

                        // try to merge neighbouring on the Y axis

                        int endY; // exclusive
                        int firstSetZY, lastSetZY;
                        y_merge:
                        for (endY = y + 1, firstSetZY = firstSetZ + incY, lastSetZY = lastSetZ + incY; endY < sizeY;
                             firstSetZY += incY, lastSetZY += incY) {

                            // test the whole XZ range
                            for (int testX = x, start = firstSetZY, end = lastSetZY; testX < endX;
                                 ++testX, start += incX, end += incX) {
                                if (!ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.isRangeSet(bitset, start, end)) {
                                    break y_merge;
                                }
                            }

                            ++endY;

                            // passed, so we can clear it
                            for (int testX = x, start = firstSetZY, end = lastSetZY; testX < endX;
                                 ++testX, start += incX, end += incX) {
                                ca.spottedleaf.moonrise.common.util.FlatBitsetUtil.clearRange(bitset, start, end);
                            }
                        }

                        consumer.consume(x, y, firstSetZ - indexX, endX, endY, lastSetZ - indexX);
                        zIdx = lastSetZ;
                    }
                }
            }
        }
    }
    // Paper end - optimise collisions

    private boolean isZStripFull(final int startZ, final int endZ, final int x, final int y) {
        return x < this.xSize && y < this.ySize && this.storage.nextClearBit(this.getIndex(x, y, startZ)) >= this.getIndex(x, y, endZ);
    }

    private boolean isXZRectangleFull(final int startX, final int endX, final int startZ, final int endZ, final int y) {
        for (int x = startX; x < endX; x++) {
            if (!this.isZStripFull(startZ, endZ, x, y)) {
                return false;
            }
        }

        return true;
    }

    private void clearZStrip(final int startZ, final int endZ, final int x, final int y) {
        this.storage.clear(this.getIndex(x, y, startZ), this.getIndex(x, y, endZ));
    }

    public boolean isInterior(final int x, final int y, final int z) {
        boolean isInterior = x > 0 && x < this.xSize - 1 && y > 0 && y < this.ySize - 1 && z > 0 && z < this.zSize - 1;
        return isInterior
            && this.isFull(x, y, z)
            && this.isFull(x - 1, y, z)
            && this.isFull(x + 1, y, z)
            && this.isFull(x, y - 1, z)
            && this.isFull(x, y + 1, z)
            && this.isFull(x, y, z - 1)
            && this.isFull(x, y, z + 1);
    }
}

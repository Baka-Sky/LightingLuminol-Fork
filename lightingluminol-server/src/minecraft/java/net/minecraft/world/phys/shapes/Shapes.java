package net.minecraft.world.phys.shapes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.math.DoubleMath;
import com.google.common.math.IntMath;
import com.mojang.math.OctahedralGroup;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Shapes {
    public static final double EPSILON = 1.0E-7;
    public static final double BIG_EPSILON = 1.0E-6;
    private static final VoxelShape BLOCK = Util.make(() -> {
        // Paper start - optimise collisions
        final DiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(1, 1, 1);
        shape.fill(0, 0, 0);

        return new ArrayVoxelShape(
            shape,
            ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE, ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE
        );
        // Paper end - optimise collisions
    });
    private static final Vec3 BLOCK_CENTER = new Vec3(0.5, 0.5, 0.5);
    public static final VoxelShape INFINITY = box(
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY
    );
    private static final VoxelShape EMPTY = new ArrayVoxelShape(
        new BitSetDiscreteVoxelShape(0, 0, 0),
        new DoubleArrayList(new double[]{0.0}),
        new DoubleArrayList(new double[]{0.0}),
        new DoubleArrayList(new double[]{0.0})
    );

    public static VoxelShape empty() {
        return EMPTY;
    }

    public static VoxelShape block() {
        return BLOCK;
    }

    // Paper start - optimise collisions
    private static final DoubleArrayList[] PARTS_BY_BITS = new DoubleArrayList[] {
        DoubleArrayList.wrap(generateCubeParts(1 << 0)),
        DoubleArrayList.wrap(generateCubeParts(1 << 1)),
        DoubleArrayList.wrap(generateCubeParts(1 << 2)),
        DoubleArrayList.wrap(generateCubeParts(1 << 3))
    };

    private static double[] generateCubeParts(final int parts) {
        // note: parts is a power of two, so we do not need to worry about loss of precision here
        // note: parts is from [2^0, 2^3]
        final double inc = 1.0 / (double)parts;

        final double[] ret = new double[parts + 1];
        double val = 0.0;
        for (int i = 0; i <= parts; ++i) {
            ret[i] = val;
            val += inc;
        }

        return ret;
    }

    private static boolean mergedMayOccludeBlock(final VoxelShape shape1, final VoxelShape shape2) {
        // if the combined bounds of the two shapes cannot occlude, then neither can the merged
        final AABB bounds1 = shape1.bounds();
        final AABB bounds2 = shape2.bounds();

        final double minX = Math.min(bounds1.minX, bounds2.minX);
        final double minY = Math.min(bounds1.minY, bounds2.minY);
        final double minZ = Math.min(bounds1.minZ, bounds2.minZ);

        final double maxX = Math.max(bounds1.maxX, bounds2.maxX);
        final double maxY = Math.max(bounds1.maxY, bounds2.maxY);
        final double maxZ = Math.max(bounds1.maxZ, bounds2.maxZ);

        return (minX <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && maxX >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) &&
            (minY <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && maxY >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON)) &&
            (minZ <= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON && maxZ >= (1 - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON));
    }
    // Paper end - optimise collisions

    public static VoxelShape box(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (!(minX > maxX) && !(minY > maxY) && !(minZ > maxZ)) {
            return create(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            throw new IllegalArgumentException("The min values need to be smaller or equals to the max values");
        }
    }

    public static VoxelShape create(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        // Paper start - optimise collisions
        if (!(maxX - minX < 1.0E-7) && !(maxY - minY < 1.0E-7) && !(maxZ - minZ < 1.0E-7)) {
            final int bitsX = findBits(minX, maxX);
            final int bitsY = findBits(minY, maxY);
            final int bitsZ = findBits(minZ, maxZ);
            if (bitsX >= 0 && bitsY >= 0 && bitsZ >= 0) {
                if (bitsX == 0 && bitsY == 0 && bitsZ == 0) {
                    return BLOCK;
                } else {
                    final int sizeX = 1 << bitsX;
                    final int sizeY = 1 << bitsY;
                    final int sizeZ = 1 << bitsZ;
                    final BitSetDiscreteVoxelShape shape = BitSetDiscreteVoxelShape.withFilledBounds(
                        sizeX, sizeY, sizeZ,
                        (int)Math.round(minX * (double)sizeX), (int)Math.round(minY * (double)sizeY), (int)Math.round(minZ * (double)sizeZ),
                        (int)Math.round(maxX * (double)sizeX), (int)Math.round(maxY * (double)sizeY), (int)Math.round(maxZ * (double)sizeZ)
                    );
                    return new ArrayVoxelShape(
                        shape,
                        PARTS_BY_BITS[bitsX],
                        PARTS_BY_BITS[bitsY],
                        PARTS_BY_BITS[bitsZ]
                    );
                }
            } else {
                return new ArrayVoxelShape(
                    BLOCK.shape,
                    minX == 0.0 && maxX == 1.0 ? ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE : DoubleArrayList.wrap(new double[] { minX, maxX }),
                    minY == 0.0 && maxY == 1.0 ? ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE : DoubleArrayList.wrap(new double[] { minY, maxY }),
                    minZ == 0.0 && maxZ == 1.0 ? ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.ZERO_ONE : DoubleArrayList.wrap(new double[] { minZ, maxZ })
                );
            }
        } else {
            return EMPTY;
        }
        // Paper end - optimise collisions
    }

    public static VoxelShape create(final AABB aabb) {
        return create(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
    }

    @VisibleForTesting
    static int findBits(final double min, final double max) {
        if (!(min < -1.0E-7) && !(max > 1.0000001)) {
            for (int bits = 0; bits <= 3; bits++) {
                int intervals = 1 << bits;
                double shMin = min * intervals;
                double shMax = max * intervals;
                boolean foundMin = Math.abs(shMin - Math.round(shMin)) < 1.0E-7 * intervals;
                boolean foundMax = Math.abs(shMax - Math.round(shMax)) < 1.0E-7 * intervals;
                if (foundMin && foundMax) {
                    return bits;
                }
            }

            return -1;
        } else {
            return -1;
        }
    }

    @VisibleForTesting
    static long lcm(final int first, final int second) {
        return (long)first * (second / IntMath.gcd(first, second));
    }

    public static VoxelShape or(final VoxelShape first, final VoxelShape second) {
        return join(first, second, BooleanOp.OR);
    }

    // Paper start - optimise collisions
    public static VoxelShape or(final VoxelShape shape1, final VoxelShape... others) {
        int size = others.length;
        if (size == 0) {
            return shape1;
        }

        // reduce complexity of joins by splitting the merges

        // add extra slot for first shape
        ++size;
        final VoxelShape[] tmp = Arrays.copyOf(others, size);
        // insert first shape
        tmp[size - 1] = shape1;

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

        return tmp[0].optimize();
        // Paper end - optimise collisions
    }

    public static VoxelShape join(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.joinOptimized(first, second, op); // Paper - optimise collisions
    }

    public static VoxelShape joinUnoptimized(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.joinUnoptimized(first, second, op); // Paper - optimise collisions
    }

    public static boolean joinIsNotEmpty(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isJoinNonEmpty(first, second, op); // Paper - optimise collisions
    }

    private static boolean joinIsNotEmpty(
        final IndexMerger xMerger,
        final IndexMerger yMerger,
        final IndexMerger zMerger,
        final DiscreteVoxelShape first,
        final DiscreteVoxelShape second,
        final BooleanOp op
    ) {
        return !xMerger.forMergedIndexes(
            (x1, x2, xr) -> yMerger.forMergedIndexes(
                (y1, y2, yr) -> zMerger.forMergedIndexes((z1, z2, zr) -> !op.apply(first.isFullWide(x1, y1, z1), second.isFullWide(x2, y2, z2)))
            )
        );
    }

    public static double collide(final Direction.Axis axis, final AABB moving, final Iterable<VoxelShape> shapes, double distance) {
        for (VoxelShape shape : shapes) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }

            distance = shape.collide(axis, moving, distance);
        }

        return distance;
    }

    // Paper start - optimise collisions
    public static boolean blockOccludes(final VoxelShape first, final VoxelShape second, final Direction direction) {
        if (first == BLOCK & second == BLOCK) {
            return true;
        }

        if (first.isEmpty() | second.isEmpty()) {
            return false;
        }

        // we optimise getOpposite, so we can use it
        // secondly, use our cache to retrieve sliced shape
        final VoxelShape newFirst = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)first).moonrise$getFaceShapeClamped(direction);
        if (newFirst.isEmpty()) {
            return false;
        }
        final VoxelShape newSecond = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)second).moonrise$getFaceShapeClamped(direction.getOpposite());
        if (newSecond.isEmpty()) {
            return false;
        }

        return !joinIsNotEmpty(newFirst, newSecond, BooleanOp.ONLY_FIRST);
        // Paper end - optimise collisions
    }

    // Paper start - optimise collisions
    public static boolean mergedFaceOccludes(final VoxelShape first, final VoxelShape second, final Direction direction) {
        // see if any of the shapes on their own occludes, only if cached
        if (((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)first).moonrise$occludesFullBlockIfCached() || ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)second).moonrise$occludesFullBlockIfCached()) {
            return true;
        }

        if (first.isEmpty() & second.isEmpty()) {
            return false;
        }

        // we optimise getOpposite, so we can use it
        // secondly, use our cache to retrieve sliced shape
        final VoxelShape newFirst = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)first).moonrise$getFaceShapeClamped(direction);
        final VoxelShape newSecond = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)second).moonrise$getFaceShapeClamped(direction.getOpposite());

        // see if any of the shapes on their own occludes, only if cached
        if (((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newFirst).moonrise$occludesFullBlockIfCached() || ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newSecond).moonrise$occludesFullBlockIfCached()) {
            return true;
        }

        final boolean firstEmpty = newFirst.isEmpty();
        final boolean secondEmpty = newSecond.isEmpty();

        if (firstEmpty & secondEmpty) {
            return false;
        }

        if (firstEmpty | secondEmpty) {
            return secondEmpty ? ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newFirst).moonrise$occludesFullBlock() : ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newSecond).moonrise$occludesFullBlock();
        }

        if (newFirst == newSecond) {
            return ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newFirst).moonrise$occludesFullBlock();
        }

        return mergedMayOccludeBlock(newFirst, newSecond) && ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)newFirst).moonrise$orUnoptimized(newSecond)).moonrise$occludesFullBlock();
    }
    // Paper end - optimise collisions

    // Paper start - optimise collisions
    public static boolean faceShapeOccludes(final VoxelShape shape1, final VoxelShape shape2) {
        if (((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape1).moonrise$occludesFullBlockIfCached() || ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape2).moonrise$occludesFullBlockIfCached()) {
            return true;
        }

        final boolean s1Empty = shape1.isEmpty();
        final boolean s2Empty = shape2.isEmpty();
        if (s1Empty & s2Empty) {
            return false;
        }

        if (s1Empty | s2Empty) {
            return s2Empty ? ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape1).moonrise$occludesFullBlock() : ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape2).moonrise$occludesFullBlock();
        }

        if (shape1 == shape2) {
            return ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape1).moonrise$occludesFullBlock();
        }

        return mergedMayOccludeBlock(shape1, shape2) && ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape1).moonrise$orUnoptimized(shape2)).moonrise$occludesFullBlock();
        // Paper end - optimise collisions
    }

    @VisibleForTesting
    private static IndexMerger createIndexMerger( // Paper - private
        final int cost, final DoubleList first, final DoubleList second, final boolean firstOnlyMatters, final boolean secondOnlyMatters
    ) {
        // Paper start - fast track the most common scenario
        // first is usually a DoubleArrayList with Infinite head/tails that falls to the final else clause
        // This is actually the most common path, so jump to it straight away
        if (first.getDouble(0) == Double.NEGATIVE_INFINITY && first.getDouble(first.size() - 1) == Double.POSITIVE_INFINITY) {
            return new IndirectMerger(first, second, firstOnlyMatters, secondOnlyMatters);
        }
        // Split out rest to hopefully inline the above
        return lessCommonMerge(cost, first, second, firstOnlyMatters, secondOnlyMatters);
    }

    private static IndexMerger lessCommonMerge(
        final int cost, final DoubleList first, final DoubleList second, final boolean firstOnlyMatters, final boolean secondOnlyMatters
    ) {
        // Paper end - fast track the most common scenario
        int firstSize = first.size() - 1;
        int secondSize = second.size() - 1;
        // Paper note - Rewrite below as optimized order if instead of nasty ternary
        if (first instanceof CubePointRange && second instanceof CubePointRange) {
            long size = lcm(firstSize, secondSize);
            if (cost * size <= 256L) {
                return new DiscreteCubeMerger(firstSize, secondSize);
            }
        }

        // Paper start - Identical happens more often than Disjoint
        if (firstSize == secondSize && Objects.equals(first, second)) {
            if (first instanceof IdenticalMerger firstMerger) {
                return firstMerger;
            } else if (second instanceof IdenticalMerger secondMerger) {
                return secondMerger;
            }
            return new IdenticalMerger(first);
        } else if (first.getDouble(firstSize) < second.getDouble(0) - 1.0E-7) {
            // Paper end - Identical happens more often than Disjoint
            return new NonOverlappingMerger(first, second, false);
        } else if (second.getDouble(secondSize) < first.getDouble(0) - 1.0E-7) {
            return new NonOverlappingMerger(second, first, true);
        } else {
            return new IndirectMerger(first, second, firstOnlyMatters, secondOnlyMatters); // Paper - Identical happens more often than Disjoint
        }
    }

    public static VoxelShape rotate(final VoxelShape shape, final OctahedralGroup rotation) {
        return rotate(shape, rotation, BLOCK_CENTER);
    }

    public static VoxelShape rotate(final VoxelShape shape, final OctahedralGroup rotation, final Vec3 rotationPoint) {
        if (rotation == OctahedralGroup.IDENTITY) {
            return shape;
        }

        DiscreteVoxelShape newDiscreteShape = shape.shape.rotate(rotation);
        if (shape instanceof CubeVoxelShape && BLOCK_CENTER.equals(rotationPoint)) {
            return new CubeVoxelShape(newDiscreteShape);
        }

        Direction.Axis newX = rotation.permutation().permuteAxis(Direction.Axis.X);
        Direction.Axis newY = rotation.permutation().permuteAxis(Direction.Axis.Y);
        Direction.Axis newZ = rotation.permutation().permuteAxis(Direction.Axis.Z);
        DoubleList newXs = shape.getCoords(newX);
        DoubleList newYs = shape.getCoords(newY);
        DoubleList newZs = shape.getCoords(newZ);
        boolean flipX = rotation.inverts(Direction.Axis.X);
        boolean flipY = rotation.inverts(Direction.Axis.Y);
        boolean flipZ = rotation.inverts(Direction.Axis.Z);
        return new ArrayVoxelShape(
            newDiscreteShape,
            flipAxisIfNeeded(newXs, flipX, rotationPoint.get(newX), rotationPoint.x),
            flipAxisIfNeeded(newYs, flipY, rotationPoint.get(newY), rotationPoint.y),
            flipAxisIfNeeded(newZs, flipZ, rotationPoint.get(newZ), rotationPoint.z)
        );
    }

    @VisibleForTesting
    static DoubleList flipAxisIfNeeded(final DoubleList newAxis, final boolean flip, final double newRelative, final double oldRelative) {
        if (!flip && newRelative == oldRelative) {
            return newAxis;
        }

        int size = newAxis.size();
        DoubleList newList = new DoubleArrayList(size);
        if (flip) {
            for (int i = size - 1; i >= 0; i--) {
                newList.add(-(newAxis.getDouble(i) - newRelative) + oldRelative);
            }
        } else {
            for (int i = 0; i >= 0 && i < size; i++) {
                newList.add(newAxis.getDouble(i) - newRelative + oldRelative);
            }
        }

        return newList;
    }

    public static boolean equal(final VoxelShape first, final VoxelShape second) {
        return !joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(final VoxelShape zAxis) {
        return rotateHorizontalAxis(zAxis, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(final VoxelShape zAxis, final Vec3 rotationCenter) {
        return Maps.newEnumMap(Map.of(Direction.Axis.Z, zAxis, Direction.Axis.X, rotate(zAxis, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter)));
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(final VoxelShape north) {
        return rotateAllAxis(north, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(final VoxelShape north, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.Axis.Z,
                north,
                Direction.Axis.X,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter),
                Direction.Axis.Y,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90, rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north) {
        return rotateHorizontal(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north, final OctahedralGroup initial) {
        return rotateHorizontal(north, initial, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north, final OctahedralGroup initial, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north) {
        return rotateAll(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north, final Vec3 rotationCenter) {
        return rotateAll(north, OctahedralGroup.IDENTITY, rotationCenter);
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north, final OctahedralGroup initial, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter),
                Direction.UP,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial), rotationCenter),
                Direction.DOWN,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(final VoxelShape north) {
        return rotateAttachFace(north, OctahedralGroup.IDENTITY);
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(final VoxelShape north, final OctahedralGroup initial) {
        return Map.of(
            AttachFace.WALL,
            rotateHorizontal(north, initial),
            AttachFace.FLOOR,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial)),
            AttachFace.CEILING,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(OctahedralGroup.BLOCK_ROT_X_90).compose(initial))
        );
    }

    public interface DoubleLineConsumer {
        void consume(double x1, double y1, double z1, double x2, double y2, double z2);
    }
}

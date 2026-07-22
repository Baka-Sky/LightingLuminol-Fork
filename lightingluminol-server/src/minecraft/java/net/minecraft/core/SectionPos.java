package net.minecraft.core;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.entity.EntityAccess;

public class SectionPos extends Vec3i {
    public static final int SECTION_BITS = 4;
    public static final int SECTION_SIZE = 16;
    public static final int SECTION_BLOCK_COUNT = 4096;
    public static final int SECTION_MASK = 15;
    public static final int SECTION_HALF_SIZE = 8;
    public static final int SECTION_MAX_INDEX = 15;
    private static final int PACKED_X_LENGTH = 22;
    private static final int PACKED_Y_LENGTH = 20;
    private static final int PACKED_Z_LENGTH = 22;
    private static final long PACKED_X_MASK = 4194303L;
    private static final long PACKED_Y_MASK = 1048575L;
    private static final long PACKED_Z_MASK = 4194303L;
    private static final int Y_OFFSET = 0;
    private static final int Z_OFFSET = 20;
    private static final int X_OFFSET = 42;
    private static final int RELATIVE_X_SHIFT = 8;
    private static final int RELATIVE_Y_SHIFT = 0;
    private static final int RELATIVE_Z_SHIFT = 4;
    public static final StreamCodec<ByteBuf, SectionPos> STREAM_CODEC = ByteBufCodecs.LONG.map(SectionPos::of, SectionPos::asLong);

    private SectionPos(final int x, final int y, final int z) {
        super(x, y, z);
    }

    public static SectionPos of(final int x, final int y, final int z) {
        return new SectionPos(x, y, z);
    }

    public static SectionPos of(final BlockPos pos) {
        return new SectionPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4); // Paper
    }

    public static SectionPos of(final ChunkPos pos, final int sectionY) {
        return new SectionPos(pos.x(), sectionY, pos.z());
    }

    public static SectionPos of(final EntityAccess entity) {
        return of(entity.blockPosition());
    }

    public static SectionPos of(final Position pos) {
        return new SectionPos(blockToSectionCoord(pos.x()), blockToSectionCoord(pos.y()), blockToSectionCoord(pos.z()));
    }

    public static SectionPos of(final long sectionNode) {
        return new SectionPos((int) (sectionNode >> 42), (int) (sectionNode << 44 >> 44), (int) (sectionNode << 22 >> 42)); // Paper
    }

    public static SectionPos bottomOf(final ChunkAccess chunk) {
        return of(chunk.getPos(), chunk.getMinSectionY());
    }

    public static long offset(final long sectionNode, final Direction offset) {
        return offset(sectionNode, offset.getStepX(), offset.getStepY(), offset.getStepZ());
    }

    // Paper start
    public static long getAdjacentFromBlockPos(int x, int y, int z, Direction direction) {
        return (((long) ((x >> 4) + direction.getStepX()) & 4194303L) << 42) | (((long) ((y >> 4) + direction.getStepY()) & 1048575L)) | (((long) ((z >> 4) + direction.getStepZ()) & 4194303L) << 20);
    }
    public static long getAdjacentFromSectionPos(int x, int y, int z, Direction direction) {
        return (((long) (x + direction.getStepX()) & 4194303L) << 42) | (((long) ((y) + direction.getStepY()) & 1048575L)) | (((long) (z + direction.getStepZ()) & 4194303L) << 20);
    }
    // Paper end
    public static long offset(final long sectionNode, final int stepX, final int stepY, final int stepZ) {
        return (((long) ((int) (sectionNode >> 42) + stepX) & 4194303L) << 42) | (((long) ((int) (sectionNode << 44 >> 44) + stepY) & 1048575L)) | (((long) ((int) (sectionNode << 22 >> 42) + stepZ) & 4194303L) << 20); // Paper - Simplify to reduce instruction count
    }

    public static int posToSectionCoord(final double pos) {
        return blockToSectionCoord(Mth.floor(pos));
    }

    public static int blockToSectionCoord(final int blockCoord) {
        return blockCoord >> 4;
    }

    public static int blockToSectionCoord(final double coord) {
        return Mth.floor(coord) >> 4;
    }

    public static int sectionRelative(final int blockCoord) {
        return blockCoord & 15;
    }

    public static short sectionRelativePos(final BlockPos pos) {
        return (short) ((pos.getX() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getY() & 15); // Paper - simplify/inline
    }

    public static int sectionRelativeX(final short relative) {
        return relative >>> 8 & 15;
    }

    public static int sectionRelativeY(final short relative) {
        return relative >>> 0 & 15;
    }

    public static int sectionRelativeZ(final short relative) {
        return relative >>> 4 & 15;
    }

    public int relativeToBlockX(final short relative) {
        return this.minBlockX() + sectionRelativeX(relative);
    }

    public int relativeToBlockY(final short relative) {
        return this.minBlockY() + sectionRelativeY(relative);
    }

    public int relativeToBlockZ(final short relative) {
        return this.minBlockZ() + sectionRelativeZ(relative);
    }

    public BlockPos relativeToBlockPos(final short relative) {
        return new BlockPos(this.relativeToBlockX(relative), this.relativeToBlockY(relative), this.relativeToBlockZ(relative));
    }

    public static int sectionToBlockCoord(final int sectionCoord) {
        return sectionCoord << 4;
    }

    public static int sectionToBlockCoord(final int sectionCoord, final int offset) {
        return sectionToBlockCoord(sectionCoord) + offset;
    }

    public static int x(final long sectionNode) {
        return (int)(sectionNode << 0 >> 42);
    }

    public static int y(final long sectionNode) {
        return (int)(sectionNode << 44 >> 44);
    }

    public static int z(final long sectionNode) {
        return (int)(sectionNode << 22 >> 42);
    }

    public int x() {
        return this.getX();
    }

    public int y() {
        return this.getY();
    }

    public int z() {
        return this.getZ();
    }

    public final int minBlockX() { // Paper - final
        return this.getX() << 4; // Paper - inline
    }

    public final int minBlockY() { // Paper - final
        return this.getY() << 4; // Paper - inline
    }

    public final int minBlockZ() { // Paper - final
        return this.getZ() << 4; // Paper - inline
    }

    public int maxBlockX() {
        return sectionToBlockCoord(this.x(), 15);
    }

    public int maxBlockY() {
        return sectionToBlockCoord(this.y(), 15);
    }

    public int maxBlockZ() {
        return sectionToBlockCoord(this.z(), 15);
    }

    public static long blockToSection(final long blockNode) {
        return (((long) (int) (blockNode >> 42) & 4194303L) << 42) | (((long) (int) ((blockNode << 52) >> 56) & 1048575L)) | (((long) (int) ((blockNode << 26) >> 42) & 4194303L) << 20); // Paper - Simplify to reduce instruction count
    }

    public static long getZeroNode(final int x, final int z) {
        return getZeroNode(asLong(x, 0, z));
    }

    public static long getZeroNode(final long sectionNode) {
        return sectionNode & -1048576L;
    }

    public static long sectionToChunk(final long sectionNode) {
        return ChunkPos.pack(x(sectionNode), z(sectionNode));
    }

    public BlockPos origin() {
        return new BlockPos(sectionToBlockCoord(this.x()), sectionToBlockCoord(this.y()), sectionToBlockCoord(this.z()));
    }

    public BlockPos center() {
        int delta = 8;
        return this.origin().offset(8, 8, 8);
    }

    public ChunkPos chunk() {
        return new ChunkPos(this.x(), this.z());
    }

    public static long asLong(final BlockPos pos) {
        return asLong(blockToSectionCoord(pos.getX()), blockToSectionCoord(pos.getY()), blockToSectionCoord(pos.getZ()));
    }

    // Paper start
    public static long blockPosAsSectionLong(int x, int y, int z) {
        return (((long) (x >> 4) & 4194303L) << 42) | (((long) (y >> 4) & 1048575L)) | (((long) (z >> 4) & 4194303L) << 20);
    }
    // Paper end
    public static long asLong(final int x, final int y, final int z) {
        return ((x & 4194303L) << 42) | ((y & 1048575L)) | ((z & 4194303L) << 20); // Paper - Simplify to reduce instruction count
    }

    public long asLong() {
        return ((this.getX() & 4194303L) << 42) | ((this.getY() & 1048575L)) | ((this.getZ() & 4194303L) << 20); // Paper - Simplify to reduce instruction count
    }

    @Override
    public SectionPos offset(final int x, final int y, final int z) {
        return x == 0 && y == 0 && z == 0 ? this : new SectionPos(this.x() + x, this.y() + y, this.z() + z);
    }

    public Stream<BlockPos> blocksInside() {
        return BlockPos.betweenClosedStream(this.minBlockX(), this.minBlockY(), this.minBlockZ(), this.maxBlockX(), this.maxBlockY(), this.maxBlockZ());
    }

    public static Stream<SectionPos> cube(final SectionPos center, final int radius) {
        return betweenClosedStream(center.getX() - radius, center.getY() - radius, center.getZ() - radius, center.getX() + radius, center.getY() + radius, center.getZ() + radius); // Paper - simplify/inline
    }

    public static Stream<SectionPos> aroundChunk(final ChunkPos center, final int radius, final int minSection, final int maxSection) {
        int x = center.x();
        int z = center.z();
        return betweenClosedStream(x - radius, minSection, z - radius, x + radius, maxSection, z + radius);
    }

    public static Stream<SectionPos> betweenClosedStream(final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        return StreamSupport.stream(new AbstractSpliterator<SectionPos>((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1), 64) {
            private final Cursor3D cursor = new Cursor3D(minX, minY, minZ, maxX, maxY, maxZ);

            @Override
            public boolean tryAdvance(final Consumer<? super SectionPos> action) {
                if (this.cursor.advance()) {
                    action.accept(new SectionPos(this.cursor.nextX(), this.cursor.nextY(), this.cursor.nextZ()));
                    return true;
                } else {
                    return false;
                }
            }
        }, false);
    }

    public static void aroundAndAtBlockPos(final BlockPos blockPos, final LongConsumer sectionConsumer) {
        aroundAndAtBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ(), sectionConsumer);
    }

    public static void aroundAndAtBlockPos(final long blockPos, final LongConsumer sectionConsumer) {
        aroundAndAtBlockPos(BlockPos.getX(blockPos), BlockPos.getY(blockPos), BlockPos.getZ(blockPos), sectionConsumer);
    }

    public static void aroundAndAtBlockPos(final int blockX, final int blockY, final int blockZ, final LongConsumer sectionConsumer) {
        int minSectionX = blockToSectionCoord(blockX - 1);
        int maxSectionX = blockToSectionCoord(blockX + 1);
        int minSectionY = blockToSectionCoord(blockY - 1);
        int maxSectionY = blockToSectionCoord(blockY + 1);
        int minSectionZ = blockToSectionCoord(blockZ - 1);
        int maxSectionZ = blockToSectionCoord(blockZ + 1);
        if (minSectionX == maxSectionX && minSectionY == maxSectionY && minSectionZ == maxSectionZ) {
            sectionConsumer.accept(asLong(minSectionX, minSectionY, minSectionZ));
        } else {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                        sectionConsumer.accept(asLong(sectionX, sectionY, sectionZ));
                    }
                }
            }
        }
    }
}

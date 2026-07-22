package net.minecraft.server.level;

import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

public class ChunkLevel {
    public static final int FULL_CHUNK_LEVEL = 33;
    public static final int BLOCK_TICKING_LEVEL = 32;
    public static final int ENTITY_TICKING_LEVEL = 31;
    private static final ChunkStep FULL_CHUNK_STEP = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL);
    public static final int RADIUS_AROUND_FULL_CHUNK = FULL_CHUNK_STEP.accumulatedDependencies().getRadius();
    public static final int MAX_LEVEL = 33 + RADIUS_AROUND_FULL_CHUNK;

    public static @Nullable ChunkStatus generationStatus(final int level) {
        return getStatusAroundFullChunk(level - FULL_CHUNK_LEVEL, null);
    }

    @Contract("_,!null->!null;_,_->_")
    public static @Nullable ChunkStatus getStatusAroundFullChunk(final int distanceToFullChunk, final @Nullable ChunkStatus defaultValue) {
        if (distanceToFullChunk > RADIUS_AROUND_FULL_CHUNK) {
            return defaultValue;
        } else {
            return distanceToFullChunk <= 0 ? ChunkStatus.FULL : FULL_CHUNK_STEP.accumulatedDependencies().get(distanceToFullChunk);
        }
    }

    public static ChunkStatus getStatusAroundFullChunk(final int distanceToFullChunk) {
        return getStatusAroundFullChunk(distanceToFullChunk, ChunkStatus.EMPTY);
    }

    public static int byStatus(final ChunkStatus status) {
        return FULL_CHUNK_LEVEL + FULL_CHUNK_STEP.getAccumulatedRadiusOf(status);
    }

    public static FullChunkStatus fullStatus(final int level) {
        if (level <= ENTITY_TICKING_LEVEL) {
            return FullChunkStatus.ENTITY_TICKING;
        } else if (level <= BLOCK_TICKING_LEVEL) {
            return FullChunkStatus.BLOCK_TICKING;
        } else {
            return level <= FULL_CHUNK_LEVEL ? FullChunkStatus.FULL : FullChunkStatus.INACCESSIBLE;
        }
    }

    public static int byStatus(final FullChunkStatus status) {
        return switch (status) {
            case INACCESSIBLE -> MAX_LEVEL;
            case FULL -> FULL_CHUNK_LEVEL;
            case BLOCK_TICKING -> BLOCK_TICKING_LEVEL;
            case ENTITY_TICKING -> ENTITY_TICKING_LEVEL;
        };
    }

    public static boolean isEntityTicking(final int level) {
        return level <= ENTITY_TICKING_LEVEL;
    }

    public static boolean isBlockTicking(final int level) {
        return level <= BLOCK_TICKING_LEVEL;
    }

    public static boolean isLoaded(final int level) {
        return level <= MAX_LEVEL;
    }
}

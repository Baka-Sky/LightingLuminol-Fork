package net.minecraft.world.level.levelgen;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;

public class WorldGenerationContext {
    private final int minY;
    private final int height;
    // Paper start - Flat bedrock generator settings
    private final net.minecraft.world.level.@org.jspecify.annotations.Nullable Level level;

    public WorldGenerationContext(final ChunkGenerator generator, final LevelHeightAccessor heightAccessor) {
        this(generator, heightAccessor, null);
    }
    public WorldGenerationContext(final ChunkGenerator generator, final LevelHeightAccessor heightAccessor, final net.minecraft.world.level.@org.jspecify.annotations.Nullable Level level) {
        this.level = level;
        // Paper end - Flat bedrock generator settings
        this.minY = Math.max(heightAccessor.getMinY(), generator.getMinY());
        this.height = Math.min(heightAccessor.getHeight(), generator.getGenDepth());
    }

    public int getMinGenY() {
        return this.minY;
    }

    public int getGenDepth() {
        return this.height;
    }

    // Paper start - Flat bedrock generator settings
    public net.minecraft.world.level.Level level() {
        if (this.level == null) {
            throw new NullPointerException("WorldGenerationContext was initialized without a Level, but WorldGenerationContext#level was called");
        }
        return this.level;
    }
    // Paper end - Flat bedrock generator settings
}

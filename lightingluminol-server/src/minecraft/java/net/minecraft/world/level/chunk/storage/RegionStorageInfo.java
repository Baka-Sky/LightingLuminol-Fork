package net.minecraft.world.level.chunk.storage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// Paper start - crazy hack to indicate what type the region file is
public record RegionStorageInfo(String level, ResourceKey<Level> dimension, String type, net.minecraft.util.datafix.DataFixTypes[] dfuType) {
    public RegionStorageInfo(String level, ResourceKey<Level> dimension, String type) {
        this(level, dimension, type, new net.minecraft.util.datafix.DataFixTypes[1]);
    }
    // Paper end - crazy hack to indicate what type the region file is
    public RegionStorageInfo withTypeSuffix(final String suffix) {
        return new RegionStorageInfo(this.level, this.dimension, this.type + suffix);
    }
}

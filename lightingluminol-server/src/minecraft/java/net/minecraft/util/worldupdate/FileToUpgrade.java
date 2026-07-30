package net.minecraft.util.worldupdate;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

public record FileToUpgrade(abomination.IRegionFile file, List<ChunkPos> chunksToUpgrade) { // Arbor - Configurable region file format
}

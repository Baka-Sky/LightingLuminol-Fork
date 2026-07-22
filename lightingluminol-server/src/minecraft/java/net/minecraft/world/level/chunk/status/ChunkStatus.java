package net.minecraft.world.level.chunk.status;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public class ChunkStatus implements ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkStatus { // Paper - rewrite chunk system
    public static final int MAX_STRUCTURE_DISTANCE = 8;
    private static final EnumSet<Heightmap.Types> WORLDGEN_HEIGHTMAPS = EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);
    public static final EnumSet<Heightmap.Types> FINAL_HEIGHTMAPS = EnumSet.of(
        Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    );
    public static final ChunkStatus EMPTY = register("empty", null, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus STRUCTURE_STARTS = register("structure_starts", EMPTY, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus STRUCTURE_REFERENCES = register("structure_references", STRUCTURE_STARTS, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus BIOMES = register("biomes", STRUCTURE_REFERENCES, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus NOISE = register("noise", BIOMES, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus SURFACE = register("surface", NOISE, WORLDGEN_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus CARVERS = register("carvers", SURFACE, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus FEATURES = register("features", CARVERS, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus INITIALIZE_LIGHT = register("initialize_light", FEATURES, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus LIGHT = register("light", INITIALIZE_LIGHT, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus SPAWN = register("spawn", LIGHT, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    public static final ChunkStatus FULL = register("full", SPAWN, FINAL_HEIGHTMAPS, ChunkType.LEVELCHUNK);
    public static final Codec<ChunkStatus> CODEC = BuiltInRegistries.CHUNK_STATUS.byNameCodec();
    private final int index;
    private final ChunkStatus parent;
    private final ChunkType chunkType;
    private final EnumSet<Heightmap.Types> heightmapsAfter;

    private static ChunkStatus register(
        final String name, final @Nullable ChunkStatus parent, final EnumSet<Heightmap.Types> heightmaps, final ChunkType chunkType
    ) {
        return Registry.register(BuiltInRegistries.CHUNK_STATUS, name, new ChunkStatus(parent, heightmaps, chunkType));
    }

    public static List<ChunkStatus> getStatusList() {
        List<ChunkStatus> list = Lists.newArrayList();

        ChunkStatus status;
        for (status = FULL; status.getParent() != status; status = status.getParent()) {
            list.add(status);
        }

        list.add(status);
        Collections.reverse(list);
        return list;
    }

    // Paper start - rewrite chunk system
    private boolean isParallelCapable;
    private boolean emptyLoadTask;
    private int writeRadius;
    private ChunkStatus nextStatus;
    private java.util.concurrent.atomic.AtomicBoolean warnedAboutNoImmediateComplete;

    @Override
    public final boolean moonrise$isParallelCapable() {
        return this.isParallelCapable;
    }

    @Override
    public final void moonrise$setParallelCapable(final boolean value) {
        this.isParallelCapable = value;
    }

    @Override
    public final int moonrise$getWriteRadius() {
        return this.writeRadius;
    }

    @Override
    public final void moonrise$setWriteRadius(final int value) {
        this.writeRadius = value;
    }

    @Override
    public final ChunkStatus moonrise$getNextStatus() {
        return this.nextStatus;
    }

    @Override
    public final boolean moonrise$isEmptyLoadStatus() {
        return this.emptyLoadTask;
    }

    @Override
    public void moonrise$setEmptyLoadStatus(final boolean value) {
        this.emptyLoadTask = value;
    }

    @Override
    public final boolean moonrise$isEmptyGenStatus() {
        return (Object)this == ChunkStatus.EMPTY;
    }

    @Override
    public final java.util.concurrent.atomic.AtomicBoolean moonrise$getWarnedAboutNoImmediateComplete() {
        return this.warnedAboutNoImmediateComplete;
    }
    // Paper end - rewrite chunk system

    @VisibleForTesting
    protected ChunkStatus(final @Nullable ChunkStatus parent, final EnumSet<Heightmap.Types> heightmapsAfter, final ChunkType chunkType) {
        // Paper start - rewrite chunk system
        this.isParallelCapable = false;
        this.writeRadius = -1;
        this.nextStatus = (ChunkStatus)(Object)this;
        if (parent != null) {
            parent.nextStatus = (ChunkStatus)(Object)this;
        }
        this.warnedAboutNoImmediateComplete = new java.util.concurrent.atomic.AtomicBoolean();
        // Paper end - rewrite chunk system
        this.parent = parent == null ? this : parent;
        this.chunkType = chunkType;
        this.heightmapsAfter = heightmapsAfter;
        this.index = parent == null ? 0 : parent.getIndex() + 1;
    }

    public int getIndex() {
        return this.index;
    }

    public ChunkStatus getParent() {
        return this.parent;
    }

    public ChunkType getChunkType() {
        return this.chunkType;
    }

    public static ChunkStatus byName(final String key) {
        return BuiltInRegistries.CHUNK_STATUS.getValue(Identifier.tryParse(key));
    }

    public EnumSet<Heightmap.Types> heightmapsAfter() {
        return this.heightmapsAfter;
    }

    public boolean isOrAfter(final ChunkStatus step) {
        return this.getIndex() >= step.getIndex();
    }

    public boolean isAfter(final ChunkStatus step) {
        return this.getIndex() > step.getIndex();
    }

    public boolean isOrBefore(final ChunkStatus step) {
        return this.getIndex() <= step.getIndex();
    }

    public boolean isBefore(final ChunkStatus step) {
        return this.getIndex() < step.getIndex();
    }

    public static ChunkStatus max(final ChunkStatus a, final ChunkStatus b) {
        return a.isAfter(b) ? a : b;
    }

    @Override
    public String toString() {
        return this.getName();
    }

    public String getName() {
        return BuiltInRegistries.CHUNK_STATUS.getKey(this).toString();
    }
}

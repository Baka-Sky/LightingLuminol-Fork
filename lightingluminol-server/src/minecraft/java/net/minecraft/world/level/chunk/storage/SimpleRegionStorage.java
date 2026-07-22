package net.minecraft.world.level.chunk.storage;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public class SimpleRegionStorage implements ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemSimpleRegionStorage, AutoCloseable { // Paper - rewrite chunk system
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger(); // Paper - rewrite chunk system
    private final RegionFileStorage storage; // Paper - rewrite chunk system
    private final DataFixer fixerUpper;
    private final DataFixTypes dataFixType;

    // Paper start - rewrite chunk system
    @Override
    public final RegionFileStorage moonrise$getRegionStorage() {
        return this.storage;
    }
    // Paper end - rewrite chunk system

    public SimpleRegionStorage(
        final RegionStorageInfo info, final Path folder, final DataFixer fixerUpper, final boolean syncWrites, final DataFixTypes dataFixType
    ) {
        info.dfuType()[0] = dataFixType; // Paper - crazy hack to indicate what type the region file is
        this.fixerUpper = fixerUpper;
        this.dataFixType = dataFixType;
        this.storage = new IOWorker(info, folder, syncWrites).storage; // Paper - rewrite chunk system
    }

    public boolean isOldChunkAround(final ChunkPos pos, final int range) {
        return true; // Paper - rewrite chunk system
    }

    public CompletableFuture<Optional<CompoundTag>> read(final ChunkPos pos) {
        // Paper start - rewrite chunk system
        try {
            return CompletableFuture.completedFuture(Optional.ofNullable(this.storage.read(pos)));
        } catch (final Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
        // Paper end - rewrite chunk system
    }

    public CompletableFuture<Void> write(final ChunkPos pos, final CompoundTag value) {
        return this.write(pos, () -> value);
    }

    public CompletableFuture<Void> write(final ChunkPos pos, final Supplier<CompoundTag> supplier) {
        // Paper start - guard against possible chunk pos desync
        final Supplier<CompoundTag> guardedPosCheck = () -> {
            final CompoundTag nbt = supplier.get();
            final boolean chunkStorage = this.dataFixType == net.minecraft.util.datafix.DataFixTypes.CHUNK;
            if (chunkStorage && nbt != null && !pos.equals(SerializableChunkData.getChunkCoordinate(nbt))) {
                final String world = (this instanceof net.minecraft.server.level.ChunkMap chunkMap) ? io.papermc.paper.util.MCUtil.getLevelName(chunkMap.level) : null;
                throw new IllegalArgumentException("Chunk coordinate and serialized data do not have matching coordinates, trying to serialize coordinate " + pos
                    + " but compound says coordinate is " + SerializableChunkData.getChunkCoordinate(nbt) + (world == null ? " for an unknown world" : (" for world: " + world)));
            }
            return nbt;
        };
        // Paper - rewrite chunk system
        try {
            this.storage.write(pos, guardedPosCheck.get());
            return CompletableFuture.completedFuture(null);
        } catch (final Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
        // Paper end - rewrite chunk system
        // Paper end - guard against possible chunk pos desync
    }

    public CompoundTag upgradeChunkTag(CompoundTag chunkTag, final int defaultVersion, final @Nullable CompoundTag dataFixContextTag, final int targetVersion) {
        int version = NbtUtils.getDataVersion(chunkTag, defaultVersion);
        if (version >= targetVersion) {
            return chunkTag;
        }

        try {
            // Spigot start - SPIGOT-6806: Quick and dirty way to prevent below zero generation in old chunks, by setting the status to heightmap instead of empty
            boolean stopBelowZero = false;
            final boolean chunkStorage = this.dataFixType == net.minecraft.util.datafix.DataFixTypes.CHUNK;
            if (chunkStorage) {
                final boolean belowZeroGenerationInExistingChunks = (this instanceof net.minecraft.server.level.ChunkMap chunkMap)
                    ? chunkMap.level.spigotConfig.belowZeroGenerationInExistingChunks
                    : org.spigotmc.SpigotConfig.belowZeroGenerationInExistingChunks;
                if (version <= 2730 && !belowZeroGenerationInExistingChunks) {
                    stopBelowZero = "full".equals(chunkTag.getCompound("Level").flatMap(level -> level.getString("Status")).orElse(null));
                }
            }
            // Spigot end
            injectDatafixingContext(chunkTag, dataFixContextTag);
            chunkTag = ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(this.getDataConverterType(), chunkTag, version, targetVersion); // Paper - rewrite dataconverter system
            // Spigot start
            if (stopBelowZero) {
                chunkTag.putString("Status", net.minecraft.core.registries.BuiltInRegistries.CHUNK_STATUS.getKey(net.minecraft.world.level.chunk.status.ChunkStatus.SPAWN).toString());
            }
            // Spigot end
            removeDatafixingContext(chunkTag);
            NbtUtils.addDataVersion(chunkTag, targetVersion);
            return chunkTag;
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Updated chunk");
            CrashReportCategory details = report.addCategory("Updated chunk details");
            details.setDetail("Data version", version);
            details.setDetail("Target version", targetVersion);
            throw new ReportedException(report);
        }
    }

    // Paper start - rewrite data conversion system
    private ca.spottedleaf.dataconverter.minecraft.datatypes.MCDataType getDataConverterType() {
        if (this.dataFixType == DataFixTypes.CHUNK) {
            return ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.CHUNK;
        } else if (this.dataFixType == DataFixTypes.ENTITY_CHUNK) {
            return ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.ENTITY_CHUNK;
        } else if (this.dataFixType == DataFixTypes.POI_CHUNK) {
            return ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.POI_CHUNK;
        } else {
            throw new UnsupportedOperationException("For " + this.dataFixType.name());
        }
    }
    // Paper end - rewrite data conversion system

    public CompoundTag upgradeChunkTag(final CompoundTag chunkTag, final int defaultVersion) {
        return this.upgradeChunkTag(chunkTag, defaultVersion, null, SharedConstants.getCurrentVersion().dataVersion().version());
    }

    public Dynamic<Tag> upgradeChunkTag(final Dynamic<Tag> chunkTag, final int defaultVersion) {
        return new Dynamic<>(
            chunkTag.getOps(),
            this.upgradeChunkTag((CompoundTag)chunkTag.getValue(), defaultVersion, null, SharedConstants.getCurrentVersion().dataVersion().version())
        );
    }

    public static void injectDatafixingContext(final CompoundTag chunkTag, final @Nullable CompoundTag contextTag) {
        if (contextTag != null) {
            chunkTag.put("__context", contextTag);
        }
    }

    private static void removeDatafixingContext(final CompoundTag chunkTag) {
        chunkTag.remove("__context");
    }

    public CompletableFuture<Void> synchronize(final boolean flush) {
        // Paper start - rewrite chunk system
        try {
            this.storage.flush();
            return CompletableFuture.completedFuture(null);
        } catch (final IOException ex) {
            LOGGER.error("Failed to flush chunk storage", ex);
            return CompletableFuture.failedFuture(ex);
        }
        // Paper end - rewrite chunk system
    }

    @Override
    public void close() throws IOException {
        this.storage.close(); // Paper - rewrite chunk system
    }

    public ChunkScanAccess chunkScanner() {
        // Paper start - rewrite chunk system
        // TODO ChunkMap implementation?
        return (chunkPos, streamTagVisitor) -> {
            try {
                this.storage.scanChunk(chunkPos, streamTagVisitor);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        // Paper end - rewrite chunk system
    }

    public RegionStorageInfo storageInfo() {
        return this.storage.info(); // Paper - rewrite chunk system
    }
}

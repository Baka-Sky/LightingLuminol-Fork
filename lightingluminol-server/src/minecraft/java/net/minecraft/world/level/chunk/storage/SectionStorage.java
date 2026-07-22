package net.minecraft.world.level.chunk.storage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SectionStorage<R, P> implements AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.level.storage.ChunkSystemSectionStorage { // Paper - rewrite chunk system
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SECTIONS_TAG = "Sections";
    // Paper - rewrite chunk system
    private final Long2ObjectMap<Optional<R>> storage = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirtyChunks = new LongLinkedOpenHashSet();
    private final Codec<P> codec;
    private final Function<R, P> packer;
    private final BiFunction<P, Runnable, R> unpacker;
    private final Function<Runnable, R> factory;
    private final RegistryAccess registryAccess;
    private final ChunkIOErrorReporter errorReporter;
    public final LevelHeightAccessor levelHeightAccessor; // Paper - public
    private final LongSet loadedChunks = new LongOpenHashSet();
    private final Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads = new Long2ObjectOpenHashMap<>();
    private final Object loadLock = new Object();

    // Paper start - rewrite chunk system
    private final RegionFileStorage regionStorage;

    @Override
    public final RegionFileStorage moonrise$getRegionStorage() {
        return this.regionStorage;
    }

    @Override
    public void moonrise$close() throws IOException {}
    // Paper end - rewrite chunk system

    public SectionStorage(
        final SimpleRegionStorage simpleRegionStorage,
        final Codec<P> codec,
        final Function<R, P> packer,
        final BiFunction<P, Runnable, R> unpacker,
        final Function<Runnable, R> factory,
        final RegistryAccess registryAccess,
        final ChunkIOErrorReporter errorReporter,
        final LevelHeightAccessor levelHeightAccessor
    ) {
        // Paper - rewrite chunk system
        this.codec = codec;
        this.packer = packer;
        this.unpacker = unpacker;
        this.factory = factory;
        this.registryAccess = registryAccess;
        this.errorReporter = errorReporter;
        this.levelHeightAccessor = levelHeightAccessor;
        this.regionStorage = ((ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemSimpleRegionStorage)simpleRegionStorage).moonrise$getRegionStorage(); // Paper - rewrite chunk system
    }

    protected void tick(final BooleanSupplier haveTime) {
        LongIterator iterator = this.dirtyChunks.iterator();

        while (iterator.hasNext() && haveTime.getAsBoolean()) {
            ChunkPos chunkPos = ChunkPos.unpack(iterator.nextLong());
            iterator.remove();
            this.writeChunk(chunkPos);
        }

        this.unpackPendingLoads();
    }

    private void unpackPendingLoads() {
        synchronized (this.loadLock) {
            Iterator<Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>>> iterator = Long2ObjectMaps.fastIterator(this.pendingLoads);

            while (iterator.hasNext()) {
                Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> entry = iterator.next();
                Optional<SectionStorage.PackedChunk<P>> chunk = entry.getValue().getNow(null);
                if (chunk != null) {
                    long chunkKey = entry.getLongKey();
                    this.unpackChunk(ChunkPos.unpack(chunkKey), chunk.orElse(null));
                    iterator.remove();
                    this.loadedChunks.add(chunkKey);
                }
            }
        }
    }

    public void flushAll() {
        if (!this.dirtyChunks.isEmpty()) {
            this.dirtyChunks.forEach(pos -> this.writeChunk(ChunkPos.unpack(pos)));
            this.dirtyChunks.clear();
        }
    }

    public boolean hasWork() {
        return !this.dirtyChunks.isEmpty();
    }

    public @Nullable Optional<R> get(final long sectionPos) { // Paper - public
        return this.storage.get(sectionPos);
    }

    public Optional<R> getOrLoad(final long sectionPos) { // Paper - public
        if (this.outsideStoredRange(sectionPos)) {
            return Optional.empty();
        } else {
            Optional<R> r = this.get(sectionPos);
            if (r != null) {
                return r;
            } else {
                this.unpackChunk(SectionPos.of(sectionPos).chunk());
                r = this.get(sectionPos);
                if (r == null) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException());
                } else {
                    return r;
                }
            }
        }
    }

    protected boolean outsideStoredRange(final long sectionPos) {
        int y = SectionPos.sectionToBlockCoord(SectionPos.y(sectionPos));
        return this.levelHeightAccessor.isOutsideBuildHeight(y);
    }

    protected R getOrCreate(final long sectionPos) {
        if (this.outsideStoredRange(sectionPos)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException("sectionPos out of bounds"));
        }

        Optional<R> r = this.getOrLoad(sectionPos);
        if (r.isPresent()) {
            return r.get();
        }

        R newR = this.factory.apply(() -> this.setDirty(sectionPos));
        this.storage.put(sectionPos, Optional.of(newR));
        return newR;
    }

    public CompletableFuture<?> prefetch(final ChunkPos chunkPos) {
        synchronized (this.loadLock) {
            long chunkKey = chunkPos.pack();
            return this.loadedChunks.contains(chunkKey)
                ? CompletableFuture.completedFuture(null)
                : this.pendingLoads.computeIfAbsent(chunkKey, k -> this.tryRead(chunkPos));
        }
    }

    private void unpackChunk(final ChunkPos chunkPos) {
        long chunkKey = chunkPos.pack();
        CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> future;
        synchronized (this.loadLock) {
            if (!this.loadedChunks.add(chunkKey)) {
                return;
            }

            future = this.pendingLoads.computeIfAbsent(chunkKey, k -> this.tryRead(chunkPos));
        }

        this.unpackChunk(chunkPos, future.join().orElse(null));
        synchronized (this.loadLock) {
            this.pendingLoads.remove(chunkKey);
        }
    }

    private CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> tryRead(final ChunkPos chunkPos) {
        throw new IllegalStateException("Only chunk system can write state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private void unpackChunk(final ChunkPos pos, final SectionStorage.@Nullable PackedChunk<P> packedChunk) {
        throw new IllegalStateException("Only chunk system can load in state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private void writeChunk(final ChunkPos chunkPos) {
        throw new IllegalStateException("Only chunk system can write state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private <T> Dynamic<T> writeChunk(final ChunkPos chunkPos, final DynamicOps<T> ops) {
        Map<T, T> sections = Maps.newHashMap();

        for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
            long key = getKey(chunkPos, sectionY);
            Optional<R> r = this.storage.get(key);
            if (r != null && !r.isEmpty()) {
                DataResult<T> serializedSection = this.codec.encodeStart(ops, this.packer.apply(r.get()));
                String yName = Integer.toString(sectionY);
                serializedSection.resultOrPartial(LOGGER::error).ifPresent(s -> sections.put(ops.createString(yName), (T)s));
            }
        }

        return new Dynamic<>(
            ops,
            ops.createMap(
                ImmutableMap.of(
                    ops.createString("Sections"),
                    ops.createMap(sections),
                    ops.createString("DataVersion"),
                    ops.createInt(SharedConstants.getCurrentVersion().dataVersion().version())
                )
            )
        );
    }

    private static long getKey(final ChunkPos chunkPos, final int sectionY) {
        return SectionPos.asLong(chunkPos.x(), sectionY, chunkPos.z());
    }

    protected void onSectionLoad(final long sectionPos) {
    }

    public void setDirty(final long sectionPos) { // Paper - public
        Optional<R> r = this.storage.get(sectionPos);
        if (r != null && !r.isEmpty()) {
            this.dirtyChunks.add(ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
        } else {
            LOGGER.warn("No data for position: {}", SectionPos.of(sectionPos));
        }
    }

    public void flush(final ChunkPos chunkPos) {
        if (this.dirtyChunks.remove(chunkPos.pack())) {
            this.writeChunk(chunkPos);
        }
    }

    @Override
    public void close() throws IOException {
        this.moonrise$close(); // Paper - rewrite chunk system
    }

    private record PackedChunk<T>(Int2ObjectMap<T> sectionsByY, boolean versionChanged) {
        public static <T> SectionStorage.PackedChunk<T> parse(
            final Codec<T> codec,
            final DynamicOps<Tag> ops,
            final Tag tag,
            final SimpleRegionStorage simpleRegionStorage,
            final LevelHeightAccessor levelHeightAccessor
        ) {
            Dynamic<Tag> originalTag = new Dynamic<>(ops, tag);
            Dynamic<Tag> fixedTag = simpleRegionStorage.upgradeChunkTag(originalTag, 1945);
            boolean versionChanged = originalTag != fixedTag;
            OptionalDynamic<Tag> sections = fixedTag.get("Sections");
            Int2ObjectMap<T> sectionsByY = new Int2ObjectOpenHashMap<>();

            for (int sectionY = levelHeightAccessor.getMinSectionY(); sectionY <= levelHeightAccessor.getMaxSectionY(); sectionY++) {
                Optional<T> section = sections.get(Integer.toString(sectionY))
                    .result()
                    .flatMap(sectionData -> codec.parse((Dynamic<Tag>)sectionData).resultOrPartial(SectionStorage.LOGGER::error));
                if (section.isPresent()) {
                    sectionsByY.put(sectionY, section.get());
                }
            }

            return new SectionStorage.PackedChunk<>(sectionsByY, versionChanged);
        }
    }
}

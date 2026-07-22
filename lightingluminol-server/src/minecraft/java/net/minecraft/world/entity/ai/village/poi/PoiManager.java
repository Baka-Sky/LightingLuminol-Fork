package net.minecraft.world.entity.ai.village.poi;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.SectionTracker;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.debug.DebugPoiInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jspecify.annotations.Nullable;

public class PoiManager extends SectionStorage<PoiSection, PoiSection.Packed> implements ca.spottedleaf.moonrise.patches.chunk_system.level.poi.ChunkSystemPoiManager { // Paper - rewrite chunk system
    public static final int MAX_VILLAGE_DISTANCE = 6;
    public static final int VILLAGE_SECTION_SIZE = 1;
    private final PoiManager.DistanceTracker distanceTracker;
    private final LongSet loadedChunks = new LongOpenHashSet();

    // Paper start - rewrite chunk system
    private final net.minecraft.server.level.ServerLevel world;

    // the vanilla tracker needs to be replaced because it does not support level removes, and we need level removes
    // to support poi unloading
    private final ca.spottedleaf.moonrise.common.misc.Delayed26WayDistancePropagator3D villageDistanceTracker = new ca.spottedleaf.moonrise.common.misc.Delayed26WayDistancePropagator3D();

    private static final int POI_DATA_SOURCE = 7;

    private static int convertBetweenLevels(final int level) {
        return POI_DATA_SOURCE - level;
    }

    private void updateDistanceTracking(long section) {
        synchronized (this.villageDistanceTracker) { // Folia - region threading
        if (this.isVillageCenter(section)) {
            this.villageDistanceTracker.setSource(section, POI_DATA_SOURCE);
        } else {
            this.villageDistanceTracker.removeSource(section);
        }
        } // Folia - region threading
    }

    @Override
    public Optional<PoiSection> get(final long pos) {
        final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionX(pos);
        final int chunkY = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionY(pos);
        final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionZ(pos);

        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Accessing poi chunk off-main");

        final ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk ret = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.world).moonrise$getChunkTaskScheduler().chunkHolderManager.getPoiChunkIfLoaded(chunkX, chunkZ, true);

        return ret == null ? Optional.empty() : ret.getSectionForVanilla(chunkY);
    }

    @Override
    public Optional<PoiSection> getOrLoad(final long pos) {
        final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionX(pos);
        final int chunkY = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionY(pos);
        final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionZ(pos);

        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Accessing poi chunk off-main");

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager manager = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.world).moonrise$getChunkTaskScheduler().chunkHolderManager;

        if (chunkY >= ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(this.world) && chunkY <= ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxSection(this.world)) {
            final ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk ret = manager.getPoiChunkIfLoaded(chunkX, chunkZ, true);
            if (ret != null) {
                return ret.getSectionForVanilla(chunkY);
            } else {
                return manager.loadPoiChunk(chunkX, chunkZ).getSectionForVanilla(chunkY);
            }
        }
        // retain vanilla behavior: do not load section if out of bounds!
        return Optional.empty();
    }

    @Override
    protected PoiSection getOrCreate(final long pos) {
        final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionX(pos);
        final int chunkY = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionY(pos);
        final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionZ(pos);

        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Accessing poi chunk off-main");

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager manager = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.world).moonrise$getChunkTaskScheduler().chunkHolderManager;

        final ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk ret = manager.getPoiChunkIfLoaded(chunkX, chunkZ, true);
        if (ret != null) {
            return ret.getOrCreateSection(chunkY);
        } else {
            return manager.loadPoiChunk(chunkX, chunkZ).getOrCreateSection(chunkY);
        }
    }

    @Override
    public final net.minecraft.server.level.ServerLevel moonrise$getWorld() {
        return this.world;
    }

    @Override
    public final void moonrise$onUnload(final long coordinate) { // Paper - rewrite chunk system
        final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(coordinate);
        final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(coordinate);

        final int minY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(this.world);
        final int maxY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxSection(this.world);

        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Unloading poi chunk off-main");
        for (int sectionY = minY; sectionY <= maxY; ++sectionY) {
            final long sectionPos = SectionPos.asLong(chunkX, sectionY, chunkZ);
            this.updateDistanceTracking(sectionPos);
        }
    }

    @Override
    public final void moonrise$loadInPoiChunk(final ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk poiChunk) {
        final int chunkX = poiChunk.chunkX;
        final int chunkZ = poiChunk.chunkZ;

        final int minY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(this.world);
        final int maxY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxSection(this.world);

        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Loading poi chunk off-main");
        for (int sectionY = minY; sectionY <= maxY; ++sectionY) {
            final PoiSection section = poiChunk.getSection(sectionY);
            if (section != null && !((ca.spottedleaf.moonrise.patches.chunk_system.level.poi.ChunkSystemPoiSection)section).moonrise$isEmpty()) {
                this.onSectionLoad(SectionPos.asLong(chunkX, sectionY, chunkZ));
            }
        }
    }

    @Override
    public final void moonrise$checkConsistency(final net.minecraft.world.level.chunk.ChunkAccess chunk) {
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();

        final int minY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(chunk);
        final int maxY = ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxSection(chunk);
        final LevelChunkSection[] sections = chunk.getSections();
        for (int section = minY; section <= maxY; ++section) {
            this.checkConsistencyWithBlocks(SectionPos.of(chunkX, section, chunkZ), sections[section - minY]);
        }
    }
    // Paper end - rewrite chunk system

    public PoiManager(
        final RegionStorageInfo info,
        final Path folder,
        final DataFixer fixerUpper,
        final boolean sync,
        final RegistryAccess registryAccess,
        final ChunkIOErrorReporter errorReporter,
        final LevelHeightAccessor levelHeightAccessor
    ) {
        super(
            new SimpleRegionStorage(info, folder, fixerUpper, sync, DataFixTypes.POI_CHUNK),
            PoiSection.Packed.CODEC,
            PoiSection::pack,
            PoiSection.Packed::unpack,
            PoiSection::new,
            registryAccess,
            errorReporter,
            levelHeightAccessor
        );
        this.distanceTracker = new PoiManager.DistanceTracker();
        this.world = (net.minecraft.server.level.ServerLevel)levelHeightAccessor; // Paper - rewrite chunk system
    }

    public @Nullable PoiRecord add(final BlockPos pos, final Holder<PoiType> type) {
        return this.getOrCreate(SectionPos.asLong(pos)).add(pos, type);
    }

    public void remove(final BlockPos pos) {
        this.getOrLoad(SectionPos.asLong(pos)).ifPresent(poiSection -> poiSection.remove(pos));
    }

    public long getCountInRange(final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy) {
        return this.getInRange(predicate, center, radius, occupancy).count();
    }

    public boolean existsAtPosition(final ResourceKey<PoiType> poiType, final BlockPos blockPos) {
        return this.exists(blockPos, p -> p.is(poiType));
    }

    public Stream<PoiRecord> getInSquare(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> ret = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, (Predicate<BlockPos>)null, center, radius, Double.MAX_VALUE, occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, ret
        );

        return ret.stream();
        // Paper end - optimise POI lookup
    }

    public Stream<PoiRecord> getInRange(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> ret = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, (Predicate<BlockPos>)null, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, ret
        );

        return ret.stream();
        // Paper end - optimise POI lookup
    }

    @VisibleForDebug
    public Stream<PoiRecord> getInChunk(final Predicate<Holder<PoiType>> predicate, final ChunkPos chunkPos, final PoiManager.Occupancy occupancy) {
        return IntStream.rangeClosed(this.levelHeightAccessor.getMinSectionY(), this.levelHeightAccessor.getMaxSectionY())
            .boxed()
            .map(sectionY -> this.getOrLoad(SectionPos.of(chunkPos, sectionY).asLong()))
            .filter(Optional::isPresent)
            .flatMap(poiSection -> poiSection.get().getRecords(predicate, occupancy));
    }

    public Stream<BlockPos> findAll(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> ret = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, ret
        );

        return ret.stream().map(PoiRecord::getPos);
        // Paper end - optimise POI lookup
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllWithType(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> ret = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, ret
        );

        return ret.stream().map((final PoiRecord record) -> {
            return Pair.of(record.getPoiType(), record.getPos());
        });
        // Paper end - optimise POI lookup
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllClosestFirstWithType(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> ret = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, ret
        );

        ret.sort((final PoiRecord record1, final PoiRecord record2) -> {
            return ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.compareDistances(center, record1.getPos(), record2.getPos());
        });

        return ret.stream().map((final PoiRecord record) -> {
            return Pair.of(record.getPoiType(), record.getPos());
        });
        // Paper end - optimise POI lookup
    }

    public Optional<BlockPos> find(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        return Optional.ofNullable(ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiPosition((PoiManager)(Object)this, predicate, filter, center, radius, occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING));
        // Paper end - optimise POI lookup
    }

    public Optional<BlockPos> findClosest(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final PoiRecord closest = ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findNearestPoiRecord(
            (PoiManager)(Object)this, predicate, null, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING
        );
        return closest == null ? Optional.empty() : Optional.of(closest.getPos());
        // Paper end - optimise POI lookup
    }

    public Optional<Pair<Holder<PoiType>, BlockPos>> findClosestWithType(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final PoiRecord closest = ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findNearestPoiRecord(
            (PoiManager)(Object)this, predicate, null, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING
        );
        return closest == null ? Optional.empty() : Optional.of(Pair.of(closest.getPoiType(), closest.getPos()));
        // Paper end - optimise POI lookup
    }

    public Optional<BlockPos> findClosest(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        // Paper start - optimise POI lookup
        final PoiRecord closest = ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findNearestPoiRecord(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING
        );
        return closest == null ? Optional.empty() : Optional.of(closest.getPos());
        // Paper end - optimise POI lookup
    }

    public Optional<BlockPos> take(
        final Predicate<Holder<PoiType>> predicate, final BiPredicate<Holder<PoiType>, BlockPos> filter, final BlockPos center, final int radius
    ) {
        // Paper start - optimise POI lookup
        final PoiRecord record = ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecord(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), PoiManager.Occupancy.HAS_SPACE, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING
        );

        if (record == null) {
            return Optional.empty();
        }

        record.acquireTicket();
        return Optional.of(record.getPos());
        // Paper end - optimise POI lookup
    }

    public Optional<BlockPos> getRandom(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final PoiManager.Occupancy occupancy,
        final BlockPos center,
        final int radius,
        final RandomSource random
    ) {
        // Paper start - optimise POI lookup
        final List<PoiRecord> list = new java.util.ArrayList<>();
        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findAnyPoiRecords(
            (PoiManager)(Object)this, predicate, filter, center, radius, (double)((long)radius * (long)radius), occupancy, ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.LOAD_FOR_SEARCHING, Integer.MAX_VALUE, list
        );

        // the old method shuffled the list and then tried to find the first element in it that
        // matched positionPredicate, however we moved positionPredicate into the poi search. This means we can avoid a
        // shuffle entirely, and just pick a random element from list
        if (list.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(list.get(random.nextInt(list.size())).getPos());
        // Paper end - optimise POI lookup
    }

    public boolean release(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos))
            .map(section -> section.release(pos))
            .orElseThrow(() -> Util.pauseInIde(new IllegalStateException("POI never registered at " + pos)));
    }

    public boolean exists(final BlockPos pos, final Predicate<Holder<PoiType>> predicate) {
        return this.getOrLoad(SectionPos.asLong(pos)).map(s -> s.exists(pos, predicate)).orElse(false);
    }

    public Optional<Holder<PoiType>> getType(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(section -> section.getType(pos));
    }

    @VisibleForDebug
    public @Nullable DebugPoiInfo getDebugPoiInfo(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(section -> section.getDebugPoiInfo(pos)).orElse(null);
    }

    public int sectionsToVillage(final SectionPos sectionPos) {
        synchronized (this.villageDistanceTracker) { // Folia - region threading
        // Paper start - rewrite chunk system
        this.villageDistanceTracker.propagateUpdates();
        return convertBetweenLevels(this.villageDistanceTracker.getLevel(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionKey(sectionPos)));
        // Paper end - rewrite chunk system
        } // Folia - region threading
    }

    private boolean isVillageCenter(final long sectionPos) {
        Optional<PoiSection> section = this.get(sectionPos);
        return section != null
            && section.<Boolean>map(s -> s.getRecords(e -> e.is(PoiTypeTags.VILLAGE), PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent()).orElse(false);
    }

    @Override
    public void tick(final BooleanSupplier haveTime) {
        synchronized (this.villageDistanceTracker) { // Folia - region threading
        this.villageDistanceTracker.propagateUpdates(); // Paper - rewrite chunk system
        } // Folia - region threading
    }

    @Override
    public void setDirty(final long sectionPos) { // Paper - public
        // Paper start - rewrite chunk system
        final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionX(sectionPos);
        final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionZ(sectionPos);
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager manager = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.world).moonrise$getChunkTaskScheduler().chunkHolderManager;
        final ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk chunk = manager.getPoiChunkIfLoaded(chunkX, chunkZ, false);
        if (chunk != null) {
            chunk.setDirty(true);
        }
        this.updateDistanceTracking(sectionPos);
        // Paper end - rewrite chunk system
    }

    @Override
    protected void onSectionLoad(final long sectionPos) {
        this.updateDistanceTracking(sectionPos); // Paper - rewrite chunk system
    }

    public void checkConsistencyWithBlocks(final SectionPos sectionPos, final LevelChunkSection blockSection) {
        Util.ifElse(this.getOrLoad(sectionPos.asLong()), section -> section.refresh(output -> {
            if (mayHavePoi(blockSection)) {
                this.updateFromSection(blockSection, sectionPos, output);
            }
        }), () -> {
            if (mayHavePoi(blockSection)) {
                PoiSection newSection = this.getOrCreate(sectionPos.asLong());
                this.updateFromSection(blockSection, sectionPos, newSection::add);
            }
        });
    }

    private static boolean mayHavePoi(final LevelChunkSection blockSection) {
        return blockSection.maybeHas(PoiTypes::hasPoi);
    }

    private void updateFromSection(final LevelChunkSection blockSection, final SectionPos pos, final BiConsumer<BlockPos, Holder<PoiType>> output) {
        pos.blocksInside()
            .forEach(
                blockPos -> {
                    BlockState state = blockSection.getBlockState(
                        SectionPos.sectionRelative(blockPos.getX()), SectionPos.sectionRelative(blockPos.getY()), SectionPos.sectionRelative(blockPos.getZ())
                    );
                    PoiTypes.forState(state).ifPresent(type -> output.accept(blockPos, (Holder<PoiType>)type));
                }
            );
    }

    public void ensureLoadedAndValid(final LevelReader reader, final BlockPos center, final int radius) {
        SectionPos.aroundChunk(
                ChunkPos.containing(center), Math.floorDiv(radius, 16), this.levelHeightAccessor.getMinSectionY(), this.levelHeightAccessor.getMaxSectionY()
            )
            .map(pos -> Pair.of(pos, this.getOrLoad(pos.asLong())))
            .filter(poiSection -> !poiSection.getSecond().map(PoiSection::isValid).orElse(false))
            .map(p -> p.getFirst().chunk())
            // Paper - rewrite chunk system
            .forEach(pos -> reader.getChunk(pos.x(), pos.z(), ChunkStatus.EMPTY));
    }

    private final class DistanceTracker extends SectionTracker {
        private final Long2ByteMap levels = new Long2ByteOpenHashMap();

        DistanceTracker() {
            super(7, 16, 256);
            this.levels.defaultReturnValue((byte)7);
        }

        @Override
        protected int getLevelFromSource(final long to) {
            return PoiManager.this.isVillageCenter(to) ? 0 : 7;
        }

        @Override
        protected int getLevel(final long node) {
            return this.levels.get(node);
        }

        @Override
        protected void setLevel(final long node, final int level) {
            if (level > 6) {
                this.levels.remove(node);
            } else {
                this.levels.put(node, (byte)level);
            }
        }

        public void runAllUpdates() {
            super.runUpdates(Integer.MAX_VALUE);
        }
    }

    public enum Occupancy {
        HAS_SPACE(PoiRecord::hasSpace),
        IS_OCCUPIED(PoiRecord::isOccupied),
        ANY(poiRecord -> true);

        private final Predicate<? super PoiRecord> test;

        Occupancy(final Predicate<? super PoiRecord> test) {
            this.test = test;
        }

        public Predicate<? super PoiRecord> getTest() {
            return this.test;
        }
    }
}

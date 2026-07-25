package net.minecraft.world.level;

import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import meow.bacteriawa.lightingluminol.config.ExperimentConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.Scoreboard;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.jspecify.annotations.Nullable;

// CraftBukkit start
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.SpawnCategory;
// CraftBukkit end

public abstract class Level implements LevelAccessor, AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel, ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemEntityGetter { // Paper - rewrite chunk system // Paper - optimise collisions
    public static final Codec<ResourceKey<Level>> RESOURCE_KEY_CODEC = ResourceKey.codec(Registries.DIMENSION);
    public static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
    public static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether"));
    public static final ResourceKey<Level> END = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_end"));
    public static final int MAX_LEVEL_SIZE = 30000000;
    public static final int ACROSS_THE_WHOLE_WORLD = 60000000;
    public static final int LONG_PARTICLE_CLIP_RANGE = 512;
    public static final int SHORT_PARTICLE_CLIP_RANGE = 32;
    public static final int MAX_BRIGHTNESS = 15;
    public static final int MAX_ENTITY_SPAWN_Y = 20000000;
    public static final int MIN_ENTITY_SPAWN_Y = -20000000;
    public static final WeightedList<ExplosionParticleInfo> DEFAULT_EXPLOSION_BLOCK_PARTICLES = WeightedList.<ExplosionParticleInfo>builder()
        .add(new ExplosionParticleInfo(ParticleTypes.POOF, 0.5F, 1.0F))
        .add(new ExplosionParticleInfo(ParticleTypes.SMOKE, 1.0F, 1.0F))
        .build();
    //public final List<TickingBlockEntity> blockEntityTickers = Lists.newArrayList(); // Folia - region threading
    public final int neighbourUpdateMax; //protected final CollectingNeighborUpdater neighborUpdater; // Folia - region threading
    //private final List<TickingBlockEntity> pendingBlockEntityTickers = Lists.newArrayList(); // Folia - region threading
    //private boolean tickingBlockEntities; // Folia - region threading
    protected final Thread thread;
    private final boolean isDebug;
    private int skyDarken;
    protected int randValue = RandomSource.createThreadLocalInstance().nextInt();
    protected final int addend = 1013904223;
    protected float oRainLevel;
    public float rainLevel;
    protected float oThunderLevel;
    public float thunderLevel;
    protected final RandomSource random = io.papermc.paper.threadedregions.util.ThreadLocalRandomSource.INSTANCE; // Paper - replace random // Folia - region threading
    @Deprecated
    private final RandomSource soundSeedGenerator = RandomSource.createThreadSafe();
    private final Holder<DimensionType> dimensionTypeRegistration;
    protected final WritableLevelData levelData;
    private final boolean isClientSide;
    private final BiomeManager biomeManager;
    private final ResourceKey<Level> dimension;
    private final RegistryAccess registryAccess;
    private final DamageSources damageSources;
    private final PalettedContainerFactory palettedContainerFactory;
    private final java.util.concurrent.atomic.AtomicLong subTickCount = new java.util.concurrent.atomic.AtomicLong(); //private long subTickCount; // Folia - region threading

    // CraftBukkit start
    public final io.papermc.paper.antixray.ChunkPacketBlockController chunkPacketBlockController; // Paper - Anti-Xray
    private final CraftWorld world;
    public org.bukkit.generator.@Nullable ChunkGenerator generator;

    // Folia - region threading - moved to regionised data
    public final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<SpawnCategory> ticksPerSpawnCategory = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
    // Folia - region threading - moved to regionised data
    // Folia - region threading
    public final org.spigotmc.SpigotWorldConfig spigotConfig; // Spigot
    // Paper start - add paper world config
    private final io.papermc.paper.configuration.WorldConfiguration paperConfig;
    public io.papermc.paper.configuration.WorldConfiguration paperConfig() {
        return this.paperConfig;
    }
    // Paper end - add paper world config

    public static @Nullable BlockPos lastPhysicsProblem; // Spigot
    //public final Map<ServerExplosion.CacheKey, Float> explosionDensityCache = new java.util.HashMap<>(); // Paper - Optimize explosions // Folia - region threading
    //public java.util.ArrayDeque<net.minecraft.world.level.block.RedstoneTorchBlock.Toggle> redstoneUpdateInfos; // Paper - Faster redstone torch rapid clock removal; Move from Map in BlockRedstoneTorch to here // Folia - region threading

    public CraftWorld getWorld() {
        return this.world;
    }

    public CraftServer getCraftServer() {
        return (CraftServer) org.bukkit.Bukkit.getServer();
    }
    // Paper start - Use getChunkIfLoadedImmediately
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.getChunkIfLoaded(chunkX, chunkZ) != null;
    }
    // Paper end - Use getChunkIfLoadedImmediately
    // Paper start - per world ticks per spawn
    private int getTicksPerSpawn(SpawnCategory spawnCategory) {
        final int perWorld = this.paperConfig().entities.spawning.ticksPerSpawn.getInt(org.bukkit.craftbukkit.util.CraftSpawnCategory.toNMS(spawnCategory));
        if (perWorld >= 0) {
            return perWorld;
        }
        return this.getCraftServer().getTicksPerSpawns(spawnCategory);
    }
    // Paper end

    public abstract ResourceKey<net.minecraft.world.level.dimension.LevelStem> getTypeKey();

    // Paper start - rewrite chunk system
    private ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup entityLookup;
    private final ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData> chunkData = new ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<>();

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup moonrise$getEntityLookup() {
        return this.entityLookup;
    }
    // Folia start - profiler
    public final int tickTimerId;
    // Folia end - profiler

    @Override
    public final void moonrise$setEntityLookup(final ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup entityLookup) {
        if (this.entityLookup != null && !(this.entityLookup instanceof ca.spottedleaf.moonrise.patches.chunk_system.level.entity.dfl.DefaultEntityLookup)) {
            throw new IllegalStateException("Entity lookup already initialised");
        }
        this.entityLookup = entityLookup;
    }

    @Override
    public final <T extends Entity> List<T> getEntitiesOfClass(final Class<T> entityClass, final AABB boundingBox, final Predicate<? super T> predicate) {
        Profiler.get().incrementCounter("getEntities");
        final List<T> ret = new java.util.ArrayList<>();

        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(entityClass, null, boundingBox, ret, predicate);

        return ret;
    }

    @Override
    public final List<Entity> moonrise$getHardCollidingEntities(final Entity entity, final AABB box, final Predicate<? super Entity> predicate) {
        Profiler.get().incrementCounter("getEntities");
        final List<Entity> ret = new java.util.ArrayList<>();

        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getHardCollidingEntities(entity, box, ret, predicate);

        return ret;
    }

    @Override
    public LevelChunk moonrise$getFullChunkIfLoaded(final int chunkX, final int chunkZ) {
        return (LevelChunk)this.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    @Override
    public ChunkAccess moonrise$getAnyChunkIfLoaded(final int chunkX, final int chunkZ) {
        return this.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
    }

    @Override
    public ChunkAccess moonrise$getSpecificChunkIfLoaded(final int chunkX, final int chunkZ, final ChunkStatus leastStatus) {
        return this.getChunkSource().getChunk(chunkX, chunkZ, leastStatus, false);
    }

    @Override
    public void moonrise$midTickTasks() {
        // no-op on ClientLevel
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData moonrise$getChunkData(final long chunkKey) {
        return this.chunkData.get(chunkKey);
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData moonrise$getChunkData(final int chunkX, final int chunkZ) {
        return this.chunkData.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData moonrise$requestChunkData(final long chunkKey) {
        return this.chunkData.compute(chunkKey, (final long keyInMap, final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData valueInMap) -> {
            if (valueInMap == null) {
                final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData ret = new ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData();
                ret.increaseRef();
                return ret;
            }

            valueInMap.increaseRef();
            return valueInMap;
        });
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData moonrise$releaseChunkData(final long chunkKey) {
        return this.chunkData.compute(chunkKey, (final long keyInMap, final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData chunkData) -> {
            return chunkData.decreaseRef() == 0 ? null : chunkData;
        });
    }

    @Override
    public boolean moonrise$areChunksLoaded(final int fromX, final int fromZ, final int toX, final int toZ) {
        final net.minecraft.world.level.chunk.ChunkSource chunkSource = this.getChunkSource();

        for (int currZ = fromZ; currZ <= toZ; ++currZ) {
            for (int currX = fromX; currX <= toX; ++currX) {
                if (!chunkSource.hasChunk(currX, currZ)) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean hasChunksAt(final int minBlockX, final int minBlockZ, final int maxBlockX, final int maxBlockZ) {
        return this.moonrise$areChunksLoaded(
            minBlockX >> 4, minBlockZ >> 4, maxBlockX >> 4, maxBlockZ >> 4
        );
    }

    /**
     * @reason Turn all getChunk(x, z, status) calls into virtual invokes, instead of interface invokes:
     *         1. The interface invoke is expensive
     *         2. The method makes other interface invokes (again, expensive)
     *         Instead, we just directly call getChunk(x, z, status, true) which avoids the interface invokes entirely.
     * @author Spottedleaf
     */
    @Override
    public ChunkAccess getChunk(final int x, final int z, final ChunkStatus status) {
        return ((Level)(Object)this).getChunk(x, z, status, true);
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types types, BlockPos blockPos) {
        return new BlockPos(blockPos.getX(), this.getHeight(types, blockPos.getX(), blockPos.getZ()), blockPos.getZ());
    }
    // Paper end - rewrite chunk system
    // Paper start - optimise collisions
    /**
     * Route to faster lookup.
     * See {@link EntityGetter#isUnobstructed(Entity, VoxelShape)} for expected behavior
     * @author Spottedleaf
     */
    @Override
    public boolean isUnobstructed(final Entity entity) {
        final AABB boundingBox = entity.getBoundingBox();
        if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isEmpty(boundingBox)) {
            return true;
        }

        final List<Entity> entities = this.getEntities(
            entity,
            boundingBox.inflate(-ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON, -ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON),
            null
        );

        for (int i = 0, len = entities.size(); i < len; ++i) {
            final Entity otherEntity = entities.get(i);

            if (otherEntity.isSpectator() || otherEntity.isRemoved() || !otherEntity.blocksBuilding || otherEntity.isPassengerOfSameVehicle(entity)) {
                continue;
            }

            return false;
        }

        return true;
    }


    private static net.minecraft.world.phys.BlockHitResult miss(final ClipContext clipContext) {
        final Vec3 to = clipContext.getTo();
        final Vec3 from = clipContext.getFrom();

        return net.minecraft.world.phys.BlockHitResult.miss(to, Direction.getApproximateNearest(from.x - to.x, from.y - to.y, from.z - to.z), BlockPos.containing(to.x, to.y, to.z));
    }

    private static final FluidState AIR_FLUIDSTATE = Fluids.EMPTY.defaultFluidState();

    private static net.minecraft.world.phys.BlockHitResult fastClip(final Vec3 from, final Vec3 to, final Level level,
                                                                    final ClipContext clipContext) {
        final double adjX = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.x - to.x);
        final double adjY = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.y - to.y);
        final double adjZ = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.z - to.z);

        if (adjX == 0.0 && adjY == 0.0 && adjZ == 0.0) {
            return miss(clipContext);
        }

        final double toXAdj = to.x - adjX;
        final double toYAdj = to.y - adjY;
        final double toZAdj = to.z - adjZ;
        final double fromXAdj = from.x + adjX;
        final double fromYAdj = from.y + adjY;
        final double fromZAdj = from.z + adjZ;

        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);

        final BlockPos.MutableBlockPos currPos = new BlockPos.MutableBlockPos();

        final double diffX = toXAdj - fromXAdj;
        final double diffY = toYAdj - fromYAdj;
        final double diffZ = toZAdj - fromZAdj;

        final double dxDouble = Math.signum(diffX);
        final double dyDouble = Math.signum(diffY);
        final double dzDouble = Math.signum(diffZ);

        final int dx = (int)dxDouble;
        final int dy = (int)dyDouble;
        final int dz = (int)dzDouble;

        final double normalizedDiffX = diffX == 0.0 ? Double.MAX_VALUE : dxDouble / diffX;
        final double normalizedDiffY = diffY == 0.0 ? Double.MAX_VALUE : dyDouble / diffY;
        final double normalizedDiffZ = diffZ == 0.0 ? Double.MAX_VALUE : dzDouble / diffZ;

        double normalizedCurrX = normalizedDiffX * (diffX > 0.0 ? (1.0 - Mth.frac(fromXAdj)) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0 ? (1.0 - Mth.frac(fromYAdj)) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0 ? (1.0 - Mth.frac(fromZAdj)) : Mth.frac(fromZAdj));

        net.minecraft.world.level.chunk.LevelChunkSection[] lastChunk = null;
        net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> lastSection = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkY = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(level);

        for (;;) {
            currPos.set(currX, currY, currZ);

            final int newChunkX = currX >> 4;
            final int newChunkY = currY >> 4;
            final int newChunkZ = currZ >> 4;

            final int chunkDiff = ((newChunkX ^ lastChunkX) | (newChunkZ ^ lastChunkZ));
            final int chunkYDiff = newChunkY ^ lastChunkY;

            if ((chunkDiff | chunkYDiff) != 0) {
                if (chunkDiff != 0) {
                    lastChunk = level.getChunk(newChunkX, newChunkZ).getSections();
                }
                final int sectionY = newChunkY - minSection;
                lastSection = sectionY >= 0 && sectionY < lastChunk.length ? lastChunk[sectionY].getStates() : null;

                lastChunkX = newChunkX;
                lastChunkY = newChunkY;
                lastChunkZ = newChunkZ;
            }

            final BlockState blockState;
            if (lastSection != null && !(blockState = lastSection.get((currX & 15) | ((currZ & 15) << 4) | ((currY & 15) << (4+4)))).isAir()) {
                final VoxelShape blockCollision = clipContext.getBlockShape(blockState, level, currPos);

                final net.minecraft.world.phys.BlockHitResult blockHit = blockCollision.isEmpty() ? null : level.clipWithInteractionOverride(from, to, currPos, blockCollision, blockState);

                final VoxelShape fluidCollision;
                final FluidState fluidState;
                if (clipContext.fluid != ClipContext.Fluid.NONE && (fluidState = blockState.getFluidState()) != AIR_FLUIDSTATE) {
                    fluidCollision = clipContext.getFluidShape(fluidState, level, currPos);

                    final net.minecraft.world.phys.BlockHitResult fluidHit = fluidCollision.clip(from, to, currPos);

                    if (fluidHit != null) {
                        if (blockHit == null) {
                            return fluidHit;
                        }

                        return from.distanceToSqr(blockHit.getLocation()) <= from.distanceToSqr(fluidHit.getLocation()) ? blockHit : fluidHit;
                    }
                }

                if (blockHit != null) {
                    return blockHit;
                }
            } // else: usually fall here

            if (normalizedCurrX > 1.0 && normalizedCurrY > 1.0 && normalizedCurrZ > 1.0) {
                return miss(clipContext);
            }

            // inc the smallest normalized coordinate

            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    // x < y && x >= z <--> z < y && z <= x
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                // y <= x && y < z
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                // y <= x && z <= y <--> z <= y && z <= x
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    /**
     * @reason Route to optimized call
     * @author Spottedleaf
     */
    @Override
    public net.minecraft.world.phys.BlockHitResult clip(final ClipContext clipContext) {
        // can only do this in this class, as not everything that implements BlockGetter can retrieve chunks
        return fastClip(clipContext.getFrom(), clipContext.getTo(), (Level)(Object)this, clipContext);
    }

    /**
     * @reason Route to faster logic
     * @author Spottedleaf
     */
    @Override
    public boolean collidesWithSuffocatingBlock(final Entity entity, final AABB box) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisionsForBlocksOrWorldBorder((Level)(Object)this, entity, box, null, null,
            ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_ONLY,
            (final BlockState state, final BlockPos pos) -> {
                return state.isSuffocating((Level)(Object)Level.this, pos);
            }
        );
    }

    private static VoxelShape inflateAABBToVoxel(final AABB aabb, final double x, final double y, final double z) {
        return net.minecraft.world.phys.shapes.Shapes.create(
            aabb.minX - x,
            aabb.minY - y,
            aabb.minZ - z,

            aabb.maxX + x,
            aabb.maxY + y,
            aabb.maxZ + z
        );
    }

    /**
     * @reason Use optimised OR operator join strategy, avoid streams
     * @author Spottedleaf
     */
    @Override
    public java.util.Optional<net.minecraft.world.phys.Vec3> findFreePosition(final Entity entity, final VoxelShape boundsShape, final Vec3 fromPosition,
                                                                              final double rangeX, final double rangeY, final double rangeZ) {
        if (boundsShape.isEmpty()) {
            return java.util.Optional.empty();
        }

        final double expandByX = rangeX * 0.5;
        final double expandByY = rangeY * 0.5;
        final double expandByZ = rangeZ * 0.5;

        // note: it is useless to look at shapes outside of range / 2.0
        final AABB collectionVolume = boundsShape.bounds().inflate(expandByX, expandByY, expandByZ);

        final List<AABB> aabbs = new java.util.ArrayList<>();
        final List<VoxelShape> voxels = new java.util.ArrayList<>();

        ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisionsForBlocksOrWorldBorder(
            (Level)(Object)this, entity, collectionVolume, voxels, aabbs,
            ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_BORDER,
            null
        );

        final WorldBorder worldBorder = this.getWorldBorder();
        if (worldBorder != null) {
            aabbs.removeIf((final AABB aabb) -> {
                return !worldBorder.isWithinBounds(aabb);
            });
            voxels.removeIf((final VoxelShape shape) -> {
                return !worldBorder.isWithinBounds(shape.bounds());
            });
        }

        // push voxels into aabbs
        for (int i = 0, len = voxels.size(); i < len; ++i) {
            aabbs.addAll(voxels.get(i).toAabbs());
        }

        // expand AABBs
        final VoxelShape first = aabbs.isEmpty() ? net.minecraft.world.phys.shapes.Shapes.empty() : inflateAABBToVoxel(aabbs.get(0), expandByX, expandByY, expandByZ);
        final VoxelShape[] rest = new VoxelShape[Math.max(0, aabbs.size() - 1)];

        for (int i = 1, len = aabbs.size(); i < len; ++i) {
            rest[i - 1] = inflateAABBToVoxel(aabbs.get(i), expandByX, expandByY, expandByZ);
        }

        // use optimized implementation of ORing the shapes together
        final VoxelShape joined = net.minecraft.world.phys.shapes.Shapes.or(first, rest);

        // find free space
        // can use unoptimized join here (instead of join()), as closestPointTo uses toAabbs()
        final VoxelShape freeSpace = net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(boundsShape, joined, net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);

        return freeSpace.closestPointTo(fromPosition);
    }

    /**
     * @reason Route to faster logic
     * @author Spottedleaf
     */
    @Override
    public java.util.Optional<net.minecraft.core.BlockPos> findSupportingBlock(final Entity entity, final AABB aabb) {
        final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection((Level)(Object)this);

        final int minBlockX = Mth.floor(aabb.minX - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) - 1;
        final int maxBlockX = Mth.floor(aabb.maxX + ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) + 1;

        final int minBlockY = Math.max((minSection << 4) - 1, Mth.floor(aabb.minY - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) - 1);
        final int maxBlockY = Math.min((ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxSection((Level)(Object)this) << 4) + 16, Mth.floor(aabb.maxY + ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) + 1);

        final int minBlockZ = Mth.floor(aabb.minZ - ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) - 1;
        final int maxBlockZ = Mth.floor(aabb.maxZ + ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON) + 1;

        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext collisionShape = new ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext(entity);
        BlockPos selected = null;
        double selectedDistance = Double.MAX_VALUE;
        final Vec3 entityPos = entity.position();

        // special cases:
        if (minBlockY > maxBlockY) {
            // no point in checking
            return java.util.Optional.empty();
        }

        final int minChunkX = minBlockX >> 4;
        final int maxChunkX = maxBlockX >> 4;

        final int minChunkY = minBlockY >> 4;
        final int maxChunkY = maxBlockY >> 4;

        final int minChunkZ = minBlockZ >> 4;
        final int maxChunkZ = maxBlockZ >> 4;

        final net.minecraft.world.level.chunk.ChunkSource chunkSource = this.getChunkSource();

        for (int currChunkZ = minChunkZ; currChunkZ <= maxChunkZ; ++currChunkZ) {
            for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                final ChunkAccess chunk = chunkSource.getChunk(currChunkX, currChunkZ, ChunkStatus.FULL, false);

                if (chunk == null) {
                    continue;
                }

                final net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();

                // bound y
                for (int currChunkY = minChunkY; currChunkY <= maxChunkY; ++currChunkY) {
                    final int sectionIdx = currChunkY - minSection;
                    if (sectionIdx < 0 || sectionIdx >= sections.length) {
                        continue;
                    }
                    final net.minecraft.world.level.chunk.LevelChunkSection section = sections[sectionIdx];
                    if (section.hasOnlyAir()) {
                        // empty
                        continue;
                    }

                    final boolean hasSpecial = ((ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection)section).moonrise$hasSpecialCollidingBlocks();
                    final int sectionAdjust = !hasSpecial ? 1 : 0;

                    final net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> blocks = section.getStates();

                    final int minXIterate = currChunkX == minChunkX ? (minBlockX & 15) + sectionAdjust : 0;
                    final int maxXIterate = currChunkX == maxChunkX ? (maxBlockX & 15) - sectionAdjust : 15;
                    final int minZIterate = currChunkZ == minChunkZ ? (minBlockZ & 15) + sectionAdjust : 0;
                    final int maxZIterate = currChunkZ == maxChunkZ ? (maxBlockZ & 15) - sectionAdjust : 15;
                    final int minYIterate = currChunkY == minChunkY ? (minBlockY & 15) + sectionAdjust : 0;
                    final int maxYIterate = currChunkY == maxChunkY ? (maxBlockY & 15) - sectionAdjust : 15;

                    for (int currY = minYIterate; currY <= maxYIterate; ++currY) {
                        final int blockY = currY | (currChunkY << 4);
                        mutablePos.setY(blockY);
                        for (int currZ = minZIterate; currZ <= maxZIterate; ++currZ) {
                            final int blockZ = currZ | (currChunkZ << 4);
                            mutablePos.setZ(blockZ);
                            for (int currX = minXIterate; currX <= maxXIterate; ++currX) {
                                final int localBlockIndex = (currX) | (currZ << 4) | ((currY) << 8);
                                final int blockX = currX | (currChunkX << 4);
                                mutablePos.setX(blockX);

                                final int edgeCount = hasSpecial ? ((blockX == minBlockX || blockX == maxBlockX) ? 1 : 0) +
                                    ((blockY == minBlockY || blockY == maxBlockY) ? 1 : 0) +
                                    ((blockZ == minBlockZ || blockZ == maxBlockZ) ? 1 : 0) : 0;
                                if (edgeCount == 3) {
                                    continue;
                                }

                                final double distance = mutablePos.distToCenterSqr(entityPos);
                                if (distance > selectedDistance || (distance == selectedDistance && selected.compareTo(mutablePos) >= 0)) {
                                    continue;
                                }

                                final BlockState blockData = blocks.get(localBlockIndex);

                                if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockData).moonrise$emptyContextCollisionShape()) {
                                    continue;
                                }

                                VoxelShape blockCollision = ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockData).moonrise$getConstantContextCollisionShape();

                                if (edgeCount == 0 || ((edgeCount != 1 || blockData.hasLargeCollisionShape()) && (edgeCount != 2 || blockData.getBlock() == Blocks.MOVING_PISTON))) {
                                    if (blockCollision == null) {
                                        blockCollision = blockData.getCollisionShape((Level)(Object)this, mutablePos, collisionShape);

                                        if (blockCollision.isEmpty()) {
                                            continue;
                                        }
                                    }

                                    // avoid VoxelShape#move by shifting the entity collision shape instead
                                    final AABB shiftedAABB = aabb.move(-(double)blockX, -(double)blockY, -(double)blockZ);

                                    final AABB singleAABB = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)blockCollision).moonrise$getSingleAABBRepresentation();
                                    if (singleAABB != null) {
                                        if (!ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersect(singleAABB, shiftedAABB)) {
                                            continue;
                                        }

                                        selected = mutablePos.immutable();
                                        selectedDistance = distance;
                                        continue;
                                    }

                                    if (!ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersectNoEmpty(blockCollision, shiftedAABB)) {
                                        continue;
                                    }

                                    selected = mutablePos.immutable();
                                    selectedDistance = distance;
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
        }

        return java.util.Optional.ofNullable(selected);
    }
    // Paper end - optimise collisions
    // Paper start - getblock optimisations - cache world height/sections
    private final int minY;
    private final int height;
    private final int maxY;
    private final int minSectionY;
    private final int maxSectionY;
    private final int sectionsCount;

    @Override
    public int getMinY() {
        return this.minY;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public int getMaxY() {
        return this.maxY;
    }

    @Override
    public int getSectionsCount() {
        return this.sectionsCount;
    }

    @Override
    public int getMinSectionY() {
        return this.minSectionY;
    }

    @Override
    public int getMaxSectionY() {
        return this.maxSectionY;
    }

    @Override
    public boolean isInsideBuildHeight(final int blockY) {
        return blockY >= this.minY && blockY <= this.maxY;
    }

    @Override
    public boolean isOutsideBuildHeight(final BlockPos pos) {
        return this.isOutsideBuildHeight(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(final int blockY) {
        return blockY < this.minY || blockY > this.maxY;
    }

    @Override
    public int getSectionIndex(final int blockY) {
        return (blockY >> 4) - this.minSectionY;
    }

    @Override
    public int getSectionIndexFromSectionY(final int sectionY) {
        return sectionY - this.minSectionY;
    }

    @Override
    public int getSectionYFromSectionIndex(final int sectionIdx) {
        return sectionIdx + this.minSectionY;
    }
    // Paper end - getblock optimisations - cache world height/sections
    // Paper start - optimise random ticking
    @Override
    public abstract Holder<Biome> getUncachedNoiseBiome(final int x, final int y, final int z);

    /**
     * @reason Make getChunk and getUncachedNoiseBiome virtual calls instead of interface calls
     *         by implementing the superclass method in this class.
     * @author Spottedleaf
     */
    @Override
    public Holder<Biome> getNoiseBiome(final int x, final int y, final int z) {
        final ChunkAccess chunk = this.getChunk(x >> 2, z >> 2, ChunkStatus.BIOMES, false);

        return chunk != null ? chunk.getNoiseBiome(x, y, z) : this.getUncachedNoiseBiome(x, y, z);
    }
    // Paper end - optimise random ticking
    // Folia start - region ticking
    public final io.papermc.paper.threadedregions.RegionizedData<io.papermc.paper.threadedregions.RegionizedWorldData> worldRegionData
        = new io.papermc.paper.threadedregions.RegionizedData<>(
        (ServerLevel)this, (regionData) -> new io.papermc.paper.threadedregions.RegionizedWorldData((ServerLevel)Level.this, regionData),
        io.papermc.paper.threadedregions.RegionizedWorldData.REGION_CALLBACK
    );
    public volatile io.papermc.paper.threadedregions.RegionizedServer.WorldLevelData tickData;
    public final java.util.concurrent.ConcurrentHashMap.KeySetView<net.minecraft.server.level.ChunkHolder, Boolean> needsChangeBroadcasting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public io.papermc.paper.threadedregions.RegionizedWorldData getCurrentWorldData() {
        final io.papermc.paper.threadedregions.RegionizedWorldData ret = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (ret == null) {
            return ret;
        }
        Level world = ret.world;
        if (world != this) {
            throw new IllegalStateException("World mismatch: expected " + this.getWorld().getName() + " but got " + world.getWorld().getName());
        }
        return ret;
    }

    @Override
    public List<net.minecraft.server.level.ServerPlayer> getLocalPlayers() {
        return this.getCurrentWorldData().getLocalPlayers();
    }
    // Folia end - region ticking

    protected Level(
        final WritableLevelData levelData,
        final ResourceKey<Level> dimension,
        final RegistryAccess registryAccess,
        final Holder<DimensionType> dimensionTypeRegistration,
        final boolean isClientSide,
        final boolean isDebug,
        final long biomeZoomSeed,
        final int maxChainedNeighborUpdates
        , String bukkitName, // Paper
        org.bukkit.generator.@Nullable ChunkGenerator generator, // Paper
        org.bukkit.generator.@Nullable BiomeProvider biomeProvider, // Paper
        org.bukkit.World.Environment environment, // Paper
        java.util.function.Function<org.spigotmc.SpigotWorldConfig, // Spigot - create per world config
        io.papermc.paper.configuration.WorldConfiguration> paperWorldConfigCreator, // Paper - create paper world config
        java.util.concurrent.Executor executor // Paper - Anti-Xray
    ) {
        // Paper start - getblock optimisations - cache world height/sections
        final DimensionType dimType = dimensionTypeRegistration.value();
        this.minY = dimType.minY();
        this.height = dimType.height();
        this.maxY = this.minY + this.height - 1;
        this.minSectionY = this.minY >> 4;
        this.maxSectionY = this.maxY >> 4;
        this.sectionsCount = this.maxSectionY - this.minSectionY + 1;
        // Paper end - getblock optimisations - cache world height/sections
        final org.bukkit.NamespacedKey worldKey = CraftNamespacedKey.fromMinecraft(dimension.identifier()); // Paper
        this.spigotConfig = new org.spigotmc.SpigotWorldConfig(bukkitName, worldKey); // Spigot
        this.paperConfig = paperWorldConfigCreator.apply(this.spigotConfig); // Paper - create paper world config
        this.generator = generator;
        this.world = new CraftWorld((ServerLevel) this, worldKey, biomeProvider, environment);

        for (SpawnCategory spawnCategory : SpawnCategory.values()) {
            if (org.bukkit.craftbukkit.util.CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                this.ticksPerSpawnCategory.put(spawnCategory, this.getTicksPerSpawn(spawnCategory));
            }
        }
        // CraftBukkit end
        this.levelData = levelData;
        this.dimensionTypeRegistration = dimensionTypeRegistration;
        this.dimension = dimension;
        this.isClientSide = isClientSide;
        this.thread = Thread.currentThread();
        this.biomeManager = new BiomeManager(this, biomeZoomSeed);
        this.isDebug = isDebug;
        this.neighbourUpdateMax = maxChainedNeighborUpdates; // Folia - region threading
        this.registryAccess = registryAccess;
        this.palettedContainerFactory = PalettedContainerFactory.create(registryAccess);
        this.damageSources = new DamageSources(registryAccess);
        this.entityLookup = new ca.spottedleaf.moonrise.patches.chunk_system.level.entity.dfl.DefaultEntityLookup(this); // Paper - rewrite chunk system
        this.chunkPacketBlockController = this.paperConfig().anticheat.antiXray.enabled ? new io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray(this, executor) : io.papermc.paper.antixray.ChunkPacketBlockController.NO_OPERATION_INSTANCE; // Paper - Anti-Xray
        // Folia start - profiler
        this.tickTimerId = ca.spottedleaf.leafprofiler.LProfilerRegistry.GLOBAL_REGISTRY.getOrCreateTimer("Tick World: " + dimension.identifier().toString());
        // Folia end - profiler
    }

    public int getNextEntityId() {
        return 0;
    }

    // Paper start - Cancel hit for vanished players
    // ret true if no collision
    public final boolean checkEntityCollision(BlockState state, Entity source, net.minecraft.world.phys.shapes.CollisionContext context,
                                              BlockPos pos, boolean checkCanSee) {
        // Copied from CollisionGetter#isUnobstructed(BlockState, BlockPos, CollisionContext) & EntityGetter#isUnobstructed(Entity, VoxelShape)
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(this, pos, context);
        if (shape.isEmpty()) {
            return true;
        }

        shape = shape.move((double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
        if (shape.isEmpty()) {
            return true;
        }

        List<Entity> entities = this.getEntities(null, shape.bounds());
        for (int i = 0, len = entities.size(); i < len; ++i) {
            Entity entity = entities.get(i);

            if (checkCanSee && source instanceof net.minecraft.server.level.ServerPlayer && entity instanceof net.minecraft.server.level.ServerPlayer
                && !((net.minecraft.server.level.ServerPlayer) source).getBukkitEntity().canSee(((net.minecraft.server.level.ServerPlayer) entity).getBukkitEntity())) {
                continue;
            }

            // !entity.isRemoved() && entity.blocksBuilding && (source == null || !entity.isPassengerOfSameVehicle(source))
            // elide the last check since vanilla calls with source = null
            // only we care about the source for the canSee check
            if (entity.isRemoved() || !entity.blocksBuilding) {
                continue;
            }

            if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape, net.minecraft.world.phys.shapes.Shapes.create(entity.getBoundingBox()), net.minecraft.world.phys.shapes.BooleanOp.AND)) {
                return false;
            }
        }

        return true;
    }
    // Paper end - Cancel hit for vanished players

    @Override
    public boolean isClientSide() {
        return this.isClientSide;
    }

    @Override
    public @Nullable MinecraftServer getServer() {
        return null;
    }

    // Paper start
    public net.minecraft.world.phys.BlockHitResult.Type clipDirect(Vec3 start, Vec3 end, net.minecraft.world.phys.shapes.CollisionContext context) {
        // To be patched over
        return this.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context)).getType();
    }
    // Paper end

    public boolean isInWorldBounds(final BlockPos pos) {
        return pos.isInsideBuildHeightAndWorldBoundsHorizontal(this); // Paper - Perf: Optimize isInWorldBounds
    }

    public boolean isInValidBounds(final BlockPos pos) {
        return this.isInsideBuildHeight(pos) && isInValidBoundsHorizontal(pos);
    }

    public static boolean isInSpawnableBounds(final BlockPos pos) {
        return !isOutsideSpawnableHeight(pos.getY()) && isInWorldBoundsHorizontal(pos);
    }

    private static boolean isInWorldBoundsHorizontal(final BlockPos pos) {
        return pos.getX() >= -30000000 && pos.getZ() >= -30000000 && pos.getX() < 30000000 && pos.getZ() < 30000000; // Paper - Diff on change warnUnsafeChunk() and isInsideBuildHeightAndWorldBoundsHorizontal
    }

    private static boolean isInValidBoundsHorizontal(final BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        return ChunkPos.isValid(chunkX, chunkZ);
    }

    private static boolean isOutsideSpawnableHeight(final int y) {
        return y < -20000000 || y >= 20000000;
    }

    public final LevelChunk getChunkAt(final BlockPos pos) { // Paper - help inline
        return this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    @Override
    public final LevelChunk getChunk(final int chunkX, final int chunkZ) { // Paper - final to help inline
        // Paper start - Perf: make sure loaded chunks get the inlined variant of this function
        net.minecraft.server.level.ServerChunkCache cps = ((ServerLevel)this).getChunkSource();
        LevelChunk ifLoaded = cps.getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        if (ifLoaded != null) {
            return ifLoaded;
        }
        return (LevelChunk) cps.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true); // Paper - avoid a method jump
        // Paper end - Perf: make sure loaded chunks get the inlined variant of this function
    }

    // Paper start - if loaded
    @Nullable
    @Override
    public final ChunkAccess getChunkIfLoadedImmediately(int x, int z) {
        return ((ServerLevel)this).getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    @Override
    @Nullable
    public final BlockState getBlockStateIfLoaded(BlockPos pos) {
        // CraftBukkit start - tree generation
        if (this.getCurrentWorldData().captureTreeGeneration) { // Folia - region threading
            CraftBlockState previous = this.getCurrentWorldData().capturedBlockStates.get(pos); // Folia - region threading
            if (previous != null) {
                return previous.getHandle();
            }
        }
        // CraftBukkit end
        if (this.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        } else {
            ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);

            return chunk == null ? null : chunk.getBlockState(pos);
        }
    }

    @Override
    @Nullable
    public final FluidState getFluidIfLoaded(BlockPos pos) {
        ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);

        return chunk == null ? null : chunk.getFluidState(pos);
    }

    @Override
    public final boolean hasChunkAt(BlockPos pos) {
        return this.getChunkIfLoaded(pos.getX() >> 4, pos.getZ() >> 4) != null; // Paper - Perf: Optimize LevelReader.hasChunkAt(BlockPos)Z
    }

    public final boolean isLoadedAndInBounds(BlockPos pos) { // Paper - final for inline
        return this.getWorldBorder().isWithinBounds(pos) && this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    public @Nullable LevelChunk getChunkIfLoaded(int x, int z) { // Overridden in ServerLevel for ABI compat which has final
        return ((ServerLevel) this).getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    public final @Nullable LevelChunk getChunkIfLoaded(BlockPos pos) {
        return ((ServerLevel) this).getChunkSource().getChunkAtIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public final @Nullable BlockState getBlockStateIfLoadedAndInBounds(BlockPos pos) {
        return this.getWorldBorder().isWithinBounds(pos) ? this.getBlockStateIfLoaded(pos) : null;
    }
    // Paper end

    @Override
    public @Nullable ChunkAccess getChunk(final int chunkX, final int chunkZ, final ChunkStatus status, final boolean loadOrGenerate) {
        ChunkAccess chunk = this.getChunkSource().getChunk(chunkX, chunkZ, status, loadOrGenerate);
        if (chunk == null && loadOrGenerate) {
            throw new IllegalStateException("Should always be able to create a chunk!");
        } else {
            return chunk;
        }
    }

    @Override
    public boolean setBlock(final BlockPos pos, final BlockState blockState, final @Block.UpdateFlags int updateFlags) {
        return this.setBlock(pos, blockState, updateFlags, Block.UPDATE_LIMIT);
    }

    @Override
    public boolean setBlock(final BlockPos pos, final BlockState blockState, final @Block.UpdateFlags int updateFlags, final int updateLimit) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread((ServerLevel)this, pos, "Updating block asynchronously"); // Folia - region threading
        final io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.getCurrentWorldData(); // Folia - region threading
        // CraftBukkit start - tree generation
        if (worldData.captureTreeGeneration) { // Folia - region threading
            // Paper start - Protect Bedrock and End Portal/Frames from being destroyed
            BlockState type = getBlockState(pos);
            if (!type.isDestroyable()) return false;
            // Paper end - Protect Bedrock and End Portal/Frames from being destroyed
            CraftBlockState blockstate = worldData.capturedBlockStates.get(pos); // Folia - region threading
            if (blockstate == null) {
                blockstate = org.bukkit.craftbukkit.block.CapturedBlockState.getTreeBlockState(this, pos, updateFlags);
                worldData.capturedBlockStates.put(pos.immutable(), blockstate); // Folia - region threading
            }
            blockstate.setBlock(blockState);
            blockstate.setFlags(updateFlags);
            return true;
        }
        // CraftBukkit end
        if (!this.isInValidBounds(pos)) {
            return false;
        }

        if (!this.isClientSide() && this.isDebug()) {
            return false;
        }

        LevelChunk chunk = this.getChunkAt(pos);
        Block block = blockState.getBlock();
        // CraftBukkit start - capture blockstates
        boolean captured = false;
        if (worldData.captureBlockStates) { // Folia - region threading
            final CraftBlockState snapshot;
            if (!worldData.capturedBlockStates.containsKey(pos)) { // Folia - region threading
                snapshot = (CraftBlockState) org.bukkit.craftbukkit.block.CraftBlock.at(this, pos).getState(); // Paper - use CB getState to get a suitable snapshot
                worldData.capturedBlockStates.put(pos.immutable(), snapshot); // Folia - region threading
                captured = true;
            } else {
                snapshot = worldData.capturedBlockStates.get(pos); // Folia - region threading
            }
            snapshot.setFlags(updateFlags); // Paper - always set the flag of the most recent call to mitigate issues with multiple update at the same pos with different flags
        }
        // CraftBukkit end - capture blockstates
        BlockState oldState = chunk.setBlockState(pos, blockState, updateFlags);
        this.chunkPacketBlockController.onBlockChange(this, pos, blockState, oldState, updateFlags, updateLimit); // Paper - Anti-Xray
        if (oldState == null) {
            // CraftBukkit start - remove blockstate if failed (or the same)
            if (worldData.captureBlockStates && captured) { // Folia - region threading
                worldData.capturedBlockStates.remove(pos); // Folia - region threading
            }
            // CraftBukkit end
            return false;
        }

        BlockState newState = this.getBlockState(pos);
        /* // CraftBukkit
        if (newState == blockState) {
            if (oldState != newState) {
                this.setBlocksDirty(pos, oldState, newState);
            }

            if ((updateFlags & Block.UPDATE_CLIENTS) != 0
                && (!this.isClientSide() || (updateFlags & Block.UPDATE_INVISIBLE) == 0)
                && (this.isClientSide() || chunk.getFullStatus() != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING))) {
                this.sendBlockUpdated(pos, oldState, blockState, updateFlags);
            }

            if ((updateFlags & Block.UPDATE_NEIGHBORS) != 0) {
                this.updateNeighborsAt(pos, oldState.getBlock());
                if (!this.isClientSide() && blockState.hasAnalogOutputSignal()) {
                    this.updateNeighbourForOutputSignal(pos, block);
                }
            }

            if ((updateFlags & Block.UPDATE_KNOWN_SHAPE) == 0 && updateLimit > 0) {
                int neighbourUpdateFlags = updateFlags & ~(Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_NEIGHBORS);
                oldState.updateIndirectNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1);
                blockState.updateNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1);
                blockState.updateIndirectNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1);
            }

            this.updatePOIOnBlockStateChange(pos, oldState, newState);
        }
        */ // CraftBukkit

        // CraftBukkit start
        if (!worldData.captureBlockStates) { // Don't notify clients or update physics while capturing blockstates // Folia - region threading
            // Modularize client and physic updates
            // Spigot start
            try {
                this.notifyAndUpdatePhysics(pos, chunk, oldState, blockState, newState, updateFlags, updateLimit);
            } catch (StackOverflowError ex) {
                Level.lastPhysicsProblem = pos.immutable();
            }
            // Spigot end
        }
        // CraftBukkit end

        return true;
    }

    // CraftBukkit start - Split off from above in order to directly send client and physic updates
    public void notifyAndUpdatePhysics(BlockPos pos, LevelChunk chunk, BlockState oldState, BlockState blockState, BlockState newState, @Block.UpdateFlags int updateFlags, int updateLimit) {
        if (newState == blockState) {
            if (oldState != newState) {
                this.setBlocksDirty(pos, oldState, newState);
            }

            if ((updateFlags & Block.UPDATE_CLIENTS) != 0 && (!this.isClientSide() || (updateFlags & Block.UPDATE_INVISIBLE) == 0) && (this.isClientSide() || chunk == null || (chunk.getFullStatus() != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)))) { // allow chunk to be null here as chunk.isReady() is false when we send our notification during block placement // Paper - rewrite chunk system - change from ticking to full
                this.sendBlockUpdated(pos, oldState, blockState, updateFlags);
            }

            if ((updateFlags & Block.UPDATE_NEIGHBORS) != 0) {
                this.updateNeighborsAt(pos, oldState.getBlock());
                if (!this.isClientSide() && blockState.hasAnalogOutputSignal()) {
                    this.updateNeighbourForOutputSignal(pos, blockState.getBlock());
                }
            }

            if ((updateFlags & Block.UPDATE_KNOWN_SHAPE) == 0 && updateLimit > 0) {
                int neighbourUpdateFlags = updateFlags & ~(Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_NEIGHBORS);

                // CraftBukkit start
                oldState.updateIndirectNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1); // Don't call an event for the old block to limit event spam
                boolean cancelledUpdates = false; // Paper - Fix block place logic
                if (((ServerLevel)this).getCurrentWorldData().hasPhysicsEvent) { // Paper - BlockPhysicsEvent // Folia - region threading
                    org.bukkit.event.block.BlockPhysicsEvent event = new org.bukkit.event.block.BlockPhysicsEvent(org.bukkit.craftbukkit.block.CraftBlock.at(this, pos), blockState.asBlockData());
                    cancelledUpdates = !event.callEvent(); // Paper - Fix block place logic
                }
                // CraftBukkit end
                if (!cancelledUpdates) { // Paper - Fix block place logic
                    blockState.updateNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1);
                    blockState.updateIndirectNeighbourShapes(this, pos, neighbourUpdateFlags, updateLimit - 1);
                } // Paper - Fix block place logic
            }

            if ((updateFlags & Block.UPDATE_SKIP_POI) == 0) // Paper - temporary flag
            this.updatePOIOnBlockStateChange(pos, oldState, newState);
        }
    }
    // CraftBukkit end

    public void updatePOIOnBlockStateChange(final BlockPos pos, final BlockState oldState, final BlockState newState) {
    }

    @Override
    public boolean removeBlock(final BlockPos pos, final boolean movedByPiston) {
        FluidState fluidState = this.getFluidState(pos);
        return this.setBlock(pos, fluidState.createLegacyBlock(), Block.UPDATE_ALL | (movedByPiston ? Block.UPDATE_MOVE_BY_PISTON : 0));
    }

    @Override
    public boolean destroyBlock(final BlockPos pos, boolean dropResources, final @Nullable Entity breaker, final int updateLimit) { // Paper - make dropResources non-final
        BlockState blockState = this.getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }

        FluidState fluidState = this.getFluidState(pos);
        // Paper start - BlockDestroyEvent; while the above removeBlock method looks very similar
        // they are NOT used with same intent and the above should not fire this event. The above method is more of a BlockSetToAirEvent,
        // it doesn't imply destruction of a block that plays a sound effect / drops an item.
        boolean playEffect = true;
        BlockState effectType = blockState;
        int xp = blockState.getBlock().getExpDrop(blockState, (ServerLevel) this, pos, ItemStack.EMPTY, true);
        if (com.destroystokyo.paper.event.block.BlockDestroyEvent.getHandlerList().getRegisteredListeners().length > 0) {
            com.destroystokyo.paper.event.block.BlockDestroyEvent event = new com.destroystokyo.paper.event.block.BlockDestroyEvent(org.bukkit.craftbukkit.block.CraftBlock.at(this, pos), fluidState.createLegacyBlock().asBlockData(), effectType.asBlockData(), xp, dropResources);
            if (!event.callEvent()) {
                return false;
            }
            effectType = ((CraftBlockData) event.getEffectBlock()).getState();
            playEffect = event.playEffect();
            dropResources = event.willDrop();
            xp = event.getExpToDrop();
        }
        // Paper end - BlockDestroyEvent
        if (playEffect && !(blockState.getBlock() instanceof BaseFireBlock)) { // Paper - BlockDestroyEvent
            this.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(effectType)); // Paper - BlockDestroyEvent
        }

        if (dropResources) {
            BlockEntity blockEntity = blockState.hasBlockEntity() ? this.getBlockEntity(pos) : null;
            Block.dropResources(blockState, this, pos, blockEntity, breaker, ItemStack.EMPTY, false); // Paper - Properly handle xp dropping
            blockState.getBlock().popExperience((ServerLevel) this, pos, xp, breaker); // Paper - Properly handle xp dropping; custom amount
        }

        boolean destroyed = this.setBlock(pos, fluidState.createLegacyBlock(), Block.UPDATE_ALL, updateLimit);
        if (destroyed) {
            this.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(breaker, blockState));
        }

        return destroyed;
    }

    public void addDestroyBlockEffect(final BlockPos pos, final BlockState blockState) {
    }

    public boolean setBlockAndUpdate(final BlockPos pos, final BlockState blockState) {
        return this.setBlock(pos, blockState, Block.UPDATE_ALL);
    }

    public abstract void sendBlockUpdated(BlockPos pos, BlockState old, BlockState current, @Block.UpdateFlags int updateFlags);

    public void setBlocksDirty(final BlockPos pos, final BlockState oldState, final BlockState newState) {
    }

    public void updateNeighborsAt(final BlockPos pos, final Block sourceBlock, final @Nullable Orientation orientation) {
    }

    public void updateNeighborsAtExceptFromFacing(
        final BlockPos pos, final Block blockObject, final Direction skipDirection, final @Nullable Orientation orientation
    ) {
    }

    public void neighborChanged(final BlockPos pos, final Block changedBlock, final @Nullable Orientation orientation) {
    }

    public void neighborChanged(
        final BlockState state, final BlockPos pos, final Block changedBlock, final @Nullable Orientation orientation, final boolean movedByPiston
    ) {
    }

    @Override
    public void neighborShapeChanged(
        final Direction direction,
        final BlockPos pos,
        final BlockPos neighborPos,
        final BlockState neighborState,
        final @Block.UpdateFlags int updateFlags,
        final int updateLimit
    ) {
        this.getCurrentWorldData().neighborUpdater.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit); // Folia - region threading
    }

    @Override
    public int getHeight(final Heightmap.Types type, final int x, final int z) {
        int y;
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000) {
            if (this.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                y = this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
            } else {
                y = this.getMinY();
            }
        } else {
            y = this.getSeaLevel() + 1;
        }

        return y;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.getChunkSource().getLightEngine();
    }

    // Folia start - region threading
    @Nullable
    public BlockState getBlockStateFromEmptyChunkIfLoaded(BlockPos pos) {
        net.minecraft.server.level.ServerChunkCache chunkProvider = (net.minecraft.server.level.ServerChunkCache)this.getChunkSource();
        ChunkAccess chunk = chunkProvider.getChunkAtImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            return chunk.getBlockState(pos);
        }
        return null;
    }

    @Nullable
    public BlockState getBlockStateFromEmptyChunk(BlockPos pos) {
        net.minecraft.server.level.ServerChunkCache chunkProvider = (net.minecraft.server.level.ServerChunkCache)this.getChunkSource();
        ChunkAccess chunk = chunkProvider.getChunkAtImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            return chunk.getBlockState(pos);
        }
        chunk = chunkProvider.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.EMPTY, true);
        return chunk.getBlockState(pos);
    }
    // Folia end - region threading

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        // CraftBukkit start - tree generation
        if (this.getCurrentWorldData().captureTreeGeneration) { // Folia - region threading
            CraftBlockState previous = this.getCurrentWorldData().capturedBlockStates.get(pos); // Paper // Folia - region threading
            if (previous != null) {
                return previous.getHandle();
            }
        }
        // CraftBukkit end
        if (!this.isInValidBounds(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }

        ChunkAccess chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true); // Paper - manually inline to reduce hops and avoid unnecessary null check to reduce total byte code size, this should never return null and if it does we will see it the next line but the real stack trace will matter in the chunk engine
        return chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        if (!this.isInValidBounds(pos)) {
            return Fluids.EMPTY.defaultFluidState();
        }

        LevelChunk chunk = this.getChunkAt(pos);
        return chunk.getFluidState(pos);
    }

    public boolean isBrightOutside() {
        return !this.dimensionType().hasFixedTime() && this.skyDarken < 4;
    }

    public boolean isDarkOutside() {
        return !this.dimensionType().hasFixedTime() && !this.isBrightOutside();
    }

    @Override
    public void playSound(
        final @Nullable Entity except, final BlockPos pos, final SoundEvent sound, final SoundSource source, final float volume, final float pitch
    ) {
        this.playSound(except, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, source, volume, pitch);
    }

    public abstract void playSeededSound(
        final @Nullable Entity except,
        final double x,
        final double y,
        final double z,
        final Holder<SoundEvent> sound,
        final SoundSource source,
        final float volume,
        final float pitch,
        final long seed
    );

    public void playSeededSound(
        final @Nullable Entity except,
        final double x,
        final double y,
        final double z,
        final SoundEvent sound,
        final SoundSource source,
        final float volume,
        final float pitch,
        final long seed
    ) {
        this.playSeededSound(except, x, y, z, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, volume, pitch, seed);
    }

    public abstract void playSeededSound(
        final @Nullable Entity except,
        final Entity sourceEntity,
        final Holder<SoundEvent> sound,
        final SoundSource source,
        final float volume,
        final float pitch,
        final long seed
    );

    public void playSound(final @Nullable Entity except, final double x, final double y, final double z, final SoundEvent sound, final SoundSource source) {
        this.playSound(except, x, y, z, sound, source, 1.0F, 1.0F);
    }

    public void playSound(
        final @Nullable Entity except,
        final double x,
        final double y,
        final double z,
        final SoundEvent sound,
        final SoundSource source,
        final float volume,
        final float pitch
    ) {
        this.playSeededSound(except, x, y, z, sound, source, volume, pitch, this.soundSeedGenerator.nextLong());
    }

    public void playSound(
        final @Nullable Entity except,
        final double x,
        final double y,
        final double z,
        final Holder<SoundEvent> sound,
        final SoundSource source,
        final float volume,
        final float pitch
    ) {
        this.playSeededSound(except, x, y, z, sound, source, volume, pitch, this.soundSeedGenerator.nextLong());
    }

    public void playSound(
        final @Nullable Entity except, final Entity sourceEntity, final SoundEvent sound, final SoundSource source, final float volume, final float pitch
    ) {
        this.playSeededSound(except, sourceEntity, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, volume, pitch, this.soundSeedGenerator.nextLong());
    }

    public void playLocalSound(
        final BlockPos pos, final SoundEvent sound, final SoundSource source, final float volume, final float pitch, final boolean distanceDelay
    ) {
        this.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, source, volume, pitch, distanceDelay);
    }

    public void playLocalSound(final Entity sourceEntity, final SoundEvent sound, final SoundSource source, final float volume, final float pitch) {
    }

    public void playLocalSound(
        final double x,
        final double y,
        final double z,
        final SoundEvent sound,
        final SoundSource source,
        final float volume,
        final float pitch,
        final boolean distanceDelay
    ) {
    }

    public void playPlayerSound(final SoundEvent sound, final SoundSource source, final float volume, final float pitch) {
    }

    @Override
    public void addParticle(final ParticleOptions particle, final double x, final double y, final double z, final double xd, final double yd, final double zd) {
    }

    public void addParticle(
        final ParticleOptions particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final double xd,
        final double yd,
        final double zd
    ) {
    }

    public void addAlwaysVisibleParticle(
        final ParticleOptions particle, final double x, final double y, final double z, final double xd, final double yd, final double zd
    ) {
    }

    public void addAlwaysVisibleParticle(
        final ParticleOptions particle,
        final boolean overrideLimiter,
        final double x,
        final double y,
        final double z,
        final double xd,
        final double yd,
        final double zd
    ) {
    }

    public void addBlockEntityTicker(final TickingBlockEntity ticker) {
        if (!this.paperConfig().unsupportedSettings.ticking.blockEntities) return; // Paper - option to disable ticking
        this.getCurrentWorldData().addBlockEntityTicker(ticker); // Folia - regionised ticking
    }

    public void tickBlockEntities() {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.getCurrentWorldData(); // Folia - regionised ticking
        regionizedWorldData.setTickingBlockEntities(true); // Folia - regionised ticking
        profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY_PENDING); try { // Folia - profiler
        regionizedWorldData.pushPendingTickingBlockEntities(); // Folia - regionised ticking
        } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY_PENDING); } // Folia - profiler
        List<TickingBlockEntity> blockEntityTickers = regionizedWorldData.getBlockEntityTickers(); // Folia - regionised ticking

        profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY_TICK); try { // Folia - profiler
        // Paper - Fix MC-117075 use removeAll - remove iterator in favour of indexed for loop, ensuring compile error if something uses iter incorrectly
        boolean tickBlockEntities = this.tickRateManager().runsNormally();

        int tickedEntities = 0; // Paper - rewrite chunk system
        // Paper start - Fix MC-117075 use removeAll
        final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<@Nullable TickingBlockEntity> toRemove = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
        toRemove.add(null);
        for (int tickerIndex = 0; tickerIndex < blockEntityTickers.size(); tickerIndex++) { // Folia - regionised ticking
            final TickingBlockEntity ticker = blockEntityTickers.get(tickerIndex); // Folia - regionised ticking
        // Paper end - Fix MC-117075 use removeAll
            if (ticker.isRemoved()) {
                toRemove.add(ticker); // Paper - Fix MC-117075 use removeAll
            } else if (tickBlockEntities && this.shouldTickBlocksAt(ticker.getPos())) {
                ticker.tick();
                // Paper start - rewrite chunk system
                if ((++tickedEntities & 7) == 0) {
                    ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)(Level)(Object)this).moonrise$midTickTasks();
                }
                // Paper end - rewrite chunk system
            }
        }

        blockEntityTickers.removeAll(toRemove); // Paper - Fix MC-117075 use removeAll // Folia - regionised ticking
        } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY_TICK); } // Folia - profiler
        regionizedWorldData.setTickingBlockEntities(false); // Folia - regionised ticking
    }

    public <T extends Entity> void guardEntityTick(final Consumer<T> tick, final T entity) {
        try {
            tick.accept(entity);
        } catch (Throwable t) {
            if (ExperimentConfig.DisableEntityExceptionCatchers.enabled) {
                throw t;
            }
            // Paper start - Prevent block entity and entity crashes
            final String msg = String.format("Entity threw exception at %s:%s,%s,%s", io.papermc.paper.util.MCUtil.getLevelName(entity.level()), entity.getX(), entity.getY(), entity.getZ());
            MinecraftServer.LOGGER.error(msg, t);
            getCraftServer().getPluginManager().callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerInternalException(msg, t))); // Paper - ServerExceptionEvent
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD); // Folia - properly disconnect players
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) player.connection.disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.generic"), org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN); // Folia - properly disconnect players
            // Paper end - Prevent block entity and entity crashes
        }
        this.moonrise$midTickTasks(); // Paper - rewrite chunk system
    }

    // Paper start - Option to prevent armor stands from doing entity lookups
    @Override
    public boolean noCollision(@Nullable Entity entity, AABB box) {
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand && !entity.level().paperConfig().entities.armorStands.doCollisionEntityLookups)
            return false;
        // Paper start - optimise collisions
        final int flags = entity != null ? (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_BORDER | ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_ONLY) : ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_ONLY;
        if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisionsForBlocksOrWorldBorder((Level)(Object)this, entity, box, null, null, flags, null)) {
            return false;
        }

        return !ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getEntityHardCollisions((Level)(Object)this, entity, box, null, flags, null);
        // Paper end - optimise collisions
    }
    // Paper end - Option to prevent armor stands from doing entity lookups

    public boolean shouldTickDeath(final Entity entity) {
        return true;
    }

    public boolean shouldTickBlocksAt(final long chunkPos) {
        return true;
    }

    public boolean shouldTickBlocksAt(final BlockPos pos) {
        return this.shouldTickBlocksAt(ChunkPos.pack(pos));
    }

    public void explode(
        final @Nullable Entity source, final double x, final double y, final double z, final float r, final Level.ExplosionInteraction blockInteraction
    ) {
        this.explode(
            source,
            Explosion.getDefaultDamageSource(this, source),
            null,
            x,
            y,
            z,
            r,
            false,
            blockInteraction,
            ParticleTypes.EXPLOSION,
            ParticleTypes.EXPLOSION_EMITTER,
            DEFAULT_EXPLOSION_BLOCK_PARTICLES,
            SoundEvents.GENERIC_EXPLODE
        );
    }

    public void explode(
        final @Nullable Entity source,
        final double x,
        final double y,
        final double z,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction blockInteraction
    ) {
        this.explode(
            source,
            Explosion.getDefaultDamageSource(this, source),
            null,
            x,
            y,
            z,
            r,
            fire,
            blockInteraction,
            ParticleTypes.EXPLOSION,
            ParticleTypes.EXPLOSION_EMITTER,
            DEFAULT_EXPLOSION_BLOCK_PARTICLES,
            SoundEvents.GENERIC_EXPLODE
        );
    }

    public void explode(
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final Vec3 boomPos,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction blockInteraction
    ) {
        this.explode(
            source,
            damageSource,
            damageCalculator,
            boomPos.x(),
            boomPos.y(),
            boomPos.z(),
            r,
            fire,
            blockInteraction,
            ParticleTypes.EXPLOSION,
            ParticleTypes.EXPLOSION_EMITTER,
            DEFAULT_EXPLOSION_BLOCK_PARTICLES,
            SoundEvents.GENERIC_EXPLODE
        );
    }

    public void explode(
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final double x,
        final double y,
        final double z,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction interactionType
    ) {
        this.explode(
            source,
            damageSource,
            damageCalculator,
            x,
            y,
            z,
            r,
            fire,
            interactionType,
            ParticleTypes.EXPLOSION,
            ParticleTypes.EXPLOSION_EMITTER,
            DEFAULT_EXPLOSION_BLOCK_PARTICLES,
            SoundEvents.GENERIC_EXPLODE
        );
    }

    public abstract void explode(
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final double x,
        final double y,
        final double z,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction interactionType,
        final ParticleOptions smallExplosionParticles,
        final ParticleOptions largeExplosionParticles,
        final WeightedList<ExplosionParticleInfo> blockParticles,
        final Holder<SoundEvent> explosionSound
    );

    public abstract String gatherChunkSourceStats();

    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
        // Folia start - region threading
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return null;
        }
        // Folia end - region threading
        // Paper start - Perf: Optimize capturedTileEntities lookup
        net.minecraft.world.level.block.entity.BlockEntity blockEntity;
        if (!this.getCurrentWorldData().capturedTileEntities.isEmpty() && (blockEntity = this.getCurrentWorldData().capturedTileEntities.get(pos)) != null) { // Folia - region threading
            return blockEntity;
        }
        // Paper end - Perf: Optimize capturedTileEntities lookup
        if (!this.isInValidBounds(pos)) {
            return null;
        } else {
            return !this.isClientSide() && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread() // Paper - rewrite chunk system
                ? null
                : this.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        }
    }

    public void setBlockEntity(final BlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        if (this.isInValidBounds(pos)) {
            // CraftBukkit start
            if (this.getCurrentWorldData().captureBlockStates) { // Folia - region threading
                this.getCurrentWorldData().capturedTileEntities.put(pos.immutable(), blockEntity); // Folia - region threading
                return;
            }
            // CraftBukkit end
            this.getChunkAt(pos).addAndRegisterBlockEntity(blockEntity);
        }
    }

    public void removeBlockEntity(final BlockPos pos) {
        if (this.isInValidBounds(pos)) {
            this.getChunkAt(pos).removeBlockEntity(pos);
        }
    }

    public boolean isLoaded(final BlockPos pos) {
        return this.isInValidBounds(pos)
            && this.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean loadedAndEntityCanStandOnFace(final BlockPos pos, final Entity entity, final Direction faceDirection) {
        if (!this.isInValidBounds(pos)) {
            return false;
        }

        ChunkAccess chunk = this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
        return chunk != null && chunk.getBlockState(pos).entityCanStandOnFace(this, pos, entity, faceDirection);
    }

    public boolean loadedAndEntityCanStandOn(final BlockPos pos, final Entity entity) {
        return this.loadedAndEntityCanStandOnFace(pos, entity, Direction.UP);
    }

    public void updateSkyBrightness() {
        this.skyDarken = (int)(15.0F - this.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL));
    }

    public void setSpawnSettings(final boolean spawnEnemies) {
        this.getChunkSource().setSpawnSettings(spawnEnemies);
    }

    public abstract void setRespawnData(final LevelData.RespawnData respawnData);

    public abstract LevelData.RespawnData getRespawnData();

    public LevelData.RespawnData getWorldBorderAdjustedRespawnData(final LevelData.RespawnData respawnData) {
        WorldBorder worldBorder = this.getWorldBorder();
        if (!worldBorder.isWithinBounds(respawnData.pos())) {
            BlockPos newPos = this.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(worldBorder.getCenterX(), 0.0, worldBorder.getCenterZ())
            );
            return LevelData.RespawnData.of(respawnData.dimension(), newPos, respawnData.yaw(), respawnData.pitch());
        } else {
            return respawnData;
        }
    }

    @Override
    public void close() throws IOException {
        this.getChunkSource().close();
    }

    @Override
    public @Nullable BlockGetter getChunkForCollisions(final int chunkX, final int chunkZ) {
        return this.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    @Override
    public List<Entity> getEntities(final @Nullable Entity except, final AABB bb, final Predicate<? super Entity> selector) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread((ServerLevel)this, bb, "Cannot getEntities asynchronously"); // Folia - region threading
        Profiler.get().incrementCounter("getEntities");
        // Paper start - rewrite chunk system
        final List<Entity> ret = new java.util.ArrayList<>();

        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(except, bb, ret, selector);

        ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, except, bb, selector, ret);

        return ret;
        // Paper end - rewrite chunk system
    }

    @Override
    public <T extends Entity> List<T> getEntities(final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector) {
        List<T> output = Lists.newArrayList();
        this.getEntities(type, bb, selector, output);
        return output;
    }

    public <T extends Entity> void getEntities(
        final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector, final List<? super T> output
    ) {
        this.getEntities(type, bb, selector, output, Integer.MAX_VALUE);
    }

    // Paper start - rewrite chunk system
    public <T extends Entity> void getEntities(final EntityTypeTest<Entity, T> entityTypeTest,
                                               final AABB boundingBox, final Predicate<? super T> predicate,
                                               final List<? super T> into, final int maxCount) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, boundingBox, "Cannot getEntities asynchronously"); // Folia - region threading
        Profiler.get().incrementCounter("getEntities");

        if (entityTypeTest instanceof net.minecraft.world.entity.EntityType<T> byType) {
            if (maxCount != Integer.MAX_VALUE) {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(byType, boundingBox, into, predicate, maxCount);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            } else {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(byType, boundingBox, into, predicate);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            }
        }

        if (entityTypeTest == null) {
            if (maxCount != Integer.MAX_VALUE) {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities((Entity)null, boundingBox, (List)into, (Predicate)predicate, maxCount);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            } else {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities((Entity)null, boundingBox, (List)into, (Predicate)predicate);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            }
        }

        final Class<? extends Entity> base = entityTypeTest.getBaseClass();

        final Predicate<? super T> modifiedPredicate;
        if (predicate == null) {
            modifiedPredicate = (final T obj) -> {
                return entityTypeTest.tryCast(obj) != null;
            };
        } else {
            modifiedPredicate = (final Entity obj) -> {
                final T casted = entityTypeTest.tryCast(obj);
                if (casted == null) {
                    return false;
                }

                return predicate.test(casted);
            };
        }

        if (base == null || base == Entity.class) {
            if (maxCount != Integer.MAX_VALUE) {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities((Entity)null, boundingBox, (List)into, (Predicate)modifiedPredicate, maxCount);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            } else {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities((Entity)null, boundingBox, (List)into, (Predicate)modifiedPredicate);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            }
        } else {
            if (maxCount != Integer.MAX_VALUE) {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(base, null, boundingBox, (List)into, (Predicate)modifiedPredicate, maxCount);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            } else {
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this).moonrise$getEntityLookup().getEntities(base, null, boundingBox, (List)into, (Predicate)modifiedPredicate);
                ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities((Level)(Object)this, entityTypeTest, boundingBox, predicate, into, maxCount);
                return;
            }
        }
    }

    public org.bukkit.entity.Entity[] getChunkEntities(int chunkX, int chunkZ) {
        ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices slices = ((ServerLevel)this).moonrise$getEntityLookup().getChunk(chunkX, chunkZ);
        if (slices == null) {
            return new org.bukkit.entity.Entity[0];
        }

        List<org.bukkit.entity.Entity> ret = new java.util.ArrayList<>();
        for (Entity entity : slices.getAllEntities()) {
            org.bukkit.entity.Entity bukkit = entity.getBukkitEntity();
            if (bukkit != null && bukkit.isValid()) {
                ret.add(bukkit);
            }
        }

        return ret.toArray(new org.bukkit.entity.Entity[0]);
    }
    // Paper end - rewrite chunk system

    public <T extends Entity> boolean hasEntities(final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector) {
        Profiler.get().incrementCounter("hasEntities");
        MutableBoolean hasEntities = new MutableBoolean();
        this.getEntities().get(type, bb, e -> {
            if (selector.test(e)) {
                hasEntities.setTrue();
                return AbortableIterationConsumer.Continuation.ABORT;
            }

            if (e instanceof EnderDragon enderDragon) {
                for (EnderDragonPart subEntity : enderDragon.getSubEntities()) {
                    T castSubPart = type.tryCast(subEntity);
                    if (castSubPart != null && selector.test(castSubPart)) {
                        hasEntities.setTrue();
                        return AbortableIterationConsumer.Continuation.ABORT;
                    }
                }
            }

            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        return hasEntities.isTrue();
    }

    public List<Entity> getPushableEntities(final Entity pusher, final AABB boundingBox) {
        return this.getEntities(pusher, boundingBox, EntitySelector.pushableBy(pusher));
    }

    public abstract @Nullable Entity getEntity(int id);

    public @Nullable Entity getEntity(final UUID uuid) {
        return this.getEntities().get(uuid);
    }

    public @Nullable Entity getEntityInAnyDimension(final UUID uuid) {
        return this.getEntity(uuid);
    }

    public @Nullable Player getPlayerInAnyDimension(final UUID uuid) {
        return this.getPlayerByUUID(uuid);
    }

    public abstract Collection<EnderDragonPart> dragonParts();

    public void blockEntityChanged(final BlockPos pos) {
        if (this.hasChunkAt(pos)) {
            this.getChunkAt(pos).markUnsaved();
        }
    }

    public void onBlockEntityAdded(final BlockEntity blockEntity) {
    }

    // Folia start - region threading
    @Override
    public long getRedstoneGameTime() {
        return this.getCurrentWorldData().getRedstoneGameTime();
    }

    @Override
    public long getGameTime() {
        // Dumb world gen thread calls this for some reason. So, check for null.
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.getCurrentWorldData();
        return worldData == null ? this.getLevelData().getGameTime() : worldData.getTickData().nonRedstoneGameTime();
    }
    // Folia end - region threading

    public long getOverworldClockTime() {
        return this.getClockTimeTicks(this.registryAccess().get(WorldClocks.OVERWORLD));
    }

    public long getDefaultClockTime() {
        return this.getClockTimeTicks(this.dimensionType().defaultClock());
    }

    private long getClockTimeTicks(final Optional<? extends Holder<WorldClock>> clock) {
        return clock.<Long>map(holder -> this.clockManager().getTotalTicks((Holder<WorldClock>)holder)).orElse(0L);
    }

    public boolean mayInteract(final Entity entity, final BlockPos pos) {
        return true;
    }

    public void broadcastEntityEvent(final Entity entity, final byte event) {
    }

    public void broadcastDamageEvent(final Entity entity, final DamageSource source) {
    }

    public void blockEvent(final BlockPos pos, final Block block, final int b0, final int b1) {
        this.getBlockState(pos).triggerEvent(this, pos, b0, b1);
    }

    @Override
    public LevelData getLevelData() {
        return this.levelData;
    }

    public abstract TickRateManager tickRateManager();

    public float getThunderLevel(final float a) {
        return Mth.lerp(a, this.oThunderLevel, this.thunderLevel) * this.getRainLevel(a);
    }

    public void setThunderLevel(final float thunderLevel) {
        float clampedThunderLevel = Mth.clamp(thunderLevel, 0.0F, 1.0F);
        this.oThunderLevel = clampedThunderLevel;
        this.thunderLevel = clampedThunderLevel;
    }

    public float getRainLevel(final float a) {
        return Mth.lerp(a, this.oRainLevel, this.rainLevel);
    }

    public void setRainLevel(final float rainLevel) {
        float clampedRainLevel = Mth.clamp(rainLevel, 0.0F, 1.0F);
        this.oRainLevel = clampedRainLevel;
        this.rainLevel = clampedRainLevel;
    }

    public boolean canHaveWeather() {
        return this.dimensionType().hasSkyLight() && !this.dimensionType().hasCeiling() && this.dimension() != END;
    }

    public boolean isThundering() {
        return this.canHaveWeather() && this.getThunderLevel(1.0F) > 0.9;
    }

    public boolean isRaining() {
        return this.canHaveWeather() && this.getRainLevel(1.0F) > 0.2;
    }

    public boolean isRainingAt(final BlockPos pos) {
        return this.precipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    public Biome.Precipitation precipitationAt(final BlockPos pos) {
        if (!this.isRaining()) {
            return Biome.Precipitation.NONE;
        }

        if (!this.canSeeSky(pos)) {
            return Biome.Precipitation.NONE;
        }

        if (this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
            return Biome.Precipitation.NONE;
        }

        Biome biome = this.getBiome(pos).value();
        return biome.getPrecipitationAt(pos, this.getSeaLevel());
    }

    public abstract @Nullable MapItemSavedData getMapData(MapId id);

    public void globalLevelEvent(final int type, final BlockPos pos, final int data) {
    }

    public CrashReportCategory fillReportDetails(final CrashReport report) {
        CrashReportCategory category = report.addCategory("Affected level", 1);
        category.setDetail("All players", () -> {
            List<? extends Player> players = this.players();
            return players.size() + " total; " + players.stream().map(Player::debugInfo).collect(Collectors.joining(", "));
        });
        category.setDetail("Chunk stats", this.getChunkSource()::gatherStats);
        category.setDetail("Level dimension", () -> this.dimension().identifier().toString());
        category.setDetail("Level time", () -> String.format(Locale.ROOT, "%d game time, %d day time", this.getGameTime(), this.getOverworldClockTime()));

        try {
            this.levelData.fillCrashReportCategory(category, this);
        } catch (Throwable t) {
            category.setDetailError("Level Data Unobtainable", t);
        }

        return category;
    }

    public abstract void destroyBlockProgress(final int id, final BlockPos blockPos, final int progress);

    public void createFireworks(
        final double x, final double y, final double z, final double xd, final double yd, final double zd, final List<FireworkExplosion> explosions
    ) {
    }

    public abstract Scoreboard getScoreboard();

    public void updateNeighbourForOutputSignal(final BlockPos pos, final Block changedBlock) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = pos.relative(direction);
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this, relativePos, 25) && this.hasChunkAt(relativePos)) { // Folia - block updates in unloaded chunks
                BlockState state = this.getBlockState(relativePos);
                if (state.is(Blocks.COMPARATOR)) {
                    this.neighborChanged(state, relativePos, changedBlock, null, false);
                } else if (state.isRedstoneConductor(this, relativePos)) {
                    relativePos = relativePos.relative(direction);
                    state = this.getBlockState(relativePos);
                    if (state.is(Blocks.COMPARATOR)) {
                        this.neighborChanged(state, relativePos, changedBlock, null, false);
                    }
                }
            }
        }
    }

    @Override
    public int getSkyDarken() {
        return this.skyDarken;
    }

    public void setSkyFlashTime(final int skyFlashTime) {
    }

    public void sendPacketToServer(final Packet<?> packet) {
        throw new UnsupportedOperationException("Can't send packets to server unless you're on the client.");
    }

    @Override
    public DimensionType dimensionType() {
        return this.dimensionTypeRegistration.value();
    }

    public Holder<DimensionType> dimensionTypeRegistration() {
        return this.dimensionTypeRegistration;
    }

    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    @Override
    public RandomSource getRandom() {
        return this.random;
    }

    @Override
    public boolean isStateAtPosition(final BlockPos pos, final Predicate<BlockState> predicate) {
        return predicate.test(this.getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(final BlockPos pos, final Predicate<FluidState> predicate) {
        return predicate.test(this.getFluidState(pos));
    }

    public abstract RecipeAccess recipeAccess();

    public BlockPos getBlockRandomPos(final int xo, final int yo, final int zo, final int yMask) {
        int val = this.random.nextInt() >> 2; // Folia - region threading
        return new BlockPos(xo + (val & 15), yo + (val >> 16 & yMask), zo + (val >> 8 & 15));
    }

    public boolean noSave() {
        return false;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return this.biomeManager;
    }

    public final boolean isDebug() {
        return this.isDebug;
    }

    public abstract LevelEntityGetter<Entity> getEntities();

    @Override
    public long nextSubTickCount() {
        return this.subTickCount.getAndIncrement(); // Folia - region threading
    }

    @Override
    public RegistryAccess registryAccess() {
        return this.registryAccess;
    }

    public DamageSources damageSources() {
        return this.damageSources;
    }

    public abstract ClockManager clockManager();

    @Override
    public abstract EnvironmentAttributeSystem environmentAttributes();

    public abstract PotionBrewing potionBrewing();

    public abstract FuelValues fuelValues();

    public int getClientLeafTintColor(final BlockPos pos) {
        return 0;
    }

    public PalettedContainerFactory palettedContainerFactory() {
        return this.palettedContainerFactory;
    }

    // Paper start - optimize redstone (Alternate Current)
    public alternate.current.wire.WireHandler getWireHandler() {
        // This method is overridden in ServerLevel.
        // Since Paper is a server platform there is no risk
        // of this implementation being called. It is here
        // only so this method can be called without casting
        // an instance of Level to ServerLevel.
        return null;
    }
    // Paper end - optimize redstone (Alternate Current)

    public enum ExplosionInteraction implements StringRepresentable {
        NONE("none"),
        BLOCK("block"),
        MOB("mob"),
        TNT("tnt"),
        TRIGGER("trigger"),
        STANDARD("standard"); // CraftBukkit - Add STANDARD which will always use Explosion.Effect.DESTROY

        public static final Codec<Level.ExplosionInteraction> CODEC = StringRepresentable.fromEnum(Level.ExplosionInteraction::values);
        private final String id;

        ExplosionInteraction(final String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return this.id;
        }
    }

    // Paper start - allow patching this logic
    public final int getEntityCount() {
        return this.moonrise$getEntityLookup().getEntityCount(); // Paper - rewrite chunk system
    }
    // Paper end - allow patching this logic
}

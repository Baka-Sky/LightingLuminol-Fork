package net.minecraft.world.level;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class ServerExplosion implements Explosion {
    private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    private final boolean fire;
    private final Explosion.BlockInteraction blockInteraction;
    private final ServerLevel level;
    private final Vec3 center;
    private final @Nullable Entity source;
    private final float radius;
    private final DamageSource damageSource;
    private final ExplosionDamageCalculator damageCalculator;
    private final Map<Player, Vec3> hitPlayers = new HashMap<>();
    // CraftBukkit - add field
    public boolean wasCanceled = false;
    public float yield;
    // CraftBukkit end
    public boolean excludeSourceFromDamage = true; // Paper - Allow explosions to damage source
    // Paper start - collisions optimisations
    private static final double[] CACHED_RAYS;
    static {
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList rayCoords = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();

        for (int x = 0; x <= 15; ++x) {
            for (int y = 0; y <= 15; ++y) {
                for (int z = 0; z <= 15; ++z) {
                    if ((x == 0 || x == 15) || (y == 0 || y == 15) || (z == 0 || z == 15)) {
                        double xDir = (double)((float)x / 15.0F * 2.0F - 1.0F);
                        double yDir = (double)((float)y / 15.0F * 2.0F - 1.0F);
                        double zDir = (double)((float)z / 15.0F * 2.0F - 1.0F);

                        double mag = Math.sqrt(
                                xDir * xDir + yDir * yDir + zDir * zDir
                        );

                        rayCoords.add((xDir / mag) * (double)0.3F);
                        rayCoords.add((yDir / mag) * (double)0.3F);
                        rayCoords.add((zDir / mag) * (double)0.3F);
                    }
                }
            }
        }

        CACHED_RAYS = rayCoords.toDoubleArray();
    }

    private static final int CHUNK_CACHE_SHIFT = 2;
    private static final int CHUNK_CACHE_MASK = (1 << CHUNK_CACHE_SHIFT) - 1;
    private static final int CHUNK_CACHE_WIDTH = 1 << CHUNK_CACHE_SHIFT;

    private static final int BLOCK_EXPLOSION_CACHE_SHIFT = 3;
    private static final int BLOCK_EXPLOSION_CACHE_MASK = (1 << BLOCK_EXPLOSION_CACHE_SHIFT) - 1;
    private static final int BLOCK_EXPLOSION_CACHE_WIDTH = 1 << BLOCK_EXPLOSION_CACHE_SHIFT;

    // resistance = (res + 0.3F) * 0.3F;
    // so for resistance = 0, we need res = -0.3F
    private static final Float ZERO_RESISTANCE = Float.valueOf(-0.3f);
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache> blockCache = null;
    private long[] chunkPosCache = null;
    private net.minecraft.world.level.chunk.LevelChunk[] chunkCache = null;
    private ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] directMappedBlockCache;
    private BlockPos.MutableBlockPos mutablePos;

    private ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache getOrCacheExplosionBlock(final int x, final int y, final int z,
                                                                                                    final long key, final boolean calculateResistance) {
        ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache ret = this.blockCache.get(key);
        if (ret != null) {
            return ret;
        }

        BlockPos pos = new BlockPos(x, y, z);

        if (!this.level.isInWorldBounds(pos)) {
            ret = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache(key, pos, null, null, 0.0f, true);
        } else {
            net.minecraft.world.level.chunk.LevelChunk chunk;
            long chunkKey = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(x >> 4, z >> 4);
            int chunkCacheKey = ((x >> 4) & CHUNK_CACHE_MASK) | (((z >> 4) << CHUNK_CACHE_SHIFT) & (CHUNK_CACHE_MASK << CHUNK_CACHE_SHIFT));
            if (this.chunkPosCache[chunkCacheKey] == chunkKey) {
                chunk = this.chunkCache[chunkCacheKey];
            } else {
                this.chunkPosCache[chunkCacheKey] = chunkKey;
                this.chunkCache[chunkCacheKey] = chunk = this.level.getChunk(x >> 4, z >> 4);
            }

            BlockState blockState = ((ca.spottedleaf.moonrise.patches.getblock.GetBlockChunk)chunk).moonrise$getBlock(x, y, z);
            FluidState fluidState = blockState.getFluidState();

            Optional<Float> resistance = !calculateResistance ? Optional.empty() : this.damageCalculator.getBlockExplosionResistance((Explosion)(Object)this, this.level, pos, blockState, fluidState);

            ret = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache(
                    key, pos, blockState, fluidState,
                    (resistance.orElse(ZERO_RESISTANCE).floatValue() + 0.3f) * 0.3f,
                    false
            );
        }

        this.blockCache.put(key, ret);

        return ret;
    }

    private boolean clipsAnything(final Vec3 from, final Vec3 to,
                                  final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context,
                                  final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache,
                                  final BlockPos.MutableBlockPos currPos) {
        // assume that context.delegated = false
        final double adjX = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.x - to.x);
        final double adjY = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.y - to.y);
        final double adjZ = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.z - to.z);

        if (adjX == 0.0 && adjY == 0.0 && adjZ == 0.0) {
            return false;
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

        for (;;) {
            currPos.set(currX, currY, currZ);

            // ClipContext.Block.COLLIDER -> BlockBehaviour.BlockStateBase::getCollisionShape
            // ClipContext.Fluid.NONE -> ignore fluids

            // read block from cache
            final long key = BlockPos.asLong(currX, currY, currZ);

            final int cacheKey =
                    (currX & BLOCK_EXPLOSION_CACHE_MASK) |
                    (currY & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT) |
                    (currZ & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT + BLOCK_EXPLOSION_CACHE_SHIFT);
            ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache cachedBlock = blockCache[cacheKey];
            if (cachedBlock == null || cachedBlock.key != key) {
                blockCache[cacheKey] = cachedBlock = this.getOrCacheExplosionBlock(currX, currY, currZ, key, false);
            }

            final BlockState blockState = cachedBlock.blockState;
            if (blockState != null && !((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockState).moonrise$emptyContextCollisionShape()) {
                net.minecraft.world.phys.shapes.VoxelShape collision = cachedBlock.cachedCollisionShape;
                if (collision == null) {
                    collision = ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockState).moonrise$getConstantContextCollisionShape();
                    if (collision == null) {
                        collision = blockState.getCollisionShape(this.level, currPos, context);
                        if (!context.isDelegated()) {
                            // if it was not delegated during this call, assume that for any future ones it will not be delegated
                            // again, and cache the result
                            cachedBlock.cachedCollisionShape = collision;
                        }
                    } else {
                        cachedBlock.cachedCollisionShape = collision;
                    }
                }

                if (!collision.isEmpty() && collision.clip(from, to, currPos) != null) {
                    return true;
                }
            }

            if (normalizedCurrX > 1.0 && normalizedCurrY > 1.0 && normalizedCurrZ > 1.0) {
                return false;
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

    private float getSeenFraction(final Vec3 source, final Entity target,
                                   final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache,
                                   final BlockPos.MutableBlockPos blockPos) {
        final AABB boundingBox = target.getBoundingBox();
        final double diffX = boundingBox.maxX - boundingBox.minX;
        final double diffY = boundingBox.maxY - boundingBox.minY;
        final double diffZ = boundingBox.maxZ - boundingBox.minZ;

        final double incX = 1.0 / (diffX * 2.0 + 1.0);
        final double incY = 1.0 / (diffY * 2.0 + 1.0);
        final double incZ = 1.0 / (diffZ * 2.0 + 1.0);

        if (incX < 0.0 || incY < 0.0 || incZ < 0.0) {
            return 0.0f;
        }

        final double offX = (1.0 - Math.floor(1.0 / incX) * incX) * 0.5 + boundingBox.minX;
        final double offY = boundingBox.minY;
        final double offZ = (1.0 - Math.floor(1.0 / incZ) * incZ) * 0.5 + boundingBox.minZ;

        final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context = new ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext(target);

        int totalRays = 0;
        int missedRays = 0;

        for (double dx = 0.0; dx <= 1.0; dx += incX) {
            final double fromX = Math.fma(dx, diffX, offX);
            for (double dy = 0.0; dy <= 1.0; dy += incY) {
                final double fromY = Math.fma(dy, diffY, offY);
                for (double dz = 0.0; dz <= 1.0; dz += incZ) {
                    ++totalRays;

                    final Vec3 from = new Vec3(
                            fromX,
                            fromY,
                            Math.fma(dz, diffZ, offZ)
                    );

                    if (!this.clipsAnything(from, source, context, blockCache, blockPos)) {
                        ++missedRays;
                    }
                }
            }
        }

        return (float)missedRays / (float)totalRays;
    }
    // Paper end - collisions optimisations

    public ServerExplosion(
        final ServerLevel level,
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final Vec3 center,
        final float radius,
        final boolean fire,
        final Explosion.BlockInteraction blockInteraction
    ) {
        this.level = level;
        this.source = source;
        this.radius = radius;
        this.center = center;
        this.fire = fire;
        this.blockInteraction = blockInteraction;
        this.damageSource = damageSource == null ? level.damageSources().explosion(this) : damageSource;
        this.damageCalculator = damageCalculator == null ? this.makeDamageCalculator(source) : damageCalculator;
        // Paper start - add yield
        this.yield = this.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY ? 1.0F / this.radius : 1.0F;
        this.yield = Double.isFinite(this.yield) ? this.yield : 0; // Paper - Don't allow infinite default yields
        // Paper end - add yield
    }

    private ExplosionDamageCalculator makeDamageCalculator(final @Nullable Entity source) {
        return source == null ? EXPLOSION_DAMAGE_CALCULATOR : new EntityBasedExplosionDamageCalculator(source);
    }

    public static float getSeenPercent(final Vec3 center, final Entity entity) {
        AABB bb = entity.getBoundingBox();
        double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (!(xs < 0.0) && !(ys < 0.0) && !(zs < 0.0)) {
            int hits = 0;
            int count = 0;

            for (double xx = 0.0; xx <= 1.0; xx += xs) {
                for (double yy = 0.0; yy <= 1.0; yy += ys) {
                    for (double zz = 0.0; zz <= 1.0; zz += zs) {
                        double x = Mth.lerp(xx, bb.minX, bb.maxX);
                        double y = Mth.lerp(yy, bb.minY, bb.maxY);
                        double z = Mth.lerp(zz, bb.minZ, bb.maxZ);
                        Vec3 from = new Vec3(x + xOffset, y, z + zOffset);
                        if (entity.level().clip(new ClipContext(from, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType()
                            == HitResult.Type.MISS) {
                            hits++;
                        }

                        count++;
                    }
                }
            }

            return (float)hits / count;
        } else {
            return 0.0F;
        }
    }

    @Override
    public float radius() {
        return this.radius;
    }

    @Override
    public Vec3 center() {
        return this.center;
    }

    private List<BlockPos> calculateExplodedPositions() {
        // Paper start - collision optimisations
        final ObjectArrayList<BlockPos> ret = new ObjectArrayList<>();

        final Vec3 center = this.center;

        final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache = this.directMappedBlockCache;

        // use initial cache value that is most likely to be used: the source position
        final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache initialCache;
        {
            final int blockX = Mth.floor(center.x);
            final int blockY = Mth.floor(center.y);
            final int blockZ = Mth.floor(center.z);

            final long key = BlockPos.asLong(blockX, blockY, blockZ);

            initialCache = this.getOrCacheExplosionBlock(blockX, blockY, blockZ, key, true);
        }

        // only ~1/3rd of the loop iterations in vanilla will result in a ray, as it is iterating the perimeter of
        // a 16x16x16 cube
        // we can cache the rays and their normals as well, so that we eliminate the excess iterations / checks and
        // calculations in one go
        // additional aggressive caching of block retrieval is very significant, as at low power (i.e tnt) most
        // block retrievals are not unique
        for (int ray = 0, len = CACHED_RAYS.length; ray < len;) {
            ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache cachedBlock = initialCache;

            double currX = center.x;
            double currY = center.y;
            double currZ = center.z;

            final double incX = CACHED_RAYS[ray];
            final double incY = CACHED_RAYS[ray + 1];
            final double incZ = CACHED_RAYS[ray + 2];

            ray += 3;

            float power = this.radius * (0.7F + this.level.getRandom().nextFloat() * 0.6F);

            do {
                final int blockX = Mth.floor(currX);
                final int blockY = Mth.floor(currY);
                final int blockZ = Mth.floor(currZ);

                final long key = BlockPos.asLong(blockX, blockY, blockZ);

                if (cachedBlock.key != key) {
                    final int cacheKey =
                        (blockX & BLOCK_EXPLOSION_CACHE_MASK) |
                            (blockY & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT) |
                            (blockZ & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT + BLOCK_EXPLOSION_CACHE_SHIFT);
                    cachedBlock = blockCache[cacheKey];
                    if (cachedBlock == null || cachedBlock.key != key) {
                        blockCache[cacheKey] = cachedBlock = this.getOrCacheExplosionBlock(blockX, blockY, blockZ, key, true);
                    }
                }

                if (cachedBlock.outOfWorld) {
                    break;
                }
                final BlockState iblockdata = cachedBlock.blockState;

                power -= cachedBlock.resistance;

                if (power > 0.0f && cachedBlock.shouldExplode == null) {
                    // note: we expect shouldBlockExplode to be pure with respect to power, as Vanilla currently is.
                    // basically, it is unused, which allows us to cache the result
                    final boolean shouldExplode = iblockdata.isDestroyable() && this.damageCalculator.shouldBlockExplode((Explosion)(Object)this, this.level, cachedBlock.immutablePos, cachedBlock.blockState, power); // Paper - Protect Bedrock and End Portal/Frames from being destroyed
                    cachedBlock.shouldExplode = shouldExplode ? Boolean.TRUE : Boolean.FALSE;
                    if (shouldExplode) {
                        if (this.fire || !cachedBlock.blockState.isAir()) {
                            ret.add(cachedBlock.immutablePos);
                            // Paper start - prevent headless pistons from forming
                            if (!io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowHeadlessPistons && iblockdata.getBlock() == net.minecraft.world.level.block.Blocks.MOVING_PISTON) {
                                net.minecraft.world.level.block.entity.BlockEntity extension = this.level.getBlockEntity(cachedBlock.immutablePos); // Paper - optimise collisions
                                if (extension instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity blockEntity && blockEntity.isSourcePiston()) {
                                    net.minecraft.core.Direction direction = iblockdata.getValue(net.minecraft.world.level.block.piston.PistonHeadBlock.FACING);
                                    ret.add(cachedBlock.immutablePos.relative(direction.getOpposite())); // Paper - optimise collisions
                                }
                            }

                            // Paper end - prevent headless pistons from forming
                        }
                    }
                }

                power -= 0.22500001F;
                currX += incX;
                currY += incY;
                currZ += incZ;
            } while (power > 0.0f);
        }

        return ret;
        // Paper end - collision optimisations
    }

    private void hurtEntities() {
        if (!(this.radius < 1.0E-5F)) {
            float doubleRadius = this.radius * 2.0F;
            int x0 = Mth.floor(this.center.x - doubleRadius - 1.0);
            int x1 = Mth.floor(this.center.x + doubleRadius + 1.0);
            int y0 = Mth.floor(this.center.y - doubleRadius - 1.0);
            int y1 = Mth.floor(this.center.y + doubleRadius + 1.0);
            int z0 = Mth.floor(this.center.z - doubleRadius - 1.0);
            int z1 = Mth.floor(this.center.z + doubleRadius + 1.0);
            List<Entity> list = this.level.getEntities(this.excludeSourceFromDamage ? this.source : null, new AABB(x0, y0, z0, x1, y1, z1), entity -> entity.isAlive() && !entity.isSpectator()); // Paper - Fix lag from explosions processing dead entities, Allow explosions to damage source

            for (Entity entity : list) { // Paper - used in loop
                if (!entity.ignoreExplosion(this)) {
                    double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;
                    if (!(dist > 1.0)) {
                        Vec3 entityOrigin = entity instanceof PrimedTnt ? entity.position() : entity.getEyePosition();
                        Vec3 direction = entityOrigin.subtract(this.center).normalize();
                        boolean shouldDamageEntity = this.damageCalculator.shouldDamageEntity(this, entity);
                        float knockbackMultiplier = this.damageCalculator.getKnockbackMultiplier(entity);
                        float exposure = !shouldDamageEntity && knockbackMultiplier == 0.0F ? 0.0F : this.getBlockDensity(this.center, entity); // Paper - Optimize explosions
                        if (shouldDamageEntity) {
                            // CraftBukkit start

                            // Special case ender dragon only give knockback if no damage is cancelled
                            // Thinks to note:
                            // - Setting a velocity to a EnderDragonPart is ignored (and therefore not needed)
                            // - Damaging EnderDragonPart while forward the damage to EnderDragon
                            // - Damaging EnderDragon does nothing
                            // - EnderDragon hitbox always covers the other parts and is therefore always present
                            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart) {
                                continue;
                            }

                            entity.lastDamageCancelled = false;

                            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon enderDragon) {
                                for (net.minecraft.world.entity.boss.enderdragon.EnderDragonPart dragonPart : enderDragon.getSubEntities()) {
                                    // Calculate damage separately for each EntityComplexPart
                                    if (list.contains(dragonPart)) {
                                        dragonPart.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, dragonPart, exposure));
                                    }
                                }
                            } else {
                                entity.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, entity, exposure));
                            }

                            if (entity.lastDamageCancelled) { // SPIGOT-5339, SPIGOT-6252, SPIGOT-6777: Skip entity if damage event was cancelled
                                continue;
                            }
                            // CraftBukkit end
                        }

                        double knockbackResistance = entity instanceof LivingEntity livingEntity
                            ? livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                            : 0.0;
                        double knockbackPower = entity instanceof Player && this.level.paperConfig().environment.disableExplosionKnockback ? 0 : (1.0 - dist) * exposure * knockbackMultiplier * (1.0 - knockbackResistance); // Paper
                        Vec3 knockback = direction.scale(knockbackPower);
                        // CraftBukkit start - Call EntityKnockbackEvent
                        if (entity instanceof LivingEntity) {
                            // Paper start - knockback events
                            io.papermc.paper.event.entity.EntityKnockbackEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityKnockbackEvent((org.bukkit.craftbukkit.entity.CraftLivingEntity) entity.getBukkitEntity(), this.source, this.damageSource.getEntity() != null ? this.damageSource.getEntity() : this.source, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION, knockbackPower, knockback);
                            knockback = event.isCancelled() ? Vec3.ZERO : org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getKnockback());
                            // Paper end - knockback events
                        }
                        // CraftBukkit end
                        entity.push(knockback);
                        if (entity.is(EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof Projectile projectile) {
                            projectile.setOwner(this.damageSource.getEntity());
                        } else if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying) && !level.paperConfig().environment.disableExplosionKnockback) { // Paper - Option to disable explosion knockback
                            this.hitPlayers.put(player, knockback);
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }
    }

    private void interactWithBlocks(final List<BlockPos> targetBlocks) {
        List<ServerExplosion.StackCollector> stacks = new ArrayList<>();
        Util.shuffle(targetBlocks, this.level.random);

        // CraftBukkit start
        org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.center, this.level);
        List<org.bukkit.block.Block> blockList = new ObjectArrayList<>();
        for (int i1 = targetBlocks.size() - 1; i1 >= 0; i1--) {
            org.bukkit.block.Block bblock = org.bukkit.craftbukkit.block.CraftBlock.at(this.level, targetBlocks.get(i1));
            if (!bblock.getType().isAir()) {
                blockList.add(bblock);
            }
        }

        List<org.bukkit.block.Block> bukkitBlocks;

        if (this.source != null) {
            org.bukkit.event.entity.EntityExplodeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityExplodeEvent(this.source, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        } else {
            org.bukkit.block.Block block = location.getBlock();
            org.bukkit.block.BlockState blockState = (this.damageSource.causingBlockSnapshot() != null) ? this.damageSource.causingBlockSnapshot() : block.getState();
            org.bukkit.event.block.BlockExplodeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockExplodeEvent(block, blockState, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        }

        targetBlocks.clear();
        for (org.bukkit.block.Block bblock : bukkitBlocks) {
            targetBlocks.add(((org.bukkit.craftbukkit.block.CraftBlock) bblock).getPosition());
        }

        if (this.wasCanceled) {
            return;
        }
        // CraftBukkit end

        for (BlockPos pos : targetBlocks) {
            // CraftBukkit start - TNTPrimeEvent
            BlockState state = this.level.getBlockState(pos);
            Block block = state.getBlock();
            if (level.getGameRules().get(GameRules.TNT_EXPLODES) && block instanceof net.minecraft.world.level.block.TntBlock) {
                Entity sourceEntity = this.source == null ? null : this.source;
                BlockPos sourceBlock = sourceEntity == null ? BlockPos.containing(this.center) : null;
                if (!org.bukkit.craftbukkit.event.CraftEventFactory.callTNTPrimeEvent(this.level, pos, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.EXPLOSION, sourceEntity, sourceBlock)) {
                    this.level.sendBlockUpdated(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), state, Block.UPDATE_ALL); // Update the block on the client
                    continue;
                }
            }
            // CraftBukkit end

            this.level.getBlockState(pos).onExplosionHit(this.level, pos, this, (stackx, position) -> addOrAppendStack(stacks, stackx, position));
        }

        for (ServerExplosion.StackCollector stack : stacks) {
            Block.popResource(this.level, stack.pos, stack.stack);
        }
    }

    private void createFire(final List<BlockPos> targetBlocks) {
        for (BlockPos pos : targetBlocks) {
            if (this.level.random.nextInt(3) == 0 && this.level.getBlockState(pos).isAir() && this.level.getBlockState(pos.below()).isSolidRender()) {
                // CraftBukkit start - Ignition by explosion
                if (!org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.level, pos, this).isCancelled()) {
                    this.level.setBlockAndUpdate(pos, BaseFireBlock.getState(this.level, pos));
                }
                // CraftBukkit end
            }
        }
    }

    public int explode() {
        // Paper start - collision optimisations
        this.blockCache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        this.chunkPosCache = new long[CHUNK_CACHE_WIDTH * CHUNK_CACHE_WIDTH];
        java.util.Arrays.fill(this.chunkPosCache, ChunkPos.INVALID_CHUNK_POS);
        this.chunkCache = new net.minecraft.world.level.chunk.LevelChunk[CHUNK_CACHE_WIDTH * CHUNK_CACHE_WIDTH];
        this.directMappedBlockCache = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[BLOCK_EXPLOSION_CACHE_WIDTH * BLOCK_EXPLOSION_CACHE_WIDTH * BLOCK_EXPLOSION_CACHE_WIDTH];
        this.mutablePos = new BlockPos.MutableBlockPos();
        // Paper end - collision optimisations
        this.level.gameEvent(this.source, GameEvent.EXPLODE, this.center);
        List<BlockPos> toBlow = this.calculateExplodedPositions();
        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("explosion_blocks");
            this.interactWithBlocks(toBlow);
            profiler.pop();
        }

        if (this.fire) {
            this.createFire(toBlow);
        }

        // Paper start - collision optimisations
        this.blockCache = null;
        this.chunkPosCache = null;
        this.chunkCache = null;
        this.directMappedBlockCache = null;
        this.mutablePos = null;
        // Paper end - collision optimisations
        return toBlow.size();
    }

    private static void addOrAppendStack(final List<ServerExplosion.StackCollector> stacks, final ItemStack stack, final BlockPos pos) {
        for (ServerExplosion.StackCollector stackCollector : stacks) {
            stackCollector.tryMerge(stack);
            if (stack.isEmpty()) {
                return;
            }
        }

        stacks.add(new ServerExplosion.StackCollector(pos, stack));
    }

    private boolean interactsWithBlocks() {
        return this.blockInteraction != Explosion.BlockInteraction.KEEP;
    }

    public Map<Player, Vec3> getHitPlayers() {
        return this.hitPlayers;
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Override
    public @Nullable LivingEntity getIndirectSourceEntity() {
        return Explosion.getIndirectSourceEntity(this.source);
    }

    @Override
    public @Nullable Entity getDirectSourceEntity() {
        return this.source;
    }

    public DamageSource getDamageSource() {
        return this.damageSource;
    }

    @Override
    public Explosion.BlockInteraction getBlockInteraction() {
        return this.blockInteraction;
    }

    @Override
    public boolean canTriggerBlocks() {
        return this.blockInteraction == Explosion.BlockInteraction.TRIGGER_BLOCK
            && (this.source == null || !this.source.is(EntityTypes.BREEZE_WIND_CHARGE) || this.level.getGameRules().get(GameRules.MOB_GRIEFING));
    }

    @Override
    public boolean shouldAffectBlocklikeEntities() {
        boolean mobGriefingEnabled = this.level.getGameRules().get(GameRules.MOB_GRIEFING);
        boolean isNotWindCharge = this.source == null || !this.source.is(EntityTypes.BREEZE_WIND_CHARGE) && !this.source.is(EntityTypes.WIND_CHARGE);
        return mobGriefingEnabled ? isNotWindCharge : this.blockInteraction.shouldAffectBlocklikeEntities() && isNotWindCharge;
    }

    public boolean isSmall() {
        return this.radius < 2.0F || !this.interactsWithBlocks();
    }

    private static class StackCollector {
        private final BlockPos pos;
        private ItemStack stack;

        private StackCollector(final BlockPos pos, final ItemStack stack) {
            this.pos = pos;
            this.stack = stack;
        }

        public void tryMerge(final ItemStack input) {
            if (ItemEntity.areMergable(this.stack, input)) {
                this.stack = ItemEntity.merge(this.stack, input, 16);
            }
        }
    }

    // Paper start - Optimize explosions
    private float getBlockDensity(Vec3 vec3d, Entity entity) {
        if (!this.level.paperConfig().environment.optimizeExplosions) {
            return this.getSeenFraction(vec3d, entity, this.directMappedBlockCache, this.mutablePos); // Paper - collision optimisations
        }
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.level.getCurrentWorldData(); // Folia - region threading
        CacheKey key = new CacheKey(this, entity.getBoundingBox());
        Float blockDensity = worldData.explosionDensityCache.get(key); // Folia - region threading
        if (blockDensity == null) {
            blockDensity = this.getSeenFraction(vec3d, entity, this.directMappedBlockCache, this.mutablePos); // Paper - collision optimisations
            worldData.explosionDensityCache.put(key, blockDensity); // Folia - region threading
        }

        return blockDensity;
    }

    public static class CacheKey { // Folia - region threading - public
        private final Level world;
        private final double posX, posY, posZ;
        private final double minX, minY, minZ;
        private final double maxX, maxY, maxZ;

        public CacheKey(Explosion explosion, AABB aabb) {
            this.world = explosion.level();
            this.posX = explosion.center().x;
            this.posY = explosion.center().y;
            this.posZ = explosion.center().z;
            this.minX = aabb.minX;
            this.minY = aabb.minY;
            this.minZ = aabb.minZ;
            this.maxX = aabb.maxX;
            this.maxY = aabb.maxY;
            this.maxZ = aabb.maxZ;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheKey cacheKey = (CacheKey) o;

            if (Double.compare(cacheKey.posX, posX) != 0) return false;
            if (Double.compare(cacheKey.posY, posY) != 0) return false;
            if (Double.compare(cacheKey.posZ, posZ) != 0) return false;
            if (Double.compare(cacheKey.minX, minX) != 0) return false;
            if (Double.compare(cacheKey.minY, minY) != 0) return false;
            if (Double.compare(cacheKey.minZ, minZ) != 0) return false;
            if (Double.compare(cacheKey.maxX, maxX) != 0) return false;
            if (Double.compare(cacheKey.maxY, maxY) != 0) return false;
            if (Double.compare(cacheKey.maxZ, maxZ) != 0) return false;
            return world.equals(cacheKey.world);
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            result = world.hashCode();
            temp = Double.doubleToLongBits(posX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            return result;
        }
    }
    // Paper end - Optimize explosions
}

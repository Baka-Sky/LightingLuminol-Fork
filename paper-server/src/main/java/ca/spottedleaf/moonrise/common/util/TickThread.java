package ca.spottedleaf.moonrise.common.util;

import io.papermc.paper.threadedregions.RegionShutdownThread;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

public class TickThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickThread.class);

    private static String getRegionInfo(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        if (region == null) {
            return "{null}";
        }

        final ChunkPos center = region.getCenterChunk();
        final net.minecraft.server.level.ServerLevel world = region.regioniser.world;

        return "{center=" + center + ",world=" + (world == null ? "null" : WorldUtil.getWorldName(world)) + "}";
    }

    private static String getThreadContext() {
        final Thread thread = Thread.currentThread();

        if (!(thread instanceof TickThread)) {
            return "[thread=" + thread + ",class=" + thread.getClass().getName() + "]";
        }

        return "[thread=" + thread.getName() + ",class=" + thread.getClass().getName() + ",region=" + getRegionInfo(TickRegionScheduler.getCurrentRegion()) + "]";

    }

    /**
     * @deprecated
     */
    @Deprecated
    public static void ensureTickThread(final String reason) {
        if (!isTickThread()) {
            LOGGER.error("Thread failed main thread check: " + reason + ", context=" + getThreadContext(), new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final BlockPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            final String ex = "Thread failed main thread check: " +
                               reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_pos=" + pos;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final BlockPos pos, final int blockRadius, final String reason) {
        if (!isTickThreadFor(world, pos, blockRadius)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_pos=" + pos + ", block_radius=" + blockRadius;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final ChunkPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", chunk_pos=" + pos;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final int chunkX, final int chunkZ, final String reason) {
        if (!isTickThreadFor(world, chunkX, chunkZ)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", chunk_pos=" + new ChunkPos(chunkX, chunkZ);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Entity entity, final String reason) {
        if (!isTickThreadFor(entity)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", entity=" + EntityUtil.dumpEntity(entity);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final AABB aabb, final String reason) {
        if (!isTickThreadFor(world, aabb)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", aabb=" + aabb;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final double blockX, final double blockZ, final String reason) {
        if (!isTickThreadFor(world, blockX, blockZ)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_pos=" + new Vec3(blockX, 0.0, blockZ);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public final int id; /* We don't override getId as the spec requires that it be unique (with respect to all other threads) */

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger();

    public TickThread(final String name) {
        this(null, name);
    }

    public TickThread(final Runnable run, final String name) {
        this(null, run, name);
    }

    public TickThread(final ThreadGroup group, final Runnable run, final String name) {
        this(group, run, name, ID_GENERATOR.incrementAndGet());
    }

    private TickThread(final ThreadGroup group, final Runnable run, final String name, final int id) {
        super(group, run, name);
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        return (TickThread)Thread.currentThread();
    }

    public static boolean isTickThread() {
        return Thread.currentThread() instanceof TickThread;
    }

    public static boolean isShutdownThread() {
        return Thread.currentThread().getClass() == RegionShutdownThread.class;
    }

    public static boolean isTickThreadFor(final Level world, final BlockPos pos) {
        return isTickThreadFor(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isTickThreadFor(final Level world, final BlockPos pos, final int blockRadius) {
        return isTickThreadFor(
                world,
                (pos.getX() - blockRadius) >> 4, (pos.getZ() - blockRadius) >> 4,
                (pos.getX() + blockRadius) >> 4, (pos.getZ() + blockRadius) >> 4
        );
    }

    public static boolean isTickThreadFor(final Level world, final ChunkPos pos) {
        return isTickThreadFor(world, pos.x(), pos.z());
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 pos) {
        return isTickThreadFor(world, net.minecraft.util.Mth.floor(pos.x) >> 4, net.minecraft.util.Mth.floor(pos.z) >> 4);
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return isShutdownThread();
        }
        return ((net.minecraft.server.level.ServerLevel)world).regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) == region;
    }

    public static boolean isTickThreadFor(final Level world, final AABB aabb) {
        return isTickThreadFor(
            world,
            CoordinateUtils.getChunkCoordinate(aabb.minX), CoordinateUtils.getChunkCoordinate(aabb.minZ),
            CoordinateUtils.getChunkCoordinate(aabb.maxX), CoordinateUtils.getChunkCoordinate(aabb.maxZ)
        );
    }

    public static boolean isTickThreadFor(final Level world, final double blockX, final double blockZ) {
        return isTickThreadFor(world, CoordinateUtils.getChunkCoordinate(blockX), CoordinateUtils.getChunkCoordinate(blockZ));
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 position, final Vec3 deltaMovement, final int buffer) {
        final int fromChunkX = CoordinateUtils.getChunkX(position);
        final int fromChunkZ = CoordinateUtils.getChunkZ(position);

        final int toChunkX = CoordinateUtils.getChunkCoordinate(position.x + deltaMovement.x);
        final int toChunkZ = CoordinateUtils.getChunkCoordinate(position.z + deltaMovement.z);

        // expect from < to, but that may not be the case
        return isTickThreadFor(
            world,
            Math.min(fromChunkX, toChunkX) - buffer,
            Math.min(fromChunkZ, toChunkZ) - buffer,
            Math.max(fromChunkX, toChunkX) + buffer,
            Math.max(fromChunkZ, toChunkZ) + buffer
        );
    }

    public static boolean isTickThreadFor(final Level world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return isShutdownThread();
        }

        final int shift = ((net.minecraft.server.level.ServerLevel)world).regioniser.sectionChunkShift;

        final int minSectionX = fromChunkX >> shift;
        final int maxSectionX = toChunkX >> shift;
        final int minSectionZ = fromChunkZ >> shift;
        final int maxSectionZ = toChunkZ >> shift;

        for (int secZ = minSectionZ; secZ <= maxSectionZ; ++secZ) {
            for (int secX = minSectionX; secX <= maxSectionX; ++secX) {
                final int lowerLeftCX = secX << shift;
                final int lowerLeftCZ = secZ << shift;
                if (((net.minecraft.server.level.ServerLevel)world).regioniser.getRegionAtUnsynchronised(lowerLeftCX, lowerLeftCZ) != region) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ, final int radius) {
        return isTickThreadFor(world, chunkX - radius, chunkZ - radius, chunkX + radius, chunkZ + radius);
    }

    public static boolean isTickThreadFor(final Entity entity) {
        if (entity == null) {
            return true;
        }
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            if (RegionizedServer.isGlobalTickThread()) {
                if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    final net.minecraft.server.network.ServerGamePacketListenerImpl possibleBad = serverPlayer.connection;
                    if (possibleBad == null) {
                        return true;
                    }

                    final net.minecraft.network.PacketListener packetListener = possibleBad.connection.getPacketListener();
                    if (packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gamePacketListener) {
                        return gamePacketListener.waitingForSwitchToConfig;
                    }
                    if (packetListener instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configurationPacketListener) {
                        return configurationPacketListener.switchToMain == null;
                    }
                    return true;
                } else {
                    return false;
                }
            }
            if (isShutdownThread()) {
                return true;
            }
            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // off-main access to server player is never ok, server player is owned by one of global context or region context always
                return false;
            }
            // only own entities that have not yet been added to the world

            // if the entity is removed, then it was in the world previously - which means that a region containing its location
            // owns it
            // if the entity has a callback, then it is contained in a world
            return entity.hasNullCallback() && !entity.isRemoved();
        }

        final Level world = entity.level();
        if (world != region.regioniser.world) {
            // world mismatch
            return false;
        }

        final RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();

        // pass through the check if the entity is removed and we own its chunk
        if (worldData.hasEntity(entity)) {
            return true;
        }
        
        if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            net.minecraft.server.network.ServerGamePacketListenerImpl conn = serverPlayer.connection;
            return conn != null && worldData.connections.contains(conn.connection);
        } else {
            return ((entity.hasNullCallback() || entity.isRemoved())) && isTickThreadFor((net.minecraft.server.level.ServerLevel)world, entity.chunkPosition());
        }
    }
}

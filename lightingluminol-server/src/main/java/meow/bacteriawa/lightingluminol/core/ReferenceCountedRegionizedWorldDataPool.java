package meow.bacteriawa.lightingluminol.core;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import io.papermc.paper.threadedregions.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;

/**
 * // at -> Access transformer(make public)
 * Take a watch at
 * @see RegionizedTaskQueue
 */
public class ReferenceCountedRegionizedWorldDataPool {
    private static final net.minecraft.server.level.TicketType<Long> TASK_QUEUE_TICKET = ChunkSystemTicketType.create("task_queue_ticket", Long::compareTo);
    private final ConcurrentChainedLong2ReferenceHashTable<ReferenceCountData> referenceCounters = new ConcurrentChainedLong2ReferenceHashTable<>();
    private final ServerLevel world;
    private volatile int mask = -1;

    public ReferenceCountedRegionizedWorldDataPool(@NotNull ServerLevel world) {
        this.world = world;
    }

    private long computeCoord(long actualCoord) {
        if (this.mask == -1) {
            this.mask = (1 << this.world.regioniser.sectionChunkShift) - 1;
        }

        return actualCoord & ~((long)this.mask);
    }

    public Tuple<RegionizedWorldData, ReferenceCountData> getAndHeldReference(int chunkX, int chunkZ) {
        return this.getAndHeldReference(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    public Tuple<RegionizedWorldData, ReferenceCountData> getAndHeldReference(@NotNull Entity entity) {
        if (entity.level() != this.world) {
            throw new IllegalArgumentException("Entity not in the same world as this pool");
        }

        return this.getAndHeldReference(entity.chunkPosition().x(), entity.chunkPosition().z());
    }

    public Tuple<RegionizedWorldData, ReferenceCountData> getAndHeldReference(BlockPos pos) {
        return this.getAndHeldReference(CoordinateUtils.getChunkKey(pos));
    }

    public void releaseReference(int chunkX, int chunkZ, @Nullable ReferenceCountData referenceCountData) {
        this.releaseReference(CoordinateUtils.getChunkKey(chunkX, chunkZ), referenceCountData);
    }

    public void releaseReference(@NotNull Entity entity, @Nullable ReferenceCountData referenceCountData) {
        if (entity.level() != this.world) {
            throw new IllegalArgumentException("Entity not in the same world as this pool");
        }

        this.releaseReference(CoordinateUtils.getChunkKey(entity.chunkPosition()), referenceCountData);
    }

    public void releaseReference(BlockPos pos, @Nullable ReferenceCountData referenceCountData) {
        this.releaseReference(CoordinateUtils.getChunkKey(pos), referenceCountData);
    }

    /**
     * Example:
     * var worldDataTuple = pool.getAndHeldReference(chunkX, chunkZ);
     * var worldData = worldDataTuple.left;
     * var referenceCountData = worldDataTuple.right;
     * try {
     *     worldData.xxxx();
     * }finally {
     *    pool.releaseReference(chunkX, chunkZ, referenceCountData);
     * }
     */

    public void releaseReference(long coord, @Nullable ReferenceCountData referenceCountData) {
        if (referenceCountData == null) {
            return;
        }

        this.decrementReference(referenceCountData, this.computeCoord(coord));
    }

    public Tuple<RegionizedWorldData, ReferenceCountData> getAndHeldReference(long coord) {
        final RegionizedWorldData tryFetch = TickRegionScheduler.getCurrentRegionizedWorldData();
        // might on the tickregion of ourselves
        if (tryFetch != null && tryFetch.world == this.world && TickThread.isTickThreadFor(this.world, CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord))) {
            // already in the right thread, no need to do anything
            return new Tuple<>(tryFetch, null);
        }

        final long sectionLeftLower = this.computeCoord(coord);

        final ReferenceCountData referenceCountData = this.incrementReference(sectionLeftLower);
        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> tickRegionData;

        boolean sync = false;
        for (;;) {
            tickRegionData = sync ?
                    this.world.regioniser.getRegionAtSynchronised(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord)) :
                    this.world.regioniser.getRegionAtUnsynchronised(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord));

            if (tickRegionData != null) {
                break;
            }

            if (!sync) {
                sync = true;
                continue;
            }

            break;
        }

        RegionizedWorldData ret = null;

        if (tickRegionData != null) {
            ret = tickRegionData.getData().getRegionizedData(this.world.worldRegionData); // at: getRegionizedData

        }

        return new Tuple<>(ret, referenceCountData);
    }

    public record Tuple<L,R>(L left, R right) {}

    // Copied from RegionizedTaskQueue for tick region alive ensuring

    private void removeTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                TASK_QUEUE_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id
        );
    }

    private void addTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                TASK_QUEUE_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id
        );
    }

    private void processTicketUpdates(final long coord) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord));
    }

    // note: only call on acquired referenceCountData
    private void ensureTicketAdded(final long coord, final ReferenceCountData referenceCountData) {
        if (!referenceCountData.addedTicket) {
            // fine if multiple threads do this, no removeTicket may be called for this coord due to reference count inc
            this.addTicket(coord, referenceCountData.id);
            this.processTicketUpdates(coord);
            referenceCountData.addedTicket = true;
        }
    }

    private void decrementReference(final ReferenceCountData referenceCountData, final long coord) {
        if (!referenceCountData.decreaseReferenceCount()) {
            return;
        } // else: need to remove ticket

        final ReferenceCountData[] toRemoveTicket = new ReferenceCountData[1];

        // note: it is possible that another thread increments and then removes the reference before we can, so
        //       use ifPresent
        this.referenceCounters.computeIfPresent(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap.referenceCount.get() != 0L) {
                return valueInMap;
            }

            // note: valueInMap may not be referenceCountData
            toRemoveTicket[0] = valueInMap;

            return null;
        });

        if (toRemoveTicket[0] != null) {
            this.removeTicket(coord, toRemoveTicket[0].id);
        }
    }

    private ReferenceCountData incrementReference(final long coord) {
        ReferenceCountData referenceCountData = this.referenceCounters.get(coord);

        if (referenceCountData != null && referenceCountData.addCount()) {
            this.ensureTicketAdded(coord, referenceCountData);
            return referenceCountData;
        }

        referenceCountData = this.referenceCounters.compute(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap == null) {
                // sets reference count to 1
                return new ReferenceCountData();
            }
            // OK if we add from 0, the remove call will use compute() and catch this race condition
            valueInMap.referenceCount.getAndIncrement();

            return valueInMap;
        });

        this.ensureTicketAdded(coord, referenceCountData);

        return referenceCountData;
    }

    public static final class ReferenceCountData {
        private static final AtomicLong ID_GENERATOR = new AtomicLong();

        private final long id = ID_GENERATOR.getAndIncrement();

        public final AtomicLong referenceCount = new AtomicLong(1L);
        public volatile boolean addedTicket;

        // returns false if reference count is 0, otherwise increments ref count
        public boolean addCount() {
            int failures = 0;
            for (long curr = this.referenceCount.get();;) {
                for (int i = 0; i < failures; ++i) {
                    Thread.onSpinWait();
                }

                if (curr == 0L) {
                    return false;
                }

                if (curr == (curr = this.referenceCount.compareAndExchange(curr, curr + 1L))) {
                    return true;
                }

                ++failures;
            }
        }

        // returns true if new reference count is 0
        public boolean decreaseReferenceCount() {
            final long res = this.referenceCount.decrementAndGet();
            if (res >= 0L) {
                return res == 0L;
            } else {
                throw new IllegalStateException("Negative reference count");
            }
        }
    }
}

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

public class ReferenceCountedRegionizedWorldDataPool {
    private final ConcurrentChainedLong2ReferenceHashTable<ReferenceCountData> referenceCounters = new ConcurrentChainedLong2ReferenceHashTable<>();
    private final ServerLevel world;
    private volatile int mask = -1;

    private static volatile Field taskQueueTicketField;
    private static volatile Method getRegionizedDataMethod;

    static {
        try {
            Class<?> regionizedTaskQueueClass = Class.forName("io.papermc.paper.threadedregions.RegionizedTaskQueue");
            taskQueueTicketField = regionizedTaskQueueClass.getDeclaredField("TASK_QUEUE_TICKET");
            taskQueueTicketField.setAccessible(true);

            Class<?> tickRegionDataClass = Class.forName("io.papermc.paper.threadedregions.TickRegions$TickRegionData");
            getRegionizedDataMethod = tickRegionDataClass.getDeclaredMethod("getRegionizedData", Class.forName("io.papermc.paper.threadedregions.RegionizedData"));
            getRegionizedDataMethod.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Object getTaskQueueTicket() {
        try {
            return taskQueueTicketField.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static RegionizedWorldData getRegionizedData(Object tickRegionData, RegionizedData<RegionizedWorldData> worldRegionData) {
        try {
            return (RegionizedWorldData) getRegionizedDataMethod.invoke(tickRegionData, worldRegionData);
        } catch (Exception e) {
            return null;
        }
    }

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

    public void releaseReference(long coord, @Nullable ReferenceCountData referenceCountData) {
        if (referenceCountData == null) {
            return;
        }

        this.decrementReference(referenceCountData, this.computeCoord(coord));
    }

    public Tuple<RegionizedWorldData, ReferenceCountData> getAndHeldReference(long coord) {
        final RegionizedWorldData tryFetch = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (tryFetch != null && tryFetch.world == this.world && TickThread.isTickThreadFor(this.world, CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord))) {
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
            ret = getRegionizedData(tickRegionData.getData(), this.world.worldRegionData);
        }

        return new Tuple<>(ret, referenceCountData);
    }

    public record Tuple<L,R>(L left, R right) {}

    private void removeTicket(final long coord, final long id) {
        Object ticket = getTaskQueueTicket();
        if (ticket != null) {
            try {
                Method method = ChunkHolderManager.class.getDeclaredMethod("removeTicketAtLevel", Class.forName("ca.spottedleaf.moonrise.patches.chunk_system.scheduling.TicketType"), long.class, int.class, Object.class);
                method.setAccessible(true);
                method.invoke(this.world.moonrise$getChunkTaskScheduler().chunkHolderManager, ticket, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void addTicket(final long coord, final long id) {
        Object ticket = getTaskQueueTicket();
        if (ticket != null) {
            try {
                Method method = ChunkHolderManager.class.getDeclaredMethod("addTicketAtLevel", Class.forName("ca.spottedleaf.moonrise.patches.chunk_system.scheduling.TicketType"), long.class, int.class, Object.class);
                method.setAccessible(true);
                method.invoke(this.world.moonrise$getChunkTaskScheduler().chunkHolderManager, ticket, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void processTicketUpdates(final long coord) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord));
    }

    private void ensureTicketAdded(final long coord, final ReferenceCountData referenceCountData) {
        if (!referenceCountData.addedTicket) {
            this.addTicket(coord, referenceCountData.id);
            this.processTicketUpdates(coord);
            referenceCountData.addedTicket = true;
        }
    }

    private void decrementReference(final ReferenceCountData referenceCountData, final long coord) {
        if (!referenceCountData.decreaseReferenceCount()) {
            return;
        }

        final ReferenceCountData[] toRemoveTicket = new ReferenceCountData[1];

        this.referenceCounters.computeIfPresent(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap.referenceCount.get() != 0L) {
                return valueInMap;
            }

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
                return new ReferenceCountData();
            }
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

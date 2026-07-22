package io.papermc.paper.threadedregions.scheduler;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import io.papermc.paper.threadedregions.RegionizedData;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class FoliaRegionScheduler implements RegionScheduler {

    private static Runnable wrap(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable run) {
        return () -> {
            try {
                run.run();
            } catch (final Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Location task for " + plugin.getDescription().getFullName()
                    + " in world " + world + " at " + chunkX + ", " + chunkZ + " generated an exception", throwable);
            }
        };
    }

    private static final RegionizedData<Scheduler> SCHEDULER_DATA = new RegionizedData<>(null, Scheduler::new, Scheduler.REGIONISER_CALLBACK);

    private static void scheduleInternalOnRegion(final LocationScheduledTask task, final long delay) {
        SCHEDULER_DATA.get().queueTask(task, delay);
    }

    private static void scheduleInternalOffRegion(final LocationScheduledTask task, final long delay) {
        final World world = task.world;
        if (world == null) {
            // cancelled
            return;
        }

        RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
            ((CraftWorld) world).getHandle(), task.chunkX, task.chunkZ, () -> {
                scheduleInternalOnRegion(task, delay);
            }
        );
    }

    @Override
    public void execute(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable run) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(world, "World may not be null");
        Objects.requireNonNull(run, "Runnable may not be null");

        if (meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.shouldUseBukkitScheduler(plugin)) {
            meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.executeOnBukkit(plugin, run, "Region");
            return;
        }

        RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
            ((CraftWorld) world).getHandle(), chunkX, chunkZ, wrap(plugin, world, chunkX, chunkZ, run)
        );
    }

    @Override
    public ScheduledTask run(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Consumer<ScheduledTask> task) {
        return this.runDelayed(plugin, world, chunkX, chunkZ, task, 1);
    }

    @Override
    public ScheduledTask runDelayed(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                    final Consumer<ScheduledTask> task, final long delayTicks) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(world, "World may not be null");
        org.apache.commons.lang3.Validate.notNull(task, "Task may not be null");
        if (delayTicks <= 0) {
            throw new IllegalArgumentException("Delay ticks may not be <= 0");
        }

        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }

        if (meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.shouldUseBukkitScheduler(plugin)) {
            return meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.runDelayedOnBukkit(plugin, task, delayTicks, "Region");
        }

        final LocationScheduledTask ret = new LocationScheduledTask(plugin, world, chunkX, chunkZ, -1, task);

        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            scheduleInternalOnRegion(ret, delayTicks);
        } else {
            scheduleInternalOffRegion(ret, delayTicks);
        }

        if (!plugin.isEnabled()) {
            // handle race condition where plugin is disabled asynchronously
            ret.cancel();
        }

        return ret;
    }

    @Override
    public ScheduledTask runAtFixedRate(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                        final Consumer<ScheduledTask> task, final long initialDelayTicks, final long periodTicks) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(world, "World may not be null");
        Objects.requireNonNull(task, "Task may not be null");
        if (initialDelayTicks <= 0) {
            throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
        }
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("Period ticks may not be <= 0");
        }

        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }

        if (meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.shouldUseBukkitScheduler(plugin)) {
            return meow.bacteriawa.lightingluminol.core.FoliaSchedulerCompatibility.runAtFixedRateOnBukkit(plugin, task, initialDelayTicks, periodTicks, "Region");
        }

        final LocationScheduledTask ret = new LocationScheduledTask(plugin, world, chunkX, chunkZ, periodTicks, task);

        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            scheduleInternalOnRegion(ret, initialDelayTicks);
        } else {
            scheduleInternalOffRegion(ret, initialDelayTicks);
        }

        if (!plugin.isEnabled()) {
            // handle race condition where plugin is disabled asynchronously
            ret.cancel();
        }

        return ret;
    }

    public void tick() {
        SCHEDULER_DATA.get().tick();
    }

    private static final class Scheduler {

        private Scheduler(final TickRegions.TickRegionData regionData) {}

        private static final RegionizedData.RegioniserCallback<Scheduler> REGIONISER_CALLBACK = new RegionizedData.RegioniserCallback<>() {
            @Override
            public void merge(final Scheduler from, final Scheduler into, final long fromTickOffset) {
                for (final Iterator<Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>>> sectionIterator = from.tasksByDeadlineBySection.long2ObjectEntrySet().fastIterator();
                     sectionIterator.hasNext();) {
                    final Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>> entry = sectionIterator.next();
                    final long sectionKey = entry.getLongKey();
                    final Long2ObjectOpenHashMap<List<LocationScheduledTask>> section = entry.getValue();

                    final Long2ObjectOpenHashMap<List<LocationScheduledTask>> sectionAdjusted = new Long2ObjectOpenHashMap<>(section.size());

                    for (final Iterator<Long2ObjectMap.Entry<List<LocationScheduledTask>>> iterator = section.long2ObjectEntrySet().fastIterator();
                         iterator.hasNext();) {
                        final Long2ObjectMap.Entry<List<LocationScheduledTask>> e = iterator.next();
                        final long newTick = e.getLongKey() + fromTickOffset;
                        final List<LocationScheduledTask> tasks = e.getValue();

                        sectionAdjusted.put(newTick, tasks);
                    }

                    into.tasksByDeadlineBySection.put(sectionKey, sectionAdjusted);
                }
            }

            @Override
            public void split(final Scheduler from, final int chunkToRegionShift, final Long2ReferenceOpenHashMap<Scheduler> regionToData,
                              final ReferenceOpenHashSet<Scheduler> dataSet) {
                for (final Scheduler into : dataSet) {
                    into.tickCount = from.tickCount;
                }

                for (final Iterator<Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>>> sectionIterator = from.tasksByDeadlineBySection.long2ObjectEntrySet().fastIterator();
                     sectionIterator.hasNext();) {
                    final Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>> entry = sectionIterator.next();
                    final long sectionKey = entry.getLongKey();
                    final Long2ObjectOpenHashMap<List<LocationScheduledTask>> section = entry.getValue();

                    final Scheduler into = regionToData.get(sectionKey);

                    into.tasksByDeadlineBySection.put(sectionKey, section);
                }
            }
        };

        private long tickCount = 0L;
        // map of region section -> map of deadline -> list of tasks
        private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<List<LocationScheduledTask>>> tasksByDeadlineBySection = new Long2ObjectOpenHashMap<>();

        private void addTicket(final int sectionX, final int sectionZ) {
            final ServerLevel world = TickRegionScheduler.getCurrentRegionizedWorldData().world;
            final int shift = world.moonrise$getRegionChunkShift();
            final int chunkX = sectionX << shift;
            final int chunkZ = sectionZ << shift;

            world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                TicketType.REGION_SCHEDULER_API_HOLD, chunkX, chunkZ, ChunkHolderManager.MAX_TICKET_LEVEL, null
            );
        }

        private void removeTicket(final long sectionKey) {
            final ServerLevel world = TickRegionScheduler.getCurrentRegionizedWorldData().world;
            final int shift = world.moonrise$getRegionChunkShift();
            final int chunkX = CoordinateUtils.getChunkX(sectionKey) << shift;
            final int chunkZ = CoordinateUtils.getChunkZ(sectionKey) << shift;

            world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                TicketType.REGION_SCHEDULER_API_HOLD, chunkX, chunkZ, ChunkHolderManager.MAX_TICKET_LEVEL, null
            );
        }

        private void queueTask(final LocationScheduledTask task, final long delay) {
            // note: must be on the thread that owns this scheduler
            // note: delay > 0

            final World world = task.world;
            if (world == null) {
                // cancelled
                return;
            }

            final int shift = ((CraftWorld)world).getHandle().moonrise$getRegionChunkShift();
            final int sectionX = task.chunkX >> shift;
            final int sectionZ = task.chunkZ >> shift;

            final Long2ObjectOpenHashMap<List<LocationScheduledTask>> section =
                this.tasksByDeadlineBySection.computeIfAbsent(CoordinateUtils.getChunkKey(sectionX, sectionZ), (final long keyInMap) -> {
                    return new Long2ObjectOpenHashMap<>();
                }
            );

            if (section.isEmpty()) {
                // need to keep the scheduler loaded for this location in order for tick() to be called...
                this.addTicket(sectionX, sectionZ);
            }

            section.computeIfAbsent(this.tickCount + delay, (final long keyInMap) -> {
                return new ArrayList<>();
            }).add(task);
        }

        public void tick() {
            ++this.tickCount;

            final List<LocationScheduledTask> run = new ArrayList<>();

            for (final Iterator<Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>>> sectionIterator = this.tasksByDeadlineBySection.long2ObjectEntrySet().fastIterator();
                 sectionIterator.hasNext();) {
                final Long2ObjectMap.Entry<Long2ObjectOpenHashMap<List<LocationScheduledTask>>> entry = sectionIterator.next();
                final long sectionKey = entry.getLongKey();
                final Long2ObjectOpenHashMap<List<LocationScheduledTask>> section = entry.getValue();

                final List<LocationScheduledTask> tasks = section.remove(this.tickCount);

                if (tasks == null) {
                    continue;
                }

                run.addAll(tasks);

                if (section.isEmpty()) {
                    this.removeTicket(sectionKey);
                    sectionIterator.remove();
                }
            }

            for (int i = 0, len = run.size(); i < len; ++i) {
                run.get(i).run();
            }
        }
    }

    private static final class LocationScheduledTask implements ScheduledTask, Runnable {

        private static final int STATE_IDLE                = 0;
        private static final int STATE_EXECUTING           = 1;
        private static final int STATE_EXECUTING_CANCELLED = 2;
        private static final int STATE_FINISHED            = 3;
        private static final int STATE_CANCELLED           = 4;

        private final Plugin plugin;
        private final int chunkX;
        private final int chunkZ;
        private final long repeatDelay; // in ticks
        private World world;
        private Consumer<ScheduledTask> run;

        private volatile int state;
        private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(LocationScheduledTask.class, "state", int.class);

        private LocationScheduledTask(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                      final long repeatDelay, final Consumer<ScheduledTask> run) {
            this.plugin = plugin;
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.repeatDelay = repeatDelay;
            this.run = run;
        }

        private final int getStateVolatile() {
            return (int)STATE_HANDLE.get(this);
        }

        private final int compareAndExchangeStateVolatile(final int expect, final int update) {
            return (int)STATE_HANDLE.compareAndExchange(this, expect, update);
        }

        private final void setStateVolatile(final int value) {
            STATE_HANDLE.setVolatile(this, value);
        }

        @Override
        public void run() {
            if (!this.plugin.isEnabled()) {
                // don't execute if the plugin is disabled
                return;
            }

            final boolean repeating = this.isRepeatingTask();
            if (STATE_IDLE != this.compareAndExchangeStateVolatile(STATE_IDLE, STATE_EXECUTING)) {
                // cancelled
                return;
            }

            try {
                this.run.accept(this);
            } catch (final Throwable throwable) {
                this.plugin.getLogger().log(Level.WARNING, "Location task for " + this.plugin.getDescription().getFullName()
                    + " in world " + world + " at " + chunkX + ", " + chunkZ + " generated an exception", throwable);
            } finally {
                boolean reschedule = false;
                 if (!repeating) {
                    this.setStateVolatile(STATE_FINISHED);
                } else if (!this.plugin.isEnabled()) {
                     this.setStateVolatile(STATE_CANCELLED);
                } else if (STATE_EXECUTING == this.compareAndExchangeStateVolatile(STATE_EXECUTING, STATE_IDLE)) {
                    reschedule = true;
                } // else: cancelled repeating task

                if (!reschedule) {
                    this.run = null;
                    this.world = null;
                } else {
                    FoliaRegionScheduler.scheduleInternalOnRegion(this, this.repeatDelay);
                }
            }
        }

        @Override
        public Plugin getOwningPlugin() {
            return this.plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return this.repeatDelay > 0;
        }

        @Override
        public CancelledState cancel() {
            for (int curr = this.getStateVolatile();;) {
                switch (curr) {
                    case STATE_IDLE: {
                        if (STATE_IDLE == (curr = this.compareAndExchangeStateVolatile(STATE_IDLE, STATE_CANCELLED))) {
                            this.state = STATE_CANCELLED;
                            this.run = null;
                            this.world = null;
                            return CancelledState.CANCELLED_BY_CALLER;
                        }
                        // try again
                        continue;
                    }
                    case STATE_EXECUTING: {
                        if (!this.isRepeatingTask()) {
                            return CancelledState.RUNNING;
                        }
                        if (STATE_EXECUTING == (curr = this.compareAndExchangeStateVolatile(STATE_EXECUTING, STATE_EXECUTING_CANCELLED))) {
                            return CancelledState.NEXT_RUNS_CANCELLED;
                        }
                        // try again
                        continue;
                    }
                    case STATE_EXECUTING_CANCELLED: {
                        return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
                    }
                    case STATE_FINISHED: {
                        return CancelledState.ALREADY_EXECUTED;
                    }
                    case STATE_CANCELLED: {
                        return CancelledState.CANCELLED_ALREADY;
                    }
                    default: {
                        throw new IllegalStateException("Unknown state: " + curr);
                    }
                }
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            final int state = this.getStateVolatile();
            switch (state) {
                case STATE_IDLE:
                    return ExecutionState.IDLE;
                case STATE_EXECUTING:
                    return ExecutionState.RUNNING;
                case STATE_EXECUTING_CANCELLED:
                    return ExecutionState.CANCELLED_RUNNING;
                case STATE_FINISHED:
                    return ExecutionState.FINISHED;
                case STATE_CANCELLED:
                    return ExecutionState.CANCELLED;
                default: {
                    throw new IllegalStateException("Unknown state: " + state);
                }
            }
        }
    }
}

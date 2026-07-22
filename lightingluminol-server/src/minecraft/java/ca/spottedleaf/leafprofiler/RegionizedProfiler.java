package ca.spottedleaf.leafprofiler;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.profiler.ProfileGraph;
import ca.spottedleaf.profiler.Profiler;
import ca.spottedleaf.profiler.ProfilerRegistry;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RegionizedProfiler {

    private static final AtomicLong ID_GENERATOR = new AtomicLong();

    public final long id;
    private final AtomicInteger profilingRegions = new AtomicInteger();
    private final MultiThreadedQueue<RecordedOperation> operations = new MultiThreadedQueue<>();
    private final MultiThreadedQueue<RegionTimings> timings = new MultiThreadedQueue<>();
    private final long absoluteStart = System.nanoTime();
    private final long absoluteEnd;
    private final Consumer<ProfileResults> onFinish;

    public RegionizedProfiler(final long id, final long recordFor, final Consumer<ProfileResults> onFinish) {
        this.id = id;
        this.onFinish = onFinish;
        this.absoluteEnd = this.absoluteStart + recordFor;
    }

    public void createProfiler(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> into) {
        final Handle newProfiler = new Handle(
            new Profiler(LProfilerRegistry.GLOBAL_REGISTRY, new ProfileGraph()),
            false, this, into
        );
        newProfiler.startProfiler();

        into.getData().profiler = newProfiler;
    }

    public void preMerge(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> from,
                         final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> into) {
        final Handle fromProfiler = from.getData().profiler;
        final Handle intoProfiler = into.getData().profiler;
        this.operations.add(new RecordedOperation(OperationType.MERGE, from.id, LongArrayList.of(into.id), System.nanoTime()));

        if (intoProfiler != null) {
            // target is already profiling
            fromProfiler.stopProfiler();
            return;
        }

        this.createProfiler(into);

        // the old profiler must be terminated only after creating the new one, so that there is always at least one
        // profiler running, otherwise the profiler will complete
        fromProfiler.stopProfiler();
    }

    public void preSplit(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> from,
                         final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> into) {
        final Handle fromProfiler = from.getData().profiler;

        final LongArrayList regions = new LongArrayList(into.size());
        for (int i = 0, len = into.size(); i < len; ++i) {
            regions.add(into.get(i).id);
        }

        this.operations.add(new RecordedOperation(OperationType.SPLIT, from.id, regions, System.nanoTime()));

        for (int i = 0, len = into.size(); i < len; ++i) {
            // create new profiler
            this.createProfiler(into.get(i));
        }

        // the old profiler must be terminated only after creating the new ones, so that there is always at least one
        // profiler running, otherwise the profiler will complete
        fromProfiler.stopProfiler();
    }

    public static record RecordedOperation(
        OperationType type,

        /*
         * The target for start,end or the `from` region for merge/split
         */
        long regionOfInterest,

        /*
         * The merge target or the split targets
         */
        LongArrayList targetRegions,

        /*
         * The timestamp for this operation
         */
        long time
    ) {}

    public static enum OperationType {
        START,
        MERGE,
        SPLIT,
        END;
    }

    public static final class Handle {

        public final Profiler profiler;
        private boolean noOp;
        public final RegionizedProfiler profilerGroup;
        private final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;
        private final TickData tickData = new TickData(Long.MAX_VALUE);
        private long startTime;

        public static final Handle NO_OP_HANDLE = new Handle(
            null, true, null, null
        );

        private Handle(final Profiler profiler, final boolean noOp,
                       final RegionizedProfiler profilerGroup,
                       final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
            this.profiler = profiler;
            this.noOp = noOp;
            this.profilerGroup = profilerGroup;
            this.region = region;
        }

        public void startTick() {
            if (this.noOp) {
                return;
            }

            this.profiler.startTimer(LProfilerRegistry.TICK, System.nanoTime());
        }

        public void stopTick() {
            if (this.noOp) {
                return;
            }



            this.profiler.stopTimer(LProfilerRegistry.TICK, System.nanoTime());
            this.profiler.accumulate();
        }

        public void startInBetweenTick() {
            if (this.noOp) {
                return;
            }

            this.profiler.startTimer(LProfilerRegistry.IN_BETWEEN_TICK, System.nanoTime());
        }

        public void stopInBetweenTick() {
            if (this.noOp) {
                return;
            }

            this.profiler.stopTimer(LProfilerRegistry.IN_BETWEEN_TICK, System.nanoTime());
        }

        public void startTimer(final int timerId) {
            if (this.noOp) {
                return;
            }

            this.profiler.startTimer(timerId, System.nanoTime());
        }

        public void stopTimer(final int timerId) {
            if (this.noOp) {
                return;
            }

            this.profiler.stopTimer(timerId, System.nanoTime());
        }

        public void addCounter(final int counterId, final long count) {
            if (this.noOp) {
                return;
            }

            this.profiler.incrementCounter(counterId, count);
        }

        public int getOrCreateTimerAndStart(final Supplier<String> name) {
            if (this.noOp) {
                return -1;
            }

            final int timer = this.profiler.registry.getOrCreateType(ProfilerRegistry.ProfileType.TIMER, name.get());

            this.profiler.startTimer(timer, System.nanoTime());

            return timer;
        }

        public void addTickTime(final TickTime tickTime) {
            if (this.noOp) {
                return;
            }
            this.tickData.addDataFrom(tickTime);
        }

        public void startProfiler() {
            if (this.noOp) {
                return;
            }
            this.profilerGroup.profilingRegions.getAndIncrement();
            this.startTime = System.nanoTime();
            this.profilerGroup.operations.add(
                new RecordedOperation(OperationType.START, this.region.id, new LongArrayList(), this.startTime)
            );
        }

        public void stopProfiler() {
            if (this.noOp) {
                return;
            }

            final long endTime = System.nanoTime();

            this.noOp = true;
            this.region.getData().profiler = null;
            this.profilerGroup.operations.add(
                new RecordedOperation(OperationType.END, this.region.id, new LongArrayList(), endTime)
            );
            this.profilerGroup.timings.add(
                new RegionTimings(
                    this.startTime, endTime, this.region.id, this.profiler, this.tickData
                )
            );

            if (this.profilerGroup.profilingRegions.decrementAndGet() == 0) {
                this.profilerGroup.onFinish.accept(
                    new ProfileResults(
                        this.profilerGroup.id,
                        this.profilerGroup.absoluteStart,
                        endTime,
                        new ArrayList<>(this.profilerGroup.timings),
                        new ArrayList<>(this.profilerGroup.operations)
                    )
                );
            }
        }

        public void checkStop() {
            if (this.noOp) {
                return;
            }
            if ((System.nanoTime() - this.profilerGroup.absoluteEnd) >= 0L) {
                this.stopProfiler();
            }
        }
    }

    public static record ProfileResults(
        long profileId,
        long startTime,
        long endTime,
        List<RegionTimings> timings,
        List<RecordedOperation> operations
    ) {}

    public static record RegionTimings(
        long startTime,
        long endTime,
        long regionId,
        Profiler profiler,
        TickData tickData
    ) {}
}

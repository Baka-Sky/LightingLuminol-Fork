package io.papermc.paper.threadedregions;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.TickRegionScheduler.RegionScheduleHandle;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.spigotmc.WatchdogThread;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FoliaWatchdogThread extends Thread {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final class RunningTick {

        public final long start;
        public final RegionScheduleHandle handle;
        public final Thread thread;

        private long lastPrint;

        public RunningTick(final long start, final RegionScheduleHandle handle, final Thread thread) {
            this.start = start;
            this.handle = handle;
            this.thread = thread;
            this.lastPrint = start;
        }
    }

    private final LinkedHashSet<RunningTick> ticks = new LinkedHashSet<>();

    public FoliaWatchdogThread() {
        super("Folia Watchdog Thread");
        this.setDaemon(true);
        this.setUncaughtExceptionHandler((final Thread thread, final Throwable throwable) -> {
            LOGGER.error("Uncaught exception in thread '" + thread.getName() + "'", throwable);
        });
    }

    @Override
    public void run() {
        for (;;) {
            try {
                Thread.sleep(1000L);
            } catch (final InterruptedException ex) {}

            if (MinecraftServer.getServer() == null || MinecraftServer.getServer().hasStopped()) {
                continue;
            }

            final List<RunningTick> ticks;
            synchronized (this.ticks) {
                if (this.ticks.isEmpty()) {
                    continue;
                }
                ticks = new ArrayList<>(this.ticks);
            }

            final long now = System.nanoTime();

            for (final RunningTick tick : ticks) {
                final long elapsed = now - tick.lastPrint;
                if (elapsed <= TimeUnit.SECONDS.toNanos(5L)) {
                    continue;
                }
                tick.lastPrint = now;

                final double totalElapsedS = (double)(now - tick.start) / 1.0E9;

                if (tick.handle instanceof TickRegions.ConcreteRegionTickHandle region) {
                    LOGGER.error(
                        "Tick region located in world '" + region.region.world.getWorld().getName() + "' around chunk '"
                            + region.region.region.getCenterChunk() + "' has not responded in " + totalElapsedS + "s:"
                    );
                } else {
                    // assume global
                    LOGGER.error("Global region has not responded in " + totalElapsedS + "s:");
                }

                WatchdogThread.dumpThread(
                    ManagementFactory.getThreadMXBean().getThreadInfo(tick.thread.threadId(), Integer.MAX_VALUE),
                    Bukkit.getServer().getLogger()
                );
            }
        }
    }

    public void addTick(final RunningTick tick) {
        synchronized (this.ticks) {
            this.ticks.add(tick);
        }
    }

    public void removeTick(final RunningTick tick) {
        synchronized (this.ticks) {
            this.ticks.remove(tick);
        }
    }
}

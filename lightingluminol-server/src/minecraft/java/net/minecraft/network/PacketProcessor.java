package net.minecraft.network;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.ReportedException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import org.slf4j.Logger;

public class PacketProcessor implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Queue<PacketProcessor.ListenerAndPacket<?>> packetsToBeHandled = Queues.newConcurrentLinkedQueue();
    private final Thread runningThread;
    private boolean closed;

    public PacketProcessor(final Thread runningThread) {
        this.runningThread = runningThread;
    }

    // Folia start - region threading
    public boolean hasPackets() {
        return !this.packetsToBeHandled.isEmpty();
    }
    // Folia end - region threading

    // Paper start - improve tick loop
    public final boolean executeSinglePacket() {
        if (this.closed) {
            return false;
        }

        final PacketProcessor.ListenerAndPacket<?> task = this.packetsToBeHandled.poll();
        if (task == null) {
            return false;
        }

        task.handle();
        return true;
    }
    // Paper end - improve tick loop

    public boolean isSameThread() {
        return Thread.currentThread() == this.runningThread;
    }

    public <T extends PacketListener> boolean scheduleIfPossible(final T listener, final Packet<T> packet) { // Folia - region threading - return whether to notify
        if (this.closed) {
            throw new io.papermc.paper.util.ServerStopRejectedExecutionException("Packet processor is shut down"); // Paper - do not prematurely disconnect players on stop // Folia - region threading - change exception message
        }
        // Paper start - improve tick loop
        // wake up main thread inbetween ticks to process packets
        final boolean isEmpty = this.packetsToBeHandled.isEmpty();
        final ListenerAndPacket<T> toAdd = new PacketProcessor.ListenerAndPacket<>(listener, packet);
        this.packetsToBeHandled.add(toAdd);
        if (isEmpty || this.packetsToBeHandled.peek() == toAdd) {
            // only unpark if we are the first packet OR are at the head of the queue
            // we unpark if we are at the head in case the main thread emptied the queue
            // immediately before we added but after checking isEmpty
            return true; // Folia - region threading - return whether to notify
        }
        // Paper end - improve tick loop
        return false; // Folia - region threading - return whether to notify
    }

    public void processQueuedPackets() {
        if (!this.closed) {
            while (!this.packetsToBeHandled.isEmpty()) {
                this.packetsToBeHandled.poll().handle();
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
    }

    // Paper start - detailed watchdog information
    public static final java.util.concurrent.ConcurrentLinkedDeque<PacketListener> packetProcessing = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final java.util.concurrent.atomic.AtomicLong totalMainThreadPacketsProcessed = new java.util.concurrent.atomic.AtomicLong();

    public static long getTotalProcessedPackets() {
        return totalMainThreadPacketsProcessed.get();
    }

    public static java.util.List<PacketListener> getCurrentPacketProcessors() {
        java.util.List<PacketListener> listeners = new java.util.ArrayList<>(4);
        for (PacketListener listener : packetProcessing) {
            listeners.add(listener);
        }

        return listeners;
    }
    // Paper end - detailed watchdog information

    private record ListenerAndPacket<T extends PacketListener>(T listener, Packet<T> packet) {
        public void handle() {
            packetProcessing.push(this.listener); // Paper - detailed watchdog information
            try { // Paper - detailed watchdog information
            if (this.listener.shouldHandleMessage(this.packet)) {
                try {
                    final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
                    final int packetTimerId = profiler.getOrCreateTimerAndStart(() -> "Packet Handler: ".concat(ListenerAndPacket.this.packet.getClass().getName())); try { // Folia - profiler
                    this.packet.handle(this.listener);
                    } finally { profiler.stopTimer(packetTimerId); } // Folia - profiler
                } catch (Exception e) {
                    if (e instanceof ReportedException re && re.getCause() instanceof OutOfMemoryError) {
                        throw PacketUtils.makeReportedException(e, this.packet, this.listener);
                    }

                    this.listener.onPacketError(this.packet, e);
                }
            } else {
                PacketProcessor.LOGGER.debug("Ignoring packet due to disconnection: {}", this.packet);
            }
            // Paper start - detailed watchdog information
            } finally {
                totalMainThreadPacketsProcessed.getAndIncrement();
                packetProcessing.pop();
            }
            // Paper end - detailed watchdog information
        }
    }
}

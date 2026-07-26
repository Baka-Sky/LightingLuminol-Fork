package meow.bacteriawa.lightingluminol.core;

import meow.bacteriawa.lightingluminol.config.OptimizationsConfig;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.BitSet;

public class CpuAffinityManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static AffinityRunnableWrapper tickRegionWrapper;
    private static AffinityRunnableWrapper chunkWorkerWrapper;
    private static AffinityRunnableWrapper chunkIoWrapper;

    public static void init() {
        if (!OptimizationsConfig.CpuAffinity.enabledForTickRegion &&
            !OptimizationsConfig.CpuAffinity.enabledForChunkSystemWorker &&
            !OptimizationsConfig.CpuAffinity.enabledForChunkSystemIo) {
            return;
        }

        try {
            if (OptimizationsConfig.CpuAffinity.enabledForTickRegion) {
                BitSet cores = parseCpuList(OptimizationsConfig.CpuAffinity.tickRegionAffinity);
                if (!cores.isEmpty()) {
                    tickRegionWrapper = new AffinityRunnableWrapper("Tick Region", cores);
                    LOGGER.info("CPU affinity for tick regions: cores {}", OptimizationsConfig.CpuAffinity.tickRegionAffinity);
                }
            }
            if (OptimizationsConfig.CpuAffinity.enabledForChunkSystemWorker) {
                BitSet cores = parseCpuList(OptimizationsConfig.CpuAffinity.chunkSystemWorkerAffinity);
                if (!cores.isEmpty()) {
                    chunkWorkerWrapper = new AffinityRunnableWrapper("Chunk Worker", cores);
                    LOGGER.info("CPU affinity for chunk workers: cores {}", OptimizationsConfig.CpuAffinity.chunkSystemWorkerAffinity);
                }
            }
            if (OptimizationsConfig.CpuAffinity.enabledForChunkSystemIo) {
                BitSet cores = parseCpuList(OptimizationsConfig.CpuAffinity.chunkSystemIoAffinity);
                if (!cores.isEmpty()) {
                    chunkIoWrapper = new AffinityRunnableWrapper("Chunk I/O", cores);
                    LOGGER.info("CPU affinity for chunk I/O: cores {}", OptimizationsConfig.CpuAffinity.chunkSystemIoAffinity);
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            LOGGER.warn("Failed to initialize CPU affinity. Make sure net.openhft:affinity is available.", e);
        }
    }

    public static Runnable wrapForTickRegion(Runnable run) {
        return tickRegionWrapper != null ? tickRegionWrapper.wrap(run) : run;
    }

    public static Runnable wrapForChunkWorker(Runnable run) {
        return chunkWorkerWrapper != null ? chunkWorkerWrapper.wrap(run) : run;
    }

    public static Runnable wrapForChunkIo(Runnable run) {
        return chunkIoWrapper != null ? chunkIoWrapper.wrap(run) : run;
    }

    private static BitSet parseCpuList(String cpuList) {
        BitSet bitSet = new BitSet();
        if (cpuList == null || cpuList.isEmpty()) return bitSet;
        for (String part : cpuList.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                int start = Integer.parseInt(range[0].trim());
                int end = Integer.parseInt(range[1].trim());
                for (int i = start; i <= end; i++) bitSet.set(i);
            } else {
                bitSet.set(Integer.parseInt(part));
            }
        }
        return bitSet;
    }
}

package zone.little.arbor.config.modules.function;

import abomination.LinearRegionFile;
import meow.bacteriawa.lightingluminol.config.FunctionConfig;
import zone.little.arbor.enums.EnumRegionFormat;
import zone.little.arbor.utils.BufferedLinearRegionFileFlusher;

public class RegionFormatConfig {
    public static EnumRegionFormat regionFormat = EnumRegionFormat.MCA;
    public static int linearCompressionLevel = 1;
    public static int linearIoThreadCount = 6;
    public static int linearIoFlushDelayMs = 100;
    public static int blinearIoFlushDelayMs = 3000;
    public static int blinearIoThreadCount = 6;
    public static boolean linearUseVirtualThread = true;

    public static BufferedLinearRegionFileFlusher blinearFlusher = null;

    public static void onLoaded() {
        // Sync from FunctionConfig
        linearCompressionLevel = FunctionConfig.RegionFormat.linearCompressionLevel;
        linearIoFlushDelayMs = FunctionConfig.RegionFormat.linearIoFlushDelayMs;
        blinearIoFlushDelayMs = FunctionConfig.RegionFormat.blinearIoFlushDelayMs;
        linearIoThreadCount = FunctionConfig.RegionFormat.linearIoThreadCount;
        blinearIoThreadCount = FunctionConfig.RegionFormat.blinearIoThreadCount;
        linearUseVirtualThread = FunctionConfig.RegionFormat.linearUseVirtualThread;

        String formatStr = FunctionConfig.RegionFormat.format.toUpperCase();
        switch (formatStr) {
            case "B_LINEAR":
                regionFormat = EnumRegionFormat.B_LINEAR;
                break;
            case "LINEAR_V2":
            case "LINEAR":
                regionFormat = EnumRegionFormat.LINEAR_V2;
                break;
            case "MCA":
            default:
                regionFormat = EnumRegionFormat.MCA;
                break;
        }

        if (regionFormat == EnumRegionFormat.LINEAR_V2) {
            checkCompressionLevel();
            LinearRegionFile.SAVE_DELAY_MS = linearIoFlushDelayMs;
            LinearRegionFile.SAVE_THREAD_MAX_COUNT = linearIoThreadCount;
            LinearRegionFile.USE_VIRTUAL_THREAD = linearUseVirtualThread;
        }

        if (regionFormat == EnumRegionFormat.B_LINEAR) {
            try {
                blinearFlusher = new BufferedLinearRegionFileFlusher(blinearIoThreadCount, 20, blinearIoFlushDelayMs);
                checkCompressionLevel();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> blinearFlusher.shutdown()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize B_LINEAR region format", e);
            }
        }
    }

    private static void checkCompressionLevel() {
        if (linearCompressionLevel > 23 || linearCompressionLevel < 1) {
            System.err.println("Linear or BufferedLinear region compression level should be between 1 and 22 in config: " + linearCompressionLevel);
            System.err.println("Falling back to compression level 1.");
            linearCompressionLevel = 1;
        }
    }
}
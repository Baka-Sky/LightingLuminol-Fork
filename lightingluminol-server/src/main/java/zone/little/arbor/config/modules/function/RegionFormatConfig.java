package zone.little.arbor.config.modules.function;

import abomination.LinearRegionFile;
import zone.little.arbor.enums.EnumRegionFormat;
import zone.little.arbor.utils.BufferedLinearRegionFileFlusher;
import net.minecraft.server.MinecraftServer;

/**
 * LightingLuminol - Region format config bridge.
 *
 * Delegates to meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat
 * so the Arbor-originated region format classes (which reference this class) work
 * without modification. Initialised by ConfigLoader after parsing the TOML file.
 */
public final class RegionFormatConfig {

    public static EnumRegionFormat regionFormat = EnumRegionFormat.MCA;
    public static int linearCompressionLevel = 1;
    public static int linearIoThreadCount = 6;
    public static int linearIoFlushDelayMs = 100;
    public static int blinearIoFlushDelayMs = 3000;
    public static int blinearIoThreadCount = 6;
    public static boolean linearUseVirtualThread = true;

    public static BufferedLinearRegionFileFlusher blinearFlusher = null;

    private RegionFormatConfig() {
    }

    /**
     * Called by ConfigLoader after FunctionConfig.RegionFormat fields are populated.
     * Translates the String format into the enum and applies runtime settings.
     */
    public static void syncFromFunctionConfig() {
        // Map the string config value to the enum
        String fmt = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.format;
        if (fmt != null) {
            switch (fmt.toUpperCase(java.util.Locale.ROOT)) {
                case "LINEAR_V2", "LINEAR" -> regionFormat = EnumRegionFormat.LINEAR_V2;
                case "B_LINEAR", "BLINEAR" -> regionFormat = EnumRegionFormat.B_LINEAR;
                default -> regionFormat = EnumRegionFormat.MCA;
            }
        }

        linearCompressionLevel = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.linearCompressionLevel;
        linearIoThreadCount = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.linearIoThreadCount;
        linearIoFlushDelayMs = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.linearIoFlushDelayMs;
        blinearIoFlushDelayMs = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.blinearIoFlushDelayMs;
        blinearIoThreadCount = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.blinearIoThreadCount;
        linearUseVirtualThread = meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.linearUseVirtualThread;

        if (regionFormat == EnumRegionFormat.LINEAR_V2) {
            checkCompressionLevel();
            LinearRegionFile.SAVE_DELAY_MS = linearIoFlushDelayMs;
            LinearRegionFile.SAVE_THREAD_MAX_COUNT = linearIoThreadCount;
            LinearRegionFile.USE_VIRTUAL_THREAD = linearUseVirtualThread;
        }

        if (regionFormat == EnumRegionFormat.B_LINEAR) {
            if (blinearFlusher == null) {
                blinearFlusher = new BufferedLinearRegionFileFlusher(blinearIoThreadCount, 20, blinearIoFlushDelayMs);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> blinearFlusher.shutdown()));
            }
            checkCompressionLevel();
        }
    }

    private static void checkCompressionLevel() {
        if (linearCompressionLevel > 23 || linearCompressionLevel < 1) {
            MinecraftServer.LOGGER.error("Linear or BufferedLinear region compression level should be between 1 and 22 in config: {}", linearCompressionLevel);
            MinecraftServer.LOGGER.error("Falling back to compression level 1.");
            linearCompressionLevel = 1;
            meow.bacteriawa.lightingluminol.config.FunctionConfig.RegionFormat.linearCompressionLevel = 1;
        }
    }
}

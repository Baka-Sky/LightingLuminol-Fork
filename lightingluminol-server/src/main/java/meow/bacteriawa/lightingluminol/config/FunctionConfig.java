package meow.bacteriawa.lightingluminol.config;

import java.util.List;

public final class FunctionConfig {
    public static class Regionbar {
        public static String format = "<gray>Util<yellow>:</yellow> <util> Chunks<yellow>:</yellow> <green><chunks></green> Players<yellow>:</yellow> <green><players></green> Entities<yellow>:</yellow> <green><entities></green>";
        public static boolean enabled = false;
        public static List<String> utilColorList = List.of("GREEN", "YELLOW", "RED", "PURPLE");
        public static String display = "BOSS_BAR";
        public static int updateIntervalTicks = 15;
    }

    public static class TripwireDupe {
        public static boolean enabled = true;
        public static String behaviorMode = "VANILLA21";
    }

    public static class PortalRateLimit {
        public static String maximumPortalTeleportsPerTickExpression = "50 * (1 + sqrt(e/1000) + c/200 + p/5)";
        public static boolean enable = true;
        public static int maximumPortalTeleportsPerTick = 200;
    }

    public static class Membar {
        public static String format = "<gray>Memory usage <yellow>:</yellow> <used>MB<yellow>/</yellow><available>MB";
        public static List<String> memoryColorList = List.of("GREEN", "YELLOW", "RED", "PURPLE");
        public static boolean enabled = false;
        public static String display = "BOSS_BAR";
        public static int updateIntervalTicks = 15;
    }

    public static class RegionFormat {
        public static int linearCompressionLevel = 1;
        public static int linearIoFlushDelayMs = 100;
        public static int blinearIoFlushDelayMs = 3000;
        public static int linearIoThreadCount = 6;
        public static int blinearIoThreadCount = 6;
        public static String format = "MCA";
        public static boolean linearUseVirtualThread = true;
    }

    public static class Tpsbar {
        public static List<String> pingColorList = List.of("GREEN", "YELLOW", "RED", "PURPLE");
        public static int precisionOfMsptValue = 2;
        public static int precisionOfTpsValue = 2;
        public static List<String> chunkhotColorList = List.of("GREEN", "YELLOW", "RED", "PURPLE");
        public static String display = "BOSS_BAR";
        public static String format = "<gray>TPS<yellow>:</yellow> <tps> MSPT<yellow>:</yellow> <mspt> Ping<yellow>:</yellow> <ping>ms ChunkHot<yellow>:</yellow> <chunkhot>";
        public static List<String> tpsColorList = List.of("GREEN", "YELLOW", "RED", "PURPLE");
        public static boolean enabled = false;
        public static int updateIntervalTicks = 15;
    }

    public static class SecureSeed {
        public static int version = 1;
        public static boolean enabled = false;
        public static String salt = "iq7baabmIDSxYEDskJEl8on3TkabfGrcgUGwXnik7vA=";
    }

    private FunctionConfig() {
    }
}
package meow.bacteriawa.lightingluminol.config;

import java.util.List;

public final class OptimizationsConfig {
    public static class CpuAffinity {
        public static boolean enabled = false;
        public static List<String> tickregionAffinity = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31");
    }

    public static class ThrottleGoalSelectorTickInInactiveTick {
        public static boolean enabled = false;
    }

    public static class UseSimd {
        public static boolean enabled = true;
    }

    public static class LobotomizeVillager {
        public static int checkInterval = 100;
        public static boolean waitUntilTradeLocked = false;
        public static boolean enabled = false;
    }

    public static class UseAsyncProtocolSwitching {
        public static boolean enabled = false;
    }

    public static class LithiumSleepingBlockEntity {
        public static boolean enabled = true;
    }

    public static class EndDragon {
        public static boolean optimizedDragonRespawn = false;
    }

    public static class VariableEntityWakingUp {
        public static double entityWakeupDurationRatioStandardDeviation = 0.2;
    }

    public static class Projectile {
        public static int maxLoadsPerProjectile = 0;
        public static int maxLoadsPerTick = 0;
    }

    public static class ReduceSensorWork {
        public static int delayTicks = 10;
        public static boolean enabled = true;
    }

    private OptimizationsConfig() {
    }
}
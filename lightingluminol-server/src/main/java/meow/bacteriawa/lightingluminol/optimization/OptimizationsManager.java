package meow.bacteriawa.lightingluminol.optimization;

import meow.bacteriawa.lightingluminol.config.OptimizationsConfig;

public class OptimizationsManager {
    public static void onServerStart() {
        setupCpuAffinity();
    }

    private static void setupCpuAffinity() {
        if (!OptimizationsConfig.CpuAffinity.enabled) {
            return;
        }

        try {
            java.util.List<String> affinityList = OptimizationsConfig.CpuAffinity.tickregionAffinity;
            if (affinityList.isEmpty()) {
                return;
            }

            String affinityStr = String.join(",", affinityList);
            System.setProperty("io.papermc.paper.threadedregions.tickregion.cpu.affinity", affinityStr);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(OptimizationsManager.class.getName())
                .warning("Failed to setup CPU affinity: " + e.getMessage());
        }
    }

    public static boolean shouldThrottleGoalSelector() {
        return OptimizationsConfig.ThrottleGoalSelectorTickInInactiveTick.enabled;
    }

    public static boolean useSimd() {
        return OptimizationsConfig.UseSimd.enabled;
    }

    public static boolean shouldLobotomizeVillager() {
        return OptimizationsConfig.LobotomizeVillager.enabled;
    }

    public static int getVillagerCheckInterval() {
        return OptimizationsConfig.LobotomizeVillager.checkInterval;
    }

    public static boolean shouldWaitUntilTradeLocked() {
        return OptimizationsConfig.LobotomizeVillager.waitUntilTradeLocked;
    }

    public static boolean useAsyncProtocolSwitching() {
        return OptimizationsConfig.UseAsyncProtocolSwitching.enabled;
    }

    public static boolean useLithiumSleepingBlockEntity() {
        return OptimizationsConfig.LithiumSleepingBlockEntity.enabled;
    }

    public static boolean useOptimizedDragonRespawn() {
        return OptimizationsConfig.EndDragon.optimizedDragonRespawn;
    }

    public static double getEntityWakeupDurationRatioStandardDeviation() {
        return OptimizationsConfig.VariableEntityWakingUp.entityWakeupDurationRatioStandardDeviation;
    }

    public static boolean shouldReduceSensorWork() {
        return OptimizationsConfig.ReduceSensorWork.enabled;
    }

    public static int getSensorDelayTicks() {
        return OptimizationsConfig.ReduceSensorWork.delayTicks;
    }

    public static int getProjectileMaxLoadsPerProjectile() {
        return OptimizationsConfig.Projectile.maxLoadsPerProjectile;
    }

    public static int getProjectileMaxLoadsPerTick() {
        return OptimizationsConfig.Projectile.maxLoadsPerTick;
    }
}
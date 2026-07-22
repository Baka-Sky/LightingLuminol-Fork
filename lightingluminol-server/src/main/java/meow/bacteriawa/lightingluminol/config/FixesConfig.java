package meow.bacteriawa.lightingluminol.config;

public final class FixesConfig {
    public static class PoiRangeFixes {
        public static boolean doNotCompetePoiIfUnloaded = false;
    }

    public static class AllowUnsafeTeleportation {
        public static boolean enabled = true;
    }

    public static class ForceCleanupDropNonOwnedEntityMemoryModule {
        public static boolean enabledForEntity = false;
        public static boolean enabledForPositionTracker = false;
        public static boolean enabledForBlockPos = false;
    }

    public static class CollisionBehavior {
        public static String mode = "VANILLA";
    }

    public static class PreventIncorrectTeleportAsyncCallsDuringMoveEvent {
        public static boolean throwWhenCaught = true;
        public static boolean enabled = false;
    }

    public static class ItemMultitask {
        public static boolean enabled = true;
    }

    public static class PathfindingFixes {
        public static boolean breakDownPathfindingWhenOutOfRegion = false;
        public static boolean doNotPathfindToNotOwnedTargets = false;
    }

    public static class FixHighVelocityIssue {
        public static boolean enabled = true;
        public static boolean warnOnDetected = false;
    }

    public static class UseVanillaRandomSource {
        public static boolean enabled = false;
    }

    public static class TpsSync {
        public static boolean enabled = true;
    }

    public static class FixEntityDupe {
        public static boolean enabled = true;
    }

    public static class PreventEntityExplosion {
        public static boolean enabled = false;
    }

    public static class PreventEndGatewayAsync {
        public static boolean enabled = true;
    }

    private FixesConfig() {
    }
}
package meow.bacteriawa.lightingluminol.config;

public final class MiscConfig {
    public static class DisableWarning {
        public static boolean disableHeightmapWarning = false;
        public static boolean disableOfflineModeWarning = false;
        public static boolean disableMovedWronglyThresholdWarning = false;
    }

    public static class FoliaWatchdog {
        public static long tickRegionTimeoutMs = 5000;
    }

    public static class UsernameChecks {
        public static boolean enabled = true;
    }

    public static class SavePortalTickets {
        public static boolean doSave = true;
    }

    public static class MojangOutOfOrderChatCheck {
        public static boolean enabled = true;
    }

    public static class ForceDisablePacketLimiterOfPaper {
        public static boolean forceDisable = false;
    }

    public static class VerifyPublicKeyOnlyInOnlineMode {
        public static boolean enabled = false;
    }

    private MiscConfig() {
    }
}
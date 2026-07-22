package meow.bacteriawa.lightingluminol.config;

public final class ExperimentConfig {
    public static class DisableEntityExceptionCatchers {
        public static boolean enabled = false;
    }

    public static class Command {
        public static boolean enableDataCommand = false;
        public static boolean enableCommandBlock = true;
        public static boolean enableWaypointsAndWaypointCommand = false;
        public static boolean enableTickCommand = true;
    }

    public static class DisableAsyncCatchers {
        public static boolean enabled = false;
    }

    private ExperimentConfig() {
    }
}
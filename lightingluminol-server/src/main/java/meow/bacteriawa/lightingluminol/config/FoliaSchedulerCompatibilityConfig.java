package meow.bacteriawa.lightingluminol.config;

import java.util.ArrayList;
import java.util.List;

public final class FoliaSchedulerCompatibilityConfig {
    public static boolean enabled = true;
    public static List<String> forceFoliaSchedulerPlugins = new ArrayList<>();
    public static List<String> forceBukkitSchedulerPlugins = new ArrayList<>();

    private FoliaSchedulerCompatibilityConfig() {
    }

    public static void load() {
        ConfigLoader.load();
    }
}
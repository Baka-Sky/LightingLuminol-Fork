package meow.bacteriawa.lightingluminol.core;

import io.papermc.paper.threadedregions.RegionizedServer;
import meow.bacteriawa.lightingluminol.config.ConfigLoader;
import meow.bacteriawa.lightingluminol.config.modules.unsupported.FoliaSchedulerCompatibilityConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;

import java.lang.reflect.Method;

public class FoliaSchedulerCompatibility {

    public static void initialize() {
        ConfigLoader.loadConfig(FoliaSchedulerCompatibilityConfig.class);
        try {
            Method registerToMethod = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("registerTo", Object.class);
        } catch (Exception e) {
        }
    }

    public static void tickBukkitScheduler() {
        if (FoliaSchedulerCompatibilityConfig.enabled) {
            try {
                ((CraftScheduler) Bukkit.getScheduler()).mainThreadHeartbeat();
            } catch (Exception e) {
            }
        }
    }

    public static boolean shouldUseBukkitScheduler(JavaPlugin plugin) {
        if (!FoliaSchedulerCompatibilityConfig.enabled) {
            return false;
        }
        String pluginName = plugin.getName();
        if (FoliaSchedulerCompatibilityConfig.forceFoliaSchedulerPlugins.contains(pluginName)) {
            return false;
        }
        if (FoliaSchedulerCompatibilityConfig.forceBukkitSchedulerPlugins.contains(pluginName)) {
            return true;
        }
        return !isFoliaSupported(plugin);
    }

    private static boolean isFoliaSupported(JavaPlugin plugin) {
        try {
            Class<?> pluginClass = plugin.getClass();
            java.io.InputStream is = pluginClass.getProtectionDomain().getCodeSource().getLocation().openStream();
            try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(is)) {
                java.util.jar.Manifest manifest = jis.getManifest();
                if (manifest != null) {
                    String foliaSupported = manifest.getMainAttributes().getValue("folia-supported");
                    return "true".equalsIgnoreCase(foliaSupported);
                }
            }
        } catch (Exception e) {
        }
        return false;
    }
}
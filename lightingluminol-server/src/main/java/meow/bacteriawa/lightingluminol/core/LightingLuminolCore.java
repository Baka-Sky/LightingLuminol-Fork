package meow.bacteriawa.lightingluminol.core;

import meow.bacteriawa.lightingluminol.function.MembarManager;
import meow.bacteriawa.lightingluminol.function.RegionbarManager;
import meow.bacteriawa.lightingluminol.function.TpsbarManager;
import meow.bacteriawa.lightingluminol.optimization.OptimizationsManager;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class LightingLuminolCore implements Listener {

    private static boolean initialized = false;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        OptimizationsManager.onServerStart();

        Bukkit.getPluginManager().registerEvents(new LightingLuminolCore(), null);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(null, task -> {
            RegionbarManager.onServerTick();
            MembarManager.onServerTick();
            TpsbarManager.onServerTick();
        }, 0L, 1L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        RegionbarManager.onPlayerJoin(player);
        MembarManager.onPlayerJoin(player);
        TpsbarManager.onPlayerJoin(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        RegionbarManager.onPlayerLeave(player);
        MembarManager.onPlayerLeave(player);
        TpsbarManager.onPlayerLeave(player);
    }
}
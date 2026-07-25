package meow.bacteriawa.lightingluminol.function;

import meow.bacteriawa.lightingluminol.config.FunctionConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MembarManager {
    private static final Map<ServerPlayer, ServerBossEvent> playerBossBars = new HashMap<>();
    private static int tickCounter = 0;

    public static void onServerTick() {
        if (!FunctionConfig.Membar.enabled) {
            return;
        }

        tickCounter++;
        if (tickCounter % FunctionConfig.Membar.updateIntervalTicks != 0) {
            return;
        }

        for (ServerPlayer player : playerBossBars.keySet()) {
            updateMembar(player);
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (!FunctionConfig.Membar.enabled) {
            return;
        }

        if (FunctionConfig.Membar.display.equals("BOSS_BAR")) {
            ServerBossEvent bossBar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.empty(),
                getColor(0.0),
                BossBarOverlay.PROGRESS
            );
            bossBar.addPlayer(player);
            playerBossBars.put(player, bossBar);
            updateMembar(player);
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        ServerBossEvent bossBar = playerBossBars.remove(player);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    private static void updateMembar(ServerPlayer player) {
        if (!FunctionConfig.Membar.enabled) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double usagePercent = maxMemory > 0 ? (double) usedMemory / maxMemory : 0.0;

        String format = FunctionConfig.Membar.format;
        format = format.replace("<used>", String.valueOf(usedMemory / 1024 / 1024));
        format = format.replace("<available>", String.valueOf(maxMemory / 1024 / 1024));

        Component title = Component.literal(format);

        ServerBossEvent bossBar = playerBossBars.get(player);
        if (bossBar != null) {
            bossBar.setName(title);
            bossBar.setColor(getColor(usagePercent));
            bossBar.setProgress((float) Math.min(1.0, usagePercent));
        }
    }

    private static BossBarColor getColor(double usage) {
        java.util.List<String> colors = FunctionConfig.Membar.memoryColorList;
        if (usage < 0.5 && colors.size() > 0) {
            return parseColor(colors.get(0));
        } else if (usage < 0.75 && colors.size() > 1) {
            return parseColor(colors.get(1));
        } else if (usage < 0.9 && colors.size() > 2) {
            return parseColor(colors.get(2));
        } else if (colors.size() > 3) {
            return parseColor(colors.get(3));
        }
        return BossBarColor.WHITE;
    }

    private static BossBarColor parseColor(String color) {
        try {
            return BossBarColor.valueOf(color.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBarColor.WHITE;
        }
    }
}
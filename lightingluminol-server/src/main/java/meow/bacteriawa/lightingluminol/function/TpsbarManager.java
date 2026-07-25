package meow.bacteriawa.lightingluminol.function;

import meow.bacteriawa.lightingluminol.config.FunctionConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TpsbarManager {
    private static final Map<ServerPlayer, ServerBossEvent> playerBossBars = new HashMap<>();
    private static int tickCounter = 0;

    public static void onServerTick() {
        if (!FunctionConfig.Tpsbar.enabled) {
            return;
        }

        tickCounter++;
        if (tickCounter % FunctionConfig.Tpsbar.updateIntervalTicks != 0) {
            return;
        }

        for (ServerPlayer player : playerBossBars.keySet()) {
            updateTpsbar(player);
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (!FunctionConfig.Tpsbar.enabled) {
            return;
        }

        if (FunctionConfig.Tpsbar.display.equals("BOSS_BAR")) {
            ServerBossEvent bossBar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.empty(),
                getTpsColor(20.0),
                BossBarOverlay.PROGRESS
            );
            bossBar.addPlayer(player);
            playerBossBars.put(player, bossBar);
            updateTpsbar(player);
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        ServerBossEvent bossBar = playerBossBars.remove(player);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    private static void updateTpsbar(ServerPlayer player) {
        if (!FunctionConfig.Tpsbar.enabled) {
            return;
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        ServerTickRateManager tickRateManager = server.tickRateManager();
        double tps = tickRateManager.tickrate();
        double mspt = tickRateManager.millisecondsPerTick();
        int ping = player.getBukkitEntity().getPing();

        String format = FunctionConfig.Tpsbar.format;
        format = format.replace("<tps>", String.format("%." + FunctionConfig.Tpsbar.precisionOfTpsValue + "f", tps));
        format = format.replace("<mspt>", String.format("%." + FunctionConfig.Tpsbar.precisionOfMsptValue + "f", mspt));
        format = format.replace("<ping>", String.valueOf(ping));

        Component title = Component.literal(format);

        ServerBossEvent bossBar = playerBossBars.get(player);
        if (bossBar != null) {
            bossBar.setName(title);
            bossBar.setColor(getTpsColor(tps));
            bossBar.setProgress((float) Math.min(1.0, tps / 20.0));
        }
    }

    private static BossBarColor getTpsColor(double tps) {
        java.util.List<String> colors = FunctionConfig.Tpsbar.tpsColorList;
        if (tps >= 18.0 && colors.size() > 0) {
            return parseColor(colors.get(0));
        } else if (tps >= 15.0 && colors.size() > 1) {
            return parseColor(colors.get(1));
        } else if (tps >= 10.0 && colors.size() > 2) {
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
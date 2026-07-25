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

public class RegionbarManager {
    private static final Map<ServerPlayer, ServerBossEvent> playerBossBars = new HashMap<>();
    private static int tickCounter = 0;

    public static void onServerTick() {
        if (!FunctionConfig.Regionbar.enabled) {
            return;
        }

        tickCounter++;
        if (tickCounter % FunctionConfig.Regionbar.updateIntervalTicks != 0) {
            return;
        }

        for (ServerPlayer player : playerBossBars.keySet()) {
            updateRegionbar(player);
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (!FunctionConfig.Regionbar.enabled) {
            return;
        }

        if (FunctionConfig.Regionbar.display.equals("BOSS_BAR")) {
            ServerBossEvent bossBar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.empty(),
                getColor(0.0),
                BossBarOverlay.PROGRESS
            );
            bossBar.addPlayer(player);
            playerBossBars.put(player, bossBar);
            updateRegionbar(player);
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        ServerBossEvent bossBar = playerBossBars.remove(player);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    private static void updateRegionbar(ServerPlayer player) {
        if (!FunctionConfig.Regionbar.enabled) {
            return;
        }

        double util = 0.5;
        int chunks = 0;
        int entities = 0;
        int players = 0;

        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            var region = serverLevel.regioniser.getRegionAtUnsynchronised(player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
            if (region != null) {
                var data = region.getData();
                if (data != null) {
                    chunks = data.getRegionStats().getChunkCount();
                    entities = data.getRegionStats().getEntityCount();
                    players = data.getRegionStats().getPlayerCount();
                    util = Math.min(1.0, (double) entities / 100.0);
                }
            }
        }

        String format = FunctionConfig.Regionbar.format;
        format = format.replace("<util>", String.format("%.1f%%", util * 100));
        format = format.replace("<chunks>", String.valueOf(chunks));
        format = format.replace("<players>", String.valueOf(players));
        format = format.replace("<entities>", String.valueOf(entities));

        Component title = Component.literal(format);

        ServerBossEvent bossBar = playerBossBars.get(player);
        if (bossBar != null) {
            bossBar.setName(title);
            bossBar.setColor(getColor(util));
            bossBar.setProgress((float) util);
        }
    }

    private static BossBarColor getColor(double util) {
        java.util.List<String> colors = FunctionConfig.Regionbar.utilColorList;
        if (util < 0.3 && colors.size() > 0) {
            return parseColor(colors.get(0));
        } else if (util < 0.6 && colors.size() > 1) {
            return parseColor(colors.get(1));
        } else if (util < 0.85 && colors.size() > 2) {
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
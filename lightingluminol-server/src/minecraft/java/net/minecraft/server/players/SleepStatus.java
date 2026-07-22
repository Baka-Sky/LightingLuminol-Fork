package net.minecraft.server.players;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public class SleepStatus {
    private int activePlayers;
    private int sleepingPlayers;

    public boolean areEnoughSleeping(final int sleepPercentageNeeded) {
        return this.sleepingPlayers >= this.sleepersNeeded(sleepPercentageNeeded);
    }

    public boolean areEnoughDeepSleeping(final int sleepPercentageNeeded, final List<ServerPlayer> players) {
        // CraftBukkit start
        int deepSleepers = (int)players.stream().filter(player -> player.isSleepingLongEnough() || player.fauxSleeping).count();
        boolean anyDeepSleep = players.stream().anyMatch(Player::isSleepingLongEnough);
        return anyDeepSleep && deepSleepers >= this.sleepersNeeded(sleepPercentageNeeded);
        // CraftBukkit end
    }

    public int sleepersNeeded(final int sleepPercentageNeeded) {
        return Math.max(1, Mth.ceil(this.activePlayers * sleepPercentageNeeded / 100.0F));
    }

    public void removeAllSleepers() {
        this.sleepingPlayers = 0;
    }

    public int amountSleeping() {
        return this.sleepingPlayers;
    }

    public boolean update(final List<ServerPlayer> players) {
        int oldActivePlayers = this.activePlayers;
        int oldSleepingPlayers = this.sleepingPlayers;
        this.activePlayers = 0;
        this.sleepingPlayers = 0;
        boolean anySleep = false; // CraftBukkit

        for (ServerPlayer player : players) {
            if (!player.isSpectator()) {
                this.activePlayers++;
                if (player.isSleeping() || player.fauxSleeping) { // CraftBukkit
                    this.sleepingPlayers++;
                }
                // CraftBukkit start
                if (player.isSleeping()) {
                    anySleep = true;
                }
                // CraftBukkit end
            }
        }

        return anySleep && (oldSleepingPlayers > 0 || this.sleepingPlayers > 0) && (oldActivePlayers != this.activePlayers || oldSleepingPlayers != this.sleepingPlayers); // CraftBukkit
    }
}

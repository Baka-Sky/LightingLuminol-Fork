package net.minecraft.server.waypoints;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.collect.Sets.SetView;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.waypoints.WaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;

public class ServerWaypointManager implements WaypointManager<WaypointTransmitter> {
    private final Set<WaypointTransmitter> waypoints = new HashSet<>();
    private final Set<ServerPlayer> players = new HashSet<>();
    private final Table<ServerPlayer, WaypointTransmitter, WaypointTransmitter.Connection> connections = HashBasedTable.create();
    // Paper start - optimize ServerWaypointManager with locator bar disabled
    public boolean locatorBarEnabled; // Avoid a gamerule lookup for each player

    public ServerWaypointManager(net.minecraft.server.level.ServerLevel serverLevel) {
        this.locatorBarEnabled = serverLevel.getGameRules().get(GameRules.LOCATOR_BAR);
    }
    // Paper end - optimize ServerWaypointManager with locator bar disabled

    @Override
    public void trackWaypoint(final WaypointTransmitter waypoint) {
        // Folia - region threading
    }

    @Override
    public void updateWaypoint(final WaypointTransmitter waypoint) {
        if (!this.locatorBarEnabled) return; // Paper - optimize ServerWaypointManager with locator bar disabled
        if (this.waypoints.contains(waypoint)) {
            Map<ServerPlayer, WaypointTransmitter.Connection> playerConnection = Tables.transpose(this.connections).row(waypoint);
            SetView<ServerPlayer> potentialPlayers = Sets.difference(this.players, playerConnection.keySet());

            for (Entry<ServerPlayer, WaypointTransmitter.Connection> waypointConnection : ImmutableSet.copyOf(playerConnection.entrySet())) {
                this.updateConnection(waypointConnection.getKey(), waypoint, waypointConnection.getValue());
            }

            for (ServerPlayer player : potentialPlayers) {
                this.createConnection(player, waypoint);
            }
        }
    }

    @Override
    public void untrackWaypoint(final WaypointTransmitter waypoint) {
        this.connections.column(waypoint).forEach((player, connection) -> connection.disconnect());
        Tables.transpose(this.connections).row(waypoint).clear();
        this.waypoints.remove(waypoint);
    }

    public void addPlayer(final ServerPlayer player) {
        // Folia - region threading
    }

    public void updatePlayer(final ServerPlayer player) {
        // Folia - region threading
    }

    public void removePlayer(final ServerPlayer player) {
        // Folia - region threading
    }

    public void breakAllConnections() {
        this.connections.values().forEach(WaypointTransmitter.Connection::disconnect);
        this.connections.clear();
    }

    public void remakeConnections(final WaypointTransmitter waypoint) {
        if (!this.locatorBarEnabled) return; // Paper - optimize ServerWaypointManager with locator bar disabled
        for (ServerPlayer player : this.players) {
            this.createConnection(player, waypoint);
        }
    }

    public Set<WaypointTransmitter> transmitters() {
        return this.waypoints;
    }

    // Paper start - optimize ServerWaypointManager with locator bar disabled
    // Avoid a gamerule lookup for each player
    private boolean isLocatorBarEnabledFor(final ServerPlayer player) {
        return locatorBarEnabled;
    // Paper end - optimize ServerWaypointManager with locator bar disabled
    }

    private void createConnection(final ServerPlayer player, final WaypointTransmitter waypoint) {
        if (player != waypoint) {
            if (isLocatorBarEnabledFor(player)) {
                waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(connection -> {
                    this.connections.put(player, waypoint, connection);
                    connection.connect();
                }, () -> {
                    WaypointTransmitter.Connection connection = this.connections.remove(player, waypoint);
                    if (connection != null) {
                        connection.disconnect();
                    }
                });
            }
        }
    }

    private void updateConnection(final ServerPlayer player, final WaypointTransmitter waypoint, final WaypointTransmitter.Connection connection) {
        if (player != waypoint) {
            if (isLocatorBarEnabledFor(player)) {
                if (!connection.isBroken()) {
                    connection.update();
                } else {
                    waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(newConnection -> {
                        newConnection.connect();
                        this.connections.put(player, waypoint, newConnection);
                    }, () -> {
                        connection.disconnect();
                        this.connections.remove(player, waypoint);
                    });
                }
            }
        }
    }
}

package net.minecraft.server.jsonrpc.internalapi;

import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.jsonrpc.JsonRpcLogger;
import net.minecraft.server.jsonrpc.methods.ClientInfo;
import net.minecraft.server.jsonrpc.methods.GameRulesService;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

public class MinecraftGameRuleServiceImpl implements MinecraftGameRuleService {
    private final NotificationManager notificationManager;
    private final JsonRpcLogger jsonrpcLogger;
    private @org.jspecify.annotations.Nullable GameRules gameRules; // Paper - per-world game rules

    public MinecraftGameRuleServiceImpl(final NotificationManager notificationManager, final JsonRpcLogger jsonrpcLogger) {
        this.notificationManager = notificationManager;
        this.jsonrpcLogger = jsonrpcLogger;
        this.gameRules = null; // Paper - per-world game rules - cannot get game rules until server is started
    }

    // Paper start - per-world game rules
    public GameRules getGameRules() {
        if (this.gameRules == null) {
            this.gameRules = this.server().overworld().getGameRules();
        }
        return this.gameRules;
    }
    // Paper end

    private DedicatedServer server() {
        return Objects.requireNonNull(this.notificationManager.server());
    }

    @Override
    public <T> GameRulesService.GameRuleUpdate<T> updateGameRule(final GameRulesService.GameRuleUpdate<T> update, final ClientInfo clientInfo) {
        GameRule<T> gameRule = update.gameRule();
        MinecraftServer server = this.server();
        GameRules gameRules = this.getGameRules(); // Paper - per-world game rules
        T oldValue = gameRules.get(gameRule);
        T newValue = update.value();
        gameRules.set(gameRule, newValue, this.server().overworld()); // Paper - per-world game rules - use overworld for vanilla protocol
        this.jsonrpcLogger
            .log(clientInfo, "Game rule '{}' updated from '{}' to '{}'", gameRule.id(), gameRule.serialize(oldValue), gameRule.serialize(newValue));
        return update;
    }

    @Override
    public <T> GameRulesService.GameRuleUpdate<T> getTypedRule(final GameRule<T> gameRule, final T value) {
        return new GameRulesService.GameRuleUpdate<>(gameRule, value);
    }

    @Override
    public Stream<GameRule<?>> getAvailableGameRules() {
        return this.getGameRules().availableRules(); // Paper - per-world game rules
    }

    @Override
    public <T> T getRuleValue(final GameRule<T> gameRule) {
        return this.getGameRules().get(gameRule); // Paper - per-world game rules
    }
}

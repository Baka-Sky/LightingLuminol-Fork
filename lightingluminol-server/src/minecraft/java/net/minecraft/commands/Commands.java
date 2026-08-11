package net.minecraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.ArgumentUtils;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.gametest.framework.TestCommand;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.AdvancementCommands;
import net.minecraft.server.commands.AttributeCommand;
import net.minecraft.server.commands.BanIpCommands;
import net.minecraft.server.commands.BanListCommands;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.commands.BossBarCommands;
import net.minecraft.server.commands.ChaseCommand;
import net.minecraft.server.commands.ClearInventoryCommands;
import net.minecraft.server.commands.CloneCommands;
import net.minecraft.server.commands.DamageCommand;
import net.minecraft.server.commands.DataPackCommand;
import net.minecraft.server.commands.DeOpCommands;
import net.minecraft.server.commands.DebugCommand;
import net.minecraft.server.commands.DebugConfigCommand;
import net.minecraft.server.commands.DebugMobSpawningCommand;
import net.minecraft.server.commands.DebugPathCommand;
import net.minecraft.server.commands.DefaultGameModeCommands;
import net.minecraft.server.commands.DialogCommand;
import net.minecraft.server.commands.DifficultyCommand;
import net.minecraft.server.commands.EffectCommands;
import net.minecraft.server.commands.EmoteCommands;
import net.minecraft.server.commands.EnchantCommand;
import net.minecraft.server.commands.ExecuteCommand;
import net.minecraft.server.commands.ExperienceCommand;
import net.minecraft.server.commands.FetchProfileCommand;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.server.commands.ForceLoadCommand;
import net.minecraft.server.commands.FunctionCommand;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.commands.GameRuleCommand;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.commands.HelpCommand;
import net.minecraft.server.commands.ItemCommands;
import net.minecraft.server.commands.JfrCommand;
import net.minecraft.server.commands.KickCommand;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.commands.LootCommand;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.commands.PardonCommand;
import net.minecraft.server.commands.PardonIpCommand;
import net.minecraft.server.commands.ParticleCommand;
import net.minecraft.server.commands.PerfCommand;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.commands.PlaySoundCommand;
import net.minecraft.server.commands.PublishCommand;
import net.minecraft.server.commands.RaidCommand;
import net.minecraft.server.commands.RandomCommand;
import net.minecraft.server.commands.RecipeCommand;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.commands.ReturnCommand;
import net.minecraft.server.commands.RideCommand;
import net.minecraft.server.commands.RotateCommand;
import net.minecraft.server.commands.SaveAllCommand;
import net.minecraft.server.commands.SaveOffCommand;
import net.minecraft.server.commands.SaveOnCommand;
import net.minecraft.server.commands.SayCommand;
import net.minecraft.server.commands.ScheduleCommand;
import net.minecraft.server.commands.ScoreboardCommand;
import net.minecraft.server.commands.SeedCommand;
import net.minecraft.server.commands.ServerPackCommand;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.server.commands.SetPlayerIdleTimeoutCommand;
import net.minecraft.server.commands.SetSpawnCommand;
import net.minecraft.server.commands.SetWorldSpawnCommand;
import net.minecraft.server.commands.SpawnArmorTrimsCommand;
import net.minecraft.server.commands.SpectateCommand;
import net.minecraft.server.commands.SpreadPlayersCommand;
import net.minecraft.server.commands.StopCommand;
import net.minecraft.server.commands.StopSoundCommand;
import net.minecraft.server.commands.StopwatchCommand;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.commands.SwingCommand;
import net.minecraft.server.commands.TagCommand;
import net.minecraft.server.commands.TeamCommand;
import net.minecraft.server.commands.TeamMsgCommand;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.commands.TellRawCommand;
import net.minecraft.server.commands.TickCommand;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.server.commands.TitleCommand;
import net.minecraft.server.commands.TransferCommand;
import net.minecraft.server.commands.TriggerCommand;
import net.minecraft.server.commands.UnpublishCommand;
import net.minecraft.server.commands.VersionCommand;
import net.minecraft.server.commands.WardenSpawnTrackerCommand;
import net.minecraft.server.commands.WaypointCommand;
import net.minecraft.server.commands.WeatherCommand;
import net.minecraft.server.commands.WhitelistCommand;
import net.minecraft.server.commands.WorldBorderCommand;
import net.minecraft.server.commands.data.DataCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionProviderCheck;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.PermissionSetSupplier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class Commands {
    public interface RestrictedMarker { } // Paper - restricted api
    public static final String COMMAND_PREFIX = "/";
    private static final ThreadLocal<@Nullable ExecutionContext<CommandSourceStack>> CURRENT_EXECUTION_CONTEXT = new ThreadLocal<>();
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PermissionCheck LEVEL_ALL = PermissionCheck.AlwaysPass.INSTANCE;
    public static final PermissionCheck LEVEL_MODERATORS = new PermissionCheck.Require(Permissions.COMMANDS_MODERATOR);
    public static final PermissionCheck LEVEL_GAMEMASTERS = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);
    public static final PermissionCheck LEVEL_ADMINS = new PermissionCheck.Require(Permissions.COMMANDS_ADMIN);
    public static final PermissionCheck LEVEL_OWNERS = new PermissionCheck.Require(Permissions.COMMANDS_OWNER);
    private static final ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR = new ClientboundCommandsPacket.NodeInspector<CommandSourceStack>() {
        private final CommandSourceStack noPermissionSource = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        @Override
        public @Nullable Identifier suggestionId(final ArgumentCommandNode<CommandSourceStack, ?> node) {
            SuggestionProvider<CommandSourceStack> suggestionProvider = node.getCustomSuggestions();
            return suggestionProvider != null ? SuggestionProviders.getName(suggestionProvider) : null;
        }

        @Override
        public boolean isExecutable(final CommandNode<CommandSourceStack> node) {
            return node.getCommand() != null;
        }

        @Override
        public boolean isRestricted(final CommandNode<CommandSourceStack> node) {
            if (node.getRequirement() instanceof RestrictedMarker) return true; // Paper - restricted api
            Predicate<CommandSourceStack> requirement = node.getRequirement();
            return !requirement.test(this.noPermissionSource);
        }
    };
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

    public Commands(final Commands.CommandSelection commandSelection, final CommandBuildContext context) {
    // Paper start - Brigadier API - modern minecraft overloads that do not use redirects but are copies instead
        this(commandSelection, context, false);
    }
    public Commands(final Commands.CommandSelection commandSelection, final CommandBuildContext context, final boolean modern) {
    // Paper end - Brigadier API - modern minecraft overloads that do not use redirects but are copies instead
        AdvancementCommands.register(this.dispatcher);
        AttributeCommand.register(this.dispatcher, context);
        ExecuteCommand.register(this.dispatcher, context);
        BossBarCommands.register(this.dispatcher, context); // LightingLuminol - re-enable /bossbar command
        ClearInventoryCommands.register(this.dispatcher, context);
        CloneCommands.register(this.dispatcher, context); // LightingLuminol - re-enable /clone command
        DamageCommand.register(this.dispatcher, context);
        DataCommands.register(this.dispatcher); // LightingLuminol - re-enable /data command (vanilla datapack support)
        DataPackCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /datapack command
        DebugCommand.register(this.dispatcher); // LightingLuminol - re-enable /debug command
        DefaultGameModeCommands.register(this.dispatcher);
        DialogCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /dialog command
        DifficultyCommand.register(this.dispatcher);
        EffectCommands.register(this.dispatcher, context);
        EmoteCommands.register(this.dispatcher);
        EnchantCommand.register(this.dispatcher, context);
        ExperienceCommand.register(this.dispatcher);
        FillCommand.register(this.dispatcher, context);
        FillBiomeCommand.register(this.dispatcher, context);
        ForceLoadCommand.register(this.dispatcher);
        FunctionCommand.register(this.dispatcher); // LightingLuminol - re-enable /function command (vanilla datapack support; depends on DataCommands)
        GameModeCommand.register(this.dispatcher);
        GameRuleCommand.register(this.dispatcher, context);
        GiveCommand.register(this.dispatcher, context);
        HelpCommand.register(this.dispatcher);
        ItemCommands.register(this.dispatcher, context); // LightingLuminol - re-enable /item command (note: block targets have Folia thread-safety risk)
        KickCommand.register(this.dispatcher);
        KillCommand.register(this.dispatcher);
        ListPlayersCommand.register(this.dispatcher);
        LocateCommand.register(this.dispatcher, context);
        LootCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /loot command (note: block targets have Folia thread-safety risk)
        MsgCommand.register(this.dispatcher);
        SwingCommand.register(this.dispatcher);
        ParticleCommand.register(this.dispatcher, context);
        PlaceCommand.register(this.dispatcher);
        PlaySoundCommand.register(this.dispatcher);
        RandomCommand.register(this.dispatcher);
        ReloadCommand.register(this.dispatcher); // LightingLuminol - re-enable /reload command for datapack support
        RecipeCommand.register(this.dispatcher);
        FetchProfileCommand.register(this.dispatcher);
        ReturnCommand.register(this.dispatcher); // LightingLuminol - re-enable /return command
        RideCommand.register(this.dispatcher);
        RotateCommand.register(this.dispatcher);
        SayCommand.register(this.dispatcher);
        ScheduleCommand.register(this.dispatcher); // LightingLuminol - re-enable /schedule command (per-world TimerQueue, Folia-safe)
        ScoreboardCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /scoreboard command (server-level data, Folia-safe)
        SeedCommand.register(this.dispatcher, commandSelection != Commands.CommandSelection.INTEGRATED);
        VersionCommand.register(this.dispatcher, commandSelection != Commands.CommandSelection.INTEGRATED);
        SetBlockCommand.register(this.dispatcher, context);
        SetSpawnCommand.register(this.dispatcher);
        SetWorldSpawnCommand.register(this.dispatcher);
        SpectateCommand.register(this.dispatcher); // LightingLuminol - re-enable /spectate command
        SpreadPlayersCommand.register(this.dispatcher); // LightingLuminol - re-enable /spreadplayers command
        StopSoundCommand.register(this.dispatcher);
        StopwatchCommand.register(this.dispatcher);
        SummonCommand.register(this.dispatcher, context);
        TagCommand.register(this.dispatcher); // LightingLuminol - re-enable /tag command
        TeamCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /team command
        TeamMsgCommand.register(this.dispatcher); // LightingLuminol - re-enable /teammsg command
        TeleportCommand.register(this.dispatcher);
        TellRawCommand.register(this.dispatcher, context);
        TestCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /test command
        TickCommand.register(this.dispatcher); // LightingLuminol - re-enable /tick command (note: limited effect under Folia region threading)
        TimeCommand.register(this.dispatcher, context);
        TitleCommand.register(this.dispatcher, context);
        TriggerCommand.register(this.dispatcher); // LightingLuminol - re-enable /trigger command
        WaypointCommand.register(this.dispatcher, context); // LightingLuminol - re-enable /waypoint command
        WeatherCommand.register(this.dispatcher);
        WorldBorderCommand.register(this.dispatcher);
        if (JvmProfiler.INSTANCE.isAvailable()) {
            JfrCommand.register(this.dispatcher);
        }

        if (SharedConstants.DEBUG_CHASE_COMMAND) {
            ChaseCommand.register(this.dispatcher);
        }

        if (SharedConstants.DEBUG_DEV_COMMANDS || SharedConstants.IS_RUNNING_IN_IDE) {
            RaidCommand.register(this.dispatcher, context);
            DebugPathCommand.register(this.dispatcher);
            DebugMobSpawningCommand.register(this.dispatcher);
            WardenSpawnTrackerCommand.register(this.dispatcher);
            SpawnArmorTrimsCommand.register(this.dispatcher);
            ServerPackCommand.register(this.dispatcher);
            if (commandSelection.includeDedicated) {
                DebugConfigCommand.register(this.dispatcher, context);
            }
        }

        if (commandSelection.includeDedicated) {
            BanIpCommands.register(this.dispatcher);
            BanListCommands.register(this.dispatcher);
            BanPlayerCommands.register(this.dispatcher);
            DeOpCommands.register(this.dispatcher);
            OpCommand.register(this.dispatcher);
            PardonCommand.register(this.dispatcher);
            PardonIpCommand.register(this.dispatcher);
            PerfCommand.register(this.dispatcher); // LightingLuminol - re-enable /perf command
            SaveAllCommand.register(this.dispatcher); // LightingLuminol - re-enable /save-all command
            SaveOffCommand.register(this.dispatcher);
            SaveOnCommand.register(this.dispatcher);
            SetPlayerIdleTimeoutCommand.register(this.dispatcher);
            StopCommand.register(this.dispatcher);
            TransferCommand.register(this.dispatcher);
            WhitelistCommand.register(this.dispatcher);
        }

        if (commandSelection.includeIntegrated) {
            PublishCommand.register(this.dispatcher);
            UnpublishCommand.register(this.dispatcher);
        }

        // Paper start - Vanilla command permission fixes
        for (final CommandNode<CommandSourceStack> node : this.dispatcher.getRoot().getChildren()) {
            if (node.getRequirement() == com.mojang.brigadier.builder.ArgumentBuilder.<CommandSourceStack>defaultRequirement()) {
                node.requirement = stack -> stack.source == CommandSource.NULL || stack.getBukkitSender().hasPermission(org.bukkit.craftbukkit.command.VanillaCommandWrapper.getPermission(node));
            } else if (node.getRequirement() instanceof PermissionProviderCheck<CommandSourceStack> check) {
                check.vanillaNode().set(node);
            }
        }
        // Paper end - Vanilla command permission fixes
        // Paper start - Brigadier Command API
        // Create legacy minecraft namespace commands
        for (final CommandNode<CommandSourceStack> node : new java.util.ArrayList<>(this.dispatcher.getRoot().getChildren())) {
            if (modern) {
                // Modern behaviour that simply creates a full copy of the commands node.
                // Avoids plenty of issues around registering redirects *to* these nodes from the API
                this.dispatcher.getRoot().addChild(
                    io.papermc.paper.command.brigadier.PaperBrigadier.copyLiteral(
                        "minecraft:" + node.getName(),
                        (com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack>) node
                    )
                );
                continue;
            }

            // Legacy behaviour of creating a flattened redirecting node.
            // Used by CommandArgumentUpgrader
            CommandNode<CommandSourceStack> flattenedAliasTarget = node;
            while (flattenedAliasTarget.getRedirect() != null) flattenedAliasTarget = flattenedAliasTarget.getRedirect();

            this.dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("minecraft:" + node.getName())
                    .executes(flattenedAliasTarget.getCommand())
                    .requires(flattenedAliasTarget.getRequirement())
                    .redirect(flattenedAliasTarget));
        }
        // Paper end - Brigadier Command API
        this.dispatcher.setConsumer(ExecutionCommandSource.resultConsumer());
    }

    public static <S> ParseResults<S> mapSource(final ParseResults<S> parse, final UnaryOperator<S> sourceOperator) {
        CommandContextBuilder<S> context = parse.getContext();
        CommandContextBuilder<S> source = context.withSource(sourceOperator.apply(context.getSource()));
        return new ParseResults<>(source, parse.getReader(), parse.getExceptions());
    }

    public void performPrefixedCommand(final CommandSourceStack sender, String command) {
        command = trimOptionalPrefix(command);
        this.performCommand(this.dispatcher.parse(command, sender), command);
    }

    public static String trimOptionalPrefix(final String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString) {
        // Paper start
        this.performCommand(command, commandString, false);
    }

    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString, final boolean throwCommandError) {
        org.spigotmc.AsyncCatcher.catchOp("Cannot perform command async");
        // Paper end
        CommandSourceStack sender = command.getContext().getSource();
        Profiler.get().push(() -> "/" + commandString);
        ContextChain<CommandSourceStack> commandChain = finishParsing(command, commandString, sender);

        try {
            if (commandChain != null) {
                executeCommandInContext(
                    sender,
                    executionContext -> ExecutionContext.queueInitialCommandExecution(
                        executionContext, commandString, commandChain, sender, CommandResultCallback.EMPTY
                    )
                );
            }
            // Paper start
        } catch (Throwable e) { // always gracefully handle it, no matter how bad:tm:
            if (throwCommandError) throw e; // rethrow directly if requested
            MutableComponent hover = Component.literal(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            LOGGER.error("Command exception: /{}", commandString, e); // Paper - always show execution exception in console log
            if (sender.getServer().isDebugging() || LOGGER.isDebugEnabled()) { // Paper - Debugging
                StackTraceElement[] stackTrace = e.getStackTrace();

                for (int i = 0; i < Math.min(stackTrace.length, 3); i++) {
                    hover.append("\n\n")
                        .append(stackTrace[i].getMethodName())
                        .append("\n ")
                        .append(stackTrace[i].getFileName())
                        .append(":")
                        .append(String.valueOf(stackTrace[i].getLineNumber()));
                }
            }

            sender.sendFailure(Component.translatable("command.failed").withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover))));
            if (SharedConstants.DEBUG_VERBOSE_COMMAND_ERRORS || SharedConstants.IS_RUNNING_IN_IDE) {
                sender.sendFailure(Component.literal(Util.describeError(e)));
                LOGGER.error("'/{}' threw an exception", commandString, e);
            }
        } finally {
            Profiler.get().pop();
        }
    }

    private static @Nullable ContextChain<CommandSourceStack> finishParsing(
        final ParseResults<CommandSourceStack> command, final String commandString, final CommandSourceStack sender
    ) {
        try {
            validateParseResults(command);
            return ContextChain.tryFlatten(command.getContext().build(commandString))
                .orElseThrow(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(command.getReader()));
        } catch (CommandSyntaxException e) {
            // Paper start - Add UnknownCommandEvent
            final net.kyori.adventure.text.TextComponent.Builder builder = net.kyori.adventure.text.Component.text();
            // sender.sendFailure(ComponentUtils.fromMessage(var7.getRawMessage()));
            builder.color(net.kyori.adventure.text.format.NamedTextColor.RED).append(io.papermc.paper.command.brigadier.MessageComponentSerializer.message().deserialize(e.getRawMessage()));
            // Paper end - Add UnknownCommandEvent
            if (e.getInput() != null && e.getCursor() >= 0) {
                int cursor = Math.min(e.getInput().length(), e.getCursor());
                MutableComponent context = Component.empty()
                    .withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand("/" + commandString)));
                if (cursor > 10) {
                    context.append(CommonComponents.ELLIPSIS);
                }

                context.append(e.getInput().substring(Math.max(0, cursor - 10), cursor));
                if (cursor < e.getInput().length()) {
                    Component remaining = Component.literal(e.getInput().substring(cursor)).withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE);
                    context.append(remaining);
                }

                context.append(Component.translatable("command.context.here").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
                // Paper start - Add UnknownCommandEvent
                // sender.sendFailure(context);
                builder
                    .append(net.kyori.adventure.text.Component.newline())
                    .append(io.papermc.paper.adventure.PaperAdventure.asAdventure(context));
            }
            org.bukkit.event.command.UnknownCommandEvent event = new org.bukkit.event.command.UnknownCommandEvent(sender, commandString, org.spigotmc.SpigotConfig.unknownCommandMessage.isEmpty() ? null : builder.build());
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(event);
            if (event.message() != null) {
                sender.sendFailure(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.message()), false);
                // Paper end - Add UnknownCommandEvent
            }

            return null;
        }
    }

    public static void executeCommandInContext(final CommandSourceStack context, final Consumer<ExecutionContext<CommandSourceStack>> config) {
        ExecutionContext<CommandSourceStack> currentContext = CURRENT_EXECUTION_CONTEXT.get();
        boolean isTopContext = currentContext == null;
        if (isTopContext) {
            GameRules gameRules = context.getLevel().getGameRules();
            int chainLimit = Math.max(1, gameRules.get(GameRules.MAX_COMMAND_SEQUENCE_LENGTH));
            int forkLimit = gameRules.get(GameRules.MAX_COMMAND_FORKS);

            try (ExecutionContext<CommandSourceStack> executionContext = new ExecutionContext<>(chainLimit, forkLimit, Profiler.get())) {
                CURRENT_EXECUTION_CONTEXT.set(executionContext);
                config.accept(executionContext);
                executionContext.runCommandQueue();
            } finally {
                CURRENT_EXECUTION_CONTEXT.set(null);
            }
        } else {
            config.accept(currentContext);
        }
    }

    public void sendCommands(final ServerPlayer player) {
        // Paper start - Send empty commands if tab completion is disabled
        if (org.spigotmc.SpigotConfig.tabComplete < 0) {
            player.connection.send(new ClientboundCommandsPacket(new RootCommandNode<>(), COMMAND_NODE_INSPECTOR));
            return;
        }
        // Paper end - Send empty commands if tab completion is disabled
        // CraftBukkit start
        // Register Vanilla commands into builtRoot as before
        // Paper start - Perf: Async command map building
        // Copy root children to avoid concurrent modification during building
        final java.util.Collection<CommandNode<CommandSourceStack>> commandNodes = new java.util.ArrayList<>(this.dispatcher.getRoot().getChildren());
        COMMAND_SENDING_POOL.execute(() -> this.sendAsync(player, commandNodes));
    }

    // Fixed pool, but with discard policy
    public static final java.util.concurrent.ExecutorService COMMAND_SENDING_POOL = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        new com.google.common.util.concurrent.ThreadFactoryBuilder()
            .setNameFormat("Paper Async Command Builder Thread Pool - %1$d")
            .setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(net.minecraft.server.MinecraftServer.LOGGER))
            .build(),
        new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    );

    private void sendAsync(ServerPlayer player, java.util.Collection<CommandNode<CommandSourceStack>> dispatcherRootChildren) {
        // Paper end - Perf: Async command map building
        Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> playerCommands = new HashMap<>();
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        playerCommands.put(this.dispatcher.getRoot(), root);
        fillUsableCommands(dispatcherRootChildren, root, player.createCommandSourceStack(), playerCommands); // Paper - Perf: Async command map building; pass copy of children

        java.util.Collection<String> bukkit = new java.util.LinkedHashSet<>();
        for (CommandNode node : root.getChildren()) {
            bukkit.add(node.getName());
        }
        // Paper start - Perf: Async command map building
        new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<CommandSourceStack>(player.getBukkitEntity(), (RootCommandNode) root, false).callEvent(); // Paper - Brigadier API
        // Folia start - region threading
        player.getBukkitEntity().taskScheduler.schedule((ServerPlayer updatedPlayer) -> {
            runSync(updatedPlayer, bukkit, root);
        }, null, 1L);
        // Folia end - region threading
    }

    private void runSync(ServerPlayer player, java.util.Collection<String> bukkit, RootCommandNode<CommandSourceStack> root) {
        // Paper end - Perf: Async command map building
        new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<CommandSourceStack>(player.getBukkitEntity(), (RootCommandNode) root, true).callEvent(); // Paper - Brigadier API
        org.bukkit.event.player.PlayerCommandSendEvent event = new org.bukkit.event.player.PlayerCommandSendEvent(player.getBukkitEntity(), new java.util.LinkedHashSet<>(bukkit));
        event.getPlayer().getServer().getPluginManager().callEvent(event);

        // Remove labels that were removed during the event
        for (String orig : bukkit) {
            if (!event.getCommands().contains(orig)) {
                root.removeCommand(orig);
            }
        }
        // CraftBukkit end
        player.connection.send(new ClientboundCommandsPacket(root, COMMAND_NODE_INSPECTOR));
    }

    private static <S> void fillUsableCommands(
        final java.util.Collection<CommandNode<S>> children, final CommandNode<S> target, final S commandFilter, final Map<CommandNode<S>, CommandNode<S>> converted // Paper - Perf: Async command map building; pass copy of children
    ) {
        for (CommandNode<S> child : children) { // Paper - Perf: Async command map building; pass copy of children
            // Paper start - Brigadier API
            if (child.clientNode != null) {
                child = child.clientNode;
            }
            // Paper end - Brigadier API
            if (!org.spigotmc.SpigotConfig.sendNamespaced && child.getName().contains(":")) continue; // Spigot
            if (child.canUse(commandFilter)) {
                ArgumentBuilder<S, ?> builder = child.createBuilder();
                // Paper start
                /*
                Because of how commands can be yeeted right left and center due to bad bukkit practices
                we need to be able to ensure that ALL commands are registered (even redirects).

                What this will do is IF the redirect seems to be "dead" it will create a builder and essentially populate (flatten)
                all the children from the dead redirect to the node.

                So, if minecraft:msg redirects to msg but the original msg node has been overriden minecraft:msg will now act as msg and will explicilty inherit its children.

                The only way to fix this is to either:
                - Send EVERYTHING flattened, don't use redirects
                - Don't allow command nodes to be deleted
                - Do this :)
                 */
                // Is there an invalid command redirect?
                if (builder.getRedirect() != null && converted.get(builder.getRedirect()) == null) {
                    // Create the argument builder with the same values as the specified node, but with a different literal and populated children

                    final CommandNode<S> redirect = builder.getRedirect();
                    // Diff copied from LiteralCommand#createBuilder
                    final com.mojang.brigadier.builder.LiteralArgumentBuilder<S> redirectBuilder = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal(child.getName());
                    redirectBuilder.requires(redirect.getRequirement());
                    // builder.forward(redirect.getRedirect(), redirect.getRedirectModifier(), redirect.isFork()); We don't want to migrate the forward, since it's invalid.
                    if (redirect.getCommand() != null) {
                        redirectBuilder.executes(redirect.getCommand());
                    }
                    // Diff copied from LiteralCommand#createBuilder
                    for (final CommandNode<S> redirectChild : redirect.getChildren()) {
                        redirectBuilder.then(redirectChild);
                    }

                    builder = redirectBuilder;
                }
                // Paper end
                if (builder.getRedirect() != null) {
                    builder.redirect(converted.get(builder.getRedirect()));
                }

                CommandNode<S> node = builder.build();
                converted.put(child, node);
                target.addChild(node);
                if (!child.getChildren().isEmpty()) {
                    fillUsableCommands(child.getChildren(), node, commandFilter, converted); // Paper - Perf: Async command map building; pass copy of children
                }
            }
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> literal(final String literal) {
        return LiteralArgumentBuilder.literal(literal);
    }

    public static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(final String name, final ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    public static Predicate<String> createValidator(final Commands.ParseFunction parser) {
        return value -> {
            try {
                parser.parse(new StringReader(value));
                return true;
            } catch (CommandSyntaxException ignored) {
                return false;
            }
        };
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return this.dispatcher;
    }

    public static <S> void validateParseResults(final ParseResults<S> command) throws CommandSyntaxException {
        CommandSyntaxException parseException = getParseException(command);
        if (parseException != null) {
            throw parseException;
        }
    }

    public static <S> @Nullable CommandSyntaxException getParseException(final ParseResults<S> parse) {
        if (!parse.getReader().canRead()) {
            return null;
        } else if (parse.getExceptions().size() == 1) {
            return parse.getExceptions().values().iterator().next();
        } else {
            return parse.getContext().getRange().isEmpty()
                ? CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(parse.getReader())
                : CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(parse.getReader());
        }
    }

    public static CommandBuildContext createValidationContext(final HolderLookup.Provider registries) {
        return new CommandBuildContext() {
            @Override
            public FeatureFlagSet enabledFeatures() {
                return FeatureFlags.REGISTRY.allFlags();
            }

            @Override
            public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
                return registries.listRegistryKeys();
            }

            @Override
            public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(final ResourceKey<? extends Registry<? extends T>> key) {
                return registries.lookup(key).map(this::createLookup);
            }

            private <T> HolderLookup.RegistryLookup.Delegate<T> createLookup(final HolderLookup.RegistryLookup<T> original) {
                return new HolderLookup.RegistryLookup.Delegate<T>() {
                    @Override
                    public HolderLookup.RegistryLookup<T> parent() {
                        return original;
                    }

                    @Override
                    public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
                        return Optional.of(this.getOrThrow(id));
                    }

                    @Override
                    public HolderSet.Named<T> getOrThrow(final TagKey<T> id) {
                        Optional<HolderSet.Named<T>> tag = this.parent().get(id);
                        return tag.orElseGet(() -> HolderSet.emptyNamed(this.parent(), id));
                    }
                };
            }
        };
    }

    public static void validate() {
        CommandBuildContext context = createValidationContext(VanillaRegistries.createLookup());
        CommandDispatcher<CommandSourceStack> dispatcher = new Commands(Commands.CommandSelection.ALL, context).getDispatcher();
        RootCommandNode<CommandSourceStack> root = dispatcher.getRoot();
        dispatcher.findAmbiguities(
            (parent, child, sibling, ambiguities) -> LOGGER.warn(
                "Ambiguity between arguments {} and {} with inputs: {}", dispatcher.getPath(child), dispatcher.getPath(sibling), ambiguities
            )
        );
        Set<ArgumentType<?>> usedArgumentTypes = ArgumentUtils.findUsedArgumentTypes(root);
        Set<ArgumentType<?>> unregisteredTypes = usedArgumentTypes.stream()
            .filter(arg -> !ArgumentTypeInfos.isClassRecognized(arg.getClass()))
            .collect(Collectors.toSet());
        if (!unregisteredTypes.isEmpty()) {
            LOGGER.warn(
                "Missing type registration for following arguments:\n {}", unregisteredTypes.stream().map(arg -> "\t" + arg).collect(Collectors.joining(",\n"))
            );
            throw new IllegalStateException("Unregistered argument types");
        }
    }

    public static <T extends PermissionSetSupplier> PermissionProviderCheck<T> hasPermission(final PermissionCheck permission) {
        return new PermissionProviderCheck<>(permission);
    }

    public static CommandSourceStack createCompilationContext(final PermissionSet compilationPermissions) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, compilationPermissions, "", CommonComponents.EMPTY, null, null);
    }

    public enum CommandSelection {
        ALL(true, true),
        DEDICATED(false, true),
        INTEGRATED(true, false);

        private final boolean includeIntegrated;
        private final boolean includeDedicated;

        CommandSelection(final boolean includeIntegrated, final boolean includeDedicated) {
            this.includeIntegrated = includeIntegrated;
            this.includeDedicated = includeDedicated;
        }
    }

    @FunctionalInterface
    public interface ParseFunction {
        void parse(StringReader value) throws CommandSyntaxException;
    }
}

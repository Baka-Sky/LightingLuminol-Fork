package net.minecraft.server.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.timeline.Timeline;

public class TimeCommand {
    private static final DynamicCommandExceptionType ERROR_NO_DEFAULT_CLOCK = new DynamicCommandExceptionType(
        dimension -> Component.translatableEscape("commands.time.no_default_clock", dimension)
    );
    private static final Dynamic2CommandExceptionType ERROR_NO_TIME_MARKER_FOUND = new Dynamic2CommandExceptionType(
        (clock, timeMarker) -> Component.translatableEscape("commands.time.no_time_marker_found", timeMarker, clock)
    );
    private static final Dynamic2CommandExceptionType ERROR_WRONG_TIMELINE_FOR_CLOCK = new Dynamic2CommandExceptionType(
        (clock, timeline) -> Component.translatableEscape("commands.time.wrong_timeline_for_clock", timeline, clock)
    );
    private static final int MAX_CLOCK_RATE = 1000;

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> baseCommand = Commands.literal("time").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        dispatcher.register(addClockNodes(context, baseCommand, c -> getDefaultClock(c.getSource())));
        dispatcher.register(
            baseCommand.then(Commands.literal("query").then(Commands.literal("gametime").executes(c -> queryGameTime(c.getSource()))))
                .then(
                    Commands.literal("of")
                        .then(
                            addClockNodes(
                                context,
                                Commands.argument("clock", ResourceArgument.resource(context, Registries.WORLD_CLOCK)),
                                c -> ResourceArgument.getClock(c, "clock")
                            )
                        )
                )
        );
    }

    private static <A extends ArgumentBuilder<CommandSourceStack, A>> A addClockNodes(
        final CommandBuildContext context, final A node, final TimeCommand.ClockGetter clockGetter
    ) {
        return node.then(
                Commands.literal("set")
                    .then(
                        Commands.argument("time", TimeArgument.time())
                            .executes(c -> setTotalTicks(c.getSource(), clockGetter.getClock(c), IntegerArgumentType.getInteger(c, "time")))
                    )
                    .then(
                        Commands.argument("timemarker", IdentifierArgument.id())
                            .suggests((c, p) -> suggestTimeMarkers(c.getSource(), p, clockGetter.getClock(c)))
                            .executes(
                                c -> setTimeToTimeMarker(
                                    c.getSource(),
                                    clockGetter.getClock(c),
                                    ResourceKey.create(ClockTimeMarkers.ROOT_ID, IdentifierArgument.getId(c, "timemarker"))
                                )
                            )
                    )
            )
            .then(
                Commands.literal("add")
                    .then(
                        Commands.argument("time", TimeArgument.time(Integer.MIN_VALUE))
                            .executes(c -> addTime(c.getSource(), clockGetter.getClock(c), IntegerArgumentType.getInteger(c, "time")))
                    )
            )
            .then(Commands.literal("pause").executes(c -> setPaused(c.getSource(), clockGetter.getClock(c), true)))
            .then(Commands.literal("resume").executes(c -> setPaused(c.getSource(), clockGetter.getClock(c), false)))
            .then(
                Commands.literal("rate")
                    .then(
                        Commands.argument("rate", FloatArgumentType.floatArg(1.0E-5F, 1000.0F))
                            .executes(c -> setRate(c.getSource(), clockGetter.getClock(c), FloatArgumentType.getFloat(c, "rate")))
                    )
            )
            .then(
                Commands.literal("query")
                    .then(Commands.literal("time").executes(c -> queryTime(c.getSource(), clockGetter.getClock(c))))
                    .then(
                        Commands.argument("timeline", ResourceArgument.resource(context, Registries.TIMELINE))
                            .suggests((c, p) -> suggestTimelines(c.getSource(), p, clockGetter.getClock(c)))
                            .executes(c -> queryTimelineTicks(c.getSource(), clockGetter.getClock(c), ResourceArgument.getTimeline(c, "timeline")))
                            .then(
                                Commands.literal("repetition")
                                    .executes(
                                        c -> queryTimelineRepetitions(c.getSource(), clockGetter.getClock(c), ResourceArgument.getTimeline(c, "timeline"))
                                    )
                            )
                    )
            );
    }

    // Folia start - region threading
    private static void sendMessage(CommandSourceStack src, CommandSyntaxException ex) {
        src.sendFailure((Component)ex.getRawMessage());
    }
    // Folia end - region threading

    private static CompletableFuture<Suggestions> suggestTimeMarkers(
        final CommandSourceStack source, final SuggestionsBuilder builder, final Holder<WorldClock> clock
    ) {
        return SharedSuggestionProvider.suggestResource(
            source.getLevel().clockManager().commandTimeMarkersForClock(clock).map(ResourceKey::identifier), builder // Paper - per-world time
        );
    }

    private static CompletableFuture<Suggestions> suggestTimelines(
        final CommandSourceStack source, final SuggestionsBuilder builder, final Holder<WorldClock> clock
    ) {
        Stream<ResourceKey<Timeline>> timelines = source.registryAccess()
            .lookupOrThrow(Registries.TIMELINE)
            .listElements()
            .filter(timeline -> timeline.value().clock().equals(clock))
            .map(Holder.Reference::key);
        return SharedSuggestionProvider.suggestResource(timelines.map(ResourceKey::identifier), builder);
    }

    private static int queryGameTime(final CommandSourceStack source) {
        long gameTime = source.getLevel().getGameTime();
        source.sendSuccess(() -> Component.translatable("commands.time.query.gametime", gameTime), false);
        return wrapTime(gameTime);
    }

    private static int queryTime(final CommandSourceStack source, final Holder<WorldClock> clock) {
        ServerClockManager clockManager = source.getLevel().clockManager(); // Paper - per-world time
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        long totalTicks = clockManager.getTotalTicks(clock);
        source.sendSuccess(() -> Component.translatable("commands.time.query.absolute", clock.getRegisteredName(), totalTicks), false);
        }); // Folia - region threading
        return 0; // Folia - region threading
    }

    private static int queryTimelineTicks(final CommandSourceStack source, final Holder<WorldClock> clock, final Holder<Timeline> timeline) throws CommandSyntaxException {
        if (!clock.equals(timeline.value().clock())) {
            throw ERROR_WRONG_TIMELINE_FOR_CLOCK.create(clock.getRegisteredName(), timeline.getRegisteredName());
        }

        ServerClockManager clockManager = source.getLevel().clockManager(); // Paper - per-world time
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        long currentTicks = timeline.value().getCurrentTicks(clockManager);
        source.sendSuccess(() -> Component.translatable("commands.time.query.timeline", timeline.getRegisteredName(), currentTicks), false);
        }); // Folia - region threading
        return 0; // Folia - region threading
    }

    private static int queryTimelineRepetitions(final CommandSourceStack source, final Holder<WorldClock> clock, final Holder<Timeline> timeline) throws CommandSyntaxException {
        if (!clock.equals(timeline.value().clock())) {
            throw ERROR_WRONG_TIMELINE_FOR_CLOCK.create(clock.getRegisteredName(), timeline.getRegisteredName());
        }

        ServerClockManager clockManager = source.getLevel().clockManager(); // Paper - per-world time
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        long repetitions = timeline.value().getPeriodCount(clockManager);
        source.sendSuccess(() -> Component.translatable("commands.time.query.timeline.repetitions", timeline.getRegisteredName(), repetitions), false);
        }); // Folia - region threading
        return 0; // Folia - region threading
    }

    // Paper start - per-world time
    private static int setTotalTicks(final CommandSourceStack source, final Holder<WorldClock> clock, final int totalTicks) {
        ServerClockManager clockManager = source.getLevel().clockManager();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        long currentTotalTicks = clockManager.getTotalTicks(clock);
        org.bukkit.event.world.ClockTimeSkipEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.createTimeSkipEvent(source, totalTicks - currentTotalTicks);
        final long newTotalTicks;
        if (event.callEvent()) {
            newTotalTicks = currentTotalTicks + event.getSkipAmount();
            clockManager.setTotalTicks(clock, newTotalTicks);
        } else {
            newTotalTicks = totalTicks; // just pass it through
        }
        source.sendSuccess(() -> Component.translatable("commands.time.set.absolute", clock.getRegisteredName(), newTotalTicks), true);
        }); // Folia - region threading
        return 0; // Folia - region threading
    }

    private static int addTime(final CommandSourceStack source, final Holder<WorldClock> clock, final int time) {
        ServerClockManager clockManager = source.getLevel().clockManager();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        org.bukkit.event.world.ClockTimeSkipEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.createTimeSkipEvent(source, time);
        if (event.callEvent()) {
            clockManager.addTicks(clock, event.getSkipAmount());
        }
        long totalTicks = clockManager.getTotalTicks(clock);
        source.sendSuccess(() -> Component.translatable("commands.time.set.absolute", clock.getRegisteredName(), totalTicks), true);
        }); // Folia - region threading
        return 0; // Folia - region threading
    }

    private static int setTimeToTimeMarker(final CommandSourceStack source, final Holder<WorldClock> clock, final ResourceKey<ClockTimeMarker> timeMarkerId) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        // Paper start - per-world time
        ServerClockManager clockManager = source.getLevel().clockManager();
        java.util.OptionalLong targetTime = clockManager.getTotalTicksToTimeMarker(clock, timeMarkerId);
        if (targetTime.isEmpty()) {
            // Paper end - per-world time
            throw ERROR_NO_TIME_MARKER_FOUND.create(clock.getRegisteredName(), timeMarkerId);
        }

        // Paper start - per-world time
        final long currentTime = clockManager.getTotalTicks(clock);
        final org.bukkit.event.world.ClockTimeSkipEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.createTimeSkipEvent(source, targetTime.getAsLong() - currentTime);
        if (event.callEvent()) {
            clockManager.setTotalTicks(clock, currentTime + event.getSkipAmount());
        }
        // Paper end - per-world time
        source.sendSuccess(() -> Component.translatable("commands.time.set.time_marker", clock.getRegisteredName(), timeMarkerId.identifier().toString()), true);
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static int setPaused(final CommandSourceStack source, final Holder<WorldClock> clock, final boolean paused) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        source.getLevel().clockManager().setPaused(clock, paused); // Paper - per-world time
        source.sendSuccess(() -> Component.translatable(paused ? "commands.time.pause" : "commands.time.resume", clock.getRegisteredName()), true);
        }); // Folia - region threading
        return Command.SINGLE_SUCCESS;
    }

    private static int setRate(final CommandSourceStack source, final Holder<WorldClock> clock, final float rate) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        source.getLevel().clockManager().setRate(clock, rate); // Paper - per-world time
        source.sendSuccess(() -> Component.translatable("commands.time.rate", clock.getRegisteredName(), rate), true);
        }); // Folia - region threading
        return Command.SINGLE_SUCCESS;
    }

    private static int wrapTime(final long ticks) {
        return Math.toIntExact(ticks % 2147483647L);
    }

    private static Holder<WorldClock> getDefaultClock(final CommandSourceStack source) throws CommandSyntaxException {
        Holder<DimensionType> dimensionType = source.getLevel().dimensionTypeRegistration();
        return dimensionType.value().defaultClock().orElseThrow(() -> ERROR_NO_DEFAULT_CLOCK.create(dimensionType.getRegisteredName()));
    }

    private interface ClockGetter {
        Holder<WorldClock> getClock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }
}

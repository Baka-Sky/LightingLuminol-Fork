package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.IntProvider;

public class WeatherCommand {
    private static final int DEFAULT_TIME = -1;

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("weather")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("clear")
                        .executes(c -> setClear(c.getSource(), -1))
                        .then(
                            Commands.argument("duration", TimeArgument.time(1))
                                .executes(c -> setClear(c.getSource(), IntegerArgumentType.getInteger(c, "duration")))
                        )
                )
                .then(
                    Commands.literal("rain")
                        .executes(c -> setRain(c.getSource(), -1))
                        .then(
                            Commands.argument("duration", TimeArgument.time(1))
                                .executes(c -> setRain(c.getSource(), IntegerArgumentType.getInteger(c, "duration")))
                        )
                )
                .then(
                    Commands.literal("thunder")
                        .executes(c -> setThunder(c.getSource(), -1))
                        .then(
                            Commands.argument("duration", TimeArgument.time(1))
                                .executes(c -> setThunder(c.getSource(), IntegerArgumentType.getInteger(c, "duration")))
                        )
                )
        );
    }

    private static int getDuration(final CommandSourceStack source, final int input, final IntProvider defaultDistribution) {
        return input == -1 ? defaultDistribution.sample(source.getLevel().getRandom()) : input;
    }

    private static int setClear(final CommandSourceStack source, final int duration) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        source.getServer().setWeatherParameters(source.getLevel(), getDuration(source, duration, ServerLevel.RAIN_DELAY), 0, false, false); // CraftBukkit - SPIGOT-7680: per-world
        source.sendSuccess(() -> Component.translatable("commands.weather.set.clear"), true);
        }); // Folia - region threading
        return duration;
    }

    private static int setRain(final CommandSourceStack source, final int duration) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        source.getServer().setWeatherParameters(source.getLevel(), 0, getDuration(source, duration, ServerLevel.RAIN_DURATION), true, false); // CraftBukkit - SPIGOT-7680: per-world
        source.sendSuccess(() -> Component.translatable("commands.weather.set.rain"), true);
        }); // Folia - region threading
        return duration;
    }

    private static int setThunder(final CommandSourceStack source, final int duration) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> { // Folia - region threading
        source.getServer().setWeatherParameters(source.getLevel(), 0, getDuration(source, duration, ServerLevel.THUNDER_DURATION), true, true); // CraftBukkit - SPIGOT-7680: per-world
        source.sendSuccess(() -> Component.translatable("commands.weather.set.thunder"), true);
        }); // Folia - region threading
        return duration;
    }
}

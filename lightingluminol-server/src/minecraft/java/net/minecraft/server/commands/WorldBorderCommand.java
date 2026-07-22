package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec2;

public class WorldBorderCommand {
    private static final SimpleCommandExceptionType ERROR_SAME_CENTER = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.center.failed")
    );
    private static final SimpleCommandExceptionType ERROR_SAME_SIZE = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.set.failed.nochange")
    );
    private static final SimpleCommandExceptionType ERROR_TOO_SMALL = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.set.failed.small")
    );
    private static final SimpleCommandExceptionType ERROR_TOO_BIG = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.set.failed.big", 5.999997E7F)
    );
    private static final SimpleCommandExceptionType ERROR_TOO_FAR_OUT = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.set.failed.far", 2.9999984E7)
    );
    private static final SimpleCommandExceptionType ERROR_SAME_WARNING_TIME = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.warning.time.failed")
    );
    private static final SimpleCommandExceptionType ERROR_SAME_WARNING_DISTANCE = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.warning.distance.failed")
    );
    private static final SimpleCommandExceptionType ERROR_SAME_DAMAGE_BUFFER = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.damage.buffer.failed")
    );
    private static final SimpleCommandExceptionType ERROR_SAME_DAMAGE_AMOUNT = new SimpleCommandExceptionType(
        Component.translatable("commands.worldborder.damage.amount.failed")
    );

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("worldborder")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("add")
                        .then(
                            Commands.argument("distance", DoubleArgumentType.doubleArg(-5.999997E7F, 5.999997E7F))
                                .executes(
                                    c -> setSize(
                                        c.getSource(), c.getSource().getLevel().getWorldBorder().getSize() + DoubleArgumentType.getDouble(c, "distance"), 0L
                                    )
                                )
                                .then(
                                    Commands.argument("time", TimeArgument.time(0))
                                        .executes(
                                            c -> setSize(
                                                c.getSource(),
                                                c.getSource().getLevel().getWorldBorder().getSize() + DoubleArgumentType.getDouble(c, "distance"),
                                                c.getSource().getLevel().getWorldBorder().getLerpTime() + IntegerArgumentType.getInteger(c, "time")
                                            )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("distance", DoubleArgumentType.doubleArg(-5.999997E7F, 5.999997E7F))
                                .executes(c -> setSize(c.getSource(), DoubleArgumentType.getDouble(c, "distance"), 0L))
                                .then(
                                    Commands.argument("time", TimeArgument.time(0))
                                        .executes(
                                            c -> setSize(c.getSource(), DoubleArgumentType.getDouble(c, "distance"), IntegerArgumentType.getInteger(c, "time"))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("center")
                        .then(Commands.argument("pos", Vec2Argument.vec2()).executes(c -> setCenter(c.getSource(), Vec2Argument.getVec2(c, "pos"))))
                )
                .then(
                    Commands.literal("damage")
                        .then(
                            Commands.literal("amount")
                                .then(
                                    Commands.argument("damagePerBlock", FloatArgumentType.floatArg(0.0F))
                                        .executes(c -> setDamageAmount(c.getSource(), FloatArgumentType.getFloat(c, "damagePerBlock")))
                                )
                        )
                        .then(
                            Commands.literal("buffer")
                                .then(
                                    Commands.argument("distance", FloatArgumentType.floatArg(0.0F))
                                        .executes(c -> setDamageBuffer(c.getSource(), FloatArgumentType.getFloat(c, "distance")))
                                )
                        )
                )
                .then(Commands.literal("get").executes(c -> getSize(c.getSource())))
                .then(
                    Commands.literal("warning")
                        .then(
                            Commands.literal("distance")
                                .then(
                                    Commands.argument("distance", IntegerArgumentType.integer(0))
                                        .executes(c -> setWarningDistance(c.getSource(), IntegerArgumentType.getInteger(c, "distance")))
                                )
                        )
                        .then(
                            Commands.literal("time")
                                .then(
                                    Commands.argument("time", TimeArgument.time(0))
                                        .executes(c -> setWarningTime(c.getSource(), IntegerArgumentType.getInteger(c, "time")))
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

    private static int setDamageBuffer(final CommandSourceStack source, final float distance) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        WorldBorder border = source.getLevel().getWorldBorder();
        if (border.getSafeZone() == distance) {
            throw ERROR_SAME_DAMAGE_BUFFER.create();
        }

        border.setSafeZone(distance);
        source.sendSuccess(() -> Component.translatable("commands.worldborder.damage.buffer.success", String.format(Locale.ROOT, "%.2f", distance)), true);
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static int setDamageAmount(final CommandSourceStack source, final float damagePerBlock) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        WorldBorder border = source.getLevel().getWorldBorder();
        if (border.getDamagePerBlock() == damagePerBlock) {
            throw ERROR_SAME_DAMAGE_AMOUNT.create();
        }

        border.setDamagePerBlock(damagePerBlock);
        source.sendSuccess(() -> Component.translatable("commands.worldborder.damage.amount.success", String.format(Locale.ROOT, "%.2f", damagePerBlock)), true);
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static int setWarningTime(final CommandSourceStack source, final int ticks) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        WorldBorder border = source.getLevel().getWorldBorder();
        if (border.getWarningTime() == ticks) {
            throw ERROR_SAME_WARNING_TIME.create();
        }

        border.setWarningTime(ticks);
        source.sendSuccess(() -> Component.translatable("commands.worldborder.warning.time.success", formatTicksToSeconds(ticks)), true);
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static int setWarningDistance(final CommandSourceStack source, final int distance) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        WorldBorder border = source.getLevel().getWorldBorder();
        if (border.getWarningBlocks() == distance) {
            throw ERROR_SAME_WARNING_DISTANCE.create();
        }

        border.setWarningBlocks(distance);
        source.sendSuccess(() -> Component.translatable("commands.worldborder.warning.distance.success", distance), true);
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static int getSize(final CommandSourceStack source) {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            // Folia end - region threading
        double size = source.getLevel().getWorldBorder().getSize();
        source.sendSuccess(() -> Component.translatable("commands.worldborder.get", String.format(Locale.ROOT, "%.0f", size)), false);
        return; // Folia - region threading
        // Folia start - region threading
        });
        return 1;
        // Folia end - region threading
    }

    private static int setCenter(final CommandSourceStack source, final Vec2 center) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        WorldBorder border = source.getLevel().getWorldBorder();
        if (border.getCenterX() == center.x && border.getCenterZ() == center.y) {
            throw ERROR_SAME_CENTER.create();
        } else if (!(Math.abs(center.x) > 2.9999984E7) && !(Math.abs(center.y) > 2.9999984E7)) {
            border.setCenter(center.x, center.y);
            source.sendSuccess(
                () -> Component.translatable(
                    "commands.worldborder.center.success", String.format(Locale.ROOT, "%.2f", center.x), String.format(Locale.ROOT, "%.2f", center.y)
                ),
                true
            );
            return; // Folia - region threading
        } else {
            throw ERROR_TOO_FAR_OUT.create();
        }
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static int setSize(final CommandSourceStack source, final double distance, final long ticks) throws CommandSyntaxException {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            try {
                // Folia end - region threading
        ServerLevel level = source.getLevel();
        WorldBorder border = level.getWorldBorder();
        double current = border.getSize();
        if (current == distance) {
            throw ERROR_SAME_SIZE.create();
        }

        if (distance < 1.0) {
            throw ERROR_TOO_SMALL.create();
        }

        if (distance > 5.999997E7F) {
            throw ERROR_TOO_BIG.create();
        }

        String formattedDistance = String.format(Locale.ROOT, "%.1f", distance);
        if (ticks > 0L) {
            border.lerpSizeBetween(current, distance, ticks, level.getGameTime());
            if (distance > current) {
                source.sendSuccess(() -> Component.translatable("commands.worldborder.set.grow", formattedDistance, formatTicksToSeconds(ticks)), true);
            } else {
                source.sendSuccess(() -> Component.translatable("commands.worldborder.set.shrink", formattedDistance, formatTicksToSeconds(ticks)), true);
            }
        } else {
            border.setSize(distance);
            source.sendSuccess(() -> Component.translatable("commands.worldborder.set.immediate", formattedDistance), true);
        }

        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 1;
        // Folia end - region threading
    }

    private static String formatTicksToSeconds(final long ticks) {
        return String.format(Locale.ROOT, "%.2f", ticks / 20.0);
    }
}

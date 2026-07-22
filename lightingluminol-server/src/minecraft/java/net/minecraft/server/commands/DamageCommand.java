package net.minecraft.server.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

public class DamageCommand {
    private static final SimpleCommandExceptionType ERROR_INVULNERABLE = new SimpleCommandExceptionType(Component.translatable("commands.damage.invulnerable"));

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("damage")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("target", EntityArgument.entity())
                        .then(
                            Commands.argument("amount", FloatArgumentType.floatArg(0.0F))
                                .executes(
                                    c -> damage(
                                        c.getSource(),
                                        EntityArgument.getEntity(c, "target"),
                                        FloatArgumentType.getFloat(c, "amount"),
                                        c.getSource().getLevel().damageSources().generic()
                                    )
                                )
                                .then(
                                    Commands.argument("damageType", ResourceArgument.resource(context, Registries.DAMAGE_TYPE))
                                        .executes(
                                            c -> damage(
                                                c.getSource(),
                                                EntityArgument.getEntity(c, "target"),
                                                FloatArgumentType.getFloat(c, "amount"),
                                                new DamageSource(ResourceArgument.getResource(c, "damageType", Registries.DAMAGE_TYPE))
                                            )
                                        )
                                        .then(
                                            Commands.literal("at")
                                                .then(
                                                    Commands.argument("location", Vec3Argument.vec3())
                                                        .executes(
                                                            c -> damage(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                FloatArgumentType.getFloat(c, "amount"),
                                                                new DamageSource(
                                                                    ResourceArgument.getResource(c, "damageType", Registries.DAMAGE_TYPE),
                                                                    Vec3Argument.getVec3(c, "location")
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("by")
                                                .then(
                                                    Commands.argument("entity", EntityArgument.entity())
                                                        .executes(
                                                            c -> damage(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                FloatArgumentType.getFloat(c, "amount"),
                                                                new DamageSource(
                                                                    ResourceArgument.getResource(c, "damageType", Registries.DAMAGE_TYPE),
                                                                    EntityArgument.getEntity(c, "entity")
                                                                )
                                                            )
                                                        )
                                                        .then(
                                                            Commands.literal("from")
                                                                .then(
                                                                    Commands.argument("cause", EntityArgument.entity())
                                                                        .executes(
                                                                            c -> damage(
                                                                                c.getSource(),
                                                                                EntityArgument.getEntity(c, "target"),
                                                                                FloatArgumentType.getFloat(c, "amount"),
                                                                                new DamageSource(
                                                                                    ResourceArgument.getResource(c, "damageType", Registries.DAMAGE_TYPE),
                                                                                    EntityArgument.getEntity(c, "entity"),
                                                                                    EntityArgument.getEntity(c, "cause")
                                                                                )
                                                                            )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
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

    private static int damage(final CommandSourceStack stack, final Entity target, final float amount, final DamageSource source) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        if (newTarget.hurtServer(stack.getLevel(), source, amount)) { // Folia - region threading
            stack.sendSuccess(() -> Component.translatable("commands.damage.success", amount, newTarget.getDisplayName()), true); // Folia - region threading
            return; // Folia - region threading
        } else {
            throw ERROR_INVULNERABLE.create();
        }
        // Folia start - region threading
        } catch (CommandSyntaxException ex) {
            sendMessage(stack, ex);
        }
        });
        return 0;
        // Folia end - region threading
    }
}

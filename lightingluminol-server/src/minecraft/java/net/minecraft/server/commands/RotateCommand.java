package net.minecraft.server.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;

public class RotateCommand {
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("rotate")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("target", EntityArgument.entity())
                        .then(
                            Commands.argument("rotation", RotationArgument.rotation())
                                .executes(c -> rotate(c.getSource(), EntityArgument.getEntity(c, "target"), RotationArgument.getRotation(c, "rotation")))
                        )
                        .then(
                            Commands.literal("facing")
                                .then(
                                    Commands.literal("entity")
                                        .then(
                                            Commands.argument("facingEntity", EntityArgument.entity())
                                                .executes(
                                                    c -> rotate(
                                                        c.getSource(),
                                                        EntityArgument.getEntity(c, "target"),
                                                        new LookAt.LookAtEntity(EntityArgument.getEntity(c, "facingEntity"), EntityAnchorArgument.Anchor.FEET)
                                                    )
                                                )
                                                .then(
                                                    Commands.argument("facingAnchor", EntityAnchorArgument.anchor())
                                                        .executes(
                                                            c -> rotate(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                new LookAt.LookAtEntity(
                                                                    EntityArgument.getEntity(c, "facingEntity"),
                                                                    EntityAnchorArgument.getAnchor(c, "facingAnchor")
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.argument("facingLocation", Vec3Argument.vec3())
                                        .executes(
                                            c -> rotate(
                                                c.getSource(),
                                                EntityArgument.getEntity(c, "target"),
                                                new LookAt.LookAtPosition(Vec3Argument.getVec3(c, "facingLocation"))
                                            )
                                        )
                                )
                        )
                )
        );
    }

    private static int rotate(final CommandSourceStack source, final Entity entityOld, final Coordinates rotation) { // Folia - region threading
        Vec2 rot = rotation.getRotation(source);
        entityOld.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity entity) -> { // Folia - region threading
        float relativeOrAbsoluteYRot = rotation.isYRelative() ? rot.y - entity.getYRot() : rot.y;
        float relativeOrAbsoluteXRot = rotation.isXRelative() ? rot.x - entity.getXRot() : rot.x;
        entity.forceSetRotation(relativeOrAbsoluteYRot, rotation.isYRelative(), relativeOrAbsoluteXRot, rotation.isXRelative());
        source.sendSuccess(() -> Component.translatable("commands.rotate.success", entity.getDisplayName()), true);
        }); // Folia - region threading
        return Command.SINGLE_SUCCESS;
    }

    private static int rotate(final CommandSourceStack source, final Entity entityOld, final LookAt facing) {
        entityOld.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity entity) -> { // Folia - region threading
        facing.perform(source, entity);
        source.sendSuccess(() -> Component.translatable("commands.rotate.success", entity.getDisplayName()), true);
        }); // Folia - region threading
        return Command.SINGLE_SUCCESS;
    }
}

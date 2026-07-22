package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class TeleportCommand {
    private static final SimpleCommandExceptionType INVALID_POSITION = new SimpleCommandExceptionType(
        Component.translatable("commands.teleport.invalidPosition")
    );

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> teleport = dispatcher.register(
            Commands.literal("teleport")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("location", Vec3Argument.vec3())
                        .executes(
                            c -> teleportToPos(
                                c.getSource(),
                                Collections.singleton(c.getSource().getEntityOrException()),
                                c.getSource().getLevel(),
                                Vec3Argument.getCoordinates(c, "location"),
                                null,
                                null
                            )
                        )
                )
                .then(
                    Commands.argument("destination", EntityArgument.entity())
                        .executes(
                            c -> teleportToEntity(
                                c.getSource(), Collections.singleton(c.getSource().getEntityOrException()), EntityArgument.getEntity(c, "destination")
                            )
                        )
                )
                .then(
                    Commands.argument("targets", EntityArgument.entities())
                        .then(
                            Commands.argument("location", Vec3Argument.vec3())
                                .executes(
                                    c -> teleportToPos(
                                        c.getSource(),
                                        EntityArgument.getEntities(c, "targets"),
                                        c.getSource().getLevel(),
                                        Vec3Argument.getCoordinates(c, "location"),
                                        null,
                                        null
                                    )
                                )
                                .then(
                                    Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(
                                            c -> teleportToPos(
                                                c.getSource(),
                                                EntityArgument.getEntities(c, "targets"),
                                                c.getSource().getLevel(),
                                                Vec3Argument.getCoordinates(c, "location"),
                                                RotationArgument.getRotation(c, "rotation"),
                                                null
                                            )
                                        )
                                )
                                .then(
                                    Commands.literal("facing")
                                        .then(
                                            Commands.literal("entity")
                                                .then(
                                                    Commands.argument("facingEntity", EntityArgument.entity())
                                                        .executes(
                                                            c -> teleportToPos(
                                                                c.getSource(),
                                                                EntityArgument.getEntities(c, "targets"),
                                                                c.getSource().getLevel(),
                                                                Vec3Argument.getCoordinates(c, "location"),
                                                                null,
                                                                new LookAt.LookAtEntity(
                                                                    EntityArgument.getEntity(c, "facingEntity"), EntityAnchorArgument.Anchor.FEET
                                                                )
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("facingAnchor", EntityAnchorArgument.anchor())
                                                                .executes(
                                                                    c -> teleportToPos(
                                                                        c.getSource(),
                                                                        EntityArgument.getEntities(c, "targets"),
                                                                        c.getSource().getLevel(),
                                                                        Vec3Argument.getCoordinates(c, "location"),
                                                                        null,
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
                                                    c -> teleportToPos(
                                                        c.getSource(),
                                                        EntityArgument.getEntities(c, "targets"),
                                                        c.getSource().getLevel(),
                                                        Vec3Argument.getCoordinates(c, "location"),
                                                        null,
                                                        new LookAt.LookAtPosition(Vec3Argument.getVec3(c, "facingLocation"))
                                                    )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.argument("destination", EntityArgument.entity())
                                .executes(
                                    c -> teleportToEntity(c.getSource(), EntityArgument.getEntities(c, "targets"), EntityArgument.getEntity(c, "destination"))
                                )
                        )
                )
        );
        dispatcher.register(Commands.literal("tp").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).redirect(teleport));
    }

    private static int teleportToEntity(final CommandSourceStack source, final Collection<? extends Entity> entities, final Entity destination) throws CommandSyntaxException {
        for (Entity entity : entities) {
            io.papermc.paper.threadedregions.TeleportUtils.teleport(entity, false, destination, Float.valueOf(destination.getYRot()), Float.valueOf(destination.getXRot()), Entity.TELEPORT_FLAG_LOAD_CHUNK, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND, null); // Folia - region threading
        }

        if (entities.size() == 1) {
            source.sendSuccess(
                () -> Component.translatable(
                    "commands.teleport.success.entity.single", entities.iterator().next().getDisplayName(), destination.getDisplayName()
                ),
                true
            );
        } else {
            source.sendSuccess(() -> Component.translatable("commands.teleport.success.entity.multiple", entities.size(), destination.getDisplayName()), true);
        }

        return entities.size();
    }

    private static int teleportToPos(
        final CommandSourceStack source,
        final Collection<? extends Entity> entities,
        final ServerLevel level,
        final Coordinates destination,
        final @Nullable Coordinates rotation,
        final @Nullable LookAt lookAt
    ) throws CommandSyntaxException {
        Vec3 pos = destination.getPosition(source);
        Vec2 rot = rotation == null ? null : rotation.getRotation(source);

        for (Entity entity : entities) {
            Set<Relative> relatives = getRelatives(destination, rotation, entity.level().dimension() == level.dimension());
            if (rot == null) {
                performTeleport(source, entity, level, pos.x, pos.y, pos.z, relatives, entity.getYRot(), entity.getXRot(), lookAt);
            } else {
                performTeleport(source, entity, level, pos.x, pos.y, pos.z, relatives, rot.y, rot.x, lookAt);
            }
        }

        if (entities.size() == 1) {
            source.sendSuccess(
                () -> Component.translatable(
                    "commands.teleport.success.location.single",
                    entities.iterator().next().getDisplayName(),
                    formatDouble(pos.x),
                    formatDouble(pos.y),
                    formatDouble(pos.z)
                ),
                true
            );
        } else {
            source.sendSuccess(
                () -> Component.translatable(
                    "commands.teleport.success.location.multiple", entities.size(), formatDouble(pos.x), formatDouble(pos.y), formatDouble(pos.z)
                ),
                true
            );
        }

        return entities.size();
    }

    private static Set<Relative> getRelatives(final Coordinates destination, final @Nullable Coordinates rotation, final boolean sameDimension) {
        Set<Relative> dir = Relative.direction(destination.isXRelative(), destination.isYRelative(), destination.isZRelative());
        Set<Relative> pos = sameDimension ? Relative.position(destination.isXRelative(), destination.isYRelative(), destination.isZRelative()) : Set.of();
        Set<Relative> rot = rotation == null ? Relative.ROTATION : Relative.rotation(rotation.isYRelative(), rotation.isXRelative());
        return Relative.union(dir, pos, rot);
    }

    private static String formatDouble(final double value) {
        return String.format(Locale.ROOT, "%f", value);
    }

    private static void performTeleport(
        final CommandSourceStack source,
        final Entity victim,
        final ServerLevel level,
        final double x,
        final double y,
        final double z,
        final Set<Relative> relatives,
        final float yRot,
        final float xRot,
        final @Nullable LookAt lookAt
    ) throws CommandSyntaxException {
        BlockPos blockPos = BlockPos.containing(x, y, z);
        if (!Level.isInSpawnableBounds(blockPos)) {
            throw INVALID_POSITION.create();
        }

        double relativeOrAbsoluteX = relatives.contains(Relative.X) ? x - victim.getX() : x;
        double relativeOrAbsoluteY = relatives.contains(Relative.Y) ? y - victim.getY() : y;
        double relativeOrAbsoluteZ = relatives.contains(Relative.Z) ? z - victim.getZ() : z;
        float relativeOrAbsoluteYRot = relatives.contains(Relative.Y_ROT) ? yRot - victim.getYRot() : yRot;
        float relativeOrAbsoluteXRot = relatives.contains(Relative.X_ROT) ? xRot - victim.getXRot() : xRot;
        float newYRot = Mth.wrapDegrees(relativeOrAbsoluteYRot);
        float newXRot = Mth.wrapDegrees(relativeOrAbsoluteXRot);
        // Folia start - region threading
        if (true) {
            Vec3 posFinal = new Vec3(x, y, z);
            Float yawFinal = Float.valueOf(newYRot);
            Float pitchFinal = Float.valueOf(newXRot);
            victim.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
                nmsEntity.stopRiding();
                nmsEntity.teleportAsync(
                        level, posFinal, yawFinal, pitchFinal, Vec3.ZERO,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND,
                        Entity.TELEPORT_FLAG_LOAD_CHUNK | Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
                        null
                );
            }, null, 1L);
            return;
        }
        // Folia end - region threading
        if (victim.teleportTo(level, relativeOrAbsoluteX, relativeOrAbsoluteY, relativeOrAbsoluteZ, relatives, newYRot, newXRot, true, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND)) { // Paper - teleport cause
            if (lookAt != null) {
                lookAt.perform(source, victim);
            }

            if (!(victim instanceof LivingEntity living && living.isFallFlying())) {
                victim.setDeltaMovement(victim.getDeltaMovement().multiply(1.0, 0.0, 1.0));
                victim.setOnGround(true);
            }

            if (victim instanceof PathfinderMob mob) {
                mob.getNavigation().stop();
            }
        }
    }
}

package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.completable.CallbackCompletable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.function.Consumer;

public final class TeleportUtils {

    public static <T extends Entity> void teleport(final T from, final boolean useFromRootVehicle, final Entity to, final Float yaw, final Float pitch,
                                                   final long teleportFlags, final PlayerTeleportEvent.TeleportCause cause, final Consumer<Entity> onComplete) {
        teleport(from, useFromRootVehicle, to, yaw, pitch, teleportFlags, cause, onComplete, null);
    }

    public static <T extends Entity> void teleport(final T from, final boolean useFromRootVehicle, final Entity to, final Float yaw, final Float pitch,
                                                   final long teleportFlags, final PlayerTeleportEvent.TeleportCause cause, final Consumer<Entity> onComplete,
                                                   final java.util.function.Predicate<T> preTeleport) {
        // retrieve coordinates
        final CallbackCompletable<Location> positionCompletable = new CallbackCompletable<>();

        positionCompletable.addWaiter(
            (final Location loc, final Throwable thr) -> {
                if (loc == null) {
                    if (onComplete != null) {
                        onComplete.accept(null);
                    }
                    return;
                }
                final boolean scheduled = from.getBukkitEntity().taskScheduler.schedule(
                    (final T realFrom) -> {
                        final Vec3 pos = new Vec3(
                            loc.getX(), loc.getY(), loc.getZ()
                        );
                        if (preTeleport != null && !preTeleport.test(realFrom)) {
                            if (onComplete != null) {
                                onComplete.accept(null);
                            }
                            return;
                        }
                        (useFromRootVehicle ? realFrom.getRootVehicle() : realFrom).teleportAsync(
                            ((CraftWorld)loc.getWorld()).getHandle(), pos, null, null, null,
                            cause, teleportFlags, onComplete
                        );
                    },
                    (final Entity retired) -> {
                        if (onComplete != null) {
                            onComplete.accept(null);
                        }
                    },
                    1L
                );
                if (!scheduled) {
                    if (onComplete != null) {
                        onComplete.accept(null);
                    }
                }
            }
        );

        final boolean scheduled = to.getBukkitEntity().taskScheduler.schedule(
            (final Entity target) -> {
                positionCompletable.complete(target.getBukkitEntity().getLocation());
            },
            (final Entity retired) -> {
                if (onComplete != null) {
                    onComplete.accept(null);
                }
            },
            1L
        );
        if (!scheduled) {
            if (onComplete != null) {
                onComplete.accept(null);
            }
        }
    }

    private TeleportUtils() {}
}
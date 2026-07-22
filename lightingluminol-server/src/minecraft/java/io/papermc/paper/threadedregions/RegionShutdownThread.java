package io.papermc.paper.threadedregions;

import ca.spottedleaf.moonrise.common.util.WorldUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RegionShutdownThread extends ca.spottedleaf.moonrise.common.util.TickThread {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> shuttingDown;

    public RegionShutdownThread(final String name) {
        super(name);
        this.setUncaughtExceptionHandler((thread, thr) -> {
            LOGGER.error("Error shutting down server", thr);
        });
    }

    static ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> getRegion() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof RegionShutdownThread shutdownThread) {
            return shutdownThread.shuttingDown;
        }
        return null;
    }


    static RegionizedWorldData getWorldData() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof RegionShutdownThread shutdownThread) {
            // no fast path for shutting down
            if (shutdownThread.shuttingDown != null) {
                return shutdownThread.shuttingDown.getData().world.worldRegionData.get();
            }
        }
        return null;
    }

    // The region shutdown thread bypasses all tick thread checks, which will allow us to execute global saves
    // it will not however let us perform arbitrary sync loads, arbitrary world state lookups simply because
    // the data required to do that is regionised, and we can only access it when we OWN the region, and we do not.
    // Thus, the only operation that the shutdown thread will perform

    private void saveLevelData(final ServerLevel world) {
        try {
            world.saveLevelData(true);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to save level data for " + world.getWorld().getName(), thr);
        }
    }

    private void finishTeleportations(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
                                      final ServerLevel world) {
        try {
            this.shuttingDown = region;
            final List<ServerLevel.PendingTeleport> pendingTeleports = world.removeAllRegionTeleports();
            if (pendingTeleports.isEmpty()) {
                return;
            }
            final ChunkPos center = region.getCenterChunk();
            LOGGER.info("Completing " + pendingTeleports.size() + " pending teleports in region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'");
            for (final ServerLevel.PendingTeleport pendingTeleport : pendingTeleports) {
                LOGGER.info("Completing teleportation to target position " + pendingTeleport.to());

                // first, add entities to entity chunk so that they will be saved
                for (final Entity.EntityTreeNode node : pendingTeleport.rootVehicle().getFullTree()) {
                    // assume that world and position are set to destination here
                    node.root.setLevel(world); // in case the pending teleport is from a portal before it finds the exact destination
                    world.moonrise$getEntityLookup().addEntityForShutdownTeleportComplete(node.root);
                }

                // then, rebuild the passenger tree so that when saving only the root vehicle will be written - and if
                // there are any player passengers, that the later player saving will save the tree
                pendingTeleport.rootVehicle().restore();

                // now we are finished
                LOGGER.info("Completed teleportation to target position " + pendingTeleport.to());
            }
        } catch (final Throwable thr) {
            LOGGER.error("Failed to complete pending teleports", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    private void saveRegionChunks(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
                                  final boolean last) {
        ChunkPos center = null;
        try {
            this.shuttingDown = region;
            center = region.getCenterChunk();
            LOGGER.info("Saving chunks around region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'");
            region.regioniser.world.moonrise$getChunkTaskScheduler().chunkHolderManager.close(true, true, false, last, false);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to save chunks for region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    private void haltChunkSystem(final ServerLevel world) {
        try {
            world.moonrise$getChunkTaskScheduler().chunkHolderManager.close(false, true, true, false, false);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to halt chunk system for world '" + world.getWorld().getName() + "'", thr);
        }
    }

    private void closePlayerInventories(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        ChunkPos center = null;
        try {
            this.shuttingDown = region;
            center = region.getCenterChunk();

            final RegionizedWorldData worldData = region.regioniser.world.worldRegionData.get();

            for (final ServerPlayer player : worldData.getLocalPlayers()) {
                try {
                    // close inventory
                    if (player.containerMenu != player.inventoryMenu) {
                        player.closeContainer(InventoryCloseEvent.Reason.DISCONNECT);
                    }

                    // drop carried item
                    if (!player.containerMenu.getCarried().isEmpty()) {
                        ItemStack carried = player.containerMenu.getCarried();
                        player.containerMenu.setCarried(ItemStack.EMPTY);
                        player.drop(carried, false);
                    }
                } catch (final Throwable thr) {
                    LOGGER.error("Failed to close player inventory for player: " + player, thr);
                }
            }
        } catch (final Throwable thr) {
            LOGGER.error("Failed to close player inventories for region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    @Override
    public final void run() {
        // await scheduler termination
        LOGGER.info("Awaiting scheduler termination for 60s...");
        if (TickRegions.getScheduler().halt(true, TimeUnit.SECONDS.toNanos(60L))) {
            LOGGER.info("Scheduler halted");
        } else {
            LOGGER.warn("Scheduler did not terminate within 60s, proceeding with shutdown anyways");
            TickRegions.getScheduler().dumpAliveThreadTraces("Did not shut down in time");
        }

        MinecraftServer.getServer().stopServer(); // stop part 1: most logic, kicking players, plugins, etc
        // halt all chunk systems first so that any in-progress chunk generation stops
        LOGGER.info("Halting chunk systems...");
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            try {
                world.moonrise$getChunkTaskScheduler().halt(false, 0L);
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to soft halt chunk system for world '" + world.getWorld().getName() + "'", throwable);
            }
        }
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            this.haltChunkSystem(world);
        }
        LOGGER.info("Halted chunk systems");

        LOGGER.info("Finishing pending teleports...");
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>>
                regions = new ArrayList<>();
            world.regioniser.computeForAllRegionsUnsynchronised(regions::add);

            for (int i = 0, len = regions.size(); i < len; ++i) {
                this.finishTeleportations(regions.get(i), world);
            }
        }
        LOGGER.info("Finished pending teleports");

        LOGGER.info("Saving all worlds");
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            LOGGER.info("Saving world data for world '" + WorldUtil.getWorldName(world) + "'");

            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>>
                regions = new ArrayList<>();
            world.regioniser.computeForAllRegionsUnsynchronised(regions::add);

            LOGGER.info("Closing player inventories...");
            for (int i = 0, len = regions.size(); i < len; ++i) {
                this.closePlayerInventories(regions.get(i));
            }
            LOGGER.info("Closed player inventories");

            LOGGER.info("Saving chunks...");
            for (int i = 0, len = regions.size(); i < len; ++i) {
                this.saveRegionChunks(regions.get(i), (i + 1) == len);
            }
            LOGGER.info("Saved chunks");

            LOGGER.info("Saving level data...");
            this.saveLevelData(world);
            LOGGER.info("Saved level data");

            LOGGER.info("Saved world data for world '" + WorldUtil.getWorldName(world) + "'");
        }
        LOGGER.info("Saved all worlds");

        LOGGER.info("Saving global world data...");
        MinecraftServer.getServer().saveGlobalData(true);
        LOGGER.info("Saved global world data.");
        // Note: only save after world data and pending teleportations
        LOGGER.info("Saving all player data...");
        MinecraftServer.getServer().getPlayerList().saveAll();
        LOGGER.info("Saved all player data");

        MinecraftServer.getServer().stopPart2(); // stop part 2: close other resources (io thread, etc)
        // done, part 2 should call exit()
    }
}
package io.papermc.paper.threadedregions.commands;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.leafprofiler.RegionizedProfiler;
import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.util.MCUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.slf4j.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class CommandProfiler extends Command {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ThreadLocal<DecimalFormat> THREE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.000");
    });
    private static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.00");
    });
    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.0");
    });
    private static final ThreadLocal<DecimalFormat> NO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0");
    });
    private static final TextColor ORANGE = TextColor.color(255, 165, 0);

    public CommandProfiler() {
        super("profiler");
        this.setUsage("/<command> <world> <block x> <block z> <time in s> [radius, default 100 blocks]");
        this.setDescription("Reports information about server health.");
        this.setPermission("bukkit.command.tps");
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (args.length < 4 || args.length > 5) {
            sender.sendMessage(Component.text("Usage: /profiler <world> <block x> <block z> <time in s> [radius, default 100 blocks]", NamedTextColor.RED));
            return true;
        }

        final World world = Bukkit.getWorld(args[0]);
        if (world == null) {
            sender.sendMessage(Component.text("No such world '" + args[0] + "'", NamedTextColor.RED));
            return true;
        }

        final double blockX;
        final double blockZ;
        final double time; // seconds
        try {
            blockX = (args[1].equals("~") && sender instanceof Entity entity) ? entity.getLocation().getX() : Double.parseDouble(args[1]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage(Component.text("Invalid input for block x: " + args[1], NamedTextColor.RED));
            return true;
        }
        try {
            blockZ = (args[2].equals("~") && sender instanceof Entity entity) ? entity.getLocation().getZ() : Double.parseDouble(args[2]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage(Component.text("Invalid input for block z: " + args[2], NamedTextColor.RED));
            return true;
        }
        try {
            time = Double.parseDouble(args[3]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage(Component.text("Invalid input for time: " + args[3], NamedTextColor.RED));
            return true;
        }

        final double radius;
        if (args.length > 4) {
            try {
                radius = Double.parseDouble(args[4]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage(Component.text("Invalid input for radius: " + args[4], NamedTextColor.RED));
                return true;
            }
        } else {
            radius = 100.0;
        }

        final int fromChunkX = Mth.floor(blockX - radius) >> 4;
        final int fromChunkZ = Mth.floor(blockZ - radius) >> 4;
        final int toChunkX = Mth.floor(blockX + radius) >> 4;
        final int toChunkZ = Mth.floor(blockZ + radius) >> 4;

        final RegionizedProfiler profiler = new RegionizedProfiler(
            ThreadLocalRandom.current().nextLong(), (long)Math.ceil(time * 1.0E9),
            (final RegionizedProfiler.ProfileResults results) -> {
                MCUtil.ASYNC_EXECUTOR.execute(() -> {
                    writeResults(results);
                    sender.sendMessage(
                        Component.text()
                            .append(Component.text("Finished profiler #", NamedTextColor.DARK_GRAY))
                            .append(Component.text(Long.toString(results.profileId()), ORANGE))
                            .append(Component.text(", result available in ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("./profiler/" + results.profileId(), ORANGE))
                            .build()
                    );
                });
            }
        );

        final int regionCount = ((CraftWorld)world).getHandle().regioniser.computeForRegions(
            fromChunkX, fromChunkZ, toChunkX, toChunkZ,
            (final Set<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> set) -> {
                for (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : set) {
                    final TickRegions.TickRegionData data = region.getData();
                    final ChunkPos center = region.getCenterChunk();

                    if (data.profiler != null) {
                        MCUtil.ASYNC_EXECUTOR.execute(() -> {
                            sender.sendMessage(
                                Component.text()
                                    .append(Component.text("Region #", NamedTextColor.DARK_GRAY))
                                    .append(Component.text(region.id, ORANGE))
                                    .append(Component.text(" centered on ", NamedTextColor.DARK_GRAY))
                                    .append(Component.text(Objects.toString(center), ORANGE))
                                    .append(Component.text(" already is being profiled", NamedTextColor.DARK_GRAY))
                                    .build()
                            );
                        });
                        continue;
                    }

                    profiler.createProfiler(region);
                    MCUtil.ASYNC_EXECUTOR.execute(() -> {
                        sender.sendMessage(
                            Component.text()
                                .append(Component.text("Started profiler #", NamedTextColor.DARK_GRAY))
                                .append(Component.text(Long.toString(profiler.id), ORANGE))
                                .append(Component.text(" for region #", NamedTextColor.DARK_GRAY))
                                .append(Component.text(Long.toString(region.id), ORANGE))
                                .append(Component.text(" centered on chunk ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(Objects.toString(center), ORANGE))
                                .build()
                        );
                    });
                }
            }
        );

        if (regionCount == 0) {
            sender.sendMessage(
                Component.text()
                    .append(Component.text("No regions around specified location in radius to profile", NamedTextColor.RED))
            );
        }

        return true;
    }

    private static void writeLines(final File file, final List<String> lines) {
        try {
            Files.write(
                file.toPath(), lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            );
        } catch (final IOException ex) {
            LOGGER.warn("Failed to write to profiler file " + file.getAbsolutePath(), ex);
        }
    }

    private static void writeResults(final RegionizedProfiler.ProfileResults results) {
        final File directory = new File(new File(".", "profiler"), Long.toString(results.profileId()));

        directory.mkdirs();

        // write region data
        for (final RegionizedProfiler.RegionTimings regionTimings : results.timings()) {
            final File regionProfile = new File(directory, "region-" + regionTimings.regionId() + ".txt");
            final TickData.TickReportData tickReport = regionTimings.tickData().generateTickReport(null, regionTimings.endTime(), TickRegionScheduler.TIME_BETWEEN_TICKS);

            final List<String> out = new ArrayList<>();
            out.add("Total time: " + THREE_DECIMAL_PLACES.get().format(1.0E-9 * (regionTimings.endTime() - regionTimings.startTime())) + "s");
            out.add("Total Ticks: " + NO_DECIMAL_PLACES.get().format(tickReport == null ? 0 : tickReport.collectedTicks()));
            out.add("Utilisation: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 0.0 : 100.0 * tickReport.utilisation()) + "%");
            out.add("");
            out.add("Min TPS: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 20.0 : tickReport.tpsData().segmentAll().least()));
            out.add("Median TPS: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 20.0 : tickReport.tpsData().segmentAll().median()));
            out.add("Average TPS: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 20.0 : tickReport.tpsData().segmentAll().average()));
            out.add("Max TPS: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 20.0 : tickReport.tpsData().segmentAll().greatest()));
            out.add("");
            out.add("Min MSPT: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 0.0 : 1.0E-6 * tickReport.timePerTickData().segmentAll().least()));
            out.add("Median MSPT: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 0.0 : 1.0E-6 *tickReport.timePerTickData().segmentAll().median()));
            out.add("Average MSPT: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 0.0 : 1.0E-6 *tickReport.timePerTickData().segmentAll().average()));
            out.add("Max MSPT: " + THREE_DECIMAL_PLACES.get().format(tickReport == null ? 0.0 : 1.0E-6 *tickReport.timePerTickData().segmentAll().greatest()));
            out.add("");

            out.addAll(regionTimings.profiler().copyAccumulated().dumpToString());
            writeLines(regionProfile, out);
        }

        // write journal
        final File journal = new File(directory, "journal.txt");
        final List<String> journalLines = new ArrayList<>();

        for (final RegionizedProfiler.RecordedOperation operation : results.operations()) {
            final String indent = "    ";
            journalLines.add("Recorded Operation:");
            journalLines.add(indent + "Type: " + operation.type());
            journalLines.add(indent + "Time: " + THREE_DECIMAL_PLACES.get().format(1.0E-9 * (operation.time() - results.startTime())) + "s");
            journalLines.add(indent + "From Region: " + operation.regionOfInterest());
            journalLines.add(indent + "Target Other Regions: " + operation.targetRegions().toString());
        }

        journalLines.add("Total time: " + THREE_DECIMAL_PLACES.get().format(1.0E-9 * (results.endTime() - results.startTime())) + "s");
        writeLines(journal, journalLines);

    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) throws IllegalArgumentException {
        if (args.length == 0) {
            return CommandUtil.getSortedList(Bukkit.getWorlds(), World::getName);
        }
        if (args.length == 1) {
            return CommandUtil.getSortedList(Bukkit.getWorlds(), World::getName, args[0]);
        }
        return new ArrayList<>();
    }
}

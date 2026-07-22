package net.minecraft.world.level.portal;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

public class PortalForcer {
    public static final int TICKET_RADIUS = 3;
    private static final int NETHER_PORTAL_RADIUS = 16;
    private static final int OVERWORLD_PORTAL_RADIUS = 128;
    private static final int FRAME_HEIGHT = 5;
    private static final int FRAME_WIDTH = 4;
    private static final int FRAME_BOX = 3;
    private static final int FRAME_HEIGHT_START = -1;
    private static final int FRAME_HEIGHT_END = 4;
    private static final int FRAME_WIDTH_START = -1;
    private static final int FRAME_WIDTH_END = 3;
    private static final int FRAME_BOX_START = -1;
    private static final int FRAME_BOX_END = 2;
    private static final int NOTHING_FOUND = -1;
    private final ServerLevel level;

    public PortalForcer(final ServerLevel level) {
        this.level = level;
    }

    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper
    public Optional<BlockPos> findClosestPortalPosition(final BlockPos approximateExitPos, final boolean toNether, final WorldBorder worldBorder) {
        // CraftBukkit start
        return this.findClosestPortalPosition(approximateExitPos, worldBorder, toNether ? 16 : 128); // Search Radius
    }

    public Optional<BlockPos> findClosestPortalPosition(final BlockPos approximateExitPos, final WorldBorder worldBorder, final int radius) {
        PoiManager poiManager = this.level.getPoiManager();
        // int radius = toNether ? 16 : 128;
        // CraftBukkit end
        // Paper start - optimise poi lookup
        //poiManager.ensureLoadedAndValid(this.level, approximateExitPos, radius); // Paper - optimise poi lookup - we can let it load as it reads
        java.util.List<PoiRecord> records = new java.util.ArrayList<>();
        ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess.findClosestPoiDataRecords(
                poiManager, type -> type.is(PoiTypes.NETHER_PORTAL),
                (final net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType> type, final BlockPos pos) -> {
                    if (!worldBorder.isWithinBounds(pos)) {
                        return false;
                    }
                    // Paper start - Configurable nether ceiling damage
                    if (this.level.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.NETHER && this.level.paperConfig().environment.netherCeilingVoidDamageHeight.test(v -> pos.getY() >= v)) {
                        return false;
                    }
                    // Paper end - Configurable nether ceiling damage

                    net.minecraft.world.level.chunk.ChunkAccess lowest = this.level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY);
                    net.minecraft.world.level.levelgen.BelowZeroRetrogen belowZeroRetrogen;
                    if (!lowest.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                            // check below zero retrogen so that pre 1.17 worlds still load portals (JMP)
                            && ((belowZeroRetrogen = lowest.getBelowZeroRetrogen()) == null || !belowZeroRetrogen.targetStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.SPAWN))) {
                        // why would we generate the chunk?
                        return false;
                    }
                    return lowest.getBlockState(pos).hasProperty(BlockStateProperties.HORIZONTAL_AXIS);
                },
                approximateExitPos, radius, Double.MAX_VALUE, PoiManager.Occupancy.ANY, true, records
        );

        // this gets us most of the way there, but Vanilla biases lower y values.
        PoiRecord lowestYRecord = null;
        for (PoiRecord record : records) {
            if (lowestYRecord == null) {
                lowestYRecord = record;
            } else if (lowestYRecord.getPos().getY() > record.getPos().getY()) {
                lowestYRecord = record;
            }
        }
        // now we're done
        return Optional.ofNullable(lowestYRecord == null ? null : lowestYRecord.getPos());
        // Paper end - optimise poi lookup
    }

    public Optional<BlockUtil.FoundRectangle> createPortal(final BlockPos origin, final Direction.Axis portalAxis) {
        // CraftBukkit start
        return this.createPortal(origin, portalAxis, null, 16);
    }

    public Optional<BlockUtil.FoundRectangle> createPortal(final BlockPos origin, final Direction.Axis portalAxis, final net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity entity, final int createRadius) {
        // CraftBukkit end
        Direction direction = Direction.get(Direction.AxisDirection.POSITIVE, portalAxis);
        double closestFullDistanceSqr = -1.0;
        BlockPos closestFullPosition = null;
        double closestPartialDistanceSqr = -1.0;
        BlockPos closestPartialPosition = null;
        WorldBorder worldBorder = this.level.getWorldBorder();
        int maxPlaceableY = Math.min(this.level.getMaxY(), this.level.getMinY() + this.level.getLogicalHeight() - 1);
        // Paper start - Configurable nether ceiling damage; make sure the max height doesn't exceed the void damage height
        if (this.level.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.NETHER && this.level.paperConfig().environment.netherCeilingVoidDamageHeight.enabled()) {
            maxPlaceableY = Math.min(maxPlaceableY, this.level.paperConfig().environment.netherCeilingVoidDamageHeight.intValue() - 1);
        }
        // Paper end - Configurable nether ceiling damage
        int edgeDistance = 1;
        BlockPos.MutableBlockPos mutable = origin.mutable();

        for (BlockPos.MutableBlockPos columnPos : BlockPos.spiralAround(origin, createRadius, Direction.EAST, Direction.SOUTH)) { // CraftBukkit
            int height = Math.min(maxPlaceableY, this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, columnPos.getX(), columnPos.getZ()));
            if (worldBorder.isWithinBounds(columnPos) && worldBorder.isWithinBounds(columnPos.move(direction, 1))) {
                columnPos.move(direction.getOpposite(), 1);

                for (int y = height; y >= this.level.getMinY(); y--) {
                    columnPos.setY(y);
                    if (this.canPortalReplaceBlock(columnPos)) {
                        int firstEmptyY = y;

                        while (y > this.level.getMinY() && this.canPortalReplaceBlock(columnPos.move(Direction.DOWN))) {
                            y--;
                        }

                        if (y + 4 <= maxPlaceableY) {
                            int deltaY = firstEmptyY - y;
                            if (deltaY <= 0 || deltaY >= 3) {
                                columnPos.setY(y);
                                if (this.canHostFrame(columnPos, mutable, direction, 0)) {
                                    double distance = origin.distSqr(columnPos);
                                    if (this.canHostFrame(columnPos, mutable, direction, -1)
                                        && this.canHostFrame(columnPos, mutable, direction, 1)
                                        && (closestFullDistanceSqr == -1.0 || closestFullDistanceSqr > distance)) {
                                        closestFullDistanceSqr = distance;
                                        closestFullPosition = columnPos.immutable();
                                    }

                                    if (closestFullDistanceSqr == -1.0 && (closestPartialDistanceSqr == -1.0 || closestPartialDistanceSqr > distance)) {
                                        closestPartialDistanceSqr = distance;
                                        closestPartialPosition = columnPos.immutable();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (closestFullDistanceSqr == -1.0 && closestPartialDistanceSqr != -1.0) {
            closestFullPosition = closestPartialPosition;
            closestFullDistanceSqr = closestPartialDistanceSqr;
        }

        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(this.level); // CraftBukkit - Use BlockStateListPopulator
        if (closestFullDistanceSqr == -1.0) {
            int minStartY = Math.max(this.level.getMinY() - -1, 70);
            int maxStartY = maxPlaceableY - 9;
            if (maxStartY < minStartY) {
                return Optional.empty();
            }

            closestFullPosition = new BlockPos(
                    origin.getX() - direction.getStepX() * 1, Mth.clamp(origin.getY(), minStartY, maxStartY), origin.getZ() - direction.getStepZ() * 1
                )
                .immutable();
            closestFullPosition = worldBorder.clampToBounds(closestFullPosition);
            Direction clockWise = direction.getClockWise();

            for (int box = -1; box < 2; box++) {
                for (int width = 0; width < 2; width++) {
                    for (int height = -1; height < 3; height++) {
                        BlockState blockState = height < 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        mutable.setWithOffset(
                            closestFullPosition,
                            width * direction.getStepX() + box * clockWise.getStepX(),
                            height,
                            width * direction.getStepZ() + box * clockWise.getStepZ()
                        );
                        blockList.setBlock(mutable, blockState, Block.UPDATE_ALL); // CraftBukkit
                    }
                }
            }
        }

        for (int width = -1; width < 3; width++) {
            for (int height = -1; height < 4; height++) {
                if (width == -1 || width == 2 || height == -1 || height == 3) {
                    mutable.setWithOffset(closestFullPosition, width * direction.getStepX(), height, width * direction.getStepZ());
                    blockList.setBlock(mutable, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
                }
            }
        }

        BlockState portalBlockState = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, portalAxis);

        for (int width = 0; width < 2; width++) {
            for (int height = 0; height < 3; height++) {
                mutable.setWithOffset(closestFullPosition, width * direction.getStepX(), height, width * direction.getStepZ());
                blockList.setBlock(mutable, portalBlockState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); // CraftBukkit
            }
        }

        // CraftBukkit start
        org.bukkit.World bworld = this.level.getWorld();
        org.bukkit.event.world.PortalCreateEvent event = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blockList.getSnapshotBlocks(), bworld, entity == null ? null : entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.NETHER_PAIR);

        this.level.getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return Optional.empty();
        }
        blockList.placeBlocks();
        // CraftBukkit end
        return Optional.of(new BlockUtil.FoundRectangle(closestFullPosition.immutable(), 2, 3));
    }

    private boolean canPortalReplaceBlock(final BlockPos.MutableBlockPos pos) {
        BlockState blockState = this.level.getBlockState(pos);
        return blockState.canBeReplaced() && blockState.getFluidState().isEmpty();
    }

    private boolean canHostFrame(final BlockPos origin, final BlockPos.MutableBlockPos mutable, final Direction direction, final int offset) {
        Direction clockWise = direction.getClockWise();

        for (int width = -1; width < 3; width++) {
            for (int height = -1; height < 4; height++) {
                mutable.setWithOffset(
                    origin, direction.getStepX() * width + clockWise.getStepX() * offset, height, direction.getStepZ() * width + clockWise.getStepZ() * offset
                );
                // Paper start - Protect Bedrock and End Portal/Frames from being destroyed
                if (!io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits) {
                    if (!this.level.getBlockState(mutable).isDestroyable()) {
                        return false;
                    }
                }
                // Paper end - Protect Bedrock and End Portal/Frames from being destroyed
                if (height < 0 && !this.level.getBlockState(mutable).isSolid()) {
                    return false;
                }

                if (height >= 0 && !this.canPortalReplaceBlock(mutable)) {
                    return false;
                }
            }
        }

        return true;
    }
}

package net.minecraft.world.level.levelgen.structure.structures;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.SinglePieceStructure;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

public class DesertPyramidStructure extends SinglePieceStructure {
    public static final MapCodec<DesertPyramidStructure> CODEC = simpleCodec(DesertPyramidStructure::new);

    public DesertPyramidStructure(final Structure.StructureSettings settings) {
        super(DesertPyramidPiece::new, 21, 21, settings);
    }

    @Override
    public void afterPlace(
        final WorldGenLevel level,
        final StructureManager structureManager,
        final ChunkGenerator generator,
        final RandomSource random,
        final BoundingBox chunkBB,
        final ChunkPos chunkPos,
        final PiecesContainer pieces
    ) {
        Set<BlockPos> uniqueSandPlacements = SortedArraySet.create(Vec3i::compareTo);

        for (StructurePiece piece : pieces.pieces()) {
            if (piece instanceof DesertPyramidPiece desertPyramidPiece) {
                uniqueSandPlacements.addAll(desertPyramidPiece.getPotentialSuspiciousSandWorldPositions());
                placeSuspiciousSand(chunkBB, level, desertPyramidPiece.getRandomCollapsedRoofPos());
            }
        }

        ObjectArrayList<BlockPos> shuffledSandPlacements = new ObjectArrayList<>(uniqueSandPlacements.stream().toList());
        RandomSource positionalRandom = RandomSource.createThreadLocalInstance(level.getSeed()).forkPositional().at(pieces.calculateBoundingBox().getCenter());
        Util.shuffle(shuffledSandPlacements, positionalRandom);
        int suspiciousSandToPlace = Math.min(uniqueSandPlacements.size(), positionalRandom.nextInt(5, 8));

        for (BlockPos blockPos : shuffledSandPlacements) {
            if (suspiciousSandToPlace > 0) {
                suspiciousSandToPlace--;
                placeSuspiciousSand(chunkBB, level, blockPos);
            } else if (chunkBB.isInside(blockPos)) {
                level.setBlock(blockPos, Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void placeSuspiciousSand(final BoundingBox chunkBB, final WorldGenLevel level, final BlockPos blockPos) {
        if (chunkBB.isInside(blockPos)) {
            // CraftBukkit start
            if (level instanceof org.bukkit.craftbukkit.util.TransformerLevelAccessor transformerAccessor && transformerAccessor.canTransformBlocks()) {
                // todo never called cause it's called in afterPlace after the whole capture logic
                org.bukkit.craftbukkit.block.CraftBrushableBlock brushableState = (org.bukkit.craftbukkit.block.CraftBrushableBlock) org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, blockPos, Blocks.SUSPICIOUS_SAND.defaultBlockState(), null);
                brushableState.setLootTable(org.bukkit.craftbukkit.CraftLootTable.minecraftToBukkit(BuiltInLootTables.DESERT_PYRAMID_ARCHAEOLOGY));
                brushableState.setSeed(blockPos.asLong());
                transformerAccessor.setCraftBlock(blockPos, brushableState, Block.UPDATE_CLIENTS);
                return;
            }
            // CraftBukkit end
            level.setBlock(blockPos, Blocks.SUSPICIOUS_SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.getBlockEntity(blockPos, BlockEntityTypes.BRUSHABLE_BLOCK)
                .ifPresent(entity -> entity.setLootTable(BuiltInLootTables.DESERT_PYRAMID_ARCHAEOLOGY, blockPos.asLong()));
        }
    }

    @Override
    public StructureType<?> type() {
        return StructureType.DESERT_PYRAMID;
    }
}

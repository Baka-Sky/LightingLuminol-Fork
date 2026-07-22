package net.minecraft.world.level.chunk;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record PalettedContainerFactory(
    Strategy<BlockState> blockStatesStrategy,
    BlockState defaultBlockState,
    Codec<PalettedContainer<BlockState>> blockStatesContainerCodec,
    Strategy<Holder<Biome>> biomeStrategy,
    Holder<Biome> defaultBiome,
    Codec<PalettedContainerRO<Holder<Biome>>> biomeContainerCodec
    , Codec<PalettedContainer<Holder<Biome>>> biomeContainerRWCodec // Paper
) {
    public static PalettedContainerFactory create(final RegistryAccess registries) {
        Strategy<BlockState> blockStateStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        BlockState defaultBlockState = Blocks.AIR.defaultBlockState();
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomes.asHolderIdMap());
        Holder.Reference<Biome> defaultBiome = biomes.getOrThrow(Biomes.PLAINS);
        return new PalettedContainerFactory(
            blockStateStrategy,
            defaultBlockState,
            PalettedContainer.codecRW(BlockState.CODEC, blockStateStrategy, defaultBlockState, null), // Paper - Anti-Xray
            biomeStrategy,
            defaultBiome,
            PalettedContainer.codecRO(biomes.holderByNameCodec(), biomeStrategy, defaultBiome)
            , PalettedContainer.codecRW(biomes.holderByNameCodec(), biomeStrategy, defaultBiome, null) // Paper // Paper - Anti-Xray
        );
    }

    public PalettedContainer<BlockState> createForBlockStates() {
        // Paper start - Anti-Xray
        return new PalettedContainer<>(this.defaultBlockState, this.blockStatesStrategy, null);
    }

    public PalettedContainer<BlockState> createForBlockStates(net.minecraft.world.level.@org.jspecify.annotations.Nullable Level level, net.minecraft.world.level.ChunkPos chunkPos, int chunkSectionY) {
        net.minecraft.world.level.block.state.BlockState[] states = null;
        if (level != null
            // This check is needed because of a circular reference in ChunkPacketBlockControllerAntiXray when creating an empty chunk section
            && level.chunkPacketBlockController != null) {
            states = level.chunkPacketBlockController.getPresetBlockStates(level, chunkPos, chunkSectionY);
        }
        return new PalettedContainer<>(this.defaultBlockState, this.blockStatesStrategy, states);
    }

    public PalettedContainer<Holder<Biome>> createForBiomes() {
        return new PalettedContainer<>(this.defaultBiome, this.biomeStrategy, null);
        // Paper end - Anti-Xray
    }
}

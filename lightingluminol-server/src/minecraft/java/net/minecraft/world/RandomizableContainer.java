package net.minecraft.world;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public interface RandomizableContainer extends Container {
    String LOOT_TABLE_TAG = "LootTable";
    String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    @Nullable ResourceKey<LootTable> getLootTable();

    void setLootTable(final @Nullable ResourceKey<LootTable> lootTable);

    default void setLootTable(final @Nullable ResourceKey<LootTable> lootTable, final long seed) { // Paper - add nullable
        this.setLootTable(lootTable);
        this.setLootTableSeed(seed);
    }

    long getLootTableSeed();

    void setLootTableSeed(final long lootTableSeed);

    BlockPos getBlockPos();

    @Nullable Level getLevel();

    static void setBlockEntityLootTable(
        final BlockGetter level, final RandomSource random, final BlockPos blockEntityPos, final ResourceKey<LootTable> lootTable
    ) {
        if (level.getBlockEntity(blockEntityPos) instanceof RandomizableContainer randomizableContainer) {
            randomizableContainer.setLootTable(lootTable, random.nextLong());
        }
    }

    default boolean tryLoadLootTable(final ValueInput base) {
        ResourceKey<LootTable> lootTable = base.read("LootTable", LootTable.KEY_CODEC).orElse(null);
        this.setLootTable(lootTable);
        if (this.lootableData() != null && lootTable != null) this.lootableData().loadNbt(base); // Paper - LootTable API
        this.setLootTableSeed(base.getLongOr("LootTableSeed", 0L));
        return lootTable != null && this.lootableData() == null; // Paper - only track the loot table if there is chance for replenish
    }

    default boolean trySaveLootTable(final ValueOutput base) {
        ResourceKey<LootTable> lootTable = this.getLootTable();
        if (lootTable == null) {
            return false;
        }

        base.store("LootTable", LootTable.KEY_CODEC, lootTable);
        if (this.lootableData() != null) this.lootableData().saveNbt(base); // Paper - LootTable API
        long lootTableSeed = this.getLootTableSeed();
        if (lootTableSeed != 0L) {
            base.putLong("LootTableSeed", lootTableSeed);
        }

        return this.lootableData() == null; // Paper - only track the loot table if there is chance for replenish
    }

    default void unpackLootTable(final @Nullable Player player) {
        // Paper start - LootTable API
        this.unpackLootTable(player, false);
    }
    default void unpackLootTable(@Nullable final Player player, final boolean forceClearLootTable) {
        // Paper end - LootTable API
        Level level = this.getLevel();
        BlockPos worldPosition = this.getBlockPos();
        ResourceKey<LootTable> lootTableKey = this.getLootTable();
        // Paper start - LootTable API
        lootReplenish: if (lootTableKey != null && level != null && level.getServer() != null) {
            if (this.lootableData() != null && !this.lootableData().shouldReplenish(this, com.destroystokyo.paper.loottable.PaperLootableInventoryData.CONTAINER, player)) {
                if (forceClearLootTable) {
                    this.setLootTable(null);
                }
                break lootReplenish;
            }
            // Paper end - LootTable API
            LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(lootTableKey);
            if (player instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.GENERATE_LOOT.trigger(serverPlayer, lootTableKey);
            }

            if (forceClearLootTable || this.lootableData() == null || this.lootableData().shouldClearLootTable(this, com.destroystokyo.paper.loottable.PaperLootableInventoryData.CONTAINER, player)) { // Paper - LootTable API
            this.setLootTable(null);
            } // Paper - LootTable API
            LootParams.Builder params = new LootParams.Builder((ServerLevel)level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(worldPosition));
            if (player != null) {
                params.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
            }

            lootTable.fill(this, params.create(LootContextParamSets.CHEST), this.getLootTableSeed());
        }
    }

    // Paper start - LootTable API
    @org.jetbrains.annotations.Contract(pure = true)
    default com.destroystokyo.paper.loottable.@Nullable PaperLootableInventoryData lootableData() {
        return null; // some containers don't really have a "replenish" ability like decorated pots
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventory getLootableInventory() {
        final org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(java.util.Objects.requireNonNull(this.getLevel(), "Cannot manage loot tables on block entities not in world"), this.getBlockPos());
        return (com.destroystokyo.paper.loottable.PaperLootableInventory) block.getState(false);
    }
    // Paper end - LootTable API
}

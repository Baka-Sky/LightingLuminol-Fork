package net.minecraft.world.inventory;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class AnvilMenu extends ItemCombinerMenu {
    public static final int INPUT_SLOT = 0;
    public static final int ADDITIONAL_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean DEBUG_COST = false;
    public static final int MAX_NAME_LENGTH = 50;
    public int repairItemCountCost;
    public @Nullable String itemName;
    public final DataSlot cost = DataSlot.standalone();
    private boolean onlyRenaming = false;
    private static final int COST_FAIL = 0;
    private static final int COST_BASE = 1;
    private static final int COST_ADDED_BASE = 1;
    private static final int COST_REPAIR_MATERIAL = 1;
    private static final int COST_REPAIR_SACRIFICE = 2;
    private static final int COST_INCOMPATIBLE_PENALTY = 1;
    private static final int COST_RENAME = 1;
    private static final int INPUT_SLOT_X_PLACEMENT = 27;
    private static final int ADDITIONAL_SLOT_X_PLACEMENT = 76;
    private static final int RESULT_SLOT_X_PLACEMENT = 134;
    private static final int SLOT_Y_PLACEMENT = 47;
    // CraftBukkit start
    public static final int DEFAULT_DENIED_COST = -1;
    public int maximumRepairCost = 40;
    private org.bukkit.craftbukkit.inventory.view.CraftAnvilView bukkitEntity;
    // CraftBukkit end
    public boolean bypassEnchantmentLevelRestriction = false; // Paper - bypass anvil level restrictions

    public AnvilMenu(final int containerId, final Inventory inventory) {
        this(containerId, inventory, ContainerLevelAccess.NULL);
    }

    public AnvilMenu(final int containerId, final Inventory inventory, final ContainerLevelAccess access) {
        super(MenuType.ANVIL, containerId, inventory, access, createInputSlotDefinitions());
        this.addDataSlot(this.cost);
    }

    private static ItemCombinerMenuSlotDefinition createInputSlotDefinitions() {
        return ItemCombinerMenuSlotDefinition.create()
            .withSlot(0, 27, 47, itemStack -> true)
            .withSlot(1, 76, 47, itemStack -> true)
            .withResultSlot(2, 134, 47)
            .build();
    }

    @Override
    protected boolean isValidBlock(final BlockState state) {
        return state.is(BlockTags.ANVIL);
    }

    @Override
    protected boolean mayPickup(final Player player, final boolean hasItem) {
        return (player.hasInfiniteMaterials() || player.experienceLevel >= this.cost.get()) && this.cost.get() > AnvilMenu.DEFAULT_DENIED_COST && hasItem; // CraftBukkit - allow cost 0 like a free item
    }

    @Override
    protected void onTake(final Player player, final ItemStack carried) {
        if (!player.hasInfiniteMaterials()) {
            player.giveExperienceLevels(-this.cost.get());
        }

        if (this.repairItemCountCost > 0) {
            ItemStack addition = this.inputSlots.getItem(1);
            if (!addition.isEmpty() && addition.getCount() > this.repairItemCountCost) {
                addition.shrink(this.repairItemCountCost);
                this.inputSlots.setItem(1, addition);
            } else {
                this.inputSlots.setItem(1, ItemStack.EMPTY);
            }
        } else if (!this.onlyRenaming) {
            this.inputSlots.setItem(1, ItemStack.EMPTY);
        }

        this.cost.set(AnvilMenu.DEFAULT_DENIED_COST); // CraftBukkit - use a variable for set a cost for denied item
        if (player instanceof ServerPlayer serverPlayer
            && !StringUtil.isBlank(this.itemName)
            && !this.inputSlots.getItem(0).getHoverName().getString().equals(this.itemName)) {
            serverPlayer.getTextFilter().processStreamMessage(this.itemName);
        }

        this.inputSlots.setItem(0, ItemStack.EMPTY);
        this.access.execute((level, pos) -> {
            BlockState state = level.getBlockState(pos);
            if (!player.hasInfiniteMaterials() && state.is(BlockTags.ANVIL) && player.getRandom().nextFloat() < 0.12F) {
                BlockState newBlockState = AnvilBlock.damage(state);
                // Paper start - AnvilDamageEvent
                com.destroystokyo.paper.event.block.AnvilDamagedEvent event = new com.destroystokyo.paper.event.block.AnvilDamagedEvent(getBukkitView(), newBlockState != null ? newBlockState.asBlockData() : null);
                if (!event.callEvent()) {
                    return;
                } else if (event.getDamageState() == com.destroystokyo.paper.event.block.AnvilDamagedEvent.DamageState.BROKEN) {
                    newBlockState = null;
                } else {
                    newBlockState = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getDamageState().getMaterial().createBlockData()).getState().setValue(AnvilBlock.FACING, state.getValue(AnvilBlock.FACING));
                }
                // Paper end - AnvilDamageEvent
                if (newBlockState == null) {
                    level.removeBlock(pos, false);
                    level.levelEvent(LevelEvent.SOUND_ANVIL_BROKEN, pos, 0);
                } else {
                    level.setBlock(pos, newBlockState, Block.UPDATE_CLIENTS);
                    level.levelEvent(LevelEvent.SOUND_ANVIL_USED, pos, 0);
                }
            } else {
                level.levelEvent(LevelEvent.SOUND_ANVIL_USED, pos, 0);
            }
        });
    }

    @Override
    public void createResult() {
        ItemStack input = this.inputSlots.getItem(0);
        this.onlyRenaming = false;
        this.cost.set(1);
        int price = 0;
        long tax = 0L;
        int namingCost = 0;
        if (!input.isEmpty() && EnchantmentHelper.canStoreEnchantments(input)) {
            ItemStack result = input.copy();
            ItemStack addition = this.inputSlots.getItem(1);
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting(result));
            tax += (long)input.getOrDefault(DataComponents.REPAIR_COST, 0).intValue() + addition.getOrDefault(DataComponents.REPAIR_COST, 0).intValue();
            this.repairItemCountCost = 0;
            if (!addition.isEmpty()) {
                boolean usingBook = addition.has(DataComponents.STORED_ENCHANTMENTS);
                if (result.isDamageableItem() && input.isValidRepairItem(addition)) {
                    int repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
                    if (repairAmount <= 0) {
                        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
                        this.cost.set(AnvilMenu.DEFAULT_DENIED_COST); // CraftBukkit - use a variable for set a cost for denied item
                        return;
                    }

                    int count;
                    for (count = 0; repairAmount > 0 && count < addition.getCount(); count++) {
                        int resultDamage = result.getDamageValue() - repairAmount;
                        result.setDamageValue(resultDamage);
                        price++;
                        repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
                    }

                    this.repairItemCountCost = count;
                } else {
                    if (!usingBook && (!result.is(addition.getItem()) || !result.isDamageableItem())) {
                        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
                        this.cost.set(AnvilMenu.DEFAULT_DENIED_COST); // CraftBukkit - use a variable for set a cost for denied item
                        return;
                    }

                    if (result.isDamageableItem() && !usingBook) {
                        int remaining1 = input.getMaxDamage() - input.getDamageValue();
                        int remaining2 = addition.getMaxDamage() - addition.getDamageValue();
                        int additional = remaining2 + result.getMaxDamage() * 12 / 100;
                        int remaining = remaining1 + additional;
                        int resultDamage = result.getMaxDamage() - remaining;
                        if (resultDamage < 0) {
                            resultDamage = 0;
                        }

                        if (resultDamage < result.getDamageValue()) {
                            result.setDamageValue(resultDamage);
                            price += 2;
                        }
                    }

                    ItemEnchantments additionalEnchantments = EnchantmentHelper.getEnchantmentsForCrafting(addition);
                    boolean isAnyEnchantmentCompatible = false;
                    boolean isAnyEnchantmentNotCompatible = false;

                    for (Entry<Holder<Enchantment>> entry : additionalEnchantments.entrySet()) {
                        Holder<Enchantment> enchantmentHolder = entry.getKey();
                        int current = enchantments.getLevel(enchantmentHolder);
                        int level = entry.getIntValue();
                        level = current == level ? level + 1 : Math.max(level, current);
                        Enchantment enchantment = enchantmentHolder.value();
                        boolean compatible = enchantment.canEnchant(input);
                        if (this.player.hasInfiniteMaterials() || input.is(Items.ENCHANTED_BOOK)) {
                            compatible = true;
                        }

                        for (Holder<Enchantment> other : enchantments.keySet()) {
                            if (!other.equals(enchantmentHolder) && !Enchantment.areCompatible(enchantmentHolder, other)) {
                                compatible = false;
                                price++;
                            }
                        }

                        if (!compatible) {
                            isAnyEnchantmentNotCompatible = true;
                        } else {
                            isAnyEnchantmentCompatible = true;
                            if (level > enchantment.getMaxLevel() && !this.bypassEnchantmentLevelRestriction) { // Paper - bypass anvil level restrictions
                                level = enchantment.getMaxLevel();
                            }

                            enchantments.set(enchantmentHolder, level);
                            int fee = enchantment.getAnvilCost();
                            if (usingBook) {
                                fee = Math.max(1, fee / 2);
                            }

                            price += fee * level;
                            if (input.getCount() > 1) {
                                price = 40;
                            }
                        }
                    }

                    if (isAnyEnchantmentNotCompatible && !isAnyEnchantmentCompatible) {
                        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
                        this.cost.set(AnvilMenu.DEFAULT_DENIED_COST); // CraftBukkit - use a variable for set a cost for denied item
                        return;
                    }
                }
            }

            if (this.itemName != null && !StringUtil.isBlank(this.itemName)) {
                if (!this.itemName.equals(input.getHoverName().getString())) {
                    namingCost = 1;
                    price += namingCost;
                    result.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
                }
            } else if (input.has(DataComponents.CUSTOM_NAME)) {
                namingCost = 1;
                price += namingCost;
                result.remove(DataComponents.CUSTOM_NAME);
            }

            int finalPrice = price <= 0 ? 0 : (int)Mth.clamp(tax + price, 0L, 2147483647L);
            this.cost.set(finalPrice);
            if (price <= 0) {
                result = ItemStack.EMPTY;
            }

            if (namingCost == price && namingCost > 0) {
                // CraftBukkit start
                if (this.cost.get() >= this.maximumRepairCost) {
                    this.cost.set(this.maximumRepairCost - 1);
                // CraftBukkit end
                }

                this.onlyRenaming = true;
            }

            if (this.cost.get() >= this.maximumRepairCost && !this.player.hasInfiniteMaterials()) { // CraftBukkit
                result = ItemStack.EMPTY;
            }

            if (!result.isEmpty()) {
                int baseCost = result.getOrDefault(DataComponents.REPAIR_COST, 0);
                if (baseCost < addition.getOrDefault(DataComponents.REPAIR_COST, 0)) {
                    baseCost = addition.getOrDefault(DataComponents.REPAIR_COST, 0);
                }

                if (namingCost != price || namingCost == 0) {
                    baseCost = calculateIncreasedRepairCost(baseCost);
                }

                result.set(DataComponents.REPAIR_COST, baseCost);
                EnchantmentHelper.setEnchantments(result, enchantments.toImmutable());
            }

            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), result); // CraftBukkit
            this.broadcastChanges();
        } else {
            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
            this.cost.set(AnvilMenu.DEFAULT_DENIED_COST); // CraftBukkit - use a variable for set a cost for denied item
        }
        this.sendAllDataToRemote(); // CraftBukkit - SPIGOT-6686, SPIGOT-7931: Always send completed inventory to stay in sync with client
    }

    public static int calculateIncreasedRepairCost(final int baseCost) {
        return (int)Math.min(baseCost * 2L + 1L, 2147483647L);
    }

    public boolean setItemName(final String name) {
        String validatedName = validateName(name);
        if (validatedName != null && !validatedName.equals(this.itemName)) {
            this.itemName = validatedName;
            if (this.getSlot(2).hasItem()) {
                ItemStack itemStack = this.getSlot(2).getItem();
                if (StringUtil.isBlank(validatedName)) {
                    itemStack.remove(DataComponents.CUSTOM_NAME);
                } else {
                    itemStack.set(DataComponents.CUSTOM_NAME, Component.literal(validatedName));
                }
            }

            this.createResult();
            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, RESULT_SLOT); // Paper - Add PrepareResultEvent
            return true;
        } else {
            return false;
        }
    }

    private static @Nullable String validateName(final String name) {
        String filteredName = StringUtil.filterText(name);
        return filteredName.length() <= 50 ? filteredName : null;
    }

    public int getCost() {
        return this.cost.get();
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftAnvilView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryAnvil inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryAnvil(
                this.access.getLocation(), this.inputSlots, this.resultSlots);
        this.bukkitEntity = new org.bukkit.craftbukkit.inventory.view.CraftAnvilView(this.player.getBukkitEntity(), inventory, this);
        this.bukkitEntity.updateFromLegacy(inventory);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}

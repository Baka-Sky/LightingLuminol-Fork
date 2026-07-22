package net.minecraft.world.inventory;

import java.util.List;
import java.util.Optional;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.IdMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;

public class EnchantmentMenu extends AbstractContainerMenu {
    private static final Identifier EMPTY_SLOT_LAPIS_LAZULI = Identifier.withDefaultNamespace("container/slot/lapis_lazuli");
    private final Container enchantSlots; // Paper - Add missing InventoryHolders - move down
    private final ContainerLevelAccess access;
    private final RandomSource random = RandomSource.create();
    private final DataSlot enchantmentSeed = DataSlot.standalone();
    public final int[] costs = new int[3];
    public final int[] enchantClue = new int[]{-1, -1, -1};
    public final int[] levelClue = new int[]{-1, -1, -1};
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.@org.jspecify.annotations.Nullable CraftEnchantmentView view = null;
    private final org.bukkit.entity.Player player;
    // CraftBukkit end

    public EnchantmentMenu(final int containerId, final Inventory inventory) {
        this(containerId, inventory, ContainerLevelAccess.NULL);
    }

    public EnchantmentMenu(final int containerId, final Inventory inventory, final ContainerLevelAccess access) {
        super(MenuType.ENCHANTMENT, containerId);
        // Paper start - Add missing InventoryHolders
        this.enchantSlots = new SimpleContainer(this.createBlockHolder(access), 2) { // Paper - Add missing InventoryHolders
            @Override
            public void setChanged() {
                super.setChanged();
                EnchantmentMenu.this.slotsChanged(this);
            }

            // CraftBukkit start
            @Override
            public org.bukkit.Location getLocation() {
                return access.getLocation();
            }
            // CraftBukkit end
        };
        // Paper end - Add missing InventoryHolders
        this.access = access;
        this.addSlot(new Slot(this.enchantSlots, 0, 15, 47) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        this.addSlot(new Slot(this.enchantSlots, 1, 35, 47) {
            @Override
            public boolean mayPlace(final ItemStack itemStack) {
                return itemStack.is(Items.LAPIS_LAZULI);
            }

            @Override
            public Identifier getNoItemIcon() {
                return EnchantmentMenu.EMPTY_SLOT_LAPIS_LAZULI;
            }
        });
        this.addStandardInventorySlots(inventory, 8, 84);
        this.addDataSlot(DataSlot.shared(this.costs, 0));
        this.addDataSlot(DataSlot.shared(this.costs, 1));
        this.addDataSlot(DataSlot.shared(this.costs, 2));
        this.addDataSlot(this.enchantmentSeed).set(inventory.player.getEnchantmentSeed());
        this.addDataSlot(DataSlot.shared(this.enchantClue, 0));
        this.addDataSlot(DataSlot.shared(this.enchantClue, 1));
        this.addDataSlot(DataSlot.shared(this.enchantClue, 2));
        this.addDataSlot(DataSlot.shared(this.levelClue, 0));
        this.addDataSlot(DataSlot.shared(this.levelClue, 1));
        this.addDataSlot(DataSlot.shared(this.levelClue, 2));
        this.player = (org.bukkit.entity.Player) inventory.player.getBukkitEntity(); // CraftBukkit
    }

    @Override
    public void slotsChanged(final Container container) {
        if (container == this.enchantSlots) {
            ItemStack itemStack = container.getItem(0);
            if (!itemStack.isEmpty()) { // CraftBukkit - relax condition
                this.access.execute((level, pos) -> {
                    IdMap<Holder<Enchantment>> holders = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
                    int bookcases = 0;

                    for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
                        if (EnchantingTableBlock.isValidBookShelf(level, pos, offset)) {
                            bookcases++;
                        }
                    }

                    this.random.setSeed(this.enchantmentSeed.get());

                    for (int ixx = 0; ixx < 3; ixx++) {
                        this.costs[ixx] = EnchantmentHelper.getEnchantmentCost(this.random, ixx, bookcases, itemStack);
                        this.enchantClue[ixx] = -1;
                        this.levelClue[ixx] = -1;
                        if (this.costs[ixx] < ixx + 1) {
                            this.costs[ixx] = 0;
                        }
                    }

                    for (int ix = 0; ix < 3; ix++) {
                        if (this.costs[ix] > 0) {
                            List<EnchantmentInstance> list = this.getEnchantmentList(level.registryAccess(), itemStack, ix, this.costs[ix]);
                            if (!list.isEmpty()) {
                                EnchantmentInstance ench = list.get(this.random.nextInt(list.size()));
                                this.enchantClue[ix] = holders.getId(ench.enchantment());
                                this.levelClue[ix] = ench.level();
                            }
                        }
                    }

                    // CraftBukkit start
                    org.bukkit.craftbukkit.inventory.CraftItemStack craftItemStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack);
                    org.bukkit.enchantments.EnchantmentOffer[] offers = new org.bukkit.enchantments.EnchantmentOffer[3];
                    for (int j = 0; j < 3; ++j) {
                        org.bukkit.enchantments.Enchantment enchantment = (this.enchantClue[j] >= 0) ? org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(holders.byId(this.enchantClue[j])) : null;
                        offers[j] = (enchantment != null) ? new org.bukkit.enchantments.EnchantmentOffer(enchantment, this.levelClue[j], this.costs[j]) : null;
                    }

                    org.bukkit.event.enchantment.PrepareItemEnchantEvent event = new org.bukkit.event.enchantment.PrepareItemEnchantEvent(this.player, this.getBukkitView(), this.access.getLocation().getBlock(), craftItemStack, offers, bookcases);
                    event.setCancelled(!itemStack.isEnchantable());
                    level.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        for (int j = 0; j < 3; ++j) {
                            this.costs[j] = 0;
                            this.enchantClue[j] = -1;
                            this.levelClue[j] = -1;
                        }
                        return;
                    }

                    for (int j = 0; j < 3; j++) {
                        org.bukkit.enchantments.EnchantmentOffer offer = event.getOffers()[j];
                        if (offer != null) {
                            this.costs[j] = offer.getCost();
                            this.enchantClue[j] = holders.getId(org.bukkit.craftbukkit.enchantments.CraftEnchantment
                                .bukkitToMinecraftHolder(offer.getEnchantment()));
                            this.levelClue[j] = offer.getEnchantmentLevel();
                        } else {
                            if (enchantClue[j] != -1) this.costs[j] = 0;
                            this.enchantClue[j] = -1;
                            this.levelClue[j] = -1;
                        }
                    }
                    // CraftBukkit end

                    this.broadcastChanges();
                });
            } else {
                for (int i = 0; i < 3; i++) {
                    this.costs[i] = 0;
                    this.enchantClue[i] = -1;
                    this.levelClue[i] = -1;
                }
            }
        }
    }

    @Override
    public boolean clickMenuButton(final Player player, final int buttonId) {
        if (buttonId >= 0 && buttonId < this.costs.length) {
            ItemStack itemStack = this.enchantSlots.getItem(0);
            ItemStack currency = this.enchantSlots.getItem(1);
            int enchantmentCost = buttonId + 1;
            if ((currency.isEmpty() || currency.getCount() < enchantmentCost) && !player.hasInfiniteMaterials()) {
                return false;
            }

            if (this.costs[buttonId] <= 0
                || itemStack.isEmpty()
                || (player.experienceLevel < enchantmentCost || player.experienceLevel < this.costs[buttonId]) && !player.hasInfiniteMaterials()) {
                return false;
            }

            this.access.execute((level, pos) -> {
                ItemStack enchantmentItem = itemStack; // Paper - diff on change
                List<EnchantmentInstance> newEnchantment = this.getEnchantmentList(level.registryAccess(), enchantmentItem, buttonId, this.costs[buttonId]);
                // CraftBukkit start
                IdMap<Holder<Enchantment>> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
                if (true || !newEnchantment.isEmpty()) {
                    // player.onEnchantmentPerformed(enchantmentItem, enchantmentCost); // Moved down
                    java.util.Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new java.util.HashMap<>();
                    for (EnchantmentInstance instance : newEnchantment) {
                        enchants.put(org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(instance.enchantment()), instance.level());
                    }
                    org.bukkit.craftbukkit.inventory.CraftItemStack craftItemStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(enchantmentItem);
                    Holder<Enchantment> holder = registry.byId(this.enchantClue[buttonId]);
                    if (holder == null) return;
                    org.bukkit.enchantments.Enchantment hintedEnchantment = org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(holder);
                    int hintedEnchantmentLevel = this.levelClue[buttonId];
                    org.bukkit.event.enchantment.EnchantItemEvent event = new org.bukkit.event.enchantment.EnchantItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), this.getBukkitView(), this.access.getLocation().getBlock(), craftItemStack, this.costs[buttonId], enchants, hintedEnchantment, hintedEnchantmentLevel, buttonId);
                    level.getCraftServer().getPluginManager().callEvent(event);
                    int itemLevel = event.getExpLevelCost();
                    if (event.isCancelled() || (itemLevel > player.experienceLevel && !player.getAbilities().instabuild) || event.getEnchantsToAdd().isEmpty()) {
                        return;
                    }
                    // CraftBukkit end
                    // Paper start
                    enchantmentItem = org.bukkit.craftbukkit.inventory.CraftItemStack.getOrCloneOnMutation(craftItemStack, event.getItem());
                    if (enchantmentItem != itemStack) {
                        this.enchantSlots.setItem(0, enchantmentItem);
                    }
                    if (enchantmentItem.is(Items.BOOK)) {
                        enchantmentItem = enchantmentItem.transmuteCopy(Items.ENCHANTED_BOOK);
                        this.enchantSlots.setItem(0, enchantmentItem);
                    }
                    // Paper end

                    // CraftBukkit start
                    for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : event.getEnchantsToAdd().entrySet()) {
                        Holder<Enchantment> enchantment = org.bukkit.craftbukkit.enchantments.CraftEnchantment.bukkitToMinecraftHolder(entry.getKey());
                        if (enchantment == null) {
                            continue;
                        }

                        enchantmentItem.enchant(enchantment, entry.getValue());
                    }
                    player.onEnchantmentPerformed(enchantmentItem, enchantmentCost);
                    // CraftBukkit end

                    // CraftBukkit - TODO: let plugins change this
                    currency.consume(enchantmentCost, player);
                    if (currency.isEmpty()) {
                        this.enchantSlots.setItem(1, ItemStack.EMPTY);
                    }

                    player.awardStat(Stats.ENCHANT_ITEM);
                    if (player instanceof ServerPlayer serverPlayer) {
                        CriteriaTriggers.ENCHANTED_ITEM.trigger(serverPlayer, enchantmentItem, enchantmentCost);
                    }

                    this.enchantSlots.setChanged();
                    this.enchantmentSeed.set(player.getEnchantmentSeed());
                    this.slotsChanged(this.enchantSlots);
                    level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
                }
            });
            return true;
        } else {
            Util.logAndPauseIfInIde(player.getPlainTextName() + " pressed invalid button id: " + buttonId);
            return false;
        }
    }

    private List<EnchantmentInstance> getEnchantmentList(final RegistryAccess access, final ItemStack itemStack, final int slot, final int enchantmentCost) {
        this.random.setSeed(this.enchantmentSeed.get() + slot);
        Optional<HolderSet.Named<Enchantment>> tag = access.lookupOrThrow(Registries.ENCHANTMENT).get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tag.isEmpty()) {
            return List.of();
        }

        List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(this.random, itemStack, enchantmentCost, tag.get().stream());
        if (itemStack.is(Items.BOOK) && list.size() > 1) {
            list.remove(this.random.nextInt(list.size()));
        }

        return list;
    }

    public int getGoldCount() {
        ItemStack goldStack = this.enchantSlots.getItem(1);
        return goldStack.isEmpty() ? 0 : goldStack.getCount();
    }

    // Paper start - add enchantment seed update API
    public void setEnchantmentSeed(int seed) {
        this.enchantmentSeed.set(seed);
    }
    // Paper end - add enchantment seed update API

    public int getEnchantmentSeed() {
        return this.enchantmentSeed.get();
    }

    @Override
    public void removed(final Player player) {
        super.removed(player);
        this.access.execute((level, pos) -> this.clearContainer(player, this.enchantSlots));
    }

    @Override
    public boolean stillValid(final Player player) {
        if (!this.checkReachable) return true; // CraftBukkit
        return stillValid(this.access, player, Blocks.ENCHANTING_TABLE);
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();
            if (slotIndex == 0) {
                if (!this.moveItemStackTo(stack, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (slotIndex == 1) {
                if (!this.moveItemStackTo(stack, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.is(Items.LAPIS_LAZULI)) {
                if (!this.moveItemStackTo(stack, 1, 2, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (this.slots.get(0).hasItem() || !this.slots.get(0).mayPlace(stack)) {
                    return ItemStack.EMPTY;
                }

                ItemStack singleItem = stack.copyWithCount(1);
                stack.shrink(1);
                this.slots.get(0).setByPlayer(singleItem);
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stack.getCount() == clicked.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stack);
        }

        return clicked;
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftEnchantmentView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryEnchanting inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryEnchanting(this.enchantSlots);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftEnchantmentView(this.player, inventory, this);
        return this.view;
    }
    // CraftBukkit end
}

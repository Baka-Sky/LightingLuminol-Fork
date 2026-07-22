package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.Merchant;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftMerchant;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public abstract class CraftAbstractVillager extends CraftAgeable implements CraftMerchant, AbstractVillager, InventoryHolder {

    public CraftAbstractVillager(CraftServer server, net.minecraft.world.entity.npc.villager.AbstractVillager entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.npc.villager.AbstractVillager getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (Villager) this.entity;
    }

    @Override
    public Merchant getMerchant() {
        return this.getHandle();
    }

    @Override
    public Inventory getInventory() {
        return new CraftInventory(this.getHandle().getInventory());
    }

    @Override
    public void resetOffers() {
        getHandle().resetOffers();
    }
}

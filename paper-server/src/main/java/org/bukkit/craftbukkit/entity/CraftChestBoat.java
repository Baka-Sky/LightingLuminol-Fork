package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.inventory.Inventory;

public abstract class CraftChestBoat extends CraftBoat implements org.bukkit.entity.ChestBoat, com.destroystokyo.paper.loottable.PaperLootableEntityInventory { // Paper

    public CraftChestBoat(CraftServer server, AbstractChestBoat entity) {
        super(server, entity);
    }

    @Override
    public AbstractChestBoat getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (AbstractChestBoat) this.entity;
    }

    @Override
    public Inventory getInventory() {
        return new CraftInventory(getHandle());
    }
}

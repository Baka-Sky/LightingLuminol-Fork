package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.entity.Pillager;
import org.bukkit.inventory.Inventory;

public class CraftPillager extends CraftIllager implements Pillager, com.destroystokyo.paper.entity.CraftRangedEntity<net.minecraft.world.entity.monster.illager.Pillager> { // Paper

    public CraftPillager(CraftServer server, net.minecraft.world.entity.monster.illager.Pillager entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.illager.Pillager getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.illager.Pillager) this.entity;
    }

    @Override
    public Inventory getInventory() {
        return new CraftInventory(this.getHandle().getInventory());
    }
}

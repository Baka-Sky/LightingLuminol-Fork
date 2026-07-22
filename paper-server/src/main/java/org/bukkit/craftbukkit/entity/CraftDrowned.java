package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Drowned;

public class CraftDrowned extends CraftZombie implements Drowned, com.destroystokyo.paper.entity.CraftRangedEntity<net.minecraft.world.entity.monster.zombie.Drowned> { // Paper

    public CraftDrowned(CraftServer server, net.minecraft.world.entity.monster.zombie.Drowned entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.zombie.Drowned getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.zombie.Drowned) this.entity;
    }
}

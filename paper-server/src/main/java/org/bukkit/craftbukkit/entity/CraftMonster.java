package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Monster;

public class CraftMonster extends CraftCreature implements Monster, CraftEnemy {

    public CraftMonster(CraftServer server, net.minecraft.world.entity.monster.Monster entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.Monster getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.Monster) this.entity;
    }
}

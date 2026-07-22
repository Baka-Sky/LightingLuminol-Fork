package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Illusioner;

public class CraftIllusioner extends CraftSpellcaster implements Illusioner, com.destroystokyo.paper.entity.CraftRangedEntity<net.minecraft.world.entity.monster.illager.Illusioner> { // Paper

    public CraftIllusioner(CraftServer server, net.minecraft.world.entity.monster.illager.Illusioner entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.illager.Illusioner getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.illager.Illusioner) this.entity;
    }
}

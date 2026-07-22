package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Silverfish;

public class CraftSilverfish extends CraftMonster implements Silverfish {

    public CraftSilverfish(CraftServer server, net.minecraft.world.entity.monster.Silverfish entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.Silverfish getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.Silverfish) this.entity;
    }
}

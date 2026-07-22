package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.animal.golem.AbstractGolem;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Golem;

public class CraftGolem extends CraftCreature implements Golem {

    public CraftGolem(CraftServer server, AbstractGolem entity) {
        super(server, entity);
    }

    @Override
    public AbstractGolem getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (AbstractGolem) this.entity;
    }
}

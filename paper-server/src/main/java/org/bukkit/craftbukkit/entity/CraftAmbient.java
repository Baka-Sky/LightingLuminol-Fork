package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.ambient.AmbientCreature;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Ambient;

public class CraftAmbient extends CraftMob implements Ambient {
    public CraftAmbient(CraftServer server, AmbientCreature entity) {
        super(server, entity);
    }

    @Override
    public AmbientCreature getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (AmbientCreature) this.entity;
    }
}

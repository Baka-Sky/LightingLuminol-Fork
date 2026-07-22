package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.animal.camel.CamelHusk;
import org.bukkit.craftbukkit.CraftServer;

public class CraftCamelHusk extends CraftCamel implements org.bukkit.entity.CamelHusk {
    public CraftCamelHusk(final CraftServer server, final CamelHusk entity) {
        super(server, entity);
    }

    @Override
    public CamelHusk getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (CamelHusk) this.entity;
    }
}

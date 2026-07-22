package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.bukkit.craftbukkit.CraftServer;

public class CraftBlockAttachedEntity extends CraftEntity {
    public CraftBlockAttachedEntity(CraftServer server, BlockAttachedEntity entity) {
        super(server, entity);
    }

    @Override
    public BlockAttachedEntity getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (BlockAttachedEntity) this.entity;
    }
}

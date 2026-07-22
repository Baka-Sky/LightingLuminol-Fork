package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Slime;

public class CraftSlime extends CraftAbstractCubeMob implements Slime, CraftEnemy {
    public CraftSlime(final CraftServer server, final net.minecraft.world.entity.monster.cubemob.Slime entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.cubemob.Slime getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.cubemob.Slime) this.entity;
    }
}

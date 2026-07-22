package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.GlowItemFrame;

public class CraftGlowItemFrame extends CraftItemFrame implements GlowItemFrame {

    public CraftGlowItemFrame(CraftServer server, net.minecraft.world.entity.decoration.GlowItemFrame entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.decoration.GlowItemFrame getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.decoration.GlowItemFrame) this.entity;
    }
}

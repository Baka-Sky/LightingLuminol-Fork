package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.CaveSpider;

public class CraftCaveSpider extends CraftSpider implements CaveSpider {
    public CraftCaveSpider(CraftServer server, net.minecraft.world.entity.monster.spider.CaveSpider entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.spider.CaveSpider getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.spider.CaveSpider) this.entity;
    }
}

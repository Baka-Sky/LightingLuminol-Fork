package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Spider;

public class CraftSpider extends CraftMonster implements Spider {

    public CraftSpider(CraftServer server, net.minecraft.world.entity.monster.spider.Spider entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.spider.Spider getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.spider.Spider) this.entity;
    }
}

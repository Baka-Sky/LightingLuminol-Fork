package meow.bacteriawa.lightingluminol.function;

import meow.bacteriawa.lightingluminol.config.FunctionConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PortalRateLimitManager {
    private static final Map<Long, AtomicInteger> portalTeleportCounts = new ConcurrentHashMap<>();
    private static long currentTick = 0;

    public static void onServerTick() {
        currentTick++;
        if (currentTick % 100 == 0) {
            portalTeleportCounts.clear();
        }
    }

    public static boolean canTeleport(Entity entity) {
        if (!FunctionConfig.PortalRateLimit.enable) {
            return true;
        }

        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return true;
        }

        var region = serverLevel.regioniser.getRegionAtUnsynchronised(entity.blockPosition().getX() >> 4, entity.blockPosition().getZ() >> 4);
        if (region == null) {
            return true;
        }

        long regionKey = region.getData().id;
        AtomicInteger count = portalTeleportCounts.computeIfAbsent(regionKey, k -> new AtomicInteger(0));

        int maxLimit = FunctionConfig.PortalRateLimit.maximumPortalTeleportsPerTick;
        if (maxLimit <= 0) {
            maxLimit = 200;
        }

        int current = count.incrementAndGet();
        return current <= maxLimit;
    }
}
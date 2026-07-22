package net.minecraft.world.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;

public class NearestItemSensor extends Sensor<Mob> {
    private static final long XZ_RANGE = 32L;
    private static final long Y_RANGE = 16L;
    public static final int MAX_DISTANCE_TO_WANTED_ITEM = 32;

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM);
    }

    @Override
    protected void doTick(final ServerLevel level, final Mob body) {
        Brain<?> brain = body.getBrain();
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(32.0, 16.0, 32.0), item -> item.closerThan(body, MAX_DISTANCE_TO_WANTED_ITEM) && body.wantsToPickUp(level, item.getItem())); // Paper - Perf: Move predicate into getEntities
        items.sort(Comparator.comparingDouble(body::distanceToSqr));
        // Paper start - Perf: remove streams from hot code
        ItemEntity nearest = null;
        for (final ItemEntity item : items) {
            if (body.hasLineOfSight(item)) { // Paper - Perf: Move predicate into getEntities
                nearest = item;
                break;
            }
        }
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, Optional.ofNullable(nearest));
        // Paper end - Perf: remove streams from hot code
    }
}

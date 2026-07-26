package net.minecraft.world.entity.ai.memory;

import net.minecraft.world.entity.ai.Brain;
import org.jspecify.annotations.Nullable;

public class MemorySlot<T> {
    private static final long NEVER_EXPIRE = Long.MAX_VALUE;
    private @Nullable T value;
    private long timeToLive;

    private MemorySlot(final @Nullable T value, final long timeToLive) {
        this.value = value;
        this.timeToLive = timeToLive;
    }

    public void tick(net.minecraft.world.entity.Entity owner) { // LightingLuminol - Add config to force clean entity memory that don't belong to current tick region
        if (this.hasValue() && this.canExpire()) {
            if (this.hasExpired()) {
                this.clear();
            } else {
                this.timeToLive--;
            }
        }
        // LightingLuminol start - Add config to force clean entity memory that don't belong to current tick region
        final net.minecraft.world.level.Level ownerLevel = owner.level();

        // type: entity
        if (meow.bacteriawa.lightingluminol.config.FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForEntity && this.value instanceof net.minecraft.world.entity.Entity entity) {
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) {
                this.clear();
            }
        }

        // type: block_pos
        if (meow.bacteriawa.lightingluminol.config.FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForBlockPos && this.value instanceof net.minecraft.core.BlockPos blockPos) {
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(ownerLevel, blockPos)) {
                this.clear();
            }
        }

        // type: position_tracker and walk_target
        if (meow.bacteriawa.lightingluminol.config.FixesConfig.ForceCleanupDropNonOwnedEntityMemoryModule.enabledForPositionTracker) {
            net.minecraft.world.entity.ai.behavior.PositionTracker tracker = null;

            if (value instanceof net.minecraft.world.entity.ai.behavior.PositionTracker positionTracker) {
                tracker = positionTracker;
            }

            if (value instanceof net.minecraft.world.entity.ai.memory.WalkTarget walkTarget) {
                tracker = walkTarget.getTarget();
            }

            if (tracker != null && !tracker.checkThread(owner.level())) {
                this.clear();
            }
        }
        // LightingLuminol end
    }

    public static <T> MemorySlot<T> create() {
        return new MemorySlot<>(null, Long.MAX_VALUE);
    }

    public void set(final T value, final long timeToLive) {
        this.value = value;
        this.timeToLive = timeToLive;
    }

    public void set(final T value) {
        this.set(value, Long.MAX_VALUE);
    }

    public void clear() {
        this.value = null;
        this.timeToLive = Long.MAX_VALUE;
    }

    public boolean hasValue() {
        return this.value != null;
    }

    public @Nullable T value() {
        return this.value;
    }

    public boolean canExpire() {
        return this.timeToLive != Long.MAX_VALUE;
    }

    public boolean hasExpired() {
        return this.timeToLive <= 0L;
    }

    public long timeToLive() {
        return this.timeToLive;
    }

    @Override
    public String toString() {
        return this.value == null ? "<empty>" : this.value + (this.canExpire() ? " (ttl: " + this.timeToLive + ")" : "");
    }

    public void visit(final MemoryModuleType<T> type, final Brain.Visitor visitor) {
        if (this.value != null) {
            if (this.canExpire()) {
                visitor.accept(type, this.value, this.timeToLive);
            } else {
                visitor.accept(type, this.value);
            }
        } else {
            visitor.acceptEmpty(type);
        }
    }
}

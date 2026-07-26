package net.minecraft.world.entity.ai.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public interface PositionTracker {
    Vec3 currentPosition();

    BlockPos currentBlockPosition();

    boolean isVisibleBy(final LivingEntity body);

    // LightingLuminol start - Fix a series issue around entity memory typed GlobalPos and WalkTarget
    boolean checkThread(net.minecraft.world.level.Level currOwnedByLevel);
    // LightingLuminol end
}

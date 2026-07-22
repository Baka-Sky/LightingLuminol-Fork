package net.minecraft.world.level.chunk.status;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jspecify.annotations.Nullable;

// Paper start - rewrite chunk system - convert record to class
public final class ChunkStep implements ca.spottedleaf.moonrise.patches.chunk_system.status.ChunkSystemChunkStep { // Paper - rewrite chunk system
    private final ChunkStatus targetStatus;
    private final ChunkDependencies directDependencies;
    private final ChunkDependencies accumulatedDependencies;
    private final int blockStateWriteRadius;
    private final ChunkStatusTask task;

    private final ChunkStatus[] byRadius; // Paper - rewrite chunk system

    public ChunkStep(
        ChunkStatus targetStatus, ChunkDependencies directDependencies, ChunkDependencies accumulatedDependencies, int blockStateWriteRadius, ChunkStatusTask task
    ) {
        this.targetStatus = targetStatus;
        this.directDependencies = directDependencies;
        this.accumulatedDependencies = accumulatedDependencies;
        this.blockStateWriteRadius = blockStateWriteRadius;
        this.task = task;

        // Paper start - rewrite chunk system
        this.byRadius = new ChunkStatus[this.getAccumulatedRadiusOf(ChunkStatus.EMPTY) + 1];
        this.byRadius[0] = targetStatus.getParent();

        for (ChunkStatus status = targetStatus.getParent(); status != ChunkStatus.EMPTY; status = status.getParent()) {
            final int radius = this.getAccumulatedRadiusOf(status);

            for (int j = 0; j <= radius; ++j) {
                if (this.byRadius[j] == null) {
                    this.byRadius[j] = status;
                }
            }
        }
        // Paper end - rewrite chunk system
    }

    // Paper start - rewrite chunk system
    @Override
    public final ChunkStatus moonrise$getRequiredStatusAtRadius(final int radius) {
        return this.byRadius[radius];
    }
    // Paper end - rewrite chunk system

    // Paper end - rewrite chunk system - convert record to class

    public int getAccumulatedRadiusOf(final ChunkStatus status) {
        return status == this.targetStatus ? 0 : this.accumulatedDependencies.getRadiusOf(status);
    }

    public CompletableFuture<ChunkAccess> apply(final WorldGenContext context, final StaticCache2D<GenerationChunkHolder> cache, final ChunkAccess chunk) {
        if (chunk.getPersistedStatus().isBefore(this.targetStatus)) {
            ProfiledDuration profiledDuration = JvmProfiler.INSTANCE.onChunkGenerate(chunk.getPos(), context.level().dimension(), this.targetStatus.getName());
            return this.task.doWork(context, this, cache, chunk).thenApply(newCenterChunk -> this.completeChunkGeneration(newCenterChunk, profiledDuration));
        } else {
            return this.task.doWork(context, this, cache, chunk);
        }
    }

    private ChunkAccess completeChunkGeneration(final ChunkAccess newCenterChunk, final @Nullable ProfiledDuration profiledDuration) {
        if (newCenterChunk instanceof ProtoChunk protochunk && protochunk.getPersistedStatus().isBefore(this.targetStatus)) {
            protochunk.setPersistedStatus(this.targetStatus);
        }

        if (profiledDuration != null) {
            profiledDuration.finish(true);
        }

        return newCenterChunk;
    }

    // Paper start - rewrite chunk system - convert record to class
    public ChunkStatus targetStatus() {
        return targetStatus;
    }

    public ChunkDependencies directDependencies() {
        return directDependencies;
    }

    public ChunkDependencies accumulatedDependencies() {
        return accumulatedDependencies;
    }

    public int blockStateWriteRadius() {
        return blockStateWriteRadius;
    }

    public ChunkStatusTask task() {
        return task;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (net.minecraft.world.level.chunk.status.ChunkStep) obj;
        return java.util.Objects.equals(this.targetStatus, that.targetStatus) &&
            java.util.Objects.equals(this.directDependencies, that.directDependencies) &&
            java.util.Objects.equals(this.accumulatedDependencies, that.accumulatedDependencies) &&
            this.blockStateWriteRadius == that.blockStateWriteRadius &&
            java.util.Objects.equals(this.task, that.task);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(targetStatus, directDependencies, accumulatedDependencies, blockStateWriteRadius, task);
    }

    @Override
    public String toString() {
        return "ChunkStep[" +
            "targetStatus=" + targetStatus + ", " +
            "directDependencies=" + directDependencies + ", " +
            "accumulatedDependencies=" + accumulatedDependencies + ", " +
            "blockStateWriteRadius=" + blockStateWriteRadius + ", " +
            "task=" + task + ']';
    }
    // Paper end - rewrite chunk system - convert record to class

    public static class Builder {
        private final ChunkStatus status;
        private final @Nullable ChunkStep parent;
        private ChunkStatus[] directDependenciesByRadius;
        private int blockStateWriteRadius = -1;
        private ChunkStatusTask task = ChunkStatusTasks::passThrough;

        protected Builder(final ChunkStatus status) {
            if (status.getParent() != status) {
                throw new IllegalArgumentException("Not starting with the first status: " + status);
            }

            this.status = status;
            this.parent = null;
            this.directDependenciesByRadius = new ChunkStatus[0];
        }

        protected Builder(final ChunkStatus status, final ChunkStep parent) {
            if (parent.targetStatus.getIndex() != status.getIndex() - 1) {
                throw new IllegalArgumentException("Out of order status: " + status);
            }

            this.status = status;
            this.parent = parent;
            this.directDependenciesByRadius = new ChunkStatus[]{parent.targetStatus};
        }

        public ChunkStep.Builder addRequirement(final ChunkStatus status, final int radius) {
            if (status.isOrAfter(this.status)) {
                throw new IllegalArgumentException("Status " + status + " can not be required by " + this.status);
            }

            ChunkStatus[] previous = this.directDependenciesByRadius;
            int newLength = radius + 1;
            if (newLength > previous.length) {
                this.directDependenciesByRadius = new ChunkStatus[newLength];
                Arrays.fill(this.directDependenciesByRadius, status);
            }

            for (int i = 0; i < Math.min(newLength, previous.length); i++) {
                this.directDependenciesByRadius[i] = ChunkStatus.max(previous[i], status);
            }

            return this;
        }

        public ChunkStep.Builder blockStateWriteRadius(final int radius) {
            this.blockStateWriteRadius = radius;
            return this;
        }

        public ChunkStep.Builder setTask(final ChunkStatusTask task) {
            this.task = task;
            return this;
        }

        public ChunkStep build() {
            return new ChunkStep(
                this.status,
                new ChunkDependencies(ImmutableList.copyOf(this.directDependenciesByRadius)),
                new ChunkDependencies(ImmutableList.copyOf(this.buildAccumulatedDependencies())),
                this.blockStateWriteRadius,
                this.task
            );
        }

        private ChunkStatus[] buildAccumulatedDependencies() {
            if (this.parent == null) {
                return this.directDependenciesByRadius;
            }

            int radiusOfParent = this.getRadiusOfParent(this.parent.targetStatus);
            ChunkDependencies parentDependencies = this.parent.accumulatedDependencies;
            ChunkStatus[] accumulatedDependencies = new ChunkStatus[Math.max(radiusOfParent + parentDependencies.size(), this.directDependenciesByRadius.length)];

            for (int distance = 0; distance < accumulatedDependencies.length; distance++) {
                int distanceInParent = distance - radiusOfParent;
                if (distanceInParent < 0 || distanceInParent >= parentDependencies.size()) {
                    accumulatedDependencies[distance] = this.directDependenciesByRadius[distance];
                } else if (distance >= this.directDependenciesByRadius.length) {
                    accumulatedDependencies[distance] = parentDependencies.get(distanceInParent);
                } else {
                    accumulatedDependencies[distance] = ChunkStatus.max(this.directDependenciesByRadius[distance], parentDependencies.get(distanceInParent));
                }
            }

            return accumulatedDependencies;
        }

        private int getRadiusOfParent(final ChunkStatus status) {
            for (int i = this.directDependenciesByRadius.length - 1; i >= 0; i--) {
                if (this.directDependenciesByRadius[i].isOrAfter(status)) {
                    return i;
                }
            }

            return 0;
        }
    }
}

package net.minecraft.server.level;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.CrashReport;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.jspecify.annotations.Nullable;

public abstract class GenerationChunkHolder {
    private static final List<ChunkStatus> CHUNK_STATUSES = ChunkStatus.getStatusList();
    private static final ChunkResult<ChunkAccess> NOT_DONE_YET = ChunkResult.error("Not done yet");
    public static final ChunkResult<ChunkAccess> UNLOADED_CHUNK = ChunkResult.error("Unloaded chunk");
    public static final CompletableFuture<ChunkResult<ChunkAccess>> UNLOADED_CHUNK_FUTURE = CompletableFuture.completedFuture(UNLOADED_CHUNK);
    protected final ChunkPos pos;
    // Paper - rewrite chunk system

    public GenerationChunkHolder(final ChunkPos pos) {
        this.pos = pos;
        if (!pos.isValid()) {
            throw new IllegalStateException("Trying to create chunk out of reasonable bounds: " + pos);
        }
    }

    public CompletableFuture<ChunkResult<ChunkAccess>> scheduleChunkGenerationTask(final ChunkStatus status, final ChunkMap scheduler) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    CompletableFuture<ChunkResult<ChunkAccess>> applyStep(
        final ChunkStep step, final GeneratingChunkMap chunkMap, final StaticCache2D<GenerationChunkHolder> cache
    ) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    protected void updateHighestAllowedStatus(final ChunkMap scheduler) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void replaceProtoChunk(final ImposterProtoChunk chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    void removeTask(final ChunkGenerationTask task) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void rescheduleChunkTask(final ChunkMap scheduler, final @Nullable ChunkStatus status) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private CompletableFuture<ChunkResult<ChunkAccess>> getOrCreateFuture(final ChunkStatus status) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void failAndClearPendingFuturesBetween(final @Nullable ChunkStatus fromExclusive, final ChunkStatus toInclusive) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void failAndClearPendingFuture(final int index, final CompletableFuture<ChunkResult<ChunkAccess>> previous) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void completeFuture(final ChunkStatus status, final ChunkAccess chunk) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private @Nullable ChunkStatus findHighestStatusWithPendingFuture(final @Nullable ChunkStatus newStatus) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private boolean acquireStatusBump(final ChunkStatus status) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private boolean isStatusDisallowed(final ChunkStatus status) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    protected abstract void addSaveDependency(final CompletableFuture<?> sync);

    public void increaseGenerationRefCount() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void decreaseGenerationRefCount() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public @Nullable ChunkAccess getChunkIfPresentUnchecked(final ChunkStatus status) {
        // Paper start - rewrite chunk system
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)(Object)this).moonrise$getRealChunkHolder().getChunkIfPresentUnchecked(status);
        // Paper end - rewrite chunk system
    }

    public @Nullable ChunkAccess getChunkIfPresent(final ChunkStatus status) {
        // Paper start - rewrite chunk system
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)(Object)this).moonrise$getRealChunkHolder().getChunkIfPresent(status);
        // Paper end - rewrite chunk system
    }

    public @Nullable ChunkAccess getLatestChunk() {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder.ChunkCompletion lastCompletion = ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)(Object)this).moonrise$getRealChunkHolder().getLastChunkCompletion();
        return lastCompletion == null ? null : lastCompletion.chunk();
        // Paper end - rewrite chunk system
    }

    public @Nullable ChunkStatus getPersistedStatus() {
        // Paper start - rewrite chunk system
        final ChunkAccess chunk = this.getLatestChunk();
        return chunk == null ? null : chunk.getPersistedStatus();
        // Paper end - rewrite chunk system
    }

    public ChunkPos getPos() {
        return this.pos;
    }

    public FullChunkStatus getFullStatus() {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)(Object)this).moonrise$getRealChunkHolder().getChunkStatus(); // Paper - rewrite chunk system
    }

    public abstract int getTicketLevel();

    public abstract int getQueueLevel();

    @VisibleForDebug
    public List<Pair<ChunkStatus, @Nullable CompletableFuture<ChunkResult<ChunkAccess>>>> getAllFutures() {
        throw new UnsupportedOperationException();  // Paper - rewrite chunk system
    }

    @VisibleForDebug
    public @Nullable ChunkStatus getLatestStatus() {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder.ChunkCompletion lastCompletion = ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)(Object)this).moonrise$getRealChunkHolder().getLastChunkCompletion();
        return lastCompletion == null ? null : lastCompletion.genStatus();
        // Paper end - rewrite chunk system
    }
}

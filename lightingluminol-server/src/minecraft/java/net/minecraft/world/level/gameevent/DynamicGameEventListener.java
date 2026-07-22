package net.minecraft.world.level.gameevent;

import java.util.function.Consumer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

public class DynamicGameEventListener<T extends GameEventListener> {
    private final T listener;
    private @Nullable SectionPos lastSection;

    public DynamicGameEventListener(final T listener) {
        this.listener = listener;
    }

    public void add(final ServerLevel level) {
        this.move(level);
    }

    public T getListener() {
        return this.listener;
    }

    public void remove(final ServerLevel level) {
        ifChunkExists(level, this.lastSection, dispatcher -> dispatcher.unregister(this.listener));
        // Paper start - rewrite chunk system
        // We need to unset the last section when removed, otherwise if the same instance is re-added at the same position it
        // will assume there was no change and fail to re-register.
        // In vanilla, chunks rarely unload and re-load quickly enough to trigger this issue. However, our chunk system has a
        // quirk where fast chunk reload cycles will often occur on player login (see PR #22).
        // So we fix this vanilla oversight as our changes cause it to manifest in bugs much more often (see issue #87).
        this.lastSection = null;
        // Paper end - rewrite chunk system
    }

    public void move(final ServerLevel level) {
        this.listener.getListenerSource().getPosition(level).map(SectionPos::of).ifPresent(currentSection -> {
            if (this.lastSection == null || !this.lastSection.equals(currentSection)) {
                ifChunkExists(level, this.lastSection, dispatcher -> dispatcher.unregister(this.listener));
                this.lastSection = currentSection;
                ifChunkExists(level, this.lastSection, dispatcher -> dispatcher.register(this.listener));
            }
        });
    }

    private static void ifChunkExists(final LevelReader level, final @Nullable SectionPos sectionPos, final Consumer<GameEventListenerRegistry> action) {
        if (sectionPos != null) {
            ChunkAccess chunk = level.getChunkIfLoadedImmediately(sectionPos.getX(), sectionPos.getZ()); // Paper - Perf: can cause sync loads while completing a chunk, resulting in deadlock
            if (chunk != null) {
                action.accept(chunk.getListenerRegistry(sectionPos.y()));
            }
        }
    }
}

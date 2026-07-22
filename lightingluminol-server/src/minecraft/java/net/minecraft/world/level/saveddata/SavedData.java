package net.minecraft.world.level.saveddata;

public abstract class SavedData {
    private volatile boolean dirty; // Folia - make map data thread-safe

    public void setDirty() {
        this.setDirty(true);
    }

    public void setDirty(final boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDirty() {
        return this.dirty;
    }
}

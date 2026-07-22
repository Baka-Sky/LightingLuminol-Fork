package org.bukkit.craftbukkit.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

public final class CraftMapView implements MapView {

    private final Map<CraftPlayer, RenderData> renderCache = new WeakHashMap<>();
    private final List<MapRenderer> renderers = new ArrayList<>();
    private final Map<MapRenderer, Map<CraftPlayer, CraftMapCanvas>> canvases = new HashMap<>();
    final MapItemSavedData worldMap;

    public CraftMapView(MapItemSavedData worldMap) {
        this.worldMap = worldMap;
        this.addRenderer(new CraftMapRenderer(worldMap));
    }

    @Override
    public int getId() {
        return this.worldMap.id.id();
    }

    @Override
    public boolean isVirtual() {
        synchronized (this.worldMap) { // Folia - region threading
        return !this.renderers.isEmpty() && !(this.renderers.get(0) instanceof CraftMapRenderer);
        } // Folia - region threading
    }

    @Override
    public Scale getScale() {
        synchronized (this.worldMap) { // Folia - region threading
        return Scale.valueOf(this.worldMap.scale);
        } // Folia - region threading
    }

    @Override
    public void setScale(Scale scale) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.scale = scale.getValue();
        } // Folia - region threading
    }

    @Override
    public World getWorld() {
        synchronized (this.worldMap) { // Folia - region threading
        ResourceKey<net.minecraft.world.level.Level> dimension = this.worldMap.dimension;
        ServerLevel world = MinecraftServer.getServer().getLevel(dimension);

        if (world != null) {
            return world.getWorld();
        }

        if (this.worldMap.uniqueId != null) {
            return Bukkit.getServer().getWorld(this.worldMap.uniqueId);
        }
        return null;
        } // Folia - region threading
    }

    @Override
    public void setWorld(World world) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.dimension = ((CraftWorld) world).getHandle().dimension();
        this.worldMap.uniqueId = world.getUID();
        } // Folia - region threading
    }

    @Override
    public int getCenterX() {
        synchronized (this.worldMap) { // Folia - region threading
        return this.worldMap.centerX;
        } // Folia - region threading
    }

    @Override
    public int getCenterZ() {
        synchronized (this.worldMap) { // Folia - region threading
        return this.worldMap.centerZ;
        } // Folia - region threading
    }

    @Override
    public void setCenterX(int x) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.centerX = x;
        } // Folia - region threading
    }

    @Override
    public void setCenterZ(int z) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.centerZ = z;
        } // Folia - region threading
    }

    @Override
    public List<MapRenderer> getRenderers() {
        synchronized (this.worldMap) { // Folia - region threading
        return new ArrayList<MapRenderer>(this.renderers);
        } // Folia - region threading
    }

    @Override
    public void addRenderer(MapRenderer renderer) {
        synchronized (this.worldMap) { // Folia - region threading
        if (!this.renderers.contains(renderer)) {
            this.renderers.add(renderer);
            this.canvases.put(renderer, new WeakHashMap<>());
            renderer.initialize(this);
        }
        } // Folia - region threading
    }

    @Override
    public boolean removeRenderer(MapRenderer renderer) {
        synchronized (this.worldMap) { // Folia - region threading
        if (this.renderers.contains(renderer)) {
            this.renderers.remove(renderer);
            for (Map.Entry<CraftPlayer, CraftMapCanvas> entry : this.canvases.get(renderer).entrySet()) {
                for (int x = 0; x < 128; ++x) {
                    for (int y = 0; y < 128; ++y) {
                        entry.getValue().setPixel(x, y, (byte) -1);
                    }
                }
            }
            this.canvases.remove(renderer);
            return true;
        } else {
            return false;
        }
        } // Folia - region threading
    }

    private boolean isContextual() {
        for (MapRenderer renderer : this.renderers) {
            if (renderer.isContextual()) return true;
        }
        return false;
    }

    public RenderData render(CraftPlayer player) {
        synchronized (this.worldMap) { // Folia - region threading
        boolean context = this.isContextual();
        RenderData render = this.renderCache.get(context ? player : null);

        if (render == null) {
            render = new RenderData();
            this.renderCache.put(context ? player : null, render);
        }

        if (context && this.renderCache.containsKey(null)) {
            this.renderCache.remove(null);
        }

        Arrays.fill(render.buffer, (byte) 0);
        render.cursors.clear();

        for (MapRenderer renderer : this.renderers) {
            CraftMapCanvas canvas = this.canvases.get(renderer).get(renderer.isContextual() ? player : null);
            if (canvas == null) {
                canvas = new CraftMapCanvas(this);
                this.canvases.get(renderer).put(renderer.isContextual() ? player : null, canvas);
            }

            canvas.setBase(render.buffer);
            try {
                renderer.render(this, canvas, player);
            } catch (Throwable ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not render map using renderer " + renderer.getClass().getName(), ex);
            }

            byte[] buf = canvas.getBuffer();
            for (int i = 0; i < buf.length; ++i) {
                byte color = buf[i];
                // There are 248 valid color id's, 0 -> 127 and -128 -> -9
                if (color >= 0 || color <= -9) render.buffer[i] = color;
            }

            for (int i = 0; i < canvas.getCursors().size(); ++i) {
                render.cursors.add(canvas.getCursors().getCursor(i));
            }
        }

        return render;
        } // Folia - region threading
    }

    @Override
    public boolean isTrackingPosition() {
        synchronized (this.worldMap) { // Folia - region threading
        return this.worldMap.trackingPosition;
        } // Folia - region threading
    }

    @Override
    public void setTrackingPosition(boolean trackingPosition) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.trackingPosition = trackingPosition;
        } // Folia - region threading
    }

    @Override
    public boolean isUnlimitedTracking() {
        synchronized (this.worldMap) { // Folia - region threading
        return this.worldMap.unlimitedTracking;
        } // Folia - region threading
    }

    @Override
    public void setUnlimitedTracking(boolean unlimited) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.unlimitedTracking = unlimited;
        } // Folia - region threading
    }

    @Override
    public boolean isLocked() {
        synchronized (this.worldMap) { // Folia - region threading
        return this.worldMap.locked;
        } // Folia - region threading
    }

    @Override
    public void setLocked(boolean locked) {
        synchronized (this.worldMap) { // Folia - region threading
        this.worldMap.locked = locked;
        } // Folia - region threading
    }
}

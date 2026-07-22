package net.minecraft.world.level.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;

public final class WeatherData extends SavedData {
    public static final Codec<WeatherData> CODEC = RecordCodecBuilder.create(
        i -> i.group(
                Codec.INT.fieldOf("clear_weather_time").forGetter(WeatherData::getClearWeatherTime),
                Codec.INT.fieldOf("rain_time").forGetter(WeatherData::getRainTime),
                Codec.INT.fieldOf("thunder_time").forGetter(WeatherData::getThunderTime),
                Codec.BOOL.fieldOf("raining").forGetter(WeatherData::isRaining),
                Codec.BOOL.fieldOf("thundering").forGetter(WeatherData::isThundering)
            )
            .apply(i, WeatherData::new)
    );
    public static final SavedDataType<WeatherData> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace("weather"), WeatherData::new, CODEC, DataFixTypes.SAVED_DATA_WEATHER
    );
    private int clearWeatherTime;
    private int rainTime;
    private int thunderTime;
    private boolean raining;
    private boolean thundering;

    public WeatherData() {
    }

    public WeatherData(final int clearWeatherTime, final int rainTime, final int thunderTime, final boolean raining, final boolean thundering) {
        this.clearWeatherTime = clearWeatherTime;
        this.rainTime = rainTime;
        this.thunderTime = thunderTime;
        this.raining = raining;
        this.thundering = thundering;
    }

    public int getClearWeatherTime() {
        return this.clearWeatherTime;
    }

    public void setClearWeatherTime(final int clearWeatherTime) {
        this.clearWeatherTime = clearWeatherTime;
        this.setDirty();
    }

    public boolean isThundering() {
        return this.thundering;
    }

    public void setThundering(final boolean thundering) {
        // Paper start - Add cause to Weather/ThunderChangeEvents
        this.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.UNKNOWN);
    }
    public void setThundering(boolean thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause cause) {
        // Paper end - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        if (this.thundering == thundering) {
            return;
        }

        org.bukkit.World world = this.level == null ? null : this.level.getWorld();
        if (world != null) {
            org.bukkit.event.weather.ThunderChangeEvent thunder = new org.bukkit.event.weather.ThunderChangeEvent(world, thundering, cause); // Paper - Add cause to Weather/ThunderChangeEvents
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(thunder);
            if (thunder.isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        this.thundering = thundering;
        this.setDirty();
    }

    public int getThunderTime() {
        return this.thunderTime;
    }

    public void setThunderTime(final int thunderTime) {
        this.thunderTime = thunderTime;
        this.setDirty();
    }

    public boolean isRaining() {
        return this.raining;
    }

    public void setRaining(final boolean raining) {
        // Paper start - Add cause to Weather/ThunderChangeEvents
        this.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.UNKNOWN);
    }

    public void setRaining(boolean raining, org.bukkit.event.weather.WeatherChangeEvent.Cause cause) {
        // Paper end - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        if (this.raining == raining) {
            return;
        }

        org.bukkit.World world = this.level == null ? null : this.level.getWorld();
        if (world != null) {
            org.bukkit.event.weather.WeatherChangeEvent weather = new org.bukkit.event.weather.WeatherChangeEvent(world, raining, cause); // Paper - Add cause to Weather/ThunderChangeEvents
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(weather);
            if (weather.isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        this.raining = raining;
        this.setDirty();
    }

    public int getRainTime() {
        return this.rainTime;
    }

    public void setRainTime(final int rainTime) {
        this.rainTime = rainTime;
        this.setDirty();
    }

    // Paper start - pass level for events
    private net.minecraft.server.level.@org.jspecify.annotations.Nullable ServerLevel level;
    public void setLevel(final net.minecraft.server.level.ServerLevel level) {
        this.level = level;
    }
    // Paper end - pass level for events
}

package net.minecraft.server.dedicated;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

public class DedicatedServerSettings {
    private final Path source;
    private DedicatedServerProperties properties;

    // CraftBukkit start
    public DedicatedServerSettings(joptsimple.OptionSet options) {
        this.source = ((java.io.File) options.valueOf("config")).toPath();
        this.properties = DedicatedServerProperties.fromFile(this.source, options);
        // CraftBukkit end
    }

    public DedicatedServerProperties getProperties() {
        return this.properties;
    }

    public void forceSave() {
        this.properties.store(this.source);
    }

    public DedicatedServerSettings update(final UnaryOperator<DedicatedServerProperties> mutator) {
        (this.properties = mutator.apply(this.properties)).store(this.source);
        return this;
    }
}

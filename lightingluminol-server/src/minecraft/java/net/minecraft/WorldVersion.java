package net.minecraft;

import java.util.Date;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.storage.DataVersion;

public interface WorldVersion {
    DataVersion dataVersion();

    String id();

    String name();

    int protocolVersion();

    PackFormat packVersion(PackType packType);

    Date buildTime();

    boolean stable();

    record Simple(
        @Override String id,
        @Override String name,
        @Override DataVersion dataVersion,
        @Override int protocolVersion,
        PackFormat resourcePackVersion,
        PackFormat datapackVersion,
        @Override Date buildTime,
        @Override boolean stable
    ) implements WorldVersion {
        @Override
        public PackFormat packVersion(final PackType packType) {
            return switch (packType) {
                case CLIENT_RESOURCES -> this.resourcePackVersion;
                case SERVER_DATA -> this.datapackVersion;
            };
        }
    }
}

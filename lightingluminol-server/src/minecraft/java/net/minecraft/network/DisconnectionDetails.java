package net.minecraft.network;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.network.chat.Component;

// Paper start - Configuration API: add support for enhanced disconnection causes
public record DisconnectionDetails(Component reason, Optional<Path> report, Optional<URI> bugReportLink, Optional<Component> quitMessage, Optional<io.papermc.paper.connection.DisconnectionReason> disconnectionReason) {
    public DisconnectionDetails(Component reason, Optional<Path> report, Optional<URI> bugReportLink) {
        this(reason, report, bugReportLink, Optional.empty(), Optional.empty());
    }
// Paper end - Configuration API: add support for enhanced disconnection causes

    public DisconnectionDetails(final Component reason) {
        this(reason, Optional.empty(), Optional.empty());
    }
}

package net.minecraft.network.chat;

import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface ChatDecorator {
    ChatDecorator PLAIN = (player, plain) -> java.util.concurrent.CompletableFuture.completedFuture(plain); // Paper - adventure; support async chat decoration events

    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper - adventure; support chat decoration events (callers should use the overload with CommandSourceStack)
    java.util.concurrent.CompletableFuture<Component> decorate(@Nullable ServerPlayer player, Component plain); // Paper - adventure; support async chat decoration events

    // Paper start - adventure; support async chat decoration events
    default java.util.concurrent.CompletableFuture<Component> decorate(@Nullable ServerPlayer sender, net.minecraft.commands.@Nullable CommandSourceStack commandSourceStack, Component plain) {
        throw new UnsupportedOperationException("Must override this implementation");
    }
    // Paper end - adventure; support async chat decoration events
}

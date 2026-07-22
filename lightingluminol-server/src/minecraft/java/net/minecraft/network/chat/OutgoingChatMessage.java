package net.minecraft.network.chat;

import net.minecraft.server.level.ServerPlayer;

public interface OutgoingChatMessage {
    Component content();

    void sendToPlayer(ServerPlayer player, boolean filtered, ChatType.Bound chatType);

    // Paper start
    default void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType, final @org.jspecify.annotations.Nullable Component unsigned) {
        this.sendToPlayer(player, filtered, chatType);
    }
    // Paper end

    static OutgoingChatMessage create(final PlayerChatMessage message) {
        return message.isSystem() ? new OutgoingChatMessage.Disguised(message.decoratedContent()) : new OutgoingChatMessage.Player(message);
    }

    record Disguised(@Override Component content) implements OutgoingChatMessage {
        @Override
        public void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType) {
            // Paper start
            this.sendToPlayer(player, filtered, chatType, null);
        }
        @Override
        public void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType, final @org.jspecify.annotations.Nullable Component unsigned) {
            player.connection.sendDisguisedChatMessage(unsigned != null ? unsigned : this.content, chatType);
            // Paper end
        }
    }

    record Player(PlayerChatMessage message) implements OutgoingChatMessage {
        @Override
        public Component content() {
            return this.message.decoratedContent();
        }

        @Override
        public void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType) {
            // Paper start
            this.sendToPlayer(player, filtered, chatType, null);
        }
        @Override
        public void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType, final @org.jspecify.annotations.Nullable Component unsigned) {
            // Paper end
            PlayerChatMessage filteredMessage = this.message.filter(filtered);
            filteredMessage = unsigned != null ? filteredMessage.withUnsignedContent(unsigned) : filteredMessage; // Paper
            if (!filteredMessage.isFullyFiltered()) {
                player.connection.sendPlayerChatMessage(filteredMessage, chatType);
            }
        }
    }
}

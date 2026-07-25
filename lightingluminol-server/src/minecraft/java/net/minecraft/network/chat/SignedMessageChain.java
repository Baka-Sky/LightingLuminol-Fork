package net.minecraft.network.chat;

import com.mojang.logging.LogUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.Signer;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import meow.bacteriawa.lightingluminol.config.MiscConfig;

public class SignedMessageChain {
    private static final Logger LOGGER = LogUtils.getLogger();
    private @Nullable SignedMessageLink nextLink;
    private Instant lastTimeStamp = Instant.EPOCH;

    public SignedMessageChain(final UUID profileId, final UUID sessionId) {
        this.nextLink = SignedMessageLink.root(profileId, sessionId);
    }

    public SignedMessageChain.Encoder encoder(final Signer signer) {
        return body -> {
            SignedMessageLink link = this.nextLink;
            if (link == null) {
                return null;
            }

            this.nextLink = link.advance();
            return new MessageSignature(signer.sign(output -> PlayerChatMessage.updateSignature(output, link, body)));
        };
    }

    public SignedMessageChain.Decoder decoder(final ProfilePublicKey profilePublicKey) {
        final SignatureValidator signatureValidator = profilePublicKey.createSignatureValidator();
        return new SignedMessageChain.Decoder() {
            @Override
            public PlayerChatMessage unpack(final @Nullable MessageSignature signature, final SignedMessageBody body) throws SignedMessageChain.DecodeException {
                if (signature == null) {
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.MISSING_PROFILE_KEY);
                }

                if (profilePublicKey.data().hasExpired()) {
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.EXPIRED_PROFILE_KEY, org.bukkit.event.player.PlayerKickEvent.Cause.EXPIRED_PROFILE_PUBLIC_KEY); // Paper - kick event causes
                }

                SignedMessageLink link = SignedMessageChain.this.nextLink;
                if (link == null) {
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.CHAIN_BROKEN);
                }

                if (MiscConfig.MojangOutOfOrderChatCheck.enabled && body.timeStamp().isBefore(SignedMessageChain.this.lastTimeStamp)) {
                    this.setChainBroken();
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.OUT_OF_ORDER_CHAT, org.bukkit.event.player.PlayerKickEvent.Cause.OUT_OF_ORDER_CHAT); // Paper - kick event causes
                }

                SignedMessageChain.this.lastTimeStamp = body.timeStamp();
                PlayerChatMessage unpacked = new PlayerChatMessage(link, signature, body, null, FilterMask.PASS_THROUGH);
                if (!unpacked.verify(signatureValidator)) {
                    this.setChainBroken();
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.INVALID_SIGNATURE);
                }

                if (unpacked.hasExpiredServer(Instant.now())) {
                    SignedMessageChain.LOGGER.warn("Received expired chat: '{}'. Is the client/server system time unsynchronized?", body.content());
                }

                SignedMessageChain.this.nextLink = link.advance();
                return unpacked;
            }

            @Override
            public void setChainBroken() {
                SignedMessageChain.this.nextLink = null;
            }
        };
    }

    public static class DecodeException extends ThrowingComponent {
        private static final Component MISSING_PROFILE_KEY = Component.translatable("chat.disabled.missingProfileKey");
        private static final Component CHAIN_BROKEN = Component.translatable("chat.disabled.chain_broken");
        private static final Component EXPIRED_PROFILE_KEY = Component.translatable("chat.disabled.expiredProfileKey");
        private static final Component INVALID_SIGNATURE = Component.translatable("chat.disabled.invalid_signature");
        private static final Component OUT_OF_ORDER_CHAT = Component.translatable("chat.disabled.out_of_order_chat");

        // Paper start
        public final org.bukkit.event.player.PlayerKickEvent.Cause kickCause;
        public DecodeException(Component component, org.bukkit.event.player.PlayerKickEvent.Cause event) {
            super(component);
            this.kickCause = event;
        }
        // Paper end
        public DecodeException(final Component component) {
            this(component, org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN); // Paper
        }
    }

    @FunctionalInterface
    public interface Decoder {
        static SignedMessageChain.Decoder unsigned(final UUID profileId, final BooleanSupplier enforcesSecureChat) {
            return (signature, body) -> {
                if (enforcesSecureChat.getAsBoolean()) {
                    throw new SignedMessageChain.DecodeException(SignedMessageChain.DecodeException.MISSING_PROFILE_KEY);
                } else {
                    return PlayerChatMessage.unsigned(profileId, body.content());
                }
            };
        }

        PlayerChatMessage unpack(@Nullable MessageSignature signature, SignedMessageBody body) throws SignedMessageChain.DecodeException;

        default void setChainBroken() {
        }
    }

    @FunctionalInterface
    public interface Encoder {
        SignedMessageChain.Encoder UNSIGNED = body -> null;

        @Nullable MessageSignature pack(SignedMessageBody body);
    }
}

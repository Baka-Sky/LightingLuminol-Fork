package net.minecraft.server.network;

import com.google.common.collect.Lists;
import com.google.common.primitives.Floats;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetGameRulePacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.FetchProfileCommand;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.FutureChain;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.StringUtil;
import net.minecraft.util.TickThrottler;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.entity.TestBlockEntity;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// CraftBukkit start
import io.papermc.paper.adventure.PaperAdventure; // Paper
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.HitResult;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
// CraftBukkit end

public class ServerGamePacketListenerImpl
    extends ServerCommonPacketListenerImpl
    implements ServerGamePacketListener,
    ServerPlayerConnection,
    TickablePacketListener,
    GameProtocols.Context {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int NO_BLOCK_UPDATES_TO_ACK = -1;
    private static final int TRACKED_MESSAGE_DISCONNECT_THRESHOLD = 4096;
    private static final int MAXIMUM_FLYING_TICKS = 80;
    private static final int ATTACK_INDICATOR_TOLERANCE_TICKS = 5;
    public static final int CLIENT_LOADED_TIMEOUT_TIME = 60;
    private static final Component CHAT_VALIDATION_FAILED = Component.translatable("multiplayer.disconnect.chat_validation_failed");
    private static final Component INVALID_COMMAND_SIGNATURE = Component.translatable("chat.disabled.invalid_command_signature").withStyle(ChatFormatting.RED);
    private static final int MAX_COMMAND_SUGGESTIONS = 1000;
    public ServerPlayer player;
    public final PlayerChunkSender chunkSender;
    private int tickCount;
    private int ackBlockChangesUpTo = -1;
    private final TickThrottler dropSpamThrottler = new TickThrottler(20, 1480);
    private final TickThrottler chatSpamThrottler;
    private final TickThrottler commandSpamThrottler;
    private final TickThrottler tabSpamThrottler = new TickThrottler(io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.tabSpamIncrement, io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.tabSpamLimit); // Paper - configurable tab spam limits
    private final TickThrottler recipeSpamPackets = new TickThrottler(io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.recipeSpamIncrement, io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.recipeSpamLimit);
    private double firstGoodX;
    private double firstGoodY;
    private double firstGoodZ;
    private double lastGoodX;
    private double lastGoodY;
    private double lastGoodZ;
    private @Nullable Entity lastVehicle;
    private double vehicleFirstGoodX;
    private double vehicleFirstGoodY;
    private double vehicleFirstGoodZ;
    private double vehicleLastGoodX;
    private double vehicleLastGoodY;
    private double vehicleLastGoodZ;
    private @Nullable Vec3 awaitingPositionFromClient;
    private int awaitingTeleport;
    private int awaitingTeleportTime;
    private boolean clientIsFloating;
    private int aboveGroundTickCount;
    private boolean clientVehicleIsFloating;
    private int aboveGroundVehicleTickCount;
    private int receivedMovePacketCount;
    private int knownMovePacketCount;
    private boolean receivedMovementThisTick;
    // CraftBukkit start - add fields
    private long lastTick = Util.getMillis() / 50L; // Folia - region threading
    private int allowedPlayerTicks = 1;
    private long lastDropTick = Util.getMillis() / 50L; // Folia - region threading
    private long lastBookTick = Util.getMillis() / 50L; // Folia - region threading
    private int dropCount = 0;

    private boolean hasMoved = false;
    private double lastPosX = Double.MAX_VALUE;
    private double lastPosY = Double.MAX_VALUE;
    private double lastPosZ = Double.MAX_VALUE;
    private float lastYRot = Float.MAX_VALUE;
    private float lastXRot = Float.MAX_VALUE;
    private boolean justTeleported = false;
    // CraftBukkit end
    private @Nullable RemoteChatSession chatSession;
    private boolean hasLoggedExpiry = false; // Paper - Prevent causing expired keys from impacting new joins
    private SignedMessageChain.Decoder signedMessageDecoder;
    private final LastSeenMessagesValidator lastSeenMessages = new LastSeenMessagesValidator(20);
    private int nextChatIndex;
    private final MessageSignatureCache messageSignatureCache = MessageSignatureCache.createDefault();
    private final FutureChain chatMessageChain;
    public volatile boolean waitingForSwitchToConfig; // Folia - rewrite login process - fix bad ordering of this field write + public
    private boolean waitingForRespawn;
    private int clientLoadedTimeoutTimer;
    private static final int MAX_SIGN_LINE_LENGTH = Integer.getInteger("Paper.maxSignLength", 80); // Paper - Limit client sign length
    private final io.papermc.paper.event.packet.ClientTickEndEvent tickEndEvent; // Paper - add client tick end event
    public final io.papermc.paper.connection.PaperPlayerGameConnection playerGameConnection; // Paper

    // Folia start - region threading
    public net.minecraft.world.level.ChunkPos disconnectPos;
    private static final java.util.concurrent.atomic.AtomicLong DISCONNECT_TICKET_ID_GENERATOR = new java.util.concurrent.atomic.AtomicLong();
    public static final net.minecraft.server.level.TicketType<Long> DISCONNECT_TICKET = ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.create("disconnect_ticket", Long::compareTo);
    public final Long disconnectTicketId = Long.valueOf(DISCONNECT_TICKET_ID_GENERATOR.getAndIncrement());
    // Folia end - region threading

    public ServerGamePacketListenerImpl(final MinecraftServer server, final Connection connection, final ServerPlayer player, final CommonListenerCookie cookie) {
        super(server, connection, cookie);
        this.commandSpamThrottler = new TickThrottler(20, 20 * server.getCommandSpamThresholdSeconds());
        this.chatSpamThrottler = new TickThrottler(20, 20 * server.getChatSpamThresholdSeconds());
        this.restartClientLoadTimerAfterRespawn();
        this.chunkSender = new PlayerChunkSender(connection.isMemoryConnection());
        this.player = player;
        player.connection = this;
        player.getTextFilter().join();
        this.signedMessageDecoder = SignedMessageChain.Decoder.unsigned(player.getUUID(), server::enforceSecureProfile);
        this.chatMessageChain = new FutureChain(server.chatExecutor); // CraftBukkit - async chat
        this.tickEndEvent = new io.papermc.paper.event.packet.ClientTickEndEvent(player.getBukkitEntity()); // Paper - add client tick end event
        this.playerGameConnection = new io.papermc.paper.connection.PaperPlayerGameConnection(this); // Paper
    }

    // Paper start - configuration phase API
    @Override
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        return this.playerGameConnection;
    }

    @Override
    public net.kyori.adventure.audience.Audience getAudience() {
        return this.getCraftPlayer();
    }
    // Paper end - configuration phase API

    @Override
    public void tick() {
        // Folia start - region threading
        this.keepConnectionAlive();
        if (this.player.wonGame) {
            return;
        }
        // Folia end - region threading
        if (this.ackBlockChangesUpTo > -1) {
            this.send(new ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
            this.ackBlockChangesUpTo = -1;
        }

        if (this.server.isPaused() || !this.tickPlayer()) {
            //this.keepConnectionAlive(); // Folia - region threading - moved to beginning of method
            this.chatSpamThrottler.tick();
            this.commandSpamThrottler.tick();
            this.dropSpamThrottler.tick();
            this.tabSpamThrottler.tick(); // Paper - configurable tab spam limits
            this.recipeSpamPackets.tick(); // Paper - auto recipe limit
            if (this.player.getLastActionTime() > 0L
                && this.server.playerIdleTimeout() > 0
                && Util.getMillis() - this.player.getLastActionTime() > TimeUnit.MINUTES.toMillis(this.server.playerIdleTimeout())
                && !this.player.wonGame) {
                this.disconnect(Component.translatable("multiplayer.disconnect.idling"), org.bukkit.event.player.PlayerKickEvent.Cause.IDLING); // Paper - kick event cause
            }
        }
    }

    private boolean tickPlayer() {
        this.resetPosition();
        this.player.xo = this.player.getX();
        this.player.yo = this.player.getY();
        this.player.zo = this.player.getZ();
        this.player.doTick();
        this.player.absSnapTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
        this.tickCount++;
        this.knownMovePacketCount = this.receivedMovePacketCount;
        if (this.clientIsFloating && !this.player.isSleeping() && !this.player.isPassenger() && !this.player.isDeadOrDying()) {
            if (++this.aboveGroundTickCount > this.getMaximumFlyingTicks(this.player)) {
                // LOGGER.warn("{} was kicked for floating too long!", this.player.getPlainTextName()); // Paper - Logging moved to net.minecraft.server.network.ServerCommonPacketListenerImpl.disconnect()
                this.disconnect(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.flyingPlayer, org.bukkit.event.player.PlayerKickEvent.Cause.FLYING_PLAYER); // Paper - use configurable kick message & kick event cause
                return true;
            }
        } else {
            this.clientIsFloating = false;
            this.aboveGroundTickCount = 0;
        }

        this.lastVehicle = this.player.getRootVehicle();
        if (this.lastVehicle != this.player && this.lastVehicle.getControllingPassenger() == this.player) {
            this.vehicleFirstGoodX = this.lastVehicle.getX();
            this.vehicleFirstGoodY = this.lastVehicle.getY();
            this.vehicleFirstGoodZ = this.lastVehicle.getZ();
            this.vehicleLastGoodX = this.lastVehicle.getX();
            this.vehicleLastGoodY = this.lastVehicle.getY();
            this.vehicleLastGoodZ = this.lastVehicle.getZ();
            if (this.clientVehicleIsFloating && this.lastVehicle.getControllingPassenger() == this.player) {
                if (++this.aboveGroundVehicleTickCount > this.getMaximumFlyingTicks(this.lastVehicle)) {
                    // LOGGER.warn("{} was kicked for floating a vehicle too long!", this.player.getPlainTextName()); // Paper - Logging moved to net.minecraft.server.network.ServerCommonPacketListenerImpl.disconnect()
                    this.disconnect(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.flyingVehicle, org.bukkit.event.player.PlayerKickEvent.Cause.FLYING_VEHICLE); // Paper - use configurable kick message & kick event cause
                    return true;
                }
            } else {
                this.clientVehicleIsFloating = false;
                this.aboveGroundVehicleTickCount = 0;
            }
        } else {
            this.lastVehicle = null;
            this.clientVehicleIsFloating = false;
            this.aboveGroundVehicleTickCount = 0;
        }

        // Paper start - Prevent causing expired keys from impacting new joins
        if (!this.hasLoggedExpiry && this.chatSession != null && this.chatSession.profilePublicKey().data().hasExpired()) {
            LOGGER.info("Player profile key for {} has expired!", this.player.getPlainTextName());
            this.hasLoggedExpiry = true;
        }
        // Paper end - Prevent causing expired keys from impacting new joins
        return false;
    }

    private int getMaximumFlyingTicks(final Entity entity) {
        double gravity = entity.getGravity();
        if (gravity < 1.0E-5F) {
            return Integer.MAX_VALUE;
        }

        double gravityModifier = 0.08 / gravity;
        return Mth.ceil(80.0 * Math.max(gravityModifier, 1.0));
    }

    public void resetFlyingTicks() {
        this.aboveGroundTickCount = 0;
        this.aboveGroundVehicleTickCount = 0;
    }

    public void resetPosition() {
        this.firstGoodX = this.player.getX();
        this.firstGoodY = this.player.getY();
        this.firstGoodZ = this.player.getZ();
        this.lastGoodX = this.player.getX();
        this.lastGoodY = this.player.getY();
        this.lastGoodZ = this.player.getZ();
        // Folia start - support vehicle teleportations
        this.lastVehicle = this.player.getRootVehicle();
        if (this.lastVehicle != this.player && this.lastVehicle.getControllingPassenger() == this.player) {
            this.vehicleFirstGoodX = this.lastVehicle.getX();
            this.vehicleFirstGoodY = this.lastVehicle.getY();
            this.vehicleFirstGoodZ = this.lastVehicle.getZ();
            this.vehicleLastGoodX = this.lastVehicle.getX();
            this.vehicleLastGoodY = this.lastVehicle.getY();
            this.vehicleLastGoodZ = this.lastVehicle.getZ();
        } else {
            this.lastVehicle = null;
        }
        // Folia end - support vehicle teleportations
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected() && !this.waitingForSwitchToConfig;
    }

    @Override
    public boolean shouldHandleMessage(final Packet<?> packet) {
        return super.shouldHandleMessage(packet)
            || this.waitingForSwitchToConfig && this.connection.isConnected() && packet instanceof ServerboundConfigurationAcknowledgedPacket;
    }

    @Override
    protected GameProfile playerProfile() {
        return this.player.getGameProfile();
    }

    private <T, R> CompletableFuture<R> filterTextPacket(final T message, final BiFunction<TextFilter, T, CompletableFuture<R>> action) {
        return action.apply(this.player.getTextFilter(), message).thenApply(result -> {
            if (!this.isAcceptingMessages()) {
                LOGGER.debug("Ignoring packet due to disconnection");
                throw new CancellationException("disconnected");
            } else {
                return (R)result;
            }
        });
    }

    private CompletableFuture<FilteredText> filterTextPacket(final String message) {
        return this.filterTextPacket(message, TextFilter::processStreamMessage);
    }

    private CompletableFuture<List<FilteredText>> filterTextPacket(final List<String> message) {
        return this.filterTextPacket(message, TextFilter::processMessageBundle);
    }

    @Override
    public void handlePlayerInput(final ServerboundPlayerInputPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        // CraftBukkit start
        if (!packet.input().equals(this.player.getLastClientInput())) {
            PlayerInputEvent event = new PlayerInputEvent(this.player.getBukkitEntity(), new org.bukkit.craftbukkit.CraftInput(packet.input()));
            this.cserver.getPluginManager().callEvent(event);
        }
        // CraftBukkit end
        // Paper start - PlayerToggleSneakEvent
        net.minecraft.world.entity.player.Input lastInput = this.player.getLastClientInput();
        if (lastInput.shift() != packet.input().shift()) {
            // Has sneak changed
            org.bukkit.event.player.PlayerToggleSneakEvent event = new org.bukkit.event.player.PlayerToggleSneakEvent(this.getCraftPlayer(), packet.input().shift());
            this.cserver.getPluginManager().callEvent(event);

            // Technically the player input and the flag is desynced, but this is previous behavior.. so should be fine?
            if (!event.isCancelled() && this.hasClientLoaded()) {
                // Only set the shift key status if the shift input has changed and the event has not been cancelled
                this.player.setShiftKeyDown(packet.input().shift());
            }
        }
        // Paper end - PlayerToggleSneakEvent
        this.player.setLastClientInput(packet.input());
        if (this.hasClientLoaded()) {
            this.player.resetLastActionTime();
            // this.player.setShiftKeyDown(packet.input().shift()); // Paper - move up and only set if event is not cancelled.
        }
        // Paper start - Add option to make parrots stay
        if (packet.input().shift() && this.player.level().paperConfig().entities.behavior.parrotsAreUnaffectedByPlayerMovement) {
            this.player.removeEntitiesOnShoulder();
        }
        // Paper end - Add option to make parrots stay

    }

    private static boolean containsInvalidValues(final double x, final double y, final double z, final float yRot, final float xRot) {
        return Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || !Floats.isFinite(xRot) || !Floats.isFinite(yRot);
    }

    private static double clampHorizontal(final double value) {
        return Mth.clamp(value, -3.0E7, 3.0E7);
    }

    private static double clampVertical(final double value) {
        return Mth.clamp(value, -2.0E7, 2.0E7);
    }

    @Override
    public void handleMoveVehicle(final ServerboundMoveVehiclePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (containsInvalidValues(packet.position().x(), packet.position().y(), packet.position().z(), packet.yRot(), packet.xRot())) {
            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_vehicle_movement"), org.bukkit.event.player.PlayerKickEvent.Cause.INVALID_VEHICLE_MOVEMENT); // Paper - kick event cause
        } else if (!this.updateAwaitingTeleport() && this.hasClientLoaded()) {
            Entity vehicle = this.player.getRootVehicle();
            // Paper start - Don't allow vehicle movement from players while teleporting
            if (this.awaitingPositionFromClient != null || this.player.isImmobile() || vehicle.isRemoved()) {
                return;
            }
            // Paper end - Don't allow vehicle movement from players while teleporting
            if (vehicle != this.player && vehicle.getControllingPassenger() == this.player && vehicle == this.lastVehicle) {
                ServerLevel level = this.player.level();
                // CraftBukkit - store current player position
                float startYRot = this.player.getYRot();
                float startXRot = this.player.getXRot();
                double startX = this.player.getX();
                double startY = this.player.getY();
                double startZ = this.player.getZ();
                // CraftBukkit end
                double oldX = vehicle.getX();
                double oldY = vehicle.getY();
                double oldZ = vehicle.getZ();
                double targetX = clampHorizontal(packet.position().x());
                double targetY = clampVertical(packet.position().y());
                double targetZ = clampHorizontal(packet.position().z());
                float targetYRot = Mth.wrapDegrees(packet.yRot());
                float targetXRot = Mth.wrapDegrees(packet.xRot());
                double xDist = targetX - this.vehicleFirstGoodX;
                double yDist = targetY - this.vehicleFirstGoodY;
                double zDist = targetZ - this.vehicleFirstGoodZ;
                double expectedDist = vehicle.getDeltaMovement().lengthSqr();
                double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                // Paper start - fix large move vectors killing the server
                double currDeltaX = targetX - oldX;
                double currDeltaY = targetY - oldY;
                double currDeltaZ = targetZ - oldZ;
                movedDist = Math.max(movedDist, (currDeltaX * currDeltaX + currDeltaY * currDeltaY + currDeltaZ * currDeltaZ) - 1);
                double otherFieldX = targetX - this.vehicleLastGoodX;
                double otherFieldY = targetY - this.vehicleLastGoodY;
                double otherFieldZ = targetZ - this.vehicleLastGoodZ;
                movedDist = Math.max(movedDist, (otherFieldX * otherFieldX + otherFieldY * otherFieldY + otherFieldZ * otherFieldZ) - 1);
                // Paper end - fix large move vectors killing the server

                int currTick = (int)(Util.getMillis() / 50); // Folia - region threading
                this.allowedPlayerTicks += currTick - this.lastTick; // Folia - region threading
                this.allowedPlayerTicks = Math.max(this.allowedPlayerTicks, 1);
                this.lastTick = (int) currTick; // Folia - region threading

                ++this.receivedMovePacketCount;
                int i = this.receivedMovePacketCount - this.knownMovePacketCount;
                if (i > Math.max(this.allowedPlayerTicks, 5)) {
                    ServerGamePacketListenerImpl.LOGGER.debug(this.player.getScoreboardName() + " is sending move packets too frequently (" + i + " packets since last tick)");
                    i = 1;
                }

                if (movedDist > 0) {
                    this.allowedPlayerTicks -= 1;
                } else {
                    this.allowedPlayerTicks = 20;
                }
                double speed;
                if (this.player.getAbilities().flying) {
                    speed = this.player.getAbilities().getFlyingSpeed() * 20f;
                } else {
                    speed = this.player.getAbilities().getWalkingSpeed() * 10f;
                }
                speed *= 2f; // TODO: Get the speed of the vehicle instead of the player

                // Paper start - Prevent moving into unloaded chunks
                if (this.player.level().paperConfig().chunks.preventMovingIntoUnloadedChunks && (
                    !level.areChunksLoadedForMove(this.player.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(this.player.position()))) ||
                        !level.areChunksLoadedForMove(vehicle.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(vehicle.position())))
                )) {
                    this.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
                    return;
                }
                // Paper end - Prevent moving into unloaded chunks
                if (movedDist - expectedDist > Math.max(100.0, Mth.square(org.spigotmc.SpigotConfig.movedTooQuicklyMultiplier * (float) i * speed)) && !this.isSingleplayerOwner()) {
                    // CraftBukkit end
                    LOGGER.warn(
                        "{} (vehicle of {}) moved too quickly! {},{},{}", vehicle.getPlainTextName(), this.player.getPlainTextName(), xDist, yDist, zDist
                    );
                    this.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
                    return;
                }

                AABB oldAABB = vehicle.getBoundingBox();
                xDist = targetX - this.vehicleLastGoodX; // Paper - diff on change, used for checking large move vectors above
                yDist = targetY - this.vehicleLastGoodY; // Paper - diff on change, used for checking large move vectors above
                zDist = targetZ - this.vehicleLastGoodZ; // Paper - diff on change, used for checking large move vectors above
                boolean vehicleRestsOnSomething = vehicle.verticalCollisionBelow;
                if (vehicle instanceof LivingEntity livingVehicle && livingVehicle.onClimbable()) {
                    livingVehicle.resetFallDistance();
                }

                vehicle.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
                final boolean didCollide = targetX != vehicle.getX() || targetY != vehicle.getY() || targetZ != vehicle.getZ(); // Paper - needed here as the difference in Y can be reset - also note: this is only a guess at whether collisions took place, floating point errors can make this true when it shouldn't be...
                double oyDist = yDist;
                xDist = targetX - vehicle.getX();
                yDist = targetY - vehicle.getY();
                if (yDist > -0.5 || yDist < 0.5) {
                    yDist = 0.0;
                }

                zDist = targetZ - vehicle.getZ();
                movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                boolean fail = false;
                if (movedDist > org.spigotmc.SpigotConfig.movedWronglyThreshold) { // Spigot
                    fail = true;
                    LOGGER.warn("{} (vehicle of {}) moved wrongly! {}", vehicle.getPlainTextName(), this.player.getPlainTextName(), Math.sqrt(movedDist));
                }

                // Paper start - optimise out extra getCubes
                boolean teleportBack = fail;
                if (!teleportBack) {
                    // note: only call after setLocation, or else getBoundingBox is wrong
                    final AABB newBox = vehicle.getBoundingBox();
                    if (didCollide || !oldAABB.equals(newBox)) {
                        teleportBack = this.hasNewCollision(level, vehicle, oldAABB, newBox);
                    } // else: no collision at all detected, why do we care?
                }
                if (teleportBack) {
                // Paper end - optimise out extra getCubes
                    vehicle.absSnapTo(oldX, oldY, oldZ, targetYRot, targetXRot);
                    this.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
                    vehicle.removeLatestMovementRecording();
                    return;
                }

                vehicle.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
                // CraftBukkit start - fire PlayerMoveEvent TODO: this should be removed.
                // Folia start - move to positionRider
                // this correction is required on folia since we move the connection tick to the beginning of the server
                // tick, which would make any desync here visible
                // this will correctly update the passenger positions for all mounted entities
                // this prevents desync and ensures that all passengers have the correct rider-adjusted position
                vehicle.repositionAllPassengers(false);
                // Folia end - move to positionRider
                org.bukkit.entity.Player player = this.getCraftPlayer();
                if (!this.hasMoved) {
                    this.lastPosX = startX;
                    this.lastPosY = startY;
                    this.lastPosZ = startZ;
                    this.lastYRot = startYRot;
                    this.lastXRot = startXRot;
                    this.hasMoved = true;
                }
                Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot); // Get the Players previous Event location.
                Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);

                // Prevent 40 event-calls for less than a single pixel of movement >.>
                double delta = Mth.square(this.lastPosX - to.getX()) + Mth.square(this.lastPosY - to.getY()) + Mth.square(this.lastPosZ - to.getZ());
                float deltaAngle = Math.abs(this.lastYRot - to.getYaw()) + Math.abs(this.lastXRot - to.getPitch());

                if ((delta > 1f / 256 || deltaAngle > 10f) && !this.player.isImmobile()) {
                    this.lastPosX = to.getX();
                    this.lastPosY = to.getY();
                    this.lastPosZ = to.getZ();
                    this.lastYRot = to.getYaw();
                    this.lastXRot = to.getPitch();

                    Location oldTo = to.clone();
                    PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
                    this.cserver.getPluginManager().callEvent(event);

                    // If the event is cancelled we move the player back to their old location.
                    if (event.isCancelled()) {
                        this.player.getBukkitEntity().teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN); // Folia - region threading
                        return;
                    }

                    // If a Plugin has changed the To destination then we teleport the Player
                    // there to avoid any 'Moved wrongly' or 'Moved too quickly' errors.
                    // We only do this if the Event was not cancelled.
                    if (!oldTo.equals(event.getTo()) && !event.isCancelled()) {
                        this.player.getBukkitEntity().teleportAsync(event.getTo(), PlayerTeleportEvent.TeleportCause.PLUGIN); // Folia - region threading
                        return;
                    }

                    // Check to see if the Players Location has some how changed during the call of the event.
                    // This can happen due to a plugin teleporting the player instead of using .setTo()
                    if (!from.equals(this.getCraftPlayer().getLocation()) && this.justTeleported) {
                        this.justTeleported = false;
                        return;
                    }
                }
                // CraftBukkit end
                this.player.level().getChunkSource().move(this.player);
                Vec3 clientDeltaMovement = new Vec3(vehicle.getX() - oldX, vehicle.getY() - oldY, vehicle.getZ() - oldZ);
                this.handlePlayerKnownMovement(clientDeltaMovement);
                vehicle.setOnGroundWithMovement(packet.onGround(), clientDeltaMovement);
                vehicle.doCheckFallDamage(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z, packet.onGround());
                this.player.checkMovementStatistics(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z);
                this.clientVehicleIsFloating = oyDist >= -0.03125
                    && !vehicleRestsOnSomething
                    && !this.server.allowFlight()
                    && !vehicle.isFlyingVehicle()
                    && !vehicle.isNoGravity()
                    && this.noBlocksAround(vehicle);
                this.vehicleLastGoodX = vehicle.getX();
                this.vehicleLastGoodY = vehicle.getY();
                this.vehicleLastGoodZ = vehicle.getZ();
            }
        }
    }

    private boolean noBlocksAround(final Entity entity) {
        // Paper start - stop using streams, this is already a known fixed problem in Entity#move
        final AABB box = entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0);
        final int minX = Mth.floor(box.minX);
        final int minY = Mth.floor(box.minY);
        final int minZ = Mth.floor(box.minZ);
        final int maxX = Mth.floor(box.maxX);
        final int maxY = Mth.floor(box.maxY);
        final int maxZ = Mth.floor(box.maxZ);

        final Level level = entity.level();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    pos.set(x, y, z);
                    final BlockState blockState = level.getBlockStateIfLoaded(pos);
                    if (blockState != null && !blockState.isAir()) {
                        return false;
                    }
                }
            }
        }

        return true;
        // Paper end - stop using streams, this is already a known fixed problem in Entity#move
    }

    @Override
    public void handleAcceptTeleportPacket(final ServerboundAcceptTeleportationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (packet.getId() == this.awaitingTeleport) {
            if (this.awaitingPositionFromClient == null) {
                this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"), org.bukkit.event.player.PlayerKickEvent.Cause.INVALID_PLAYER_MOVEMENT); // Paper - kick event cause
                return;
            }

            // Folia start - do not allow out of region teleport accept
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(
                    this.player.level(),
                    ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(this.awaitingPositionFromClient.x),
                    ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(this.awaitingPositionFromClient.z),
                    1)
            ) {
                LOGGER.warn("Disconnecting '" + this.player.getScoreboardName() + "' for out of region teleport accept");
                this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"), org.bukkit.event.player.PlayerKickEvent.Cause.INVALID_PLAYER_MOVEMENT);
                return;
            }
            // Folia end - do not allow out of region teleport accept

            this.player
                .absSnapTo(
                    this.awaitingPositionFromClient.x,
                    this.awaitingPositionFromClient.y,
                    this.awaitingPositionFromClient.z,
                    this.player.getYRot(),
                    this.player.getXRot()
                );
            this.lastGoodX = this.awaitingPositionFromClient.x;
            this.lastGoodY = this.awaitingPositionFromClient.y;
            this.lastGoodZ = this.awaitingPositionFromClient.z;
            this.player.hasChangedDimension();
            this.awaitingPositionFromClient = null;
            this.player.level().getChunkSource().move(this.player); // CraftBukkit
        }
    }

    @Override
    public void handleAcceptPlayerLoad(final ServerboundPlayerLoadedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.markClientLoaded(false); // Paper - Add PlayerLoadedWorldEvent
    }

    @Override
    public void handleRecipeBookSeenRecipePacket(final ServerboundRecipeBookSeenRecipePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        RecipeManager.ServerDisplayInfo entry = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
        if (entry != null) {
            this.player.getRecipeBook().removeHighlight(entry.parent().id());
        }
    }

    @Override
    public void handleBundleItemSelectedPacket(final ServerboundSelectBundleItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.containerMenu.setSelectedBundleItemIndex(packet.slotId(), packet.selectedItemIndex());
    }

    @Override
    public void handleRecipeBookChangeSettingsPacket(final ServerboundRecipeBookChangeSettingsPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        CraftEventFactory.callRecipeBookSettingsEvent(this.player, packet.getBookType(), packet.isOpen(), packet.isFiltering()); // CraftBukkit
        this.player.getRecipeBook().setBookSetting(packet.getBookType(), packet.isOpen(), packet.isFiltering());
    }

    @Override
    public void handleSeenAdvancements(final ServerboundSeenAdvancementsPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (packet.getAction() == ServerboundSeenAdvancementsPacket.Action.OPENED_TAB) {
            Identifier id = Objects.requireNonNull(packet.getTab());
            AdvancementHolder advancement = this.server.getAdvancements().get(id);
            if (advancement != null) {
                this.player.getAdvancements().setSelectedTab(advancement);
            }
        }
    }

    // Paper start - AsyncTabCompleteEvent
    private static final java.util.concurrent.ExecutorService TAB_COMPLETE_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(4,
        new com.google.common.util.concurrent.ThreadFactoryBuilder().setDaemon(true).setNameFormat("Async Tab Complete Thread - #%d").setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER)).build());
    // Paper end - AsyncTabCompleteEvent

    @Override
    public void handleCustomCommandSuggestions(final ServerboundCommandSuggestionPacket packet) {
        // PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level()); // Paper - AsyncTabCompleteEvent; run this async
        // CraftBukkit start
        if (!this.tabSpamThrottler.isIncrementAndUnderThreshold() && !this.server.getPlayerList().isOp(this.player.nameAndId()) && !this.server.isSingleplayerOwner(this.player.nameAndId())) { // Paper - configurable tab spam limits
            this.disconnectAsync(Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM); // Paper - Kick event cause // Paper - add proper async disconnect
            return;
        }
        // CraftBukkit end
        // Paper start - Don't suggest if tab-complete is disabled
        if (org.spigotmc.SpigotConfig.tabComplete < 0) {
            return;
        }
        // Paper end - Don't suggest if tab-complete is disabled
        // Paper start
        final int index;
        if (packet.getCommand().length() > 64 && ((index = packet.getCommand().indexOf(' ')) == -1 || index >= 64)) {
            this.disconnectAsync(Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM); // Paper - add proper async disconnect
            return;
        }
        // Paper end
        // Paper start - AsyncTabCompleteEvent
        TAB_COMPLETE_EXECUTOR.execute(() -> this.handleCustomCommandSuggestions0(packet));
    }

    private void handleCustomCommandSuggestions0(final ServerboundCommandSuggestionPacket packet) {
        // Paper end - AsyncTabCompleteEvent
        StringReader command = new StringReader(packet.getCommand());
        if (command.canRead() && command.peek() == '/') {
            command.skip();
        }

        // Paper start - AsyncTabCompleteEvent
        final com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event = new com.destroystokyo.paper.event.server.AsyncTabCompleteEvent(this.getCraftPlayer(), packet.getCommand(), true, null);
        event.callEvent();
        final List<com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion> completions = event.isCancelled() ? com.google.common.collect.ImmutableList.of() : event.completions();
        // If the event isn't handled, we can assume that we have no completions, and so we'll ask the server
        if (!event.isHandled()) {
            if (event.isCancelled()) {
                return;
            }

            // This needs to be on main
            this.player.getBukkitEntity().taskScheduler.schedule((ServerPlayer player) -> this.sendServerSuggestions(packet, command), null, 1L); // Folia - region threading
        } else if (!completions.isEmpty()) {
            final com.mojang.brigadier.suggestion.SuggestionsBuilder builder0 = new com.mojang.brigadier.suggestion.SuggestionsBuilder(packet.getCommand(), command.getTotalLength());
            final com.mojang.brigadier.suggestion.SuggestionsBuilder builder = builder0.createOffset(builder0.getInput().lastIndexOf(' ') + 1);
            for (final com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion completion : completions) {
                final Integer intSuggestion = com.google.common.primitives.Ints.tryParse(completion.suggestion());
                if (intSuggestion != null) {
                    builder.suggest(intSuggestion, PaperAdventure.asVanilla(completion.tooltip()));
                } else {
                    builder.suggest(completion.suggestion(), PaperAdventure.asVanilla(completion.tooltip()));
                }
            }
            // Paper start - Brigadier API
            com.mojang.brigadier.suggestion.Suggestions suggestions = builder.buildFuture().join();
            com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent suggestEvent = new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent(this.getCraftPlayer(), suggestions, packet.getCommand());
            suggestEvent.setCancelled(suggestions.isEmpty());
            if (suggestEvent.callEvent()) {
                this.connection.send(new ClientboundCommandSuggestionsPacket(packet.getId(), limitTo(suggestEvent.getSuggestions(), ServerGamePacketListenerImpl.MAX_COMMAND_SUGGESTIONS)));
            }
            // Paper end - Brigadier API
        }
    }
    // Paper start - brig API
    private static Suggestions limitTo(final Suggestions suggestions, final int size) {
        return suggestions.getList().size() <= size ? suggestions : new Suggestions(suggestions.getRange(), suggestions.getList().subList(0, size));
    }
    // Paper end - brig API

    private void sendServerSuggestions(final ServerboundCommandSuggestionPacket packet, final StringReader command) {
        // Paper end - AsyncTabCompleteEvent
        ParseResults<CommandSourceStack> parse = this.server.getCommands().getDispatcher().parse(command, this.player.createCommandSourceStack());
        // Paper start - Handle non-recoverable exceptions
        if (!parse.getExceptions().isEmpty()
            && parse.getExceptions().values().stream().anyMatch(e -> e instanceof io.papermc.paper.brigadier.TagParseCommandSyntaxException)) {
            this.disconnect(Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM);
            return;
        }
        // Paper end - Handle non-recoverable exceptions
        this.server.getCommands().getDispatcher().getCompletionSuggestions(parse).thenAccept(results -> {
            // Paper start - Don't tab-complete namespaced commands if send-namespaced is false
            if (!org.spigotmc.SpigotConfig.sendNamespaced && results.getRange().getStart() <= 1) {
                results.getList().removeIf(suggestion -> suggestion.getText().contains(":"));
            }
            // Paper end - Don't tab-complete namespaced commands if send-namespaced is false
            // Paper start - Brigadier API
            com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent suggestEvent = new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent(this.getCraftPlayer(), results, packet.getCommand());
            suggestEvent.setCancelled(results.isEmpty());
            if (suggestEvent.callEvent()) {
                this.send(new ClientboundCommandSuggestionsPacket(packet.getId(), limitTo(suggestEvent.getSuggestions(), ServerGamePacketListenerImpl.MAX_COMMAND_SUGGESTIONS)));
            }
            // Paper end - Brigadier API
        });
    }

    @Override
    public void handleSetCommandBlock(final ServerboundSetCommandBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.canUseGameMasterBlocks() && (!this.player.isCreative() || !this.player.getBukkitEntity().hasPermission("minecraft.commandblock"))) { // Paper - command block permission
            this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
        } else {
            BaseCommandBlock commandBlock = null;
            CommandBlockEntity autoCommandBlock = null;
            BlockPos blockPos = packet.getPos();
            BlockEntity blockEntity = this.player.level().getBlockEntity(blockPos);
            if (blockEntity instanceof CommandBlockEntity commandBlockEntity) {
                autoCommandBlock = commandBlockEntity;
                commandBlock = autoCommandBlock.getCommandBlock();
            }

            String command = packet.getCommand();
            boolean trackOutput = packet.isTrackOutput();
            if (commandBlock != null) {
                CommandBlockEntity.Mode oldMode = autoCommandBlock.getMode();
                BlockState currentBlockState = this.player.level().getBlockState(blockPos);
                Direction direction = currentBlockState.getValue(CommandBlock.FACING);

                BlockState baseBlockState = switch (packet.getMode()) {
                    case SEQUENCE -> Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState();
                    case AUTO -> Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState();
                    default -> Blocks.COMMAND_BLOCK.defaultBlockState();
                };
                BlockState blockState = baseBlockState.setValue(CommandBlock.FACING, direction).setValue(CommandBlock.CONDITIONAL, packet.isConditional());
                if (blockState != currentBlockState) {
                    this.player.level().setBlock(blockPos, blockState, Block.UPDATE_CLIENTS);
                    blockEntity.setBlockState(blockState);
                    this.player.level().getChunkAt(blockPos).setBlockEntity(blockEntity);
                }

                commandBlock.setCommand(command);
                commandBlock.setTrackOutput(trackOutput);
                if (!trackOutput) {
                    commandBlock.setLastOutput(null);
                }

                autoCommandBlock.setAutomatic(packet.isAutomatic());
                if (oldMode != packet.getMode()) {
                    autoCommandBlock.onModeSwitch();
                }

                if (this.player.level().isCommandBlockEnabled()) {
                    commandBlock.onUpdated(this.player.level());
                }

                if (!StringUtil.isNullOrEmpty(command)) {
                    this.player
                        .sendSystemMessage(
                            Component.translatable(
                                this.player.level().isCommandBlockEnabled() ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command
                            )
                        );
                }
            }
        }
    }

    @Override
    public void handleSetCommandMinecart(final ServerboundSetCommandMinecartPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.canUseGameMasterBlocks() && (!this.player.isCreative() || !this.player.getBukkitEntity().hasPermission("minecraft.commandblock"))) { // Paper - command block permission
            this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
        } else {
            BaseCommandBlock commandBlock = packet.getCommandBlock(this.player.level());
            if (commandBlock != null) {
                String command = packet.getCommand();
                commandBlock.setCommand(command);
                commandBlock.setTrackOutput(packet.isTrackOutput());
                if (!packet.isTrackOutput()) {
                    commandBlock.setLastOutput(null);
                }

                boolean commandBlockEnabled = this.player.level().isCommandBlockEnabled();
                if (commandBlockEnabled) {
                    commandBlock.onUpdated(this.player.level());
                }

                if (!StringUtil.isNullOrEmpty(command)) {
                    this.player
                        .sendSystemMessage(Component.translatable(commandBlockEnabled ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command));
                }
            }
        }
    }

    @Override
    public void handlePickItemFromBlock(final ServerboundPickItemFromBlockPacket packet) {
        ServerLevel level = this.player.level();
        PacketUtils.ensureRunningOnSameThread(packet, this, level);
        BlockPos pos = packet.pos();
        if (this.player.isWithinBlockInteractionRange(pos, 1.0)) {
            if (level.isLoaded(pos)) {
                BlockState blockState = level.getBlockState(pos);
                boolean includeData = this.player.hasInfiniteMaterials() && packet.includeData();
                ItemStack itemStack = blockState.getCloneItemStack(level, pos, includeData);
                if (!itemStack.isEmpty()) {
                    if (includeData && this.player.getBukkitEntity().hasPermission("minecraft.nbt.copy")) { // Spigot
                        addBlockDataToItem(blockState, level, pos, itemStack);
                    }

                    this.tryPickItem(itemStack, pos, null, packet.includeData()); // Paper - Extend PlayerPickItemEvent API
                }
            }
        }
    }

    private static void addBlockDataToItem(final BlockState blockState, final ServerLevel level, final BlockPos pos, final ItemStack itemStack) {
        BlockEntity blockEntity = blockState.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        if (blockEntity != null) {
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LOGGER)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
                blockEntity.saveCustomOnly(output);
                blockEntity.removeComponentsFromTag(output);
                BlockItem.setBlockEntityData(itemStack, blockEntity.getType(), output);
                itemStack.applyComponents(blockEntity.collectComponents());
            }
        }
    }

    @Override
    public void handlePickItemFromEntity(final ServerboundPickItemFromEntityPacket packet) {
        ServerLevel level = this.player.level();
        PacketUtils.ensureRunningOnSameThread(packet, this, level);
        Entity entity = level.getEntityOrPart(packet.id());
        if (entity != null && this.player.isWithinEntityInteractionRange(entity, 3.0)) {
            ItemStack itemStack = entity.getPickResult();
            if (itemStack != null && !itemStack.isEmpty()) {
                this.tryPickItem(itemStack, null, entity, packet.includeData()); // Paper - Extend PlayerPickItemEvent API
            }

            if (packet.includeData() && this.player.canUseGameMasterBlocks() && entity instanceof Avatar avatar) {
                FetchProfileCommand.printForAvatar(this.player.createCommandSourceStack(), avatar);
            }
        }
    }

    private void tryPickItem(ItemStack itemStack, @Nullable BlockPos blockPos, @Nullable Entity entity, boolean includeData) { // Paper - Extend PlayerPickItemEvent API
        if (itemStack.isItemEnabled(this.player.level().enabledFeatures())) {
            Inventory inventory = this.player.getInventory();
            int slotWithExistingItem = inventory.findSlotMatchingItem(itemStack);
            // Paper start - Add PlayerPickItemEvent
            final int sourceSlot = slotWithExistingItem;
            final int targetSlot = Inventory.isHotbarSlot(sourceSlot) ? sourceSlot : inventory.getSuitableHotbarSlot();
            final org.bukkit.entity.Player bukkitPlayer = this.player.getBukkitEntity();
            final io.papermc.paper.event.player.PlayerPickItemEvent event = entity != null
                ? new io.papermc.paper.event.player.PlayerPickEntityEvent(bukkitPlayer, entity.getBukkitEntity(), includeData, targetSlot, sourceSlot)
                : new io.papermc.paper.event.player.PlayerPickBlockEvent(bukkitPlayer, org.bukkit.craftbukkit.block.CraftBlock.at(this.player.level(), blockPos), includeData, targetSlot, sourceSlot);
            if (!event.callEvent()) {
                return;
            }
            slotWithExistingItem = event.getSourceSlot();
            // Paper end - Add PlayerPickItemEvent
            if (slotWithExistingItem != Inventory.NOT_FOUND_INDEX) {
                if (Inventory.isHotbarSlot(slotWithExistingItem) && Inventory.isHotbarSlot(event.getTargetSlot())) { // Paper - Add PlayerPickItemEvent
                    inventory.setSelectedSlot(event.getTargetSlot()); // Paper - Add PlayerPickItemEvent
                } else {
                    inventory.pickSlot(slotWithExistingItem, event.getTargetSlot()); // Paper - Add PlayerPickItemEvent
                }
            } else if (this.player.hasInfiniteMaterials()) {
                inventory.addAndPickItem(itemStack, event.getTargetSlot()); // Paper - Add PlayerPickItemEvent
            }

            this.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
            this.player.inventoryMenu.broadcastChanges();
            if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
        }
    }

    @Override
    public void handleRenameItem(final ServerboundRenameItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.containerMenu instanceof AnvilMenu menu) {
            if (!menu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, menu);
                return;
            }

            menu.setItemName(packet.getName());
        }
    }

    @Override
    public void handleSetBeaconPacket(final ServerboundSetBeaconPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.containerMenu instanceof BeaconMenu menu) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
                return;
            }

            if (!menu.updateEffects(packet.primary(), packet.secondary())) {
                this.disconnect(Component.translatable("multiplayer.disconnect.generic"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause
                LOGGER.warn("Player {} tried to set invalid beacon effects", this.player);
            }
        }
    }

    @Override
    public void handleSetGameRule(final ServerboundSetGameRulePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamerule")) { // Paper - add permission check
            LOGGER.warn("Player {} tried to set game rule values without required permissions", this.player.getGameProfile().name());
        } else {
            GameRules gameRules = this.player.level().getGameRules();

            for (ServerboundSetGameRulePacket.Entry entry : packet.entries()) {
                GameRule<?> rule = BuiltInRegistries.GAME_RULE.getValue(entry.gameRuleKey());
                if (rule != null) {
                    this.setGameRuleValue(gameRules, rule, entry.value());
                } else {
                    LOGGER.warn("Received request to set unknown game rule: {}", entry.gameRuleKey());
                }
            }
        }
    }

    private <T> void setGameRuleValue(final GameRules gameRules, final GameRule<T> rule, final String value) {
        rule.deserialize(value).result().ifPresent(parsedValue -> {
            parsedValue = org.bukkit.craftbukkit.event.CraftEventFactory.handleGameRuleSet(rule, parsedValue, this.player.level(), this.player.getBukkitEntity()).value(); // Paper - per-world game rules and event
            this.broadcastGameRuleChangeToOperators(rule, (T)parsedValue);
        });
    }

    private <T> void broadcastGameRuleChangeToOperators(final GameRule<T> rule, final T value) {
        Component message = Component.translatable("commands.gamerule.set", rule.id(), rule.serialize(value));
        PlayerList playerList = this.server.getPlayerList();
        playerList.getPlayers().stream().filter(op -> playerList.isOp(op.nameAndId())).forEach(op -> op.sendSystemMessage(message));
    }

    @Override
    public void handleSetStructureBlock(final ServerboundSetStructureBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos blockPos = packet.getPos();
            BlockState state = this.player.level().getBlockState(blockPos);
            if (this.player.level().getBlockEntity(blockPos) instanceof StructureBlockEntity structure) {
                structure.setMode(packet.getMode());
                structure.setStructureName(packet.getName());
                structure.setStructurePos(packet.getOffset());
                structure.setStructureSize(packet.getSize());
                structure.setMirror(packet.getMirror());
                structure.setRotation(packet.getRotation());
                structure.setMetaData(packet.getData());
                structure.setIgnoreEntities(packet.isIgnoreEntities());
                structure.setStrict(packet.isStrict());
                structure.setShowAir(packet.isShowAir());
                structure.setShowBoundingBox(packet.isShowBoundingBox());
                structure.setIntegrity(packet.getIntegrity());
                structure.setSeed(packet.getSeed());
                if (structure.hasStructureName()) {
                    String actualStructureName = structure.getStructureName();
                    if (packet.getUpdateType() == StructureBlockEntity.UpdateType.SAVE_AREA) {
                        if (structure.saveStructure()) {
                            this.player.sendSystemMessage(Component.translatable("structure_block.save_success", actualStructureName));
                        } else {
                            this.player.sendSystemMessage(Component.translatable("structure_block.save_failure", actualStructureName));
                        }
                    } else if (packet.getUpdateType() == StructureBlockEntity.UpdateType.LOAD_AREA) {
                        if (!structure.isStructureLoadable()) {
                            this.player.sendSystemMessage(Component.translatable("structure_block.load_not_found", actualStructureName));
                        } else if (structure.placeStructureIfSameSize(this.player.level())) {
                            this.player.sendSystemMessage(Component.translatable("structure_block.load_success", actualStructureName));
                        } else {
                            this.player.sendSystemMessage(Component.translatable("structure_block.load_prepare", actualStructureName));
                        }
                    } else if (packet.getUpdateType() == StructureBlockEntity.UpdateType.SCAN_AREA) {
                        if (structure.detectSize()) {
                            this.player.sendSystemMessage(Component.translatable("structure_block.size_success", actualStructureName));
                        } else {
                            this.player.sendSystemMessage(Component.translatable("structure_block.size_failure"));
                        }
                    }
                } else {
                    this.player.sendSystemMessage(Component.translatable("structure_block.invalid_structure_name", packet.getName()));
                }

                structure.setChanged();
                this.player.level().sendBlockUpdated(blockPos, state, state, Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleSetTestBlock(final ServerboundSetTestBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos blockPos = packet.position();
            BlockState initialState = this.player.level().getBlockState(blockPos);
            if (this.player.level().getBlockEntity(blockPos) instanceof TestBlockEntity testBlock) {
                testBlock.setMode(packet.mode());
                testBlock.setMessage(packet.message());
                testBlock.setChanged();
                this.player.level().sendBlockUpdated(blockPos, initialState, testBlock.getBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleTestInstanceBlockAction(final ServerboundTestInstanceBlockActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        BlockPos pos = packet.pos();
        if (this.player.canUseGameMasterBlocks() && this.player.level().getBlockEntity(pos) instanceof TestInstanceBlockEntity blockEntity) {
            if (packet.action() != ServerboundTestInstanceBlockActionPacket.Action.QUERY
                && packet.action() != ServerboundTestInstanceBlockActionPacket.Action.INIT) {
                blockEntity.set(packet.data());
                if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.RESET) {
                    blockEntity.resetTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.SAVE) {
                    blockEntity.saveTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.EXPORT) {
                    blockEntity.exportTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.RUN) {
                    blockEntity.runTest(this.player::sendSystemMessage);
                }

                BlockState state = this.player.level().getBlockState(pos);
                this.player.level().sendBlockUpdated(pos, Blocks.AIR.defaultBlockState(), state, Block.UPDATE_ALL);
            } else {
                Registry<GameTestInstance> registry = this.player.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE);
                Optional<Holder.Reference<GameTestInstance>> test = packet.data().test().flatMap(registry::get);
                Component status;
                if (test.isPresent()) {
                    status = test.get().value().describe();
                } else {
                    status = Component.translatable("test_instance.description.no_test").withStyle(ChatFormatting.RED);
                }

                Optional<Vec3i> size;
                if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.QUERY) {
                    size = packet.data()
                        .test()
                        .flatMap(testKey -> TestInstanceBlockEntity.getStructureSize(this.player.level(), (ResourceKey<GameTestInstance>)testKey));
                } else {
                    size = Optional.empty();
                }

                this.connection.send(new ClientboundTestInstanceBlockStatus(status, size));
            }
        }
    }

    @Override
    public void handleSetJigsawBlock(final ServerboundSetJigsawBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos blockPos = packet.getPos();
            BlockState state = this.player.level().getBlockState(blockPos);
            if (this.player.level().getBlockEntity(blockPos) instanceof JigsawBlockEntity jigsaw) {
                jigsaw.setName(packet.getName());
                jigsaw.setTarget(packet.getTarget());
                jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, packet.getPool()));
                jigsaw.setFinalState(packet.getFinalState());
                jigsaw.setJoint(packet.getJoint());
                jigsaw.setPlacementPriority(packet.getPlacementPriority());
                jigsaw.setSelectionPriority(packet.getSelectionPriority());
                jigsaw.setChanged();
                this.player.level().sendBlockUpdated(blockPos, state, state, Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleJigsawGenerate(final ServerboundJigsawGeneratePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos blockPos = packet.getPos();
            if (this.player.level().getBlockEntity(blockPos) instanceof JigsawBlockEntity jigsaw) {
                jigsaw.generate(this.player.level(), packet.levels(), packet.keepJigsaws());
            }
        }
    }

    @Override
    public void handleSelectTrade(final ServerboundSelectTradePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        int selection = packet.getItem();
        if (this.player.containerMenu instanceof MerchantMenu menu) {
            // CraftBukkit start
            final org.bukkit.event.inventory.TradeSelectEvent tradeSelectEvent = CraftEventFactory.callTradeSelectEvent(selection, menu);
            if (tradeSelectEvent.isCancelled()) {
                this.player.containerMenu.sendAllDataToRemote();
                return;
            }
            // CraftBukkit end
            if (!menu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, menu);
                return;
            }

            menu.setSelectionHint(selection);
            menu.tryMoveItems(selection);
        }
    }

    @Override
    public void handleEditBook(final ServerboundEditBookPacket packet) {
        // Paper start - Book size limits
        final io.papermc.paper.configuration.type.number.IntOr.Disabled pageMax = io.papermc.paper.configuration.GlobalConfiguration.get().itemValidation.bookSize.pageMax;
        if (!this.cserver.isPrimaryThread() && pageMax.enabled()) {
            final List<String> pageList = packet.pages();
            long byteTotal = 0;
            final int maxBookPageSize = pageMax.intValue();
            final double multiplier = Math.clamp(io.papermc.paper.configuration.GlobalConfiguration.get().itemValidation.bookSize.totalMultiplier, 0.3D, 1D);
            long byteAllowed = maxBookPageSize;
            for (final String page : pageList) {
                final int byteLength = page.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                byteTotal += byteLength;
                final int length = page.length();
                int multiByteCharacters = 0;
                if (byteLength != length) {
                    // Count the number of multi byte characters
                    for (final char c : page.toCharArray()) {
                        if (c > 127) {
                            multiByteCharacters++;
                        }
                    }
                }

                // Allow pages with fewer characters to consume less of the allowed byte quota
                byteAllowed += maxBookPageSize * Math.clamp((double) length / 255D, 0.1D, 1) * multiplier;

                if (multiByteCharacters > 1) {
                    // Penalize multibyte characters
                    byteAllowed -= multiByteCharacters;
                }
            }

            if (byteTotal > byteAllowed) {
                ServerGamePacketListenerImpl.LOGGER.warn("{} tried to send a book too large. Book size: {} - Allowed: {} - Pages: {}", this.player.getScoreboardName(), byteTotal, byteAllowed, pageList.size());
                this.disconnectAsync(Component.literal("Book too large!"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause // Paper - add proper async disconnect
                return;
            }
        }
        // Paper end - Book size limits
        // CraftBukkit start
        if (this.lastBookTick + 20 > this.lastTick) { // Folia - region threading
            this.disconnectAsync(Component.literal("Book edited too quickly!"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause // Paper - add proper async disconnect
            return;
        }
        this.lastBookTick = this.lastTick; // Folia - region threading
        // CraftBukkit end
        int slot = packet.slot();
        if (Inventory.isHotbarSlot(slot) || slot == 40) {
            List<String> contents = Lists.newArrayList();
            Optional<String> title = packet.title();
            title.ifPresent(contents::add);
            contents.addAll(packet.pages());
            Consumer<List<FilteredText>> handler = title.isPresent()
                ? filteredContents -> this.signBook(filteredContents.get(0), filteredContents.subList(1, filteredContents.size()), slot)
                : filteredContents -> this.updateBookContents(filteredContents, slot);
            // Folia start - region threading
            this.filterTextPacket(contents).thenAcceptAsync(handler, this.player::queuePacketTask).whenComplete((Object res, Throwable thr) -> {
                if (thr != null) {
                    LOGGER.error("Failed to handle book update packet", thr);
                }
            });
            // Folia end - region threading
        }
    }

    private void updateBookContents(final List<FilteredText> contents, final int slot) {
        // CraftBukkit start
        ItemStack handItem = this.player.getInventory().getItem(slot);
        ItemStack carried = handItem.copy();
        // CraftBukkit end
        if (carried.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
            List<Filterable<String>> pages = contents.stream().map(this::filterableFromOutgoing).toList();
            carried.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
            this.player.getInventory().setItem(slot, CraftEventFactory.handleEditBookEvent(this.player, slot, handItem, carried)); // CraftBukkit // Paper - Don't ignore result (see other callsite for handleEditBookEvent)
        }
    }

    private void signBook(final FilteredText title, final List<FilteredText> contents, final int slot) {
        ItemStack carried = this.player.getInventory().getItem(slot);
        if (carried.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
            ItemStack writtenBook = carried.transmuteCopy(Items.WRITTEN_BOOK);
            writtenBook.remove(DataComponents.WRITABLE_BOOK_CONTENT);
            List<Filterable<Component>> pages = contents.stream().map(page -> this.filterableFromOutgoing(page).<Component>map(Component::literal)).toList();
            writtenBook.set(
                DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(this.filterableFromOutgoing(title), this.player.getPlainTextName(), 0, pages, true)
            );
            CraftEventFactory.handleEditBookEvent(this.player, slot, carried, writtenBook); // CraftBukkit
            this.player.getInventory().setItem(slot, carried); // CraftBukkit - event factory updates the hand book
        }
    }

    private Filterable<String> filterableFromOutgoing(final FilteredText text) {
        return this.player.isTextFilteringEnabled() ? Filterable.passThrough(text.filteredOrEmpty()) : Filterable.from(text);
    }

    @Override
    public void handleEntityTagQuery(final ServerboundEntityTagQueryPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            Entity entity = this.player.level().getEntity(packet.getEntityId());
            if (entity != null) {
                try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                    TagValueOutput output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
                    entity.saveWithoutId(output);
                    CompoundTag result = output.buildResult();
                    this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), result));
                }
            }
        }
    }

    @Override
    public void handleContainerSlotStateChanged(final ServerboundContainerSlotStateChangedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.isSpectator() && packet.containerId() == this.player.containerMenu.containerId) {
            if (this.player.containerMenu instanceof CrafterMenu crafterMenu && crafterMenu.getContainer() instanceof CrafterBlockEntity crafterBlockEntity) {
                crafterBlockEntity.setSlotState(packet.slotId(), packet.newState());
            }
        }
    }

    @Override
    public void handleBlockEntityTagQuery(final ServerboundBlockEntityTagQueryPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            BlockEntity blockEntity = this.player.level().getBlockEntity(packet.getPos());
            CompoundTag tag = blockEntity != null ? blockEntity.saveWithoutMetadata(this.player.registryAccess()) : null;
            this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), tag));
        }
    }

    @Override
    public void handleMovePlayer(final ServerboundMovePlayerPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (containsInvalidValues(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0), packet.getYRot(0.0F), packet.getXRot(0.0F))) {
            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"), org.bukkit.event.player.PlayerKickEvent.Cause.INVALID_PLAYER_MOVEMENT); // Paper - kick event cause
        } else {
            ServerLevel level = this.player.level();
            if (!this.player.wonGame && !this.player.isImmobile()) { // CraftBukkit
                if (this.tickCount == 0) {
                    this.resetPosition();
                }

                if (this.hasClientLoaded()) {
                    float targetYRot = Mth.wrapDegrees(packet.getYRot(this.player.getYRot()));
                    float targetXRot = Mth.wrapDegrees(packet.getXRot(this.player.getXRot()));
                    if (this.updateAwaitingTeleport()) {
                        this.player.absSnapRotationTo(targetYRot, targetXRot);
                    } else {
                        double targetX = clampHorizontal(packet.getX(this.player.getX()));
                        double targetY = clampVertical(packet.getY(this.player.getY()));
                        double targetZ = clampHorizontal(packet.getZ(this.player.getZ()));
                        if (this.player.isPassenger()) {
                            this.player.absSnapTo(this.player.getX(), this.player.getY(), this.player.getZ(), targetYRot, targetXRot);
                            this.player.level().getChunkSource().move(this.player);
                            this.allowedPlayerTicks = 20; // CraftBukkit
                        } else {
                            // CraftBukkit - Make sure the move is valid but then reset it for plugins to modify
                            float startYRot = this.player.getYRot();
                            float startXRot = this.player.getXRot();
                            // CraftBukkit end
                            double startX = this.player.getX();
                            double startY = this.player.getY();
                            double startZ = this.player.getZ();
                            double xDist = targetX - this.firstGoodX;
                            double yDist = targetY - this.firstGoodY;
                            double zDist = targetZ - this.firstGoodZ;
                            double expectedDist = this.player.getDeltaMovement().lengthSqr();
                            double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                            // Paper start - fix large move vectors killing the server
                            double currDeltaX = targetX - startX;
                            double currDeltaY = targetY - startY;
                            double currDeltaZ = targetZ - startZ;
                            movedDist = Math.max(movedDist, (currDeltaX * currDeltaX + currDeltaY * currDeltaY + currDeltaZ * currDeltaZ) - 1);
                            double otherFieldX = targetX - this.lastGoodX;
                            double otherFieldY = targetY - this.lastGoodY;
                            double otherFieldZ = targetZ - this.lastGoodZ;
                            movedDist = Math.max(movedDist, (otherFieldX * otherFieldX + otherFieldY * otherFieldY + otherFieldZ * otherFieldZ) - 1);
                            // Paper end - fix large move vectors killing the server
                            if (this.player.isSleeping()) {
                                if (movedDist > 1.0) {
                                    this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(), targetYRot, targetXRot);
                                }
                            } else {
                                boolean isFallFlying = this.player.isFallFlying();
                                if (level.tickRateManager().runsNormally()) {
                                    this.receivedMovePacketCount++;
                                    int deltaPackets = this.receivedMovePacketCount - this.knownMovePacketCount;
                                    // CraftBukkit start - handle custom speeds and skipped ticks
                                    int currTick = (int)(Util.getMillis() / 50); // Folia - region threading
                                    this.allowedPlayerTicks += currTick - this.lastTick; // Folia - region threading
                                    this.allowedPlayerTicks = Math.max(this.allowedPlayerTicks, 1);
                                    this.lastTick = currTick; // Folia - region threading

                                    if (deltaPackets > Math.max(this.allowedPlayerTicks, 5)) {
                                    // CraftBukkit end
                                        LOGGER.debug(
                                            "{} is sending move packets too frequently ({} packets since last tick)",
                                            this.player.getPlainTextName(),
                                            deltaPackets
                                        );
                                        deltaPackets = 1;
                                    }
                                    // CraftBukkit start - handle custom speeds and skipped ticks
                                    if (packet.hasRotation() || movedDist > 0) {
                                        this.allowedPlayerTicks -= 1;
                                    } else {
                                        this.allowedPlayerTicks = 20;
                                    }
                                    double speed;
                                    if (this.player.getAbilities().flying) {
                                        speed = this.player.getAbilities().getFlyingSpeed() * 20f;
                                    } else {
                                        speed = this.player.getAbilities().getWalkingSpeed() * 10f;
                                    }
                                    // Paper start - Prevent moving into unloaded chunks
                                    if (this.player.level().paperConfig().chunks.preventMovingIntoUnloadedChunks && (this.player.getX() != targetX || this.player.getZ() != targetZ) && !level.areChunksLoadedForMove(this.player.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(this.player.position())))) {
                                        // Paper start - Add fail move event
                                        io.papermc.paper.event.player.PlayerFailMoveEvent event = fireFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason.MOVED_INTO_UNLOADED_CHUNK,
                                                targetX, targetY, targetZ, targetYRot, targetXRot, false);
                                        if (!event.isAllowed()) {
                                            this.internalTeleport(PositionMoveRotation.of(this.player), Collections.emptySet());
                                            return;
                                        }
                                        // Paper end - Add fail move event
                                    }
                                    // Paper end - Prevent moving into unloaded chunks

                                    if (this.shouldCheckPlayerMovement(isFallFlying)) {
                                        float metersPerTick = isFallFlying ? 300.0F : 100.0F;
                                        if (movedDist - expectedDist > Math.max(metersPerTick, Mth.square(org.spigotmc.SpigotConfig.movedTooQuicklyMultiplier * (float) deltaPackets * speed))) {
                                            // CraftBukkit end
                                            // Paper start - Add fail move event
                                            io.papermc.paper.event.player.PlayerFailMoveEvent event = fireFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY,
                                                    targetX, targetY, targetZ, targetYRot, targetXRot, true);
                                            if (!event.isAllowed()) {
                                                if (event.getLogWarning()) {
                                            LOGGER.warn("{} moved too quickly! {},{},{}", this.player.getPlainTextName(), xDist, yDist, zDist);
                                                }
                                                this.teleport(
                                                        this.player.getX(), this.player.getY(), this.player.getZ(), this.player.getYRot(), this.player.getXRot()
                                                );
                                                return;
                                            }
                                            // Paper end - Add fail move event
                                        }
                                    }
                                }

                                AABB oldAABB = this.player.getBoundingBox(); // Paper - diff on change, should be old AABB
                                xDist = targetX - this.lastGoodX; // Paper - diff on change, used for checking large move vectors above
                                yDist = targetY - this.lastGoodY; // Paper - diff on change, used for checking large move vectors above
                                zDist = targetZ - this.lastGoodZ; // Paper - diff on change, used for checking large move vectors above
                                boolean movedUpwards = yDist > 0.0;
                                if (this.player.onGround() && !packet.isOnGround() && movedUpwards) {
                                    // Paper start - Add PlayerJumpEvent
                                    org.bukkit.entity.Player player = this.getCraftPlayer();
                                    Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot); // Get the Players previous Event location.
                                    Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);

                                    com.destroystokyo.paper.event.player.PlayerJumpEvent event = new com.destroystokyo.paper.event.player.PlayerJumpEvent(player, from, to);

                                    if (event.callEvent()) {
                                        this.player.jumpFromGround();
                                    } else {
                                        from = event.getFrom();
                                        this.internalTeleport(new PositionMoveRotation(org.bukkit.craftbukkit.util.CraftLocation.toVec3(from), Vec3.ZERO, from.getYaw(), from.getPitch()), Collections.emptySet());
                                        return;
                                    }
                                    // Paper end - Add PlayerJumpEvent
                                }

                                boolean playerStandsOnSomething = this.player.verticalCollisionBelow;
                                this.player.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
                                this.player.onGround = packet.isOnGround(); // CraftBukkit - SPIGOT-5810, SPIGOT-5835, SPIGOT-6828: reset by this.player.move
                                final boolean didCollide = targetX != this.player.getX() || targetY != this.player.getY() || targetZ != this.player.getZ(); // Paper - needed here as the difference in Y can be reset - also note: this is only a guess at whether collisions took place, floating point errors can make this true when it shouldn't be...
                                // Paper start - prevent position desync
                                if (this.awaitingPositionFromClient != null) {
                                    return; // ... thanks Mojang for letting move calls teleport across dimensions.
                                }
                                // Paper end - prevent position desync
                                double oyDist = yDist;
                                xDist = targetX - this.player.getX();
                                yDist = targetY - this.player.getY();
                                if (yDist > -0.5 || yDist < 0.5) {
                                    yDist = 0.0;
                                }

                                zDist = targetZ - this.player.getZ();
                                movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                                boolean movedWrongly = false; // Paper - Add fail move event; rename
                                if (!this.player.isChangingDimension()
                                    && movedDist > org.spigotmc.SpigotConfig.movedWronglyThreshold // Spigot
                                    && !this.player.isSleeping()
                                    && !this.player.isCreative()
                                    && !this.player.isSpectator()
                                    && !this.player.isInPostImpulseGraceTime()) {
                                    // Paper start - Add fail move event
                                    io.papermc.paper.event.player.PlayerFailMoveEvent event = fireFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason.MOVED_WRONGLY,
                                            targetX, targetY, targetZ, targetYRot, targetXRot, true);
                                    if (!event.isAllowed()) {
                                        movedWrongly = true;
                                        if (event.getLogWarning())
                                     // Paper end
                                    LOGGER.warn("{} moved wrongly!", this.player.getPlainTextName());
                                    } // Paper
                                }

                                // Paper start - Add fail move event
                                // Paper start - optimise out extra getCubes
                                boolean allowMovement = this.player.noPhysics || this.player.isSleeping() || !movedWrongly;
                                this.player.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot); // prevent desync by tping to the set position, dropped for unknown reasons by mojang
                                if (!this.player.noPhysics && !this.player.isSleeping() && allowMovement) {
                                    final AABB newBox = this.player.getBoundingBox();
                                    if (didCollide || !oldAABB.equals(newBox)) {
                                        // note: only call after setLocation, or else getBoundingBox is wrong
                                        allowMovement = !this.hasNewCollision(level, this.player, oldAABB, newBox);
                                    } // else: no collision at all detected, why do we care?
                                }
                                // Paper end - optimise out extra getCubes
                                if (!allowMovement) {
                                    io.papermc.paper.event.player.PlayerFailMoveEvent event = fireFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason.CLIPPED_INTO_BLOCK,
                                            targetX, targetY, targetZ, targetYRot, targetXRot, false);
                                    if (event.isAllowed()) {
                                        allowMovement = true;
                                    }
                                }
                                if (allowMovement) {
                                    // Paper end - Add fail move event
                                    // CraftBukkit start - fire PlayerMoveEvent
                                    // Reset to old location first
                                    this.player.absSnapTo(startX, startY, startZ, startYRot, startXRot);

                                    org.bukkit.entity.Player player = this.getCraftPlayer();
                                    if (!this.hasMoved) {
                                        this.lastPosX = startX;
                                        this.lastPosY = startY;
                                        this.lastPosZ = startZ;
                                        this.lastYRot = startYRot;
                                        this.lastXRot = startXRot;
                                        this.hasMoved = true;
                                    }
                                    Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot); // Get the Players previous Event location.
                                    Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);

                                    // Prevent 40 event-calls for less than a single pixel of movement >.>
                                    double delta = Mth.square(this.lastPosX - to.getX()) + Mth.square(this.lastPosY - to.getY()) + Mth.square(this.lastPosZ - to.getZ());
                                    float deltaAngle = Math.abs(this.lastYRot - to.getYaw()) + Math.abs(this.lastXRot - to.getPitch());

                                    if ((delta > 1f / 256 || deltaAngle > 10.0F) && !this.player.isImmobile()) {
                                        this.lastPosX = to.getX();
                                        this.lastPosY = to.getY();
                                        this.lastPosZ = to.getZ();
                                        this.lastYRot = to.getYaw();
                                        this.lastXRot = to.getPitch();

                                        Location oldTo = to.clone();
                                        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
                                        this.cserver.getPluginManager().callEvent(event);

                                        // If the event is cancelled we move the player back to their old location.
                                        if (event.isCancelled()) {
                                            this.player.getBukkitEntity().teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN); // Folia - region threading
                                            return;
                                        }

                                        // If a Plugin has changed the To destination then we teleport the Player
                                        // there to avoid any 'Moved wrongly' or 'Moved too quickly' errors.
                                        // We only do this if the Event was not cancelled.
                                        if (!oldTo.equals(event.getTo()) && !event.isCancelled()) {
                                            this.player.getBukkitEntity().teleportAsync(event.getTo(), PlayerTeleportEvent.TeleportCause.PLUGIN); // Folia - region threading
                                            return;
                                        }

                                        // Check to see if the Players Location has some how changed during the call of the event.
                                        // This can happen due to a plugin teleporting the player instead of using .setTo()
                                        if (!from.equals(this.getCraftPlayer().getLocation()) && this.justTeleported) {
                                            this.justTeleported = false;
                                            return;
                                        }
                                    }
                                    // Paper end
                                    this.player.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
                                    boolean isAutoSpinAttack = this.player.isAutoSpinAttack();
                                    this.clientIsFloating = oyDist >= -0.03125
                                        && !playerStandsOnSomething
                                        && !this.player.isSpectator()
                                        && !this.server.allowFlight()
                                        && !this.player.getAbilities().mayfly
                                        && !this.player.hasEffect(MobEffects.LEVITATION)
                                        && !isFallFlying
                                        && !isAutoSpinAttack
                                        && this.noBlocksAround(this.player);
                                    this.player.level().getChunkSource().move(this.player);
                                    Vec3 clientDeltaMovement = new Vec3(this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ);
                                    this.player.setOnGroundWithMovement(packet.isOnGround(), packet.horizontalCollision(), clientDeltaMovement);
                                    this.player.doCheckFallDamage(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z, packet.isOnGround());
                                    this.handlePlayerKnownMovement(clientDeltaMovement);
                                    if (movedUpwards) {
                                        this.player.resetFallDistance();
                                    }

                                    if (packet.isOnGround()
                                        || this.player.hasLandedInLiquid()
                                        || this.player.onClimbable()
                                        || this.player.isSpectator()
                                        || isFallFlying
                                        || isAutoSpinAttack) {
                                        this.player.tryResetCurrentImpulseContext();
                                    }

                                    this.player.checkMovementStatistics(this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ);
                                    this.lastGoodX = this.player.getX();
                                    this.lastGoodY = this.player.getY();
                                    this.lastGoodZ = this.player.getZ();
                                } else {
                                    this.internalTeleport(startX, startY, startZ, targetYRot, targetXRot); // CraftBukkit - SPIGOT-1807: Don't call teleport event, when the client thinks the player is falling, because the chunks are not loaded on the client yet.
                                    this.player
                                        .doCheckFallDamage(
                                            this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ, packet.isOnGround()
                                        );
                                    this.player.removeLatestMovementRecording();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean shouldCheckPlayerMovement(final boolean isFallFlying) {
        if (this.isSingleplayerOwner()) {
            return false;
        }

        if (this.player.isChangingDimension()) {
            return false;
        }

        GameRules gameRules = this.player.level().getGameRules();
        return gameRules.get(GameRules.PLAYER_MOVEMENT_CHECK) && (!isFallFlying || gameRules.get(GameRules.ELYTRA_MOVEMENT_CHECK));
    }

    private boolean updateAwaitingTeleport() {
        if (this.awaitingPositionFromClient != null) {
            if (false && this.tickCount - this.awaitingTeleportTime > 20) { // Paper - this will greatly screw with clients with > 1000ms RTT
                this.awaitingTeleportTime = this.tickCount;
                this.teleport(
                    this.awaitingPositionFromClient.x,
                    this.awaitingPositionFromClient.y,
                    this.awaitingPositionFromClient.z,
                    this.player.getYRot(),
                    this.player.getXRot()
                );
            }
            this.allowedPlayerTicks = 20; // CraftBukkit

            return true;
        } else {
            this.awaitingTeleportTime = this.tickCount;
            return false;
        }
    }

    // Paper start - optimise out extra getCubes
    private boolean hasNewCollision(final ServerLevel level, final Entity entity, final AABB oldBox, final AABB newBox) {
        final List<AABB> collisionsBB = new java.util.ArrayList<>();
        final List<VoxelShape> collisionsVoxel = new java.util.ArrayList<>();

        int flags = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_BORDER;
        if (level.paperConfig().chunks.preventMovingIntoUnloadedChunks) {
            flags |= ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_COLLIDE_WITH_UNLOADED_CHUNKS;
        }
        ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisions(
            level, entity, newBox, collisionsVoxel, collisionsBB,
            flags,
            null, null
        );

        for (int i = 0, len = collisionsBB.size(); i < len; ++i) {
            final AABB box = collisionsBB.get(i);
            if (!ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersect(box, oldBox)) {
                return true;
            }
        }

        for (int i = 0, len = collisionsVoxel.size(); i < len; ++i) {
            final VoxelShape voxel = collisionsVoxel.get(i);
            if (!ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersectNoEmpty(voxel, oldBox)) {
                return true;
            }
        }

        return false;
    }
    // Paper end - optimise out extra getCubes

    private boolean isEntityCollidingWithAnythingNew(
        final LevelReader level, final Entity entity, final AABB oldAABB, final double newX, final double newY, final double newZ
    ) {
        AABB newAABB = entity.getBoundingBox().move(newX - entity.getX(), newY - entity.getY(), newZ - entity.getZ());
        Iterable<VoxelShape> newCollisions = level.getPreMoveCollisions(entity, newAABB.deflate(1.0E-5F), oldAABB.getBottomCenter());
        VoxelShape oldShape = Shapes.create(oldAABB.deflate(1.0E-5F));

        for (VoxelShape shape : newCollisions) {
            if (!Shapes.joinIsNotEmpty(shape, oldShape, BooleanOp.AND)) {
                return true;
            }
        }

        return false;
    }

    public void teleport(final double x, final double y, final double z, final float yRot, final float xRot) {
        // CraftBukkit start
        this.teleport(x, y, z, yRot, xRot, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public boolean teleport(double x, double y, double z, float yRot, float xRot, PlayerTeleportEvent.TeleportCause cause) {
        return this.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet(), cause);
        // CraftBukkit end
    }

    public void teleport(final PositionMoveRotation destination, final Set<Relative> relatives) {
        // CraftBukkit start
        this.teleport(destination, relatives, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public boolean teleport(PositionMoveRotation destination, Set<Relative> relatives, PlayerTeleportEvent.TeleportCause cause) { // CraftBukkit - Return event status
        org.bukkit.entity.Player player = this.getCraftPlayer();
        Location from = player.getLocation();
        PositionMoveRotation absolutePosition = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(this.player), destination, relatives);
        Location to = CraftLocation.toBukkit(absolutePosition.position(), this.player.level(), absolutePosition.yRot(), absolutePosition.xRot());
        // SPIGOT-5171: Triggered on join
        if (from.equals(to)) {
            this.internalTeleport(destination, relatives);
            return true; // CraftBukkit - Return event status
        }

        // Paper start - Teleport API
        final Set<io.papermc.paper.entity.TeleportFlag.Relative> relativeFlags = java.util.EnumSet.noneOf(io.papermc.paper.entity.TeleportFlag.Relative.class);
        for (final Relative relativeArgument : relatives) {
            final io.papermc.paper.entity.TeleportFlag.Relative flag = org.bukkit.craftbukkit.entity.CraftEntity.deltaRelativeToAPI(relativeArgument);
            if (flag != null) relativeFlags.add(flag);
        }
        PlayerTeleportEvent event = new PlayerTeleportEvent(player, from.clone(), to.clone(), cause, java.util.Set.copyOf(relativeFlags));
        // Paper end - Teleport API
        this.cserver.getPluginManager().callEvent(event);

        if (event.isCancelled() || !to.equals(event.getTo())) {
            relatives = Set.of(); // target pos is absolute
            to = event.isCancelled() ? event.getFrom() : event.getTo();
            destination = new PositionMoveRotation(CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch());
        }

        this.internalTeleport(destination, relatives);
        return !event.isCancelled(); // CraftBukkit - Return event status
    }

    public void internalTeleport(Location dest) {
        this.internalTeleport(dest.getX(), dest.getY(), dest.getZ(), dest.getYaw(), dest.getPitch());
    }

    public void internalTeleport(double x, double y, double z, float yRot, float xRot) {
        this.internalTeleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet());
    }

    public void internalTeleport(PositionMoveRotation destination, Set<Relative> relatives) {
        org.spigotmc.AsyncCatcher.catchOp("teleport"); // Paper
        // Paper start - Prevent teleporting dead entities
        if (this.player.isRemoved()) {
            LOGGER.info("Attempt to teleport removed player {} restricted", this.player.getScoreboardName());
            if (this.server.isDebugging()) io.papermc.paper.util.TraceUtil.dumpTraceForThread("Attempt to teleport removed player");
            return;
        }
        // Paper end - Prevent teleporting dead entities
        if (Float.isNaN(destination.yRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), 0, destination.xRot());
        }
        if (Float.isNaN(destination.xRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), destination.yRot(), 0);
        }

        this.justTeleported = true;
        // CraftBukkit end
        this.awaitingTeleportTime = this.tickCount;
        if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
        }

        this.player.teleportSetPosition(destination, relatives);
        this.awaitingPositionFromClient = this.player.position();
        // CraftBukkit start - update last location
        this.lastPosX = this.awaitingPositionFromClient.x;
        this.lastPosY = this.awaitingPositionFromClient.y;
        this.lastPosZ = this.awaitingPositionFromClient.z;
        this.lastYRot = this.player.getYRot();
        this.lastXRot = this.player.getXRot();
        // CraftBukkit end
        this.send(ClientboundPlayerPositionPacket.of(this.awaitingTeleport, destination, relatives));
    }

    @Override
    public void handlePlayerAction(final ServerboundPlayerActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        if (this.hasClientLoaded()) {
            BlockPos pos = packet.getPos();
            this.player.resetLastActionTime();
            ServerboundPlayerActionPacket.Action action = packet.getAction();
            switch (action) {
                case STAB:
                    if (this.player.isSpectator()) {
                        return;
                    } else {
                        ItemStack itemInHand = this.player.getItemInHand(InteractionHand.MAIN_HAND);
                        if (this.player.cannotAttackWithItem(itemInHand, 5)) {
                            return;
                        }

                        PiercingWeapon piercingWeapon = itemInHand.get(DataComponents.PIERCING_WEAPON);
                        if (piercingWeapon != null) {
                            piercingWeapon.attack(this.player, EquipmentSlot.MAINHAND);
                        }

                        return;
                    }
                case SWAP_ITEM_WITH_OFFHAND:
                    if (!this.player.isSpectator()) {
                        ItemStack swap = this.player.getItemInHand(InteractionHand.OFF_HAND);
                        // CraftBukkit start - inspiration taken from DispenserRegistry (See SpigotCraft#394)
                        CraftItemStack mainHand = CraftItemStack.asCraftMirror(swap);
                        CraftItemStack offHand = CraftItemStack.asCraftMirror(this.player.getItemInHand(InteractionHand.MAIN_HAND));
                        PlayerSwapHandItemsEvent swapItemsEvent = new PlayerSwapHandItemsEvent(this.getCraftPlayer(), mainHand.clone(), offHand.clone());
                        this.cserver.getPluginManager().callEvent(swapItemsEvent);
                        if (swapItemsEvent.isCancelled()) {
                            return;
                        }
                        if (swapItemsEvent.getOffHandItem().equals(offHand)) {
                            this.player.setItemInHand(InteractionHand.OFF_HAND, this.player.getItemInHand(InteractionHand.MAIN_HAND));
                        } else {
                            this.player.setItemInHand(InteractionHand.OFF_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getOffHandItem()));
                        }
                        if (swapItemsEvent.getMainHandItem().equals(mainHand)) {
                            this.player.setItemInHand(InteractionHand.MAIN_HAND, swap);
                        } else {
                            this.player.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getMainHandItem()));
                        }
                        // CraftBukkit end
                        this.player.stopUsingItem();
                        if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                    }

                    return;
                case DROP_ITEM:
                    if (!this.player.isSpectator()) {
                        // limit how quickly items can be dropped
                        // If the ticks aren't the same then the count starts from 0 and we update the lastDropTick.
                        if (this.lastDropTick != io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick()) { // Folia - region threading
                            this.dropCount = 0;
                            this.lastDropTick = io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick(); // Folia - region threading
                        } else {
                            // Else we increment the drop count and check the amount.
                            this.dropCount++;
                            if (this.dropCount >= 20) {
                                ServerGamePacketListenerImpl.LOGGER.warn(this.player.getScoreboardName() + " dropped their items too quickly!");
                                this.disconnect(Component.literal("You dropped your items too quickly (Hacking?)"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause
                                return;
                            }
                        }
                        // CraftBukkit end
                        this.player.drop(false);
                        if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                    }

                    return;
                case DROP_ALL_ITEMS:
                    if (!this.player.isSpectator()) {
                        this.player.drop(true);
                        if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                    }

                    return;
                case RELEASE_USE_ITEM:
                    this.player.releaseUsingItem();
                    if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                    return;
                case START_DESTROY_BLOCK:
                case ABORT_DESTROY_BLOCK:
                case STOP_DESTROY_BLOCK:
                    // Paper start - Don't allow digging into unloaded chunks
                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.player.level(), pos.getX() >> 4, pos.getZ() >> 4, 8) || this.player.level().getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null || !this.player.isWithinBlockInteractionRange(pos, 1.0)) { // Folia - region threading - don't destroy blocks not owned
                        this.player.connection.ackBlockChangesUpTo(packet.getSequence());
                        return;
                    }
                    // Paper end - Don't allow digging into unloaded chunks
                    // Paper start - Send block entities after destroy prediction
                    this.player.gameMode.capturedBlockEntity = false;
                    this.player.gameMode.captureSentBlockEntities = true;
                    // Paper end - Send block entities after destroy prediction
                    this.player.gameMode.handleBlockBreakAction(pos, action, packet.getDirection(), this.player.level().getMaxY(), packet.getSequence());
                    this.ackBlockChangesUpTo(packet.getSequence());
                    // Paper start - Send block entities after destroy prediction
                    this.player.gameMode.captureSentBlockEntities = false;
                    // If a block entity was modified speedup the block change ack to avoid the block entity
                    // being overridden.
                    if (this.player.gameMode.capturedBlockEntity) {
                        // manually tick
                        this.send(new ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
                        this.player.connection.ackBlockChangesUpTo = -1;

                        this.player.gameMode.capturedBlockEntity = false;
                        BlockEntity blockEntity = this.player.level().getBlockEntity(pos);
                        if (blockEntity != null) {
                            this.player.connection.send(blockEntity.getUpdatePacket());
                        }
                    }
                    // Paper end - Send block entities after destroy prediction
                    if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                    return;
                default:
                    throw new IllegalArgumentException("Invalid player action");
            }
        }
    }

    private static boolean wasBlockPlacementAttempt(final ServerPlayer player, final ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }

        Item item = itemStack.getItem();
        return (item instanceof BlockItem || item instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY)
            && !player.getCooldowns().isOnCooldown(itemStack);
    }

    // Paper start - limit place/interactions
    private int limitedPackets;
    private long lastLimitedPacket = -1;

    private boolean checkLimit(long timestamp) {
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.enabled()) {
            return true;
        }

        final int threshold = io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.intValue();

        if (this.lastLimitedPacket != -1 && timestamp - this.lastLimitedPacket < threshold && this.limitedPackets++ >= 8) {
            return false;
        }

        if (this.lastLimitedPacket == -1 || timestamp - this.lastLimitedPacket >= threshold) {
            this.lastLimitedPacket = timestamp;
            this.limitedPackets = 0;
            return true;
        }

        return true;
    }
    // Paper end - limit place/interactions

    @Override
    public void handleUseItemOn(final ServerboundUseItemOnPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        if (!this.checkLimit(packet.timestamp)) return; // Spigot - check limit
        if (this.hasClientLoaded()) {
            this.ackBlockChangesUpTo(packet.getSequence());
            ServerLevel level = this.player.level();
            InteractionHand hand = packet.getHand();
            ItemStack itemStack = this.player.getItemInHand(hand);
            if (itemStack.isItemEnabled(level.enabledFeatures())) {
                BlockHitResult blockHit = packet.getHitResult();
                Vec3 location = blockHit.getLocation();
                // Paper start - improve distance check
                if (!location.isFinite()) {
                    return;
                }
                // Paper end - improve distance check
                BlockPos pos = blockHit.getBlockPos();
                if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.player.level(), pos.getX() >> 4, pos.getZ() >> 4, 8) &&  this.player.isWithinBlockInteractionRange(pos, 1.0)) { // Folia - do not allow players to interact with blocks outside the current region
                    Vec3 distance = location.subtract(Vec3.atCenterOf(pos));
                    double limit = 1.0000001;
                    if (Math.abs(distance.x()) < 1.0000001 && Math.abs(distance.y()) < 1.0000001 && Math.abs(distance.z()) < 1.0000001) {
                        Direction direction = blockHit.getDirection();
                        this.player.resetLastActionTime();
                        int maxY = level.getMaxY();
                        int minY = level.getMinY();
                        if (pos.getY() > maxY) {
                            this.player.sendBuildLimitMessage(true, maxY);
                        } else if (pos.getY() < minY) {
                            this.player.sendBuildLimitMessage(false, minY);
                        } else {
                            // Paper start - Allow using signs inside spawn protection
                            final boolean passthroughSignInteraction = (level.paperConfig().spawn.allowUsingSignsInsideSpawnProtection && level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.SignBlock);
                            if (!passthroughSignInteraction && this.server.isUnderSpawnProtection(level, pos, this.player)) {
                                this.player.sendSpawnProtectionMessage(pos);
                            } else if (this.awaitingPositionFromClient == null && (level.mayInteract(this.player, pos) || passthroughSignInteraction)) {
                                // Paper end - Allow using signs inside spawn protection
                                this.player.stopUsingItem(); // CraftBukkit - SPIGOT-4706
                                InteractionResult interactionResult = this.player.gameMode.useItemOn(this.player, level, itemStack, hand, blockHit);
                                if (interactionResult.consumesAction()) {
                                    CriteriaTriggers.ANY_BLOCK_USE.trigger(this.player, blockHit.getBlockPos(), itemStack);
                                }

                                if (direction == Direction.UP
                                    && !interactionResult.consumesAction()
                                    && pos.getY() >= maxY
                                    && wasBlockPlacementAttempt(this.player, itemStack)) {
                                    this.player.sendBuildLimitMessage(true, maxY);
                                } else if (interactionResult instanceof InteractionResult.Success success
                                    && success.swingSource() == InteractionResult.SwingSource.SERVER && !this.player.gameMode.interactResult) { // Paper - Call interact event
                                    this.player.swing(hand, true);
                                }

                                if (!interactionResult.consumesAction() && wasBlockPlacementAttempt(this.player, itemStack)) {
                                    if (direction == Direction.UP && pos.getY() >= maxY) {
                                        this.player.sendBuildLimitMessage(true, maxY);
                                    } else if (direction == Direction.DOWN && pos.getY() <= minY) {
                                        this.player.sendBuildLimitMessage(false, minY);
                                    }
                                } else if (interactionResult instanceof InteractionResult.Success success
                                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                                    this.player.swing(hand, true);
                                }
                            // Paper start - Fix inventory desync; MC-99075
                            // From level.mayInteract(this.player, pos)
                            } else if (this.server.isUnderSpawnProtection(level, pos, player)) {
                                if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(this.player, level.getBlockState(pos), itemStack)) {
                                    this.player.containerMenu.sendAllDataToRemote();
                                } else {
                                    this.player.containerMenu.forceHeldSlot(hand);
                                }
                            // Paper end - Fix inventory desync
                            } else {
                                // this.player.sendBuildLimitMessage(true, maxY); // Paper - Fix MC-306582: Fix build limit message being sent when it shouldn't be
                            }

                            this.send(new ClientboundBlockUpdatePacket(level, pos));
                            this.send(new ClientboundBlockUpdatePacket(level, pos.relative(direction)));
                        }
                        if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                        // Paper - Remove unused warning
                    }
                }
            }
        }
    }

    @Override
    public void handleUseItem(final ServerboundUseItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        if (!this.checkLimit(packet.timestamp)) return; // Spigot - check limit
        if (this.hasClientLoaded()) {
            this.ackBlockChangesUpTo(packet.getSequence());
            ServerLevel level = this.player.level();
            InteractionHand hand = packet.getHand();
            ItemStack itemStack = this.player.getItemInHand(hand);
            this.player.resetLastActionTime();
            if (!itemStack.isEmpty() && itemStack.isItemEnabled(level.enabledFeatures())) {
                float targetYRot = Mth.wrapDegrees(packet.getYRot());
                float targetXRot = Mth.wrapDegrees(packet.getXRot());
                if (targetXRot != this.player.getXRot() || targetYRot != this.player.getYRot()) {
                    this.player.absSnapRotationTo(targetYRot, targetXRot);
                }

                // CraftBukkit start
                // Raytrace to look for 'rogue armswings'
                Vec3 from = this.player.getEyePosition();
                Vec3 delta = this.player.getViewVector(1.0F).scale(this.player.blockInteractionRange());
                Vec3 to = from.add(delta);
                BlockHitResult hitResult = this.player.level().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, this.player));

                boolean cancelled;
                if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
                    org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(this.player, Action.RIGHT_CLICK_AIR, itemStack, hand);
                    cancelled = event.useItemInHand() == Event.Result.DENY;
                } else {
                    if (this.player.gameMode.firedInteract && this.player.gameMode.interactPosition.equals(hitResult.getBlockPos()) && this.player.gameMode.interactHand == hand && ItemStack.isSameItemSameComponents(this.player.gameMode.interactItemStack, itemStack)) {
                        cancelled = this.player.gameMode.interactResult;
                    } else {
                        org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(this.player, Action.RIGHT_CLICK_BLOCK, hitResult.getBlockPos(), hitResult.getDirection(), itemStack, true, hand, hitResult.getLocation());
                        cancelled = event.useItemInHand() == Event.Result.DENY;
                    }
                    this.player.gameMode.firedInteract = false;
                }

                if (cancelled) {
                    this.player.resyncUsingItem(this.player); // Paper - Properly cancel usable items
                    // Paper start - Fix inventory desync; SPIGOT-2524
                    if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(this.player, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), itemStack)) {
                        this.player.containerMenu.sendAllDataToRemote();
                    } else {
                        this.player.containerMenu.forceHeldSlotAndArmor(hand);
                    }
                    // Paper end - Fix inventory desync
                    return;
                }
                itemStack = this.player.getItemInHand(hand); // Update in case it was changed in the event
                if (itemStack.isEmpty()) {
                    return;
                }
                // CraftBukkit end

                if (this.player.gameMode.useItem(this.player, level, itemStack, hand) instanceof InteractionResult.Success success
                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                    this.player.swing(hand, true);
                }
            }
        }
    }

    @Override
    public void handleTeleportToEntityPacket(final ServerboundTeleportToEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isSpectator()) {
            for (ServerLevel level : this.server.getAllLevels()) {
                Entity entity = packet.getEntity(level);
                if (entity != null) {
                    io.papermc.paper.threadedregions.TeleportUtils.teleport(this.player, false, entity, null, null, Entity.TELEPORT_FLAG_LOAD_CHUNK, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.SPECTATE, null); // Folia - region threading
                    return;
                }
            }
        }
    }

    @Override
    public void handlePaddleBoat(final ServerboundPaddleBoatPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.getControlledVehicle() instanceof AbstractBoat boat) {
            boat.setPaddleState(packet.getLeft(), packet.getRight());
        }
    }

    @Override
    public void onDisconnect(final DisconnectionDetails details) {
        LOGGER.info("{} lost connection: {}", this.player.getPlainTextName(), details.reason().getString());
        // Paper start - Fix kick event leave message not being sent
        final net.kyori.adventure.text.Component quitMessage = details.quitMessage().map(io.papermc.paper.adventure.PaperAdventure::asAdventure).orElse(null);
        if (!this.waitingForSwitchToConfig) this.removePlayerFromWorld(quitMessage); // Folia - region threading - don't double remove
        // Paper end - Fix kick event leave message not being sent
        super.onDisconnect(details);
    }

    private void removePlayerFromWorld() {
        // Paper start - Fix kick event leave message not being sent
        this.removePlayerFromWorld(null);
    }

    public boolean hackSwitchingConfig; // Folia - rewrite login process

    private void removePlayerFromWorld(net.kyori.adventure.text.@Nullable Component quitMessage) {
        // Paper end - Fix kick event leave message not being sent
        this.chatMessageChain.close();
        // CraftBukkit start - Replace vanilla quit message handling with our own.
        /*
        this.server.invalidateStatus();
        this.server
            .getPlayerList()
            .broadcastSystemMessage(Component.translatable("multiplayer.player.left", this.player.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
         */
        this.player.disconnect();
        // Paper start - Adventure
        quitMessage = quitMessage == null ? this.server.getPlayerList().remove(this.player) : this.server.getPlayerList().remove(this.player, quitMessage); // Paper - pass in quitMessage to fix kick message not being used
        if (!this.hackSwitchingConfig) this.disconnectPos = this.player.chunkPosition(); // Folia - region threading - note: only set after removing, since it can tick the player
        if (!this.hackSwitchingConfig) this.player.level().moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(DISCONNECT_TICKET, this.disconnectPos, ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL, this.disconnectTicketId); // Folia - region threading - force chunk to be loaded so that the region is not lost
        if ((quitMessage != null) && !quitMessage.equals(net.kyori.adventure.text.Component.empty())) {
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(quitMessage), false);
            // Paper end - Adventure
        }
        // CraftBukkit end
        this.player.getTextFilter().leave();
    }

    public void ackBlockChangesUpTo(final int packetSequenceNr) {
        if (packetSequenceNr < 0) {
            this.disconnect(Component.literal("Expected packet sequence nr >= 0"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - Treat sequence violations like they should be
            throw new IllegalArgumentException("Expected packet sequence nr >= 0");
        }

        this.ackBlockChangesUpTo = Math.max(packetSequenceNr, this.ackBlockChangesUpTo);
    }

    @Override
    public void handleSetCarriedItem(final ServerboundSetCarriedItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        if (packet.getSlot() >= 0 && packet.getSlot() < Inventory.getSelectionSize()) {
            if (packet.getSlot() == this.player.getInventory().getSelectedSlot()) { return; } // Paper - don't fire itemheldevent when there wasn't a slot change
            PlayerItemHeldEvent event = new PlayerItemHeldEvent(this.getCraftPlayer(), this.player.getInventory().getSelectedSlot(), packet.getSlot());
            this.cserver.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                this.send(new ClientboundSetHeldSlotPacket(this.player.getInventory().getSelectedSlot()));
                this.player.resetLastActionTime();
                return;
            }
            // CraftBukkit end
            if (this.player.getInventory().getSelectedSlot() != packet.getSlot() && this.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                this.player.stopUsingItem();
            }

            this.player.getInventory().setSelectedSlot(packet.getSlot());
            this.player.resetLastActionTime();
            if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
        } else {
            LOGGER.warn("{} tried to set an invalid carried item", this.player.getPlainTextName());
            this.disconnect(Component.literal("Invalid hotbar selection (Hacking?)"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // CraftBukkit // Paper - kick event cause
        }
    }

    @Override
    public void handleChat(final ServerboundChatPacket packet) {
        // CraftBukkit start - async chat
        // SPIGOT-3638
        if (this.server.isStopped()) {
            return;
        }
        // CraftBukkit end
        Optional<LastSeenMessages> unpackedLastSeen = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
        if (!unpackedLastSeen.isEmpty()) {
            this.tryHandleChat(packet.message(), false, () -> {
                PlayerChatMessage signedMessage;
                try {
                    signedMessage = this.getSignedMessage(packet, unpackedLastSeen.get());
                } catch (SignedMessageChain.DecodeException e) {
                    this.handleMessageDecodeFailure(e);
                    return;
                }

                CompletableFuture<FilteredText> filteredFuture = this.filterTextPacket(signedMessage.signedContent()).thenApplyAsync(java.util.function.Function.identity(), this.server.chatExecutor); // CraftBukkit - async chat
                CompletableFuture<Component> decoratedFuture = this.server.getChatDecorator().decorate(this.player, null, signedMessage.decoratedContent()); // Paper - Adventure

                this.chatMessageChain.append(CompletableFuture.allOf(filteredFuture, decoratedFuture), ($) -> { // Paper - Adventure
                    PlayerChatMessage filteredMessage = signedMessage.withUnsignedContent(decoratedFuture.join()).filter(filteredFuture.join().mask()); // Paper - Adventure
                    this.broadcastChatMessage(filteredMessage);
                });
            }, false); // CraftBukkit - async chat
        }
    }

    @Override
    public void handleChatCommand(final ServerboundChatCommandPacket packet) {
        this.tryHandleChat(packet.command(), true, () -> {
            // CraftBukkit start - SPIGOT-7346: Prevent disconnected players from executing commands
            if (this.player.hasDisconnected()) {
                return;
            }
            // CraftBukkit end
            this.performUnsignedChatCommand(packet.command());
            this.detectCommandRateSpam(packet.command()); // Spigot - spam exclusions
        }, true); // CraftBukkit - sync commands
    }

    // CraftBukkit start
    private void performUnsignedChatCommand(String command) {
        String prefixedCommand = "/" + command;
        if (org.spigotmc.SpigotConfig.logCommands) { // Paper - Add missing SpigotConfig logCommands check
            LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), prefixedCommand);
        }

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(this.getCraftPlayer(), prefixedCommand, new org.bukkit.craftbukkit.util.LazyPlayerSet(this.server));
        this.cserver.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }
        command = event.getMessage().substring(1);
        // CraftBukkit end
        ParseResults<CommandSourceStack> parsed = this.parseCommand(command);
        if (this.server.enforceSecureProfile() && SignableCommand.hasSignableArguments(parsed)) {
            LOGGER.error(
                "Received unsigned command packet from {}, but the command requires signable arguments: {}", this.player.getGameProfile().name(), command
            );
            this.player.sendSystemMessage(INVALID_COMMAND_SIGNATURE);
        } else {
            this.server.getCommands().performCommand(parsed, command);
        }
    }

    @Override
    public void handleSignedChatCommand(final ServerboundChatCommandSignedPacket packet) {
        Optional<LastSeenMessages> unpackedLastSeen = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
        if (!unpackedLastSeen.isEmpty()) {
            this.tryHandleChat(packet.command(), true, () -> {
                // CraftBukkit start - SPIGOT-7346: Prevent disconnected players from executing commands
                if (this.player.hasDisconnected()) {
                    return;
                }
                // CraftBukkit end
                this.performSignedChatCommand(packet, unpackedLastSeen.get());
                this.detectCommandRateSpam(packet.command()); // Spigot - spam exclusions
            }, true); // CraftBukkit - sync commands
        }
    }

    private void performSignedChatCommand(final ServerboundChatCommandSignedPacket packet, final LastSeenMessages lastSeenMessages) {
        // CraftBukkit start
        String commandString = "/" + packet.command();
        if (org.spigotmc.SpigotConfig.logCommands) { // Paper - Add missing SpigotConfig logCommands check
            LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), commandString);
        } // Paper - Add missing SpigotConfig logCommands check

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(this.getCraftPlayer(), commandString, new org.bukkit.craftbukkit.util.LazyPlayerSet(this.server));
        this.cserver.getPluginManager().callEvent(event);
        commandString = event.getMessage().substring(1);

        ParseResults<CommandSourceStack> command = this.parseCommand(packet.command());

        Map<String, PlayerChatMessage> signedArguments;
        try {
            // Paper - Always parse the original command to add to the chat chain
            signedArguments = this.collectSignedArguments(packet, SignableCommand.of(command), lastSeenMessages);
        } catch (SignedMessageChain.DecodeException e) {
            this.handleMessageDecodeFailure(e);
            return;
        }

        // Paper start - Fix cancellation and message changing
        if (event.isCancelled()) {
            // Only now are we actually good to return
            return;
        }

        // Remove signed parts if the command was changed
        if (!commandString.equals(packet.command())) {
            command = this.parseCommand(commandString);
            signedArguments = Collections.emptyMap();
        }
        // Paper end - Fix cancellation and message changing

        CommandSigningContext signingContext = new CommandSigningContext.SignedArguments(signedArguments);
        command = Commands.mapSource(command, source -> source.withSigningContext(signingContext, this.chatMessageChain));
        this.server.getCommands().performCommand(command, commandString); // CraftBukkit
    }

    private void handleMessageDecodeFailure(final SignedMessageChain.DecodeException e) {
        LOGGER.warn("Failed to update secure chat state for {}: '{}'", this.player.getGameProfile().name(), e.getComponent().getString());
        this.player.sendSystemMessage(e.getComponent().copy().withStyle(ChatFormatting.RED));
    }

    private <S> Map<String, PlayerChatMessage> collectSignedArguments(
        final ServerboundChatCommandSignedPacket packet, final SignableCommand<S> command, final LastSeenMessages lastSeenMessages
    ) throws SignedMessageChain.DecodeException {
        List<ArgumentSignatures.Entry> argumentSignatures = packet.argumentSignatures().entries();
        List<SignableCommand.Argument<S>> parsedArguments = command.arguments();
        if (argumentSignatures.isEmpty()) {
            return this.collectUnsignedArguments(parsedArguments);
        }

        Map<String, PlayerChatMessage> signedArguments = new Object2ObjectOpenHashMap<>();

        for (ArgumentSignatures.Entry clientArgument : argumentSignatures) {
            SignableCommand.Argument<S> expectedArgument = command.getArgument(clientArgument.name());
            if (expectedArgument == null) {
                this.signedMessageDecoder.setChainBroken();
                throw createSignedArgumentMismatchException(packet.command(), argumentSignatures, parsedArguments);
            }

            SignedMessageBody body = new SignedMessageBody(expectedArgument.value(), packet.timeStamp(), packet.salt(), lastSeenMessages);
            signedArguments.put(expectedArgument.name(), this.signedMessageDecoder.unpack(clientArgument.signature(), body));
        }

        for (SignableCommand.Argument<S> expectedArgument : parsedArguments) {
            if (!signedArguments.containsKey(expectedArgument.name())) {
                throw createSignedArgumentMismatchException(packet.command(), argumentSignatures, parsedArguments);
            }
        }

        return signedArguments;
    }

    private <S> Map<String, PlayerChatMessage> collectUnsignedArguments(final List<SignableCommand.Argument<S>> parsedArguments) throws SignedMessageChain.DecodeException {
        Map<String, PlayerChatMessage> arguments = new HashMap<>();

        for (SignableCommand.Argument<S> parsedArgument : parsedArguments) {
            SignedMessageBody body = SignedMessageBody.unsigned(parsedArgument.value());
            arguments.put(parsedArgument.name(), this.signedMessageDecoder.unpack(null, body));
        }

        return arguments;
    }

    private static <S> SignedMessageChain.DecodeException createSignedArgumentMismatchException(
        final String command, final List<ArgumentSignatures.Entry> clientArguments, final List<SignableCommand.Argument<S>> expectedArguments
    ) {
        String clientNames = clientArguments.stream().map(ArgumentSignatures.Entry::name).collect(Collectors.joining(", "));
        String expectedNames = expectedArguments.stream().map(SignableCommand.Argument::name).collect(Collectors.joining(", "));
        LOGGER.error("Signed command mismatch between server and client ('{}'): got [{}] from client, but expected [{}]", command, clientNames, expectedNames);
        return new SignedMessageChain.DecodeException(INVALID_COMMAND_SIGNATURE);
    }

    private ParseResults<CommandSourceStack> parseCommand(final String command) {
        CommandDispatcher<CommandSourceStack> commands = this.server.getCommands().getDispatcher();
        return commands.parse(command, this.player.createCommandSourceStack());
    }

    private void tryHandleChat(final String message, final boolean isCommand, final Runnable chatHandler, final boolean sync) { // CraftBukkit
        if (isChatMessageIllegal(message)) {
            this.disconnectAsync(Component.translatable("multiplayer.disconnect.illegal_characters"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_CHARACTERS); // Paper - add proper async disconnect
        } else if (this.player.isRemoved() || (!isCommand && this.player.getChatVisibility() == ChatVisiblity.HIDDEN)) { // CraftBukkit - dead men tell no tales
            this.send(new ClientboundSystemChatPacket(Component.translatable("chat.disabled.options").withStyle(ChatFormatting.RED), false));
        } else {
            this.player.resetLastActionTime();
            // CraftBukkit start
            if (sync) {
                this.player.getBukkitEntity().taskScheduler.schedule((ServerPlayer player) -> chatHandler.run(), null, 1L);
            } else {
                chatHandler.run();
            }
            // CraftBukkit end
        }
    }

    private Optional<LastSeenMessages> unpackAndApplyLastSeen(final LastSeenMessages.Update update) {
        synchronized (this.lastSeenMessages) {
            Optional var10000;
            try {
                LastSeenMessages result = this.lastSeenMessages.applyUpdate(update);
                var10000 = Optional.of(result);
            } catch (LastSeenMessagesValidator.ValidationException e) {
                LOGGER.error("Failed to validate message acknowledgements from {}: {}", this.player.getPlainTextName(), e.getMessage());
                this.disconnectAsync(CHAT_VALIDATION_FAILED, org.bukkit.event.player.PlayerKickEvent.Cause.CHAT_VALIDATION_FAILED); // Paper - kick event causes & add proper async disconnect
                return Optional.empty();
            }

            return var10000;
        }
    }

    public static boolean isChatMessageIllegal(final String message) {
        for (int i = 0; i < message.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(message.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    // CraftBukkit start
    public void chat(String msg, PlayerChatMessage original, boolean async) {
        if (msg.isEmpty() || this.player.getChatVisibility() == ChatVisiblity.HIDDEN) {
            return;
        }

        if (this.player.getChatVisibility() == ChatVisiblity.SYSTEM) {
            // Do nothing, this is coming from a plugin
            // Paper start
        } else {
            if (!async && !org.bukkit.Bukkit.isPrimaryThread()) {
                org.spigotmc.AsyncCatcher.catchOp("Asynchronous player chat is not allowed here");
            }
            new io.papermc.paper.adventure.ChatProcessor(this.server, this.player, original, async).process();
            // Paper end
        }
    }

    @Deprecated // Paper
    public void handleCommand(String command) {
        // Paper start - Remove all this old duplicated logic
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        /*
        It should be noted that this represents the "legacy" command execution path.
        Api can call commands even if there is no additional context provided.
        This method should ONLY be used if you need to execute a command WITHOUT
        an actual player's input.
        */
        this.performUnsignedChatCommand(command);
        // Paper end
    }
    // CraftBukkit end

    private PlayerChatMessage getSignedMessage(final ServerboundChatPacket packet, final LastSeenMessages lastSeenMessages) throws SignedMessageChain.DecodeException {
        SignedMessageBody body = new SignedMessageBody(packet.message(), packet.timeStamp(), packet.salt(), lastSeenMessages);
        return this.signedMessageDecoder.unpack(packet.signature(), body);
    }

    private void broadcastChatMessage(final PlayerChatMessage message) {
        // CraftBukkit start
        String rawMessage = message.signedContent();
        if (rawMessage.isEmpty()) {
            LOGGER.warn("{} tried to send an empty message", this.player.getScoreboardName());
        } else if (this.getCraftPlayer().isConversing()) {
            if (true) throw new UnsupportedOperationException(); // Folia - region threading
            final String conversationInput = rawMessage;
            this.server.processQueue.add(() -> ServerGamePacketListenerImpl.this.getCraftPlayer().acceptConversationInput(conversationInput));
        } else if (this.player.getChatVisibility() == ChatVisiblity.SYSTEM) { // Re-add "Command Only" flag check
            this.send(new ClientboundSystemChatPacket(Component.translatable("chat.cannotSend").withStyle(ChatFormatting.RED), false));
        } else {
            this.chat(rawMessage, message, true);
        }
        // this.server.getPlayerList().broadcastChatMessage(message, this.player, ChatType.bind(ChatType.CHAT, this.player));
        // CraftBukkit end
        this.detectChatRateSpam(rawMessage); // Spigot - spam exclusions
    }

    // Spigot start - spam exclusions
    private void detectRateSpam(final TickThrottler throttler, final String message) {
        if (org.spigotmc.SpigotConfig.enableSpamExclusions) {
            for (String exclude : org.spigotmc.SpigotConfig.spamExclusions) {
                if (exclude != null && message.startsWith(exclude)) {
                    return;
                }
            }
        }
        // Spigot end - spam exclusions

        // CraftBukkit start - replaced with thread safe throttle
        // throttler.increment();
        if (!throttler.isIncrementAndUnderThreshold()
        // CraftBukkit end - replaced with thread safe throttle
            && !this.server.getPlayerList().isOp(this.player.nameAndId())
            && !this.server.isSingleplayerOwner(this.player.nameAndId())) {
            this.disconnectAsync(Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM); // Paper - kick event cause & add proper async disconnect
        }
    }

    private void detectCommandRateSpam(final String command) { // Spigot - spam exclusions
        this.detectRateSpam(this.commandSpamThrottler, "/" + command); // Spigot - spam exclusions
    }

    private void detectChatRateSpam(final String message) { // Spigot - spam exclusions
        this.detectRateSpam(this.chatSpamThrottler, message); // Spigot - spam exclusions
    }

    @Override
    public void handleChatAck(final ServerboundChatAckPacket packet) {
        synchronized (this.lastSeenMessages) {
            try {
                this.lastSeenMessages.applyOffset(packet.offset());
            } catch (LastSeenMessagesValidator.ValidationException e) {
                LOGGER.error("Failed to validate message acknowledgement offset from {}: {}", this.player.getPlainTextName(), e.getMessage());
                this.disconnectAsync(ServerGamePacketListenerImpl.CHAT_VALIDATION_FAILED, org.bukkit.event.player.PlayerKickEvent.Cause.CHAT_VALIDATION_FAILED); // Paper - kick event causes & add proper async disconnect
            }
        }
    }

    @Override
    public void handleAnimate(final ServerboundSwingPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        this.player.resetLastActionTime();
        // CraftBukkit start - Raytrace to look for 'rogue armswings'
        Location origin = CraftLocation.toBukkit(this.player.getEyePosition(), this.player.level(), this.player.getYRot(), this.player.getXRot());
        double maxRange = Math.max(this.player.blockInteractionRange(), this.player.entityInteractionRange()); // todo flawed
        // SPIGOT-5607: Only call interact event if no block or entity is being clicked. Use bukkit ray trace method, because it handles blocks and entities at the same time
        // SPIGOT-7429: Make sure to call PlayerInteractEvent for spectators and non-pickable entities
        org.bukkit.util.RayTraceResult result = this.player.level().getWorld().rayTrace(origin, origin.getDirection(), maxRange, org.bukkit.FluidCollisionMode.NEVER, false, 0.0, entity -> { // Paper - Call interact event; change raySize from 0.1 to 0.0
            Entity handle = ((CraftEntity) entity).getHandle();
            return entity != this.player.getBukkitEntity() && this.player.getBukkitEntity().canSee(entity) && !handle.isSpectator() && handle.isPickable() && !handle.isPassengerOfSameVehicle(this.player);
        });
        if (result == null) {
            CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
        } else { // Paper start - Call interact event
            GameType gameType = this.player.gameMode.getGameModeForPlayer();
            if (gameType == GameType.ADVENTURE && result.getHitBlock() != null) {
                CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_BLOCK, ((org.bukkit.craftbukkit.block.CraftBlock) result.getHitBlock()).getPosition(), org.bukkit.craftbukkit.block.CraftBlock.blockFaceToNotch(result.getHitBlockFace()), this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
            } else if (gameType != GameType.CREATIVE && result.getHitEntity() != null && origin.toVector().distanceSquared(result.getHitPosition()) > this.player.entityInteractionRange() * this.player.entityInteractionRange()) {
                CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
            }
        } // Paper end - Call interact event

        // Arm swing animation
        io.papermc.paper.event.player.PlayerArmSwingEvent event = new io.papermc.paper.event.player.PlayerArmSwingEvent(this.getCraftPlayer(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(packet.getHand())); // Paper - Add PlayerArmSwingEvent
        this.cserver.getPluginManager().callEvent(event);

        if (event.isCancelled()) return;
        // CraftBukkit end
        this.player.swing(packet.getHand());
    }

    @Override
    public void handlePlayerCommand(final ServerboundPlayerCommandPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            // CraftBukkit start
            if (this.player.isRemoved()) return;
            switch (packet.getAction()) {
                case START_SPRINTING:
                case STOP_SPRINTING: {
                    PlayerToggleSprintEvent event = new PlayerToggleSprintEvent(this.getCraftPlayer(), packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING);
                    this.cserver.getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return;
                    }
                    break;
                }
            }
            // CraftBukkit end
            this.player.resetLastActionTime();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.player.setSprinting(true);
                    break;
                case STOP_SPRINTING:
                    this.player.setSprinting(false);
                    break;
                case STOP_SLEEPING:
                    if (this.player.isSleeping()) {
                        this.player.stopSleepInBed(false, true);
                        this.awaitingPositionFromClient = this.player.position();
                    }
                    break;
                case START_RIDING_JUMP:
                    if (this.player.getControlledVehicle() instanceof PlayerRideableJumping vehicle) {
                        int data = packet.getData();
                        if (vehicle.canJump() && data > 0) {
                            vehicle.handleStartJump(data);
                        }
                    }
                    break;
                case STOP_RIDING_JUMP:
                    if (this.player.getControlledVehicle() instanceof PlayerRideableJumping vehicle) {
                        vehicle.handleStopJump();
                    }
                    break;
                case OPEN_INVENTORY:
                    if (this.player.getVehicle() instanceof HasCustomInventoryScreen vehicleWithInventory) {
                        vehicleWithInventory.openCustomInventoryScreen(this.player);
                    }
                    break;
                case START_FALL_FLYING:
                    if (!this.player.tryToStartFallFlying()) {
                        this.player.stopFallFlying();
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Invalid client command!");
            }
        }
    }

    public void sendPlayerChatMessage(final PlayerChatMessage message, final ChatType.Bound chatType) {
        // CraftBukkit start - SPIGOT-7262: if hidden we have to send as disguised message. Query whether we should send at all (but changing this may not be expected).
        if (!this.getCraftPlayer().canSeePlayer(message.link().sender())) {
            this.sendDisguisedChatMessage(message.decoratedContent(), chatType);
            return;
        }
        // CraftBukkit end
        // Paper start - Ensure that client receives chat packets in the same order that we add into the message signature cache
        synchronized (this.messageSignatureCache) {
        this.send(
            new ClientboundPlayerChatPacket(
                this.nextChatIndex++,
                message.link().sender(),
                message.link().index(),
                message.signature(),
                message.signedBody().pack(this.messageSignatureCache),
                message.unsignedContent(),
                message.filterMask(),
                chatType
            )
        );
        MessageSignature signature = message.signature();
        if (signature != null) {
            this.messageSignatureCache.push(message.signedBody(), message.signature());
            int trackedCount;
            synchronized (this.lastSeenMessages) {
                this.lastSeenMessages.addPending(signature);
                trackedCount = this.lastSeenMessages.trackedMessagesCount();
            }

            if (trackedCount > 4096) {
                this.disconnectAsync(Component.translatable("multiplayer.disconnect.too_many_pending_chats"), org.bukkit.event.player.PlayerKickEvent.Cause.TOO_MANY_PENDING_CHATS); // Paper - kick event cause & add proper async disconnect
            }
        }
        }
        // Paper end - Ensure that client receives chat packets in the same order that we add into the message signature cache
    }

    public void sendDisguisedChatMessage(final Component content, final ChatType.Bound chatType) {
        this.send(new ClientboundDisguisedChatPacket(content, chatType));
    }

    public SocketAddress getRemoteAddress() {
        return this.connection.getRemoteAddress();
    }

    // Spigot start
    public SocketAddress getRawAddress() {
        // Paper start - Unix domain socket support; this can be nullable in the case of a Unix domain socket, so if it is, fake something
        if (this.connection.channel.remoteAddress() == null) {
            return new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0);
        }
        // Paper end - Unix domain socket support
        return this.connection.channel.remoteAddress();
    }
    // Spigot end

    public void switchToConfig() {
        // Folia start - rewrite login process
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.player, "Cannot switch config off-main");
        if (io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread()) {
            throw new IllegalStateException("Cannot switch config while on global tick thread");
        }
        // Folia end - rewrite login process
        // Folia start - rewrite login process - fix bad ordering of this field write - move after removed from world
        // the field write ordering is bad as it allows the client to send the response packet before the player is
        // removed from the world
        // Folia end - rewrite login process - fix bad ordering of this field write - move after removed from world
        try { // Folia - rewrite login process - move connection ownership to global region
            this.hackSwitchingConfig = true; // Folia - rewrite login process - avoid adding logout ticket here
        this.removePlayerFromWorld();
        } finally { // Folia start - rewrite login process - move connection ownership to global region
            io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.player.level().getCurrentWorldData();
            worldData.connections.remove(this.connection);
            // once waitingForSwitchToConfig is set, the global tick thread will own the connection
        } // Folia end - rewrite login process - move connection ownership to global region
        this.waitingForSwitchToConfig = true; // Folia - rewrite login process - fix bad ordering of this field write - moved down
        this.send(ClientboundStartConfigurationPacket.INSTANCE);
        this.connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
    }

    @Override
    public void handlePingRequest(final ServerboundPingRequestPacket packet) {
        this.connection.send(new ClientboundPongResponsePacket(packet.getTime()));
    }

    @Override
    public void handleAttack(final ServerboundAttackPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // Paper
        if (this.hasClientLoaded() && !this.player.isSpectator()) {
            ServerLevel level = this.player.level();
            Entity target = level.getEntityOrPart(packet.entityId());
            // Spigot start
            if (target == this.player) {
                this.disconnect(Component.literal("Cannot interact with self!"), org.bukkit.event.player.PlayerKickEvent.Cause.SELF_INTERACTION); // Paper - kick event cause
                return;
            }
            // Spigot end
            this.player.resetLastActionTime();
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(target) && target != null && level.getWorldBorder().isWithinBounds(target.blockPosition())) { // Folia - region threading - do not allow interaction of entities outside the current region
                AABB targetBounds = target.getBoundingBox();
                ItemStack mainHandItem = this.player.getMainHandItem();
                if (this.player.isWithinAttackRange(mainHandItem, targetBounds, io.papermc.paper.configuration.GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))) { // Paper - configurable lenience
                    if (!mainHandItem.has(DataComponents.PIERCING_WEAPON)) {
                        if (target instanceof ItemEntity
                            || target instanceof ExperienceOrb
                            || target == this.player
                            || target instanceof AbstractArrow abstractArrow && !abstractArrow.isAttackable()) {
                            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_entity_attacked"), org.bukkit.event.player.PlayerKickEvent.Cause.INVALID_ENTITY_ATTACKED); // Paper - add cause
                            LOGGER.warn("Player {} tried to attack an invalid entity", this.player.getPlainTextName());
                        } else if (mainHandItem.isItemEnabled(level.enabledFeatures())) {
                            if (!this.player.cannotAttackWithItem(mainHandItem, 5)) {
                                this.player.attack(target);
                            }
                        }
                    }
                }
            }
            // Paper start - PlayerUseUnknownEntityEvent
            else if (target == null) {
                CraftEventFactory.callPlayerUseUnknownEntityEvent(ServerGamePacketListenerImpl.this.player, packet.entityId(), true, net.minecraft.world.InteractionHand.MAIN_HAND, null);
            }
            // Paper end - PlayerUseUnknownEntityEvent
            if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
        }
    }

    @Override
    public void handleInteract(final ServerboundInteractPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        if (this.hasClientLoaded()) {
            ServerLevel level = this.player.level();
            Entity target = level.getEntityOrPart(packet.entityId());
            // Spigot start
            if (target == this.player && !this.player.isSpectator()) {
                this.disconnect(Component.literal("Cannot interact with self!"), org.bukkit.event.player.PlayerKickEvent.Cause.SELF_INTERACTION); // Paper - kick event cause
                return;
            }
            // Spigot end
            this.player.resetLastActionTime();
            this.player.setShiftKeyDown(packet.usingSecondaryAction());
            if (target != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(target) && level.getWorldBorder().isWithinBounds(target.blockPosition())) { // Folia - region threading
                AABB targetBounds = target.getBoundingBox();
                if (this.player.isWithinEntityInteractionRange(targetBounds, io.papermc.paper.configuration.GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))) { // Paper - configurable lenience value for interact range
                    InteractionHand hand = packet.hand();
                    Vec3 location = packet.location();
                    ItemStack tool = this.player.getItemInHand(hand);
                    if (tool.isItemEnabled(level.enabledFeatures())) {
                        ItemStack usedItemStack = tool.copy();
                        // CraftBukkit start
                        final Item itemType = tool.getItem();
                        boolean triggerLeashUpdate = usedItemStack.is(Items.LEAD) && target instanceof net.minecraft.world.entity.Leashable;

                        final PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(
                            ServerGamePacketListenerImpl.this.getCraftPlayer(), target.getBukkitEntity(),
                            org.bukkit.craftbukkit.util.CraftVector.toBukkit(location), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand)
                        );
                        ServerGamePacketListenerImpl.this.cserver.getPluginManager().callEvent(event);
                        final boolean resendData = event.isCancelled() || !ServerGamePacketListenerImpl.this.player.getItemInHand(hand).is(itemType);

                        // Entity in bucket - SPIGOT-4048 and SPIGOT-6859
                        if (itemType == Items.WATER_BUCKET && target instanceof net.minecraft.world.entity.Bucketable && target instanceof LivingEntity && resendData) {
                            target.resendPossiblyDesyncedEntityData(ServerGamePacketListenerImpl.this.player); // Paper - The entire mob gets deleted, so resend it
                        }

                        // Paper start - Fix inventory desync
                        if (resendData) {
                            if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(ServerGamePacketListenerImpl.this.player, target, tool)) {
                                ServerGamePacketListenerImpl.this.player.containerMenu.sendAllDataToRemote();
                            } else {
                                ServerGamePacketListenerImpl.this.player.containerMenu.forceHeldSlot(hand);
                            }
                        }
                        // Paper end - Fix inventory desync

                        if (triggerLeashUpdate && resendData) {
                            // Refresh the current leash state
                            ServerGamePacketListenerImpl.this.send(new net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket(target, ((net.minecraft.world.entity.Leashable) target).getLeashHolder()));
                        }

                        if (resendData) {
                            // Refresh the current entity metadata
                            target.refreshEntityData(ServerGamePacketListenerImpl.this.player);
                            // SPIGOT-7136 - Allays
                            if (target instanceof net.minecraft.world.entity.animal.allay.Allay || target instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) { // Paper - Fix horse armor desync
                                ServerGamePacketListenerImpl.this.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                                    target.getId(), java.util.Arrays.stream(net.minecraft.world.entity.EquipmentSlot.values())
                                    .map((slot) -> com.mojang.datafixers.util.Pair.of(slot, ((LivingEntity) target).getItemBySlot(slot).copy()))
                                    .collect(Collectors.toList()), true)); // Paper - sanitize
                                player.containerMenu.sendAllDataToRemote();
                            } else {
                                ServerGamePacketListenerImpl.this.player.containerMenu.forceHeldSlot(hand); // Paper - fix slot desync (is this needed?)
                            }
                        }

                        if (event.isCancelled()) {
                            return;
                        }
                        // CraftBukkit end
                        if (this.player.interactOn(target, hand, location) instanceof InteractionResult.Success success) {
                            ItemStack awardedForStack = success.wasItemInteraction() ? usedItemStack : ItemStack.EMPTY;
                            CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(this.player, awardedForStack, target);
                            if (success.swingSource() == InteractionResult.SwingSource.SERVER) {
                                this.player.swing(hand, true);
                            }
                        }
                    }
                }
            }
            // Paper start - PlayerUseUnknownEntityEvent
            else if (target == null) {
                CraftEventFactory.callPlayerUseUnknownEntityEvent(ServerGamePacketListenerImpl.this.player, packet.entityId(), false, packet.hand(), packet.location());
            }
            // Paper end - PlayerUseUnknownEntityEvent
            if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
        }
    }

    @Override
    public void handleSpectatorAction(final ServerboundSpectatorActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded() && this.player.isSpectator()) {
            this.player.resetLastActionTime();
            if (packet.spectateEntityId().isPresent()) {
                ServerLevel level = this.player.level();
                Entity target = level.getEntityOrPart(packet.spectateEntityId().getAsInt());
                if (target != null && level.getWorldBorder().isWithinBounds(target.blockPosition())) {
                    if (this.player.isWithinEntityInteractionRange(target.getBoundingBox(), io.papermc.paper.configuration.GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))) { // Paper - configurable lenience
                        if (target.isPickable()) {
                            this.player.setCamera(target);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleClientCommand(final ServerboundClientCommandPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        ServerboundClientCommandPacket.Action action = packet.getAction();
        switch (action) {
            case PERFORM_RESPAWN:
                if (this.player.wonGame) {
                    // Folia start - region threading
                    if (true) {
                        this.player.exitEndCredits();
                        return;
                    }
                    // Folia end - region threading
                    this.player.wonGame = false;
                    this.player = this.server.getPlayerList().respawn(this.player, true, Entity.RemovalReason.CHANGED_DIMENSION, RespawnReason.END_PORTAL); // CraftBukkit
                    this.resetPosition();
                    this.restartClientLoadTimerAfterRespawn();
                    CriteriaTriggers.CHANGED_DIMENSION.trigger(this.player, Level.END, Level.OVERWORLD);
                } else {
                    if (this.player.getHealth() > 0.0F) {
                        return;
                    }

                    // Folia start - region threading
                    if (true) {
                        this.player.respawn((ServerPlayer player) -> {
                            if (this.server.isHardcore()) {
                                this.player.setGameMode(GameType.SPECTATOR, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH, null); // Paper - Expand PlayerGameModeChangeEvent
                                this.player.level().getGameRules().set(GameRules.SPECTATORS_GENERATE_CHUNKS, false, this.player.level()); // CraftBukkit - per-world
                            }
                        }, org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
                        return;
                    }
                    // Folia end - region threading
                    this.player = this.server.getPlayerList().respawn(this.player, false, Entity.RemovalReason.KILLED, RespawnReason.DEATH); // CraftBukkit
                    this.resetPosition();
                    this.restartClientLoadTimerAfterRespawn();
                    if (this.server.isHardcore()) {
                        this.player.setGameMode(GameType.SPECTATOR, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH, null); // Paper - Expand PlayerGameModeChangeEvent
                    }
                }
                break;
            case REQUEST_STATS:
                this.player.getStats().sendStats(this.player);
                break;
            case REQUEST_GAMERULE_VALUES:
                this.sendGameRuleValues();
        }
    }

    private void sendGameRuleValues() {
        if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamerule")) { // Paper - add permission check
            LOGGER.warn("Player {} tried to request game rule values without required permissions", this.player.getGameProfile().name());
        } else {
            GameRules gameRules = this.player.level().getGameRules();
            Map<ResourceKey<GameRule<?>>, String> values = new HashMap<>();
            gameRules.availableRules().forEach(rule -> addGameRuleValue(gameRules, values, (GameRule<?>)rule));
            this.send(new ClientboundGameRuleValuesPacket(values));
        }
    }

    private static <T> void addGameRuleValue(final GameRules gameRules, final Map<ResourceKey<GameRule<?>>, String> values, final GameRule<T> rule) {
        BuiltInRegistries.GAME_RULE.getResourceKey(rule).ifPresent(key -> values.put((ResourceKey<GameRule<?>>)key, rule.serialize(gameRules.get(rule))));
    }

    @Override
    public void handleContainerClose(final ServerboundContainerClosePacket packet) {
        // Paper start - Inventory close reason
        this.handleContainerClose(packet, org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLAYER);
    }

    public void handleContainerClose(ServerboundContainerClosePacket packet, org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        // Paper end - Inventory close reason
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());

        if (this.player.isImmobile()) return; // CraftBukkit
        CraftEventFactory.handleInventoryCloseEvent(this.player, reason); // CraftBukkit // Paper
        this.player.doCloseContainer();
    }

    @Override
    public void handleContainerClick(final ServerboundContainerClickPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        this.player.resetLastActionTime();
        if (this.player.containerMenu.containerId == packet.containerId() && this.player.containerMenu.stillValid(this.player)) { // CraftBukkit
            boolean cancelled = this.player.isSpectator(); // CraftBukkit - pre cancelled when in spectator
            if ((false && this.player.isSpectator()) || this.player.isDeadOrDying()) { // CraftBukkit
                this.player.containerMenu.sendAllDataToRemote();
            } else if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                int slotIndex = packet.slotNum();
                if (!this.player.containerMenu.isValidSlotIndex(slotIndex)) {
                    LOGGER.debug(
                        "Player {} clicked invalid slot index: {}, available slots: {}",
                        this.player.getPlainTextName(),
                        slotIndex,
                        this.player.containerMenu.slots.size()
                    );
                } else {
                    boolean fullResyncNeeded = packet.stateId() != this.player.containerMenu.getStateId();
                    this.player.containerMenu.suppressRemoteUpdates();
                    // CraftBukkit start - Call InventoryClickEvent
                    if (slotIndex < -1 && slotIndex != net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                        return;
                    }

                    org.bukkit.inventory.InventoryView inventory = this.player.containerMenu.getBukkitView();
                    SlotType type = inventory.getSlotType(slotIndex);

                    InventoryClickEvent event;
                    ClickType click = ClickType.UNKNOWN;
                    InventoryAction action = InventoryAction.UNKNOWN;

                    switch (packet.containerInput()) {
                        case PICKUP:
                            if (packet.buttonNum() == 0) {
                                click = ClickType.LEFT;
                            } else if (packet.buttonNum() == 1) {
                                click = ClickType.RIGHT;
                            }
                            if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                                action = InventoryAction.NOTHING; // Don't want to repeat ourselves
                                if (slotIndex == net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                                    if (!this.player.containerMenu.getCarried().isEmpty()) {
                                        action = packet.buttonNum() == 0 ? InventoryAction.DROP_ALL_CURSOR : InventoryAction.DROP_ONE_CURSOR;
                                    }
                                } else if (slotIndex < 0) {
                                    action = InventoryAction.NOTHING;
                                } else {
                                    Slot slot = this.player.containerMenu.getSlot(slotIndex);
                                    if (slot != null) {
                                        ItemStack clickedItem = slot.getItem();
                                        ItemStack cursor = this.player.containerMenu.getCarried();
                                        if (clickedItem.isEmpty()) {
                                            if (!cursor.isEmpty()) {
                                                if (cursor.getItem() instanceof net.minecraft.world.item.BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() != 0) {
                                                    action = cursor.get(DataComponents.BUNDLE_CONTENTS).isEmpty() ? InventoryAction.NOTHING : InventoryAction.PLACE_FROM_BUNDLE;
                                                } else {
                                                    action = packet.buttonNum() == 0 ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_ONE;
                                                }
                                            }
                                        } else if (slot.mayPickup(this.player)) {
                                            if (cursor.isEmpty()) {
                                                if (slot.getItem().getItem() instanceof net.minecraft.world.item.BundleItem && slot.getItem().has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() != 0) {
                                                    action = slot.getItem().get(DataComponents.BUNDLE_CONTENTS).isEmpty() ? InventoryAction.NOTHING : InventoryAction.PICKUP_FROM_BUNDLE;
                                                } else {
                                                    action = packet.buttonNum() == 0 ? InventoryAction.PICKUP_ALL : InventoryAction.PICKUP_HALF;
                                                }
                                            } else if (slot.mayPlace(cursor)) {
                                                if (ItemStack.isSameItemSameComponents(clickedItem, cursor)) {
                                                    int toPlace = packet.buttonNum() == 0 ? cursor.getCount() : 1;
                                                    toPlace = Math.min(toPlace, clickedItem.getMaxStackSize() - clickedItem.getCount());
                                                    toPlace = Math.min(toPlace, slot.container.getMaxStackSize() - clickedItem.getCount());
                                                    if (toPlace == 1) {
                                                        action = InventoryAction.PLACE_ONE;
                                                    } else if (toPlace == cursor.getCount()) {
                                                        action = InventoryAction.PLACE_ALL;
                                                    } else if (toPlace < 0) {
                                                        action = toPlace != -1 ? InventoryAction.PICKUP_SOME : InventoryAction.PICKUP_ONE; // this happens with oversized stacks
                                                    } else if (toPlace != 0) {
                                                        action = InventoryAction.PLACE_SOME;
                                                    }
                                                } else if (cursor.getCount() <= slot.getMaxStackSize()) {
                                                    if (cursor.getItem() instanceof net.minecraft.world.item.BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() == 0) {
                                                        int toPickup = cursor.get(DataComponents.BUNDLE_CONTENTS).getMaxAmountToAdd(slot.getItem());
                                                        if (toPickup >= slot.getItem().getCount()) {
                                                            action = InventoryAction.PICKUP_ALL_INTO_BUNDLE;
                                                        } else if (toPickup == 0) {
                                                            action = InventoryAction.NOTHING;
                                                        } else {
                                                            action = InventoryAction.PICKUP_SOME_INTO_BUNDLE;
                                                        }
                                                    } else if (slot.getItem().getItem() instanceof net.minecraft.world.item.BundleItem && slot.getItem().has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() == 0) {
                                                        int toPickup = slot.getItem().get(DataComponents.BUNDLE_CONTENTS).getMaxAmountToAdd(cursor);
                                                        if (toPickup >= cursor.getCount()) {
                                                            action = InventoryAction.PLACE_ALL_INTO_BUNDLE;
                                                        } else if (toPickup == 0) {
                                                            action = InventoryAction.NOTHING;
                                                        } else {
                                                            action = InventoryAction.PLACE_SOME_INTO_BUNDLE;
                                                        }
                                                    } else {
                                                        action = InventoryAction.SWAP_WITH_CURSOR;
                                                    }
                                                }
                                            } else if (ItemStack.isSameItemSameComponents(cursor, clickedItem)) {
                                                if (clickedItem.getCount() >= 0) {
                                                    if (clickedItem.getCount() + cursor.getCount() <= cursor.getMaxStackSize()) {
                                                        // As of 1.5, this is result slots only
                                                        action = InventoryAction.PICKUP_ALL;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            break;
                        // TODO check on updates
                        case QUICK_MOVE:
                            if (packet.buttonNum() == 0) {
                                click = ClickType.SHIFT_LEFT;
                            } else if (packet.buttonNum() == 1) {
                                click = ClickType.SHIFT_RIGHT;
                            }
                            if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                                if (slotIndex < 0) {
                                    action = InventoryAction.NOTHING;
                                } else {
                                    Slot slot = this.player.containerMenu.getSlot(slotIndex);
                                    if (slot != null && slot.mayPickup(this.player) && slot.hasItem()) {
                                        action = InventoryAction.MOVE_TO_OTHER_INVENTORY;
                                    } else {
                                        action = InventoryAction.NOTHING;
                                    }
                                }
                            }
                            break;
                        case SWAP:
                            if ((packet.buttonNum() >= 0 && packet.buttonNum() < 9) || packet.buttonNum() == Inventory.SLOT_OFFHAND) {
                                // Paper start - Add slot sanity checks to container clicks
                                if (slotIndex < 0) {
                                    action = InventoryAction.NOTHING;
                                    break;
                                }
                                // Paper end - Add slot sanity checks to container clicks
                                click = (packet.buttonNum() == Inventory.SLOT_OFFHAND) ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;
                                Slot clickedSlot = this.player.containerMenu.getSlot(slotIndex);
                                if (clickedSlot.mayPickup(this.player)) {
                                    ItemStack hotbar = this.player.getInventory().getItem(packet.buttonNum());
                                    if ((!hotbar.isEmpty() && clickedSlot.mayPlace(hotbar)) || (hotbar.isEmpty() && clickedSlot.hasItem())) { // Paper - modernify this logic (no such thing as a "hotbar move and readd"
                                        action = InventoryAction.HOTBAR_SWAP;
                                    } else {
                                        action = InventoryAction.NOTHING;
                                    }
                                } else {
                                    action = InventoryAction.NOTHING;
                                }
                            }
                            break;
                        case CLONE:
                            if (packet.buttonNum() == 2) {
                                click = ClickType.MIDDLE;
                                if (slotIndex < 0) {
                                    action = InventoryAction.NOTHING;
                                } else {
                                    Slot slot = this.player.containerMenu.getSlot(slotIndex);
                                    if (slot != null && slot.hasItem() && this.player.getAbilities().instabuild && this.player.containerMenu.getCarried().isEmpty()) {
                                        action = InventoryAction.CLONE_STACK;
                                    } else {
                                        action = InventoryAction.NOTHING;
                                    }
                                }
                            } else {
                                click = ClickType.UNKNOWN;
                                action = InventoryAction.UNKNOWN;
                            }
                            break;
                        case THROW:
                            if (slotIndex >= 0) {
                                if (packet.buttonNum() == 0) {
                                    click = ClickType.DROP;
                                    Slot slot = this.player.containerMenu.getSlot(slotIndex);
                                    if (slot != null && slot.hasItem() && slot.mayPickup(this.player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != Items.AIR) {
                                        action = InventoryAction.DROP_ONE_SLOT;
                                    } else {
                                        action = InventoryAction.NOTHING;
                                    }
                                } else if (packet.buttonNum() == 1) {
                                    click = ClickType.CONTROL_DROP;
                                    Slot slot = this.player.containerMenu.getSlot(slotIndex);
                                    if (slot != null && slot.hasItem() && slot.mayPickup(this.player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != Items.AIR) {
                                        action = InventoryAction.DROP_ALL_SLOT;
                                    } else {
                                        action = InventoryAction.NOTHING;
                                    }
                                }
                            } else {
                                // Sane default (because this happens when they are holding nothing. Don't ask why.)
                                click = ClickType.LEFT;
                                if (packet.buttonNum() == 1) {
                                    click = ClickType.RIGHT;
                                }
                                action = InventoryAction.NOTHING;
                            }
                            break;
                        case QUICK_CRAFT:
                            // Paper start - Fix CraftBukkit drag system
                            net.minecraft.world.inventory.AbstractContainerMenu containerMenu = this.player.containerMenu;
                            int currentStatus = this.player.containerMenu.quickcraftStatus;
                            int newStatus = net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftHeader(packet.buttonNum());
                            if ((currentStatus != net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE || newStatus != net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_END && currentStatus != newStatus)) {
                            } else if (containerMenu.getCarried().isEmpty()) {
                            } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_START) {
                            } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE) {
                            } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_END) {
                                if (!this.player.containerMenu.quickcraftSlots.isEmpty()) {
                                    if (this.player.containerMenu.quickcraftSlots.size() == 1) {
                                        int index = containerMenu.quickcraftSlots.iterator().next().index;
                                        containerMenu.resetQuickCraft();
                                        this.handleContainerClick(new ServerboundContainerClickPacket(packet.containerId(), packet.stateId(), (short) index, (byte) containerMenu.quickcraftType, net.minecraft.world.inventory.ContainerInput.PICKUP, packet.changedSlots(), packet.carriedItem()));
                                        return;
                                    }
                                }
                            }
                            // Paper end - Fix CraftBukkit drag system
                            this.player.containerMenu.clicked(slotIndex, packet.buttonNum(), packet.containerInput(), this.player);
                            break;
                        case PICKUP_ALL:
                            click = ClickType.DOUBLE_CLICK;
                            action = InventoryAction.NOTHING;
                            if (slotIndex >= 0 && !this.player.containerMenu.getCarried().isEmpty()) {
                                ItemStack cursor = this.player.containerMenu.getCarried();
                                action = InventoryAction.NOTHING;
                                // Quick check for if we have any of the item
                                if (inventory.getTopInventory().contains(org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(cursor.getItem())) || inventory.getBottomInventory().contains(org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(cursor.getItem()))) {
                                    action = InventoryAction.COLLECT_TO_CURSOR;
                                }
                            }
                            break;
                        default:
                            break;
                    }

                    if (packet.containerInput() != net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT) {
                        // If a non quick craft packet is emitted by the client while in quick crafting, the server will reset quick crafting and
                        // discard any further packet action.
                        if (this.player.containerMenu.quickcraftStatus != 0) {
                            action = InventoryAction.NOTHING;
                        }

                        if (click == ClickType.NUMBER_KEY) {
                            event = new InventoryClickEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                        } else {
                            event = new InventoryClickEvent(inventory, type, slotIndex, click, action);
                        }

                        org.bukkit.inventory.Inventory top = inventory.getTopInventory();
                        if (slotIndex == 0 && top instanceof final org.bukkit.inventory.CraftingInventory craftingInv) {
                            org.bukkit.inventory.Recipe recipe = craftingInv.getRecipe();
                            if (recipe != null) {
                                if (click == ClickType.NUMBER_KEY) {
                                    event = new CraftItemEvent(recipe, inventory, type, slotIndex, click, action, packet.buttonNum());
                                } else {
                                    event = new CraftItemEvent(recipe, inventory, type, slotIndex, click, action);
                                }
                            }
                        }

                        if (slotIndex == 3 && top instanceof final org.bukkit.inventory.SmithingInventory smithingInv) {
                            org.bukkit.inventory.ItemStack result = smithingInv.getResult();
                            if (result != null) {
                                if (click == ClickType.NUMBER_KEY) {
                                    event = new SmithItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                                } else {
                                    event = new SmithItemEvent(inventory, type, slotIndex, click, action);
                                }
                            }
                        }

                        // Paper start - cartography item event
                        if (slotIndex == net.minecraft.world.inventory.CartographyTableMenu.RESULT_SLOT && top instanceof org.bukkit.inventory.CartographyInventory cartographyInventory) {
                            org.bukkit.inventory.ItemStack result = cartographyInventory.getResult();
                            if (result != null && !result.isEmpty()) {
                                if (click == ClickType.NUMBER_KEY) {
                                    event = new io.papermc.paper.event.player.CartographyItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                                } else {
                                    event = new io.papermc.paper.event.player.CartographyItemEvent(inventory, type, slotIndex, click, action);
                                }
                            }
                        }
                        // Paper end - cartography item event

                        event.setCancelled(cancelled);
                        net.minecraft.world.inventory.AbstractContainerMenu oldContainer = this.player.containerMenu; // SPIGOT-1224
                        this.cserver.getPluginManager().callEvent(event);
                        if (this.player.containerMenu != oldContainer) {
                            this.player.containerMenu.resumeRemoteUpdates();
                            this.player.containerMenu.broadcastFullState();
                            return;
                        }

                        if (event.getResult() != org.bukkit.event.Event.Result.DENY) { // if denied, synchronizing is fully handled by broadcastChanges at the end
                            this.player.containerMenu.clicked(slotIndex, packet.buttonNum(), packet.containerInput(), this.player);
                        }

                        if (event instanceof CraftItemEvent || event instanceof SmithItemEvent) {
                            // Need to update the inventory on crafting to
                            // correctly support custom recipes
                            this.player.containerMenu.sendAllDataToRemote();
                        }
                    }
                    // CraftBukkit end

                    for (Entry<HashedStack> e : Int2ObjectMaps.fastIterable(packet.changedSlots())) {
                        this.player.containerMenu.setRemoteSlotUnsafe(e.getIntKey(), e.getValue());
                    }

                    this.player.containerMenu.setRemoteCarried(packet.carriedItem());
                    this.player.containerMenu.resumeRemoteUpdates();
                    if (fullResyncNeeded) {
                        this.player.containerMenu.broadcastFullState();
                    } else {
                        this.player.containerMenu.broadcastChanges();
                    }
                    if (packet.buttonNum() == Inventory.SLOT_OFFHAND && this.player.containerMenu != this.player.inventoryMenu) this.player.containerSynchronizer.sendOffHandSlotChange(); // Paper - update offhand data when the player is clicking in an inventory not their own as the sychronizer does not include offhand slots
                    if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
                }
            }
        }
    }

    @Override
    public void handlePlaceRecipe(final ServerboundPlaceRecipePacket packet) {
        // Paper start - auto recipe limit
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            if (!this.recipeSpamPackets.isIncrementAndUnderThreshold()) {
                this.disconnectAsync(net.minecraft.network.chat.Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM); // Paper - kick event cause // Paper - add proper async disconnect
                return;
            }
        }
        // Paper end - auto recipe limit
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        if (!this.player.isSpectator() && this.player.containerMenu.containerId == packet.containerId()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                RecipeManager.ServerDisplayInfo displayInfo = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
                if (displayInfo != null) {
                    RecipeHolder<?> recipe = displayInfo.parent();
                    if (this.player.getRecipeBook().contains(recipe.id())) {
                        if (this.player.containerMenu instanceof RecipeBookMenu recipeBookMenu) {
                            if (recipe.value().placementInfo().isImpossibleToPlace()) {
                                LOGGER.debug("Player {} tried to place impossible recipe {}", this.player, recipe.id().identifier());
                                return;
                            }

                            // Paper start - Add PlayerRecipeBookClickEvent
                            NamespacedKey recipeName = org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(recipe.id().identifier());
                            boolean makeAll = packet.useMaxItems();
                            com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent paperEvent = new com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent(
                                this.player.getBukkitEntity(), recipeName, makeAll
                            );
                            if (!paperEvent.callEvent()) {
                                return;
                            }
                            recipeName = paperEvent.getRecipe();
                            makeAll = paperEvent.isMakeAll();
                            if (org.bukkit.event.player.PlayerRecipeBookClickEvent.getHandlerList().getRegisteredListeners().length > 0) {
                                // Paper end - Add PlayerRecipeBookClickEvent
                                // CraftBukkit start - implement PlayerRecipeBookClickEvent
                                org.bukkit.inventory.Recipe bukkitRecipe = this.cserver.getRecipe(recipeName); // Paper - Add PlayerRecipeBookClickEvent - forward to legacy event
                                if (bukkitRecipe == null) {
                                    return;
                                }
                                // Paper start - Add PlayerRecipeBookClickEvent - forward to legacy event
                                org.bukkit.event.player.PlayerRecipeBookClickEvent event = CraftEventFactory.callRecipeBookClickEvent(this.player, bukkitRecipe, makeAll);
                                recipeName = ((org.bukkit.Keyed) event.getRecipe()).getKey();
                                makeAll = event.isShiftClick();
                            }
                            if (!(this.player.containerMenu instanceof RecipeBookMenu)) {
                                return;
                            }
                            // Paper end - Add PlayerRecipeBookClickEvent - forward to legacy event

                            // Cast to keyed should be safe as the recipe will never be a MerchantRecipe.
                            recipe = this.server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, org.bukkit.craftbukkit.util.CraftNamespacedKey.toMinecraft(recipeName))).orElse(null); // Paper - Add PlayerRecipeBookClickEvent - forward to legacy event
                            if (recipe == null) {
                                return;
                            }

                            RecipeBookMenu.PostPlaceAction postPlaceAction = recipeBookMenu.handlePlacement(
                                makeAll, this.player.isCreative(), recipe, this.player.level(), this.player.getInventory()
                            );
                            // CraftBukkit end
                            if (postPlaceAction == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE) {
                                this.send(new ClientboundPlaceGhostRecipePacket(this.player.containerMenu.containerId, displayInfo.display().display()));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleContainerButtonClick(final ServerboundContainerButtonClickPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isImmobile()) return; // CraftBukkit
        this.player.resetLastActionTime();
        if (this.player.containerMenu.containerId == packet.containerId() && !this.player.isSpectator()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                boolean clickAccepted = this.player.containerMenu.clickMenuButton(this.player, packet.buttonId());
                if (clickAccepted) {
                    this.player.containerMenu.broadcastChanges();
                }
                if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
            }
        }
    }

    @Override
    public void handleSetCreativeModeSlot(final ServerboundSetCreativeModeSlotPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.hasInfiniteMaterials()) {
            boolean drop = packet.slotNum() < 0;
            ItemStack itemStack = packet.itemStack();
            if (!itemStack.isItemEnabled(this.player.level().enabledFeatures())) {
                return;
            }

            boolean validSlot = packet.slotNum() >= 1 && packet.slotNum() <= 45;
            boolean validData = itemStack.isEmpty() || itemStack.getCount() <= itemStack.getMaxStackSize();
            if (drop || (validSlot && !ItemStack.matches(this.player.inventoryMenu.getSlot(packet.slotNum()).getItem(), packet.itemStack()))) { // Insist on valid slot
                // CraftBukkit start - Call click event
                org.bukkit.inventory.InventoryView inventory = this.player.inventoryMenu.getBukkitView();
                org.bukkit.inventory.ItemStack item = CraftItemStack.asBukkitCopy(packet.itemStack());

                SlotType type = SlotType.QUICKBAR;
                if (drop) {
                    type = SlotType.OUTSIDE;
                } else if (packet.slotNum() < 36) {
                    if (packet.slotNum() >= 5 && packet.slotNum() < 9) {
                        type = SlotType.ARMOR;
                    } else {
                        type = SlotType.CONTAINER;
                    }
                }
                InventoryCreativeEvent event = new InventoryCreativeEvent(inventory, type, drop ? net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE : packet.slotNum(), item);
                this.cserver.getPluginManager().callEvent(event);

                itemStack = CraftItemStack.asNMSCopy(event.getCursor());

                switch (event.getResult()) {
                    case ALLOW:
                        // Plugin cleared the id / stacksize checks
                        validData = true;
                        break;
                    case DEFAULT:
                        break;
                    case DENY:
                        // Reset the slot
                        if (packet.slotNum() >= 0) {
                            this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(this.player.inventoryMenu.containerId, this.player.inventoryMenu.incrementStateId(), packet.slotNum(), this.player.inventoryMenu.getSlot(packet.slotNum()).getItem()));
                            this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(ItemStack.EMPTY.copy())); // Paper - correctly set cursor
                        }
                        return;
                }
            }
            // CraftBukkit end
            if (validSlot && validData) {
                this.player.inventoryMenu.getSlot(packet.slotNum()).setByPlayer(itemStack);
                this.player.inventoryMenu.setRemoteSlot(packet.slotNum(), itemStack);
                this.player.inventoryMenu.broadcastChanges();
                if (io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) this.player.detectEquipmentUpdates(); // Paper - Force update attributes.
            } else if (drop && validData) {
                if (this.dropSpamThrottler.isUnderThreshold()) {
                    this.dropSpamThrottler.increment();
                    this.player.drop(itemStack, true);
                } else {
                    LOGGER.warn("Player {} was dropping items too fast in creative mode, ignoring.", this.player.getPlainTextName());
                }
            }
        }
    }

    @Override
    public void handleSignUpdate(final ServerboundSignUpdatePacket packet) {
        // Paper start - Limit client sign length
        String[] linesArray = packet.getLines();
        for (int i = 0; i < linesArray.length; ++i) {
            if (MAX_SIGN_LINE_LENGTH > 0 && linesArray[i].length() > MAX_SIGN_LINE_LENGTH) {
                // This handles multibyte characters as 1
                int offset = linesArray[i].codePoints().limit(MAX_SIGN_LINE_LENGTH).map(Character::charCount).sum();
                if (offset < linesArray[i].length()) {
                    linesArray[i] = linesArray[i].substring(0, offset); // this will break any filtering, but filtering is NYI as of 1.17
                }
            }
        }
        List<String> lines = Stream.of(linesArray).map(ChatFormatting::stripFormatting).collect(Collectors.toList());
        // Paper end - Limit client sign length
        // Folia start - region threading
        this.filterTextPacket(lines).thenAcceptAsync(filteredLines -> this.updateSignText(packet, (List<FilteredText>)filteredLines), this.player::queuePacketTask)
                .whenComplete((Object res, Throwable thr) -> {
            if (thr != null) {
                LOGGER.error("Failed to handle sign update packet", thr);
            }
        });
        // Folia end - region threading
    }

    private void updateSignText(final ServerboundSignUpdatePacket packet, final List<FilteredText> lines) {
        if (this.player.isImmobile()) return; // CraftBukkit
        this.player.resetLastActionTime();
        ServerLevel level = this.player.level();
        BlockPos pos = packet.getPos();
        if (level.hasChunkAt(pos)) {
            // Paper start - Add API for client-side signs
            if (!new io.papermc.paper.event.packet.UncheckedSignChangeEvent(
                this.player.getBukkitEntity(),
                io.papermc.paper.util.MCUtil.toPosition(pos),
                packet.isFrontText() ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK,
                lines.stream().<net.kyori.adventure.text.Component>map(line -> net.kyori.adventure.text.Component.text(line.raw())).toList())
                .callEvent()) {
                return;
            }
            // Paper end - Add API for client-side signs
            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
                return;
            }

            sign.updateSignText(this.player, packet.isFrontText(), lines);
        }
    }

    @Override
    public void handlePlayerAbilities(final ServerboundPlayerAbilitiesPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        // CraftBukkit start
        if (this.player.getAbilities().mayfly && this.player.getAbilities().flying != packet.isFlying()) {
            PlayerToggleFlightEvent event = new PlayerToggleFlightEvent(this.player.getBukkitEntity(), packet.isFlying());
            this.cserver.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                this.player.getAbilities().flying = packet.isFlying(); // Actually set the player's flying status
            } else {
                this.player.onUpdateAbilities(); // Tell the player their ability was reverted
            }
        }
        // CraftBukkit end
    }

    @Override
    public void handleClientInformation(final ServerboundClientInformationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        // Paper start - do not accept invalid information
        if (packet.information().viewDistance() < 0) {
            LOGGER.warn("Disconnecting {} for invalid view distance: {}", this.player.getScoreboardName(), packet.information().viewDistance());
            this.disconnect(Component.literal("Invalid client settings"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION);
            return;
        }
        // Paper end - do not accept invalid information
        boolean wasHatShown = this.player.isModelPartShown(PlayerModelPart.HAT);
        this.player.updateOptions(packet.information());
        this.connection.channel.attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).set(net.kyori.adventure.translation.Translator.parseLocale(packet.information().language())); // Paper
        if (this.player.isModelPartShown(PlayerModelPart.HAT) != wasHatShown) {
            this.server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, this.player));
        }
    }

    @Override
    public void handleChangeDifficulty(final ServerboundChangeDifficultyPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && !this.isSingleplayerOwner()) {
            LOGGER.warn(
                "Player {} tried to change difficulty to {} without required permissions",
                this.player.getGameProfile().name(),
                packet.difficulty().getDisplayName()
            );
        } else {
            // this.server.setDifficulty(packet.difficulty(), false); // Paper - per level difficulty; don't allow clients to change this
        }
    }

    @Override
    public void handleChangeGameMode(final ServerboundChangeGameModePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!GameModeCommand.PERMISSION_CHECK.check(this.player.permissions()) && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamemode")) { // Paper - add permission check
            LOGGER.warn(
                "Player {} tried to change game mode to {} without required permissions",
                this.player.getGameProfile().name(),
                packet.mode().getShortDisplayName().getString()
            );
        } else {
            GameModeCommand.setGameMode(this.player.createCommandSourceStack(), this.player, packet.mode(), org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.GAMEMODE_SWITCHER); // Paper - add cause
        }
    }

    @Override
    public void handleLockDifficulty(final ServerboundLockDifficultyPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || this.isSingleplayerOwner()) {
            this.server.setDifficultyLocked(packet.isLocked());
        }
    }

    @Override
    public void handleChatSessionUpdate(final ServerboundChatSessionUpdatePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        RemoteChatSession.Data newChatSession = packet.chatSession();
        ProfilePublicKey.Data oldProfileKey = this.chatSession != null ? this.chatSession.profilePublicKey().data() : null;
        ProfilePublicKey.Data newProfileKey = newChatSession.profilePublicKey();
        if (!Objects.equals(oldProfileKey, newProfileKey)) {
            if (oldProfileKey != null && newProfileKey.expiresAt().isBefore(oldProfileKey.expiresAt())) {
                this.disconnect(ProfilePublicKey.EXPIRED_PROFILE_PUBLIC_KEY, org.bukkit.event.player.PlayerKickEvent.Cause.EXPIRED_PROFILE_PUBLIC_KEY); // Paper - kick event causes
            } else {
                try {
                    SignatureValidator profileKeySignatureValidator = this.server.services().profileKeySignatureValidator();
                    if (profileKeySignatureValidator == null) {
                        LOGGER.warn("Ignoring chat session from {} due to missing Services public key", this.player.getGameProfile().name());
                        return;
                    }

                    this.resetPlayerChatState(newChatSession.validate(this.player.getGameProfile(), profileKeySignatureValidator));
                } catch (ProfilePublicKey.ValidationException e) {
                    // LOGGER.error("Failed to validate profile key: {}", e.getMessage()); // Paper - Improve logging and errors
                    this.disconnect(e.getComponent(), e.kickCause); // Paper - kick event causes
                }
            }
        }
    }

    @Override
    public void handleConfigurationAcknowledged(final ServerboundConfigurationAcknowledgedPacket packet) {
        if (!this.waitingForSwitchToConfig) {
            throw new IllegalStateException("Client acknowledged config, but none was requested");
        }

        final ServerConfigurationPacketListenerImpl listener = new ServerConfigurationPacketListenerImpl(this.server, this.connection, this.createCookie(this.player.clientInformation())); // Paper
        this.connection
            .setupInboundProtocol(
                ConfigurationProtocols.SERVERBOUND,
                listener // Paper
            );
        new io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent(listener.paperConnection).callEvent(); // Paper
    }

    @Override
    public void handleChunkBatchReceived(final ServerboundChunkBatchReceivedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.chunkSender.onChunkBatchReceivedByClient(packet.desiredChunksPerTick());
    }

    @Override
    public void handleDebugSubscriptionRequest(final ServerboundDebugSubscriptionRequestPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.requestDebugSubscriptions(packet.subscriptions());
    }

    private void resetPlayerChatState(final RemoteChatSession chatSession) {
        this.chatSession = chatSession;
        this.hasLoggedExpiry = false; // Paper - Prevent causing expired keys from impacting new joins
        this.signedMessageDecoder = chatSession.createMessageDecoder(this.player.getUUID());
        this.chatMessageChain
            .append(
                () -> {
                    io.papermc.paper.threadedregions.RegionizedServer.getInstance().blockOn(() -> { // Paper - Broadcast chat session update sync // Folia - region threading
                    this.player.setChatSession(chatSession);
                    this.server
                        .getPlayerList()
                        .broadcastAll(
                            new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT), List.of(this.player)), this.player // Paper - Use single player info update packet on join
                        );
                    });
                }
            );
    }

    @Override
    public void handleCustomPayload(final ServerboundCustomPayloadPacket packet) {
        super.handleCustomPayload(packet); // Paper
    }

    @Override
    public void handleClientTickEnd(final ServerboundClientTickEndPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.tickEndEvent.callEvent(); // Paper - add client tick end event
        if (!this.receivedMovementThisTick) {
            this.player.setKnownMovement(Vec3.ZERO);
        }

        this.receivedMovementThisTick = false;
    }

    private void handlePlayerKnownMovement(final Vec3 movement) {
        if (movement.lengthSqr() > 1.0E-5F) {
            this.player.resetLastActionTime();
        }

        this.player.setKnownMovement(movement);
        this.receivedMovementThisTick = true;
    }

    @Override
    public boolean hasInfiniteMaterials() {
        return this.player.hasInfiniteMaterials();
    }

    @Override
    public ServerPlayer getPlayer() {
        return this.player;
    }

    public boolean hasClientLoaded() {
        return !this.waitingForRespawn && this.clientLoadedTimeoutTimer <= 0;
    }

    public void tickClientLoadTimeout() {
        if (this.clientLoadedTimeoutTimer > 1) { // Paper - Add PlayerLoadedWorldEvent - only reduce till 1, let the below else-if call #markClientLoaded to trigger event and reduce to 0
            this.clientLoadedTimeoutTimer--;
        } else if (this.clientLoadedTimeoutTimer == 1) this.markClientLoaded(true); // Paper - Add PlayerLoadedWorldEvent - decrement by calling #markClientLoaded which will set the value to 0 *and* call the event.
    }

    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper - Add PlayerLoadedWorldEvent
    private void markClientLoaded() {
        // Paper start - Add PlayerLoadedWorldEvent
        this.markClientLoaded(false);
    }

    private void markClientLoaded(boolean timeout) {
        if (!hasClientLoaded()) {
            final io.papermc.paper.event.player.PlayerClientLoadedWorldEvent event = new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(this.player.getBukkitEntity(), timeout);
            event.callEvent();
        }
        // Paper end - Add PlayerLoadedWorldEvent
        this.clientLoadedTimeoutTimer = 0;
    }

    public void markClientUnloadedAfterDeath() {
        this.waitingForRespawn = true;
    }

    public void restartClientLoadTimerAfterRespawn() {
        this.waitingForRespawn = false;
        this.clientLoadedTimeoutTimer = 60;
    }

    // Paper start - Add fail move event
    private io.papermc.paper.event.player.PlayerFailMoveEvent fireFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason failReason,
                                                                           double targetX, double targetY, double targetZ, float targetYRot, float targetXRot, boolean logWarning) {
        org.bukkit.entity.Player player = this.getCraftPlayer();
        Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot);
        Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
        io.papermc.paper.event.player.PlayerFailMoveEvent event = new io.papermc.paper.event.player.PlayerFailMoveEvent(player, failReason,
            false, logWarning, from, to);
        event.callEvent();
        return event;
    }
    // Paper end - Add fail move event

    // Paper start - add utilities
    public org.bukkit.craftbukkit.entity.CraftPlayer getCraftPlayer() {
        return this.player == null ? null : this.player.getBukkitEntity();
    }

    @Override
    public void disconnectAsync(final net.minecraft.network.DisconnectionDetails disconnectionInfo) {
        this.disconnect(disconnectionInfo); // Folia - threaded regions
    }

    public final boolean isDisconnected() {
        return (!this.player.joining && !this.connection.isConnected());
    }

    @Override
    public void handleResourcePackResponse(final net.minecraft.network.protocol.common.ServerboundResourcePackPacket packet) {
        super.handleResourcePackResponse(packet);
        // Paper start - store last pack status
        org.bukkit.event.player.PlayerResourcePackStatusEvent.Status packStatus = org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.values()[packet.action().ordinal()];
        this.connection.resourcePackStatus = packStatus;
        this.cserver.getPluginManager().callEvent(new org.bukkit.event.player.PlayerResourcePackStatusEvent(this.getCraftPlayer(), packet.id(), packStatus)); // CraftBukkit
        // Paper end - store last pack status
    }

    // Paper start - adventure
    public void disconnect(final net.kyori.adventure.text.Component reason) {
        this.disconnect(reason, org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN);
    }

    public void disconnect(final net.kyori.adventure.text.Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(reason), io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    public void disconnect(final Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnect(reason, io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    public void disconnectAsync(net.kyori.adventure.text.Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnectAsync(io.papermc.paper.adventure.PaperAdventure.asVanilla(reason), cause);
    }

    public void disconnectAsync(Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnectAsync(reason, io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    @Override
    public io.papermc.paper.connection.PaperPlayerGameConnection paperConnection() {
        return this.playerGameConnection;
    }
    // Paper end
}

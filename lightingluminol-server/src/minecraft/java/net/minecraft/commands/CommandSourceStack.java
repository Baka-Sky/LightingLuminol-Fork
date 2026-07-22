package net.minecraft.commands;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.execution.TraceCallbacks;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class CommandSourceStack implements SharedSuggestionProvider, ExecutionCommandSource<CommandSourceStack>, io.papermc.paper.command.brigadier.PaperCommandSourceStack { // Paper - Brigadier API
    public static final SimpleCommandExceptionType ERROR_NOT_PLAYER = new SimpleCommandExceptionType(Component.translatable("permissions.requires.player"));
    public static final SimpleCommandExceptionType ERROR_NOT_ENTITY = new SimpleCommandExceptionType(Component.translatable("permissions.requires.entity"));
    public final CommandSource source;
    private final Vec3 worldPosition;
    private final ServerLevel level;
    private final PermissionSet permissions;
    private final String textName;
    private final Component displayName;
    private final MinecraftServer server;
    private final boolean silent;
    private final @Nullable Entity entity;
    private final CommandResultCallback resultCallback;
    private final EntityAnchorArgument.Anchor anchor;
    private final Vec2 rotation;
    private final CommandSigningContext signingContext;
    private final TaskChainer chatMessageChainer;
    public boolean bypassSelectorPermissions = false; // Paper - add bypass for selector permissions

    public CommandSourceStack(
        final CommandSource source,
        final Vec3 position,
        final Vec2 rotation,
        final ServerLevel level,
        final PermissionSet permissions,
        final String textName,
        final Component displayName,
        final MinecraftServer server,
        final @Nullable Entity entity
    ) {
        this(
            source,
            position,
            rotation,
            level,
            permissions,
            textName,
            displayName,
            server,
            entity,
            false,
            CommandResultCallback.EMPTY,
            EntityAnchorArgument.Anchor.FEET,
            CommandSigningContext.ANONYMOUS,
            TaskChainer.immediate((Runnable run) -> { io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(run);}) // Folia - region threading
        );
    }

    private CommandSourceStack(
        final CommandSource source,
        final Vec3 position,
        final Vec2 rotation,
        final ServerLevel level,
        final PermissionSet permissions,
        final String textName,
        final Component displayName,
        final MinecraftServer server,
        final @Nullable Entity entity,
        final boolean silent,
        final CommandResultCallback resultCallback,
        final EntityAnchorArgument.Anchor anchor,
        final CommandSigningContext signingContext,
        final TaskChainer chatMessageChainer
    ) {
        this.source = source;
        this.worldPosition = position;
        this.level = level;
        this.silent = silent;
        this.entity = entity;
        this.permissions = permissions;
        this.textName = textName;
        this.displayName = displayName;
        this.server = server;
        this.resultCallback = resultCallback;
        this.anchor = anchor;
        this.rotation = rotation;
        this.signingContext = signingContext;
        this.chatMessageChainer = chatMessageChainer;
    }

    public CommandSourceStack withSource(final CommandSource source) {
        return this.source == source
            ? this
            : new CommandSourceStack(
                source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    public CommandSourceStack withEntity(final Entity entity) {
        return this.entity == entity
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                entity.getPlainTextName(),
                entity.getDisplayName(),
                this.server,
                entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    public CommandSourceStack withPosition(final Vec3 pos) {
        return this.worldPosition.equals(pos)
            ? this
            : new CommandSourceStack(
                this.source,
                pos,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    // Paper start - Expose 'with' functions from the CommandSourceStack
    @Override
    public CommandSourceStack withLocation(org.bukkit.Location location) {
        return this.getLocation().equals(location)
            ? this
            : new CommandSourceStack(
            this.source,
            new Vec3(location.x(), location.y(), location.z()),
            new Vec2(location.getPitch(), location.getYaw()),
            ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle(),
            this.permissions,
            this.textName,
            this.displayName,
            this.server,
            this.entity,
            this.silent,
            this.resultCallback,
            this.anchor,
            this.signingContext,
            this.chatMessageChainer
        );
    }
    // Paper end - Expose 'with' functions from the CommandSourceStack

    public CommandSourceStack withRotation(final Vec2 rotation) {
        return this.rotation.equals(rotation)
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    @Override
    public CommandSourceStack withCallback(final CommandResultCallback resultCallback) {
        return Objects.equals(this.resultCallback, resultCallback)
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    public CommandSourceStack withCallback(final CommandResultCallback newCallback, final BinaryOperator<CommandResultCallback> combiner) {
        CommandResultCallback newCompositeCallback = combiner.apply(this.resultCallback, newCallback);
        return this.withCallback(newCompositeCallback);
    }

    public CommandSourceStack withSuppressedOutput() {
        return !this.silent && !this.source.alwaysAccepts()
            ? new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                true,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            )
            : this;
    }

    public CommandSourceStack withPermission(final PermissionSet permissions) {
        return permissions == this.permissions
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    public CommandSourceStack withMaximumPermission(final PermissionSet newPermissions) {
        return this.withPermission(this.permissions.union(newPermissions));
    }

    public CommandSourceStack withAnchor(final EntityAnchorArgument.Anchor anchor) {
        return anchor == this.anchor
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                anchor,
                this.signingContext,
                this.chatMessageChainer
            );
    }

    public CommandSourceStack withLevel(final ServerLevel level) {
        if (level == this.level) {
            return this;
        }

        double scale = DimensionType.getTeleportationScale(this.level.dimensionType(), level.dimensionType());
        Vec3 pos = new Vec3(this.worldPosition.x * scale, this.worldPosition.y, this.worldPosition.z * scale);
        return new CommandSourceStack(
            this.source,
            pos,
            this.rotation,
            level,
            this.permissions,
            this.textName,
            this.displayName,
            this.server,
            this.entity,
            this.silent,
            this.resultCallback,
            this.anchor,
            this.signingContext,
            this.chatMessageChainer
        );
    }

    public CommandSourceStack facing(final Entity entity, final EntityAnchorArgument.Anchor anchor) {
        return this.facing(anchor.apply(entity));
    }

    public CommandSourceStack facing(final Vec3 pos) {
        Vec3 from = this.anchor.apply(this);
        double xd = pos.x - from.x;
        double yd = pos.y - from.y;
        double zd = pos.z - from.z;
        double sd = Math.sqrt(xd * xd + zd * zd);
        float xRot = Mth.wrapDegrees((float)(-(Mth.atan2(yd, sd) * 180.0F / (float)Math.PI)));
        float yRot = Mth.wrapDegrees((float)(Mth.atan2(zd, xd) * 180.0F / (float)Math.PI) - 90.0F);
        return this.withRotation(new Vec2(xRot, yRot));
    }

    public CommandSourceStack withSigningContext(final CommandSigningContext signingContext, final TaskChainer chatMessageChainer) {
        return signingContext == this.signingContext && chatMessageChainer == this.chatMessageChainer
            ? this
            : new CommandSourceStack(
                this.source,
                this.worldPosition,
                this.rotation,
                this.level,
                this.permissions,
                this.textName,
                this.displayName,
                this.server,
                this.entity,
                this.silent,
                this.resultCallback,
                this.anchor,
                signingContext,
                chatMessageChainer
            );
    }

    public Component getDisplayName() {
        return this.displayName;
    }

    public String getTextName() {
        return this.textName;
    }

    @Override
    public PermissionSet permissions() {
        return this.permissions;
    }

    // Paper start - Fix permission levels for command blocks
    private boolean forceRespectPermissionLevel() {
        return this.source == CommandSource.NULL || (this.source instanceof final net.minecraft.world.level.BaseCommandBlock commandBlock && commandBlock.getLevel().paperConfig().commandBlocks.forceFollowPermLevel);
    }
    // Paper end - Fix permission levels for command blocks

    // CraftBukkit start
    public boolean hasPermission(net.minecraft.server.permissions.Permission permission, String bukkitPermission) {
        // Paper start - Fix permission levels for command blocks
        final java.util.function.BooleanSupplier hasBukkitPerm = () -> this.source == CommandSource.NULL /*treat NULL as having all bukkit perms*/ || this.getBukkitSender().hasPermission(bukkitPermission); // lazily check bukkit perms to the benefit of custom permission setups
        // if the server is null, we must check the vanilla perm level system
        // if ignoreVanillaPermissions is true, we can skip vanilla perms and just run the bukkit perm check
        //noinspection ConstantValue
        if (this.getServer() == null || !this.getServer().server.ignoreVanillaPermissions) { // server & level are null for command function loading
            final boolean hasPermLevel = this.permissions.hasPermission(permission);
            if (this.forceRespectPermissionLevel()) { // NULL CommandSource and command blocks (if setting is enabled) should always pass the vanilla perm check
                return hasPermLevel && hasBukkitPerm.getAsBoolean();
            } else { // otherwise check vanilla perm first then bukkit perm, matching upstream behavior
                return hasPermLevel || hasBukkitPerm.getAsBoolean();
            }
        }
        return hasBukkitPerm.getAsBoolean();
        // Paper end - Fix permission levels for command blocks
    }
    // CraftBukkit end

    public Vec3 getPosition() {
        return this.worldPosition;
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    public @Nullable Entity getEntity() {
        return this.entity;
    }

    public Entity getEntityOrException() throws CommandSyntaxException {
        if (this.entity == null) {
            throw ERROR_NOT_ENTITY.create();
        } else {
            return this.entity;
        }
    }

    public ServerPlayer getPlayerOrException() throws CommandSyntaxException {
        if (this.entity instanceof ServerPlayer player) {
            return player;
        } else {
            throw ERROR_NOT_PLAYER.create();
        }
    }

    public @Nullable ServerPlayer getPlayer() {
        return this.entity instanceof ServerPlayer player ? player : null;
    }

    public boolean isPlayer() {
        return this.entity instanceof ServerPlayer;
    }

    public Vec2 getRotation() {
        return this.rotation;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public EntityAnchorArgument.Anchor getAnchor() {
        return this.anchor;
    }

    public CommandSigningContext getSigningContext() {
        return this.signingContext;
    }

    public TaskChainer getChatMessageChainer() {
        return this.chatMessageChainer;
    }

    public boolean shouldFilterMessageTo(final ServerPlayer receiver) {
        ServerPlayer player = this.getPlayer();
        return receiver != player && (player != null && player.isTextFilteringEnabled() || receiver.isTextFilteringEnabled());
    }

    public void sendChatMessage(final OutgoingChatMessage message, final boolean filtered, final ChatType.Bound chatType) {
        if (!this.silent) {
            ServerPlayer player = this.getPlayer();
            if (player != null) {
                player.sendChatMessage(message, filtered, chatType);
            } else {
                this.source.sendSystemMessage(chatType.decorate(message.content()));
            }
        }
    }

    public void sendSystemMessage(final Component message) {
        if (!this.silent) {
            ServerPlayer player = this.getPlayer();
            if (player != null) {
                player.sendSystemMessage(message);
            } else {
                this.source.sendSystemMessage(message);
            }
        }
    }

    public void sendSuccess(final Supplier<Component> messageSupplier, final boolean broadcast) {
        boolean shouldSendSystemMessage = this.source.acceptsSuccess() && !this.silent;
        boolean shouldBroadcast = broadcast && this.source.shouldInformAdmins() && !this.silent;
        if (shouldSendSystemMessage || shouldBroadcast) {
            Component message = messageSupplier.get();
            if (shouldSendSystemMessage) {
                this.source.sendSystemMessage(message);
            }

            if (shouldBroadcast) {
                this.broadcastToAdmins(message);
            }
        }
    }

    private void broadcastToAdmins(final Component message) {
        Component broadcast = Component.translatable("chat.type.admin", this.getDisplayName(), message).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        GameRules gameRules = this.level.getGameRules();
        if (gameRules.get(GameRules.SEND_COMMAND_FEEDBACK)) {
            for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
                if (player.commandSource() != this.source && player.getBukkitEntity().hasPermission("minecraft.admin.command_feedback")) { // CraftBukkit
                    player.sendSystemMessage(broadcast);
                }
            }
        }

        if (this.source != this.server && gameRules.get(GameRules.LOG_ADMIN_COMMANDS) && (!org.spigotmc.SpigotConfig.silentCommandBlocks || !(this.source instanceof net.minecraft.world.level.BaseCommandBlock.CloseableCommandBlockSource))) { // Spigot // Paper - Fix spigot config silentCommandBlocks filtering player commands
            this.server.sendSystemMessage(broadcast);
        }
    }

    public void sendFailure(final Component message) {
        // Paper start - Add UnknownCommandEvent
        this.sendFailure(message, true);
    }
    public void sendFailure(final Component message, final boolean withStyle) {
        // Paper end - Add UnknownCommandEvent
        if (this.source.acceptsFailure() && !this.silent) {
            this.source.sendSystemMessage(withStyle ? Component.empty().append(message).withStyle(ChatFormatting.RED) : message); // Paper - Add UnknownCommandEvent
        }
    }

    @Override
    public CommandResultCallback callback() {
        return this.resultCallback;
    }

    @Override
    public Collection<String> getOnlinePlayerNames() {
        return this.entity instanceof ServerPlayer sourcePlayer && !sourcePlayer.getBukkitEntity().hasPermission("paper.bypass-visibility.tab-completion") ? this.getServer().getPlayerList().getPlayers().stream().filter(serverPlayer -> sourcePlayer.getBukkitEntity().canSee(serverPlayer.getBukkitEntity())).map(serverPlayer -> serverPlayer.getGameProfile().name()).toList() : Lists.newArrayList(this.server.getPlayerNames()); // Paper - Make CommandSourceStack respect hidden players
    }

    @Override
    public Collection<String> getAllTeams() {
        return this.server.getScoreboard().getTeamNames();
    }

    @Override
    public Stream<Identifier> getAvailableSounds() {
        return BuiltInRegistries.SOUND_EVENT.stream().map(SoundEvent::location);
    }

    @Override
    public CompletableFuture<Suggestions> customSuggestion(final CommandContext<?> context) {
        return Suggestions.empty();
    }

    @Override
    public CompletableFuture<Suggestions> suggestRegistryElements(
        final ResourceKey<? extends Registry<?>> key,
        final SharedSuggestionProvider.ElementSuggestionType elements,
        final SuggestionsBuilder builder,
        final CommandContext<?> context
    ) {
        if (key == Registries.RECIPE) {
            return SharedSuggestionProvider.suggestResource(this.server.getRecipeManager().getRecipes().stream().map(e -> e.id().identifier()), builder);
        } else if (key == Registries.ADVANCEMENT) {
            Collection<AdvancementHolder> advancements = this.server.getAdvancements().getAllAdvancements();
            return SharedSuggestionProvider.suggestResource(advancements.stream().map(AdvancementHolder::id), builder);
        } else {
            return this.getLookup(key).map(registry -> {
                this.suggestRegistryElements((HolderLookup<?>)registry, elements, builder);
                return builder.buildFuture();
            }).orElseGet(Suggestions::empty);
        }
    }

    private Optional<? extends HolderLookup<?>> getLookup(final ResourceKey<? extends Registry<?>> key) {
        Optional<? extends Registry<?>> lookup = this.registryAccess().lookup(key);
        return lookup.isPresent() ? lookup : this.server.reloadableRegistries().lookup().lookup(key);
    }

    @Override
    public Set<ResourceKey<Level>> levels() {
        return this.server.levelKeys();
    }

    @Override
    public RegistryAccess registryAccess() {
        return this.server.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.level.enabledFeatures();
    }

    @Override
    public CommandDispatcher<CommandSourceStack> dispatcher() {
        return this.getServer().getFunctions().getDispatcher();
    }

    @Override
    public void handleError(final CommandExceptionType type, final Message message, final boolean forked, final @Nullable TraceCallbacks tracer) {
        if (tracer != null) {
            tracer.onError(message.getString());
        }

        if (!forked) {
            this.sendFailure(ComponentUtils.fromMessage(message));
        }
    }

    @Override
    public boolean isSilent() {
        return this.silent;
    }

    // Paper start
    @Override
    public CommandSourceStack getHandle() {
        return this;
    }
    // Paper end
    // CraftBukkit start
    public org.bukkit.command.CommandSender getBukkitSender() {
        return this.source.getBukkitSender(this);
    }
    // CraftBukkit end
}

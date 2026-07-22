package net.minecraft.world.level.block.entity;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SignBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TEXT_LINE_WIDTH = 90;
    private static final int TEXT_LINE_HEIGHT = 10;
    private static final boolean DEFAULT_IS_WAXED = false;
    private @Nullable UUID playerWhoMayEdit;
    private SignText frontText;
    private SignText backText;
    private boolean isWaxed = false;

    public SignBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        this(BlockEntityTypes.SIGN, worldPosition, blockState);
    }

    public SignBlockEntity(final BlockEntityType<? extends SignBlockEntity> type, final BlockPos worldPosition, final BlockState blockState) {
        super(type, worldPosition, blockState);
        this.frontText = this.createDefaultSignText();
        this.backText = this.createDefaultSignText();
    }

    protected SignText createDefaultSignText() {
        return new SignText();
    }

    public boolean isFacingFrontText(final Player player) {
        // Paper start - More Sign Block API
        return this.isFacingFrontText(player.getX(), player.getZ());
    }
    public boolean isFacingFrontText(final double x, final double z) {
        // Paper end - More Sign Block API
        if (this.getBlockState().getBlock() instanceof SignBlock sign) {
            Vec3 signPositionOffset = sign.getSignHitboxCenterPosition(this.getBlockState());
            double xd = x - (this.getBlockPos().getX() + signPositionOffset.x); // Paper - More Sign Block API
            double zd = z - (this.getBlockPos().getZ() + signPositionOffset.z); // Paper - More Sign Block API
            float signYRot = sign.getYRotationDegrees(this.getBlockState());
            float playerYRot = (float)(Mth.atan2(zd, xd) * 180.0F / (float)Math.PI) - 90.0F;
            return Mth.degreesDifferenceAbs(signYRot, playerYRot) <= 90.0F;
        } else {
            return false;
        }
    }

    public SignText getText(final boolean isFrontText) {
        return isFrontText ? this.frontText : this.backText;
    }

    public SignText getFrontText() {
        return this.frontText;
    }

    public SignText getBackText() {
        return this.backText;
    }

    public int getTextLineHeight() {
        return 10;
    }

    public int getMaxTextLineWidth() {
        return 90;
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        output.store("front_text", SignText.DIRECT_CODEC, this.frontText);
        output.store("back_text", SignText.DIRECT_CODEC, this.backText);
        output.putBoolean("is_waxed", this.isWaxed);
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.frontText = input.read("front_text", SignText.DIRECT_CODEC).map(this::loadLines).orElseGet(SignText::new);
        this.backText = input.read("back_text", SignText.DIRECT_CODEC).map(this::loadLines).orElseGet(SignText::new);
        this.isWaxed = input.getBooleanOr("is_waxed", false);
    }

    private SignText loadLines(SignText data) {
        for (int i = 0; i < 4; i++) {
            Component unfilteredMessage = this.loadLine(data.getMessage(i, false));
            Component filteredMessage = this.loadLine(data.getMessage(i, true));
            data = data.setMessage(i, unfilteredMessage, filteredMessage);
        }

        return data;
    }

    private Component loadLine(final Component component) {
        if (this.level instanceof ServerLevel serverLevel) {
            try {
                return ComponentUtils.resolve(ResolutionContext.create(createCommandSourceStack(null, serverLevel, this.worldPosition)), component);
            } catch (CommandSyntaxException var4) {
            }
        }

        return component;
    }

    public void updateSignText(final Player player, final boolean frontText, final List<FilteredText> lines) {
        if (!this.isWaxed() && player.getUUID().equals(this.getPlayerWhoMayEdit()) && this.level != null) {
            this.updateText(text -> this.setMessages(player, lines, text, frontText), frontText); // CraftBukkit
            this.setAllowedPlayerEditor(null);
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL);
        } else {
            LOGGER.warn("Player {} just tried to change non-editable sign", player.getPlainTextName());
            if (player.distanceToSqr(this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ()) < Mth.square(32)) // Paper - Don't send far away sign update
            ((net.minecraft.server.level.ServerPlayer) player).connection.send(this.getUpdatePacket()); // CraftBukkit
        }
    }

    public boolean updateText(final UnaryOperator<SignText> function, final boolean isFrontText) {
        SignText text = this.getText(isFrontText);
        return this.setText(function.apply(text), isFrontText);
    }

    private SignText setMessages(final Player player, final List<FilteredText> lines, SignText text, final boolean front) { // CraftBukkit
        SignText originalText = text; // CraftBukkit
        for (int i = 0; i < lines.size(); i++) {
            FilteredText line = lines.get(i);
            Style currentTextStyle = text.getMessage(i, player.isTextFilteringEnabled()).getStyle();
            if (player.isTextFilteringEnabled()) {
                text = text.setMessage(i, Component.literal(net.minecraft.util.StringUtil.filterText(line.filteredOrEmpty())).setStyle(currentTextStyle)); // Paper - filter sign text to chat only
            } else {
                text = text.setMessage(
                    i, Component.literal(line.raw()).setStyle(currentTextStyle), Component.literal(net.minecraft.util.StringUtil.filterText(line.filteredOrEmpty())).setStyle(currentTextStyle) // Paper - filter sign text to chat only
                );
            }
        }

        // CraftBukkit start
        org.bukkit.entity.Player apiPlayer = ((net.minecraft.server.level.ServerPlayer) player).getBukkitEntity();
        List<net.kyori.adventure.text.Component> componentLines = new java.util.ArrayList<>(); // Paper - adventure

        for (int i = 0; i < lines.size(); ++i) {
            componentLines.add(io.papermc.paper.adventure.PaperAdventure.asAdventure(text.getMessage(i, player.isTextFilteringEnabled()))); // Paper - Adventure
        }

        org.bukkit.event.block.SignChangeEvent event = new org.bukkit.event.block.SignChangeEvent(org.bukkit.craftbukkit.block.CraftBlock.at(this.level, this.worldPosition), apiPlayer, new java.util.ArrayList<>(componentLines), (front) ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK); // Paper - Adventure
        if (!event.callEvent()) {
            return originalText;
        }

        Component[] components = org.bukkit.craftbukkit.block.CraftSign.sanitizeLines(event.lines()); // Paper - Adventure
        for (int i = 0; i < components.length; i++) {
            if (!java.util.Objects.equals(componentLines.get(i), event.line(i))) { // Paper - Adventure
                text = text.setMessage(i, components[i]);
            }
        }
        // CraftBukkit end

        return text;
    }

    public boolean setText(final SignText text, final boolean isFrontText) {
        return isFrontText ? this.setFrontText(text) : this.setBackText(text);
    }

    private boolean setBackText(final SignText text) {
        if (text != this.backText) {
            this.backText = text;
            this.markUpdated();
            return true;
        } else {
            return false;
        }
    }

    private boolean setFrontText(final SignText text) {
        if (text != this.frontText) {
            this.frontText = text;
            this.markUpdated();
            return true;
        } else {
            return false;
        }
    }

    public boolean canExecuteClickCommands(final boolean isFrontText, final Player player) {
        return this.isWaxed() && this.getText(isFrontText).hasAnyClickCommands(player);
    }

    public boolean executeClickCommandsIfPresent(final ServerLevel level, final Player player, final BlockPos pos, final boolean isFrontText) {
        boolean hasAnyClickCommand = false;

        for (Component message : this.getText(isFrontText).getMessages(player.isTextFilteringEnabled())) {
            Style style = message.getStyle();
            ClickEvent event = style.getClickEvent();
            switch (event) {
                case ClickEvent.RunCommand command:
                    // Paper start - Fix commands from signs not firing command events
                    String commandLine = command.command().startsWith("/") ? command.command() : "/" + command.command();
                    if (org.spigotmc.SpigotConfig.logCommands) {
                        LOGGER.info("{} issued server command: {}", player.getScoreboardName(), commandLine);
                    }
                    final io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent bukkitEvent = new io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent(
                        (org.bukkit.entity.Player) player.getBukkitEntity(),
                        commandLine,
                        new org.bukkit.craftbukkit.util.LazyPlayerSet(level.getServer()),
                        (org.bukkit.block.Sign) org.bukkit.craftbukkit.block.CraftBlock.at(this.level, this.worldPosition).getState(),
                        isFrontText ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK
                    );
                    if (!bukkitEvent.callEvent()) {
                        return false;
                    }
                    level.getServer().getCommands().performPrefixedCommand(createCommandSourceStack(((org.bukkit.craftbukkit.entity.CraftPlayer) bukkitEvent.getPlayer()).getHandle(), level, pos), bukkitEvent.getMessage());
                    // Paper end - Fix commands from signs not firing command events
                    hasAnyClickCommand = true;
                    break;
                case ClickEvent.ShowDialog dialog:
                    player.openDialog(dialog.dialog());
                    hasAnyClickCommand = true;
                    break;
                case ClickEvent.Custom custom:
                    level.getServer().handleCustomClickAction(custom.id(), custom.payload());
                    hasAnyClickCommand = true;
                    break;
                case null:
                default:
            }
        }

        return hasAnyClickCommand;
    }

    // CraftBukkit start
    private final CommandSource commandSource = new CommandSource() {

        @Override
        public void sendSystemMessage(Component message) {}

        @Override
        public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack commandSourceStack) {
            return commandSourceStack.getEntity() != null ? commandSourceStack.getEntity().getBukkitEntity() : new org.bukkit.craftbukkit.command.CraftBlockCommandSender(commandSourceStack, SignBlockEntity.this);
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };

    private CommandSourceStack createCommandSourceStack(final @Nullable Player player, final ServerLevel level, final BlockPos pos) {
        // CraftBukkit end
        String textName = player == null ? "Sign" : player.getPlainTextName();
        Component displayName = player == null ? Component.literal("Sign") : player.getDisplayName();
        // Paper start - Fix commands from signs not firing command events
        CommandSource commandSource = level.paperConfig().misc.showSignClickCommandFailureMsgsToPlayer ? new io.papermc.paper.commands.DelegatingCommandSource(this.commandSource) {
            @Override
            public void sendSystemMessage(Component message) {
                if (player instanceof final net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(message);
                }
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }
        } : this.commandSource;
        // Paper end - Fix commands from signs not firing command events
        // CraftBukkit - this
        return new CommandSourceStack(
            commandSource, Vec3.atCenterOf(pos), Vec2.ZERO, level, LevelBasedPermissionSet.GAMEMASTER, textName, displayName, level.getServer(), player // Paper - Fix commands from signs not firing command events
        );
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    public void setAllowedPlayerEditor(final @Nullable UUID playerUUID) {
        this.playerWhoMayEdit = playerUUID;
    }

    public @Nullable UUID getPlayerWhoMayEdit() {
        // CraftBukkit start - unnecessary sign ticking removed, so do this lazily
        if (this.level != null && this.playerWhoMayEdit != null) {
            this.clearInvalidPlayerWhoMayEdit(this, this.level, this.playerWhoMayEdit);
        }
        // CraftBukkit end
        return this.playerWhoMayEdit;
    }

    private void markUpdated() {
        this.setChanged();
        if (this.level != null) this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL); // CraftBukkit - skip notify if world is null (SPIGOT-5122)
    }

    public boolean isWaxed() {
        return this.isWaxed;
    }

    public boolean setWaxed(final boolean isWaxed) {
        if (this.isWaxed != isWaxed) {
            this.isWaxed = isWaxed;
            this.markUpdated();
            return true;
        } else {
            return false;
        }
    }

    public boolean playerIsTooFarAwayToEdit(final UUID player) {
        Player editingPlayer = this.level.getPlayerByUUID(player);
        return editingPlayer == null || !editingPlayer.isWithinBlockInteractionRange(this.getBlockPos(), 4.0);
    }

    public static void tick(final Level level, final BlockPos blockPos, final BlockState blockState, final SignBlockEntity signBlockEntity) {
        UUID playerWhoMayEdit = signBlockEntity.getPlayerWhoMayEdit();
        if (playerWhoMayEdit != null) {
            signBlockEntity.clearInvalidPlayerWhoMayEdit(signBlockEntity, level, playerWhoMayEdit);
        }
    }

    private void clearInvalidPlayerWhoMayEdit(final SignBlockEntity signBlockEntity, final Level level, final UUID playerWhoMayEdit) {
        if (signBlockEntity.playerIsTooFarAwayToEdit(playerWhoMayEdit)) {
            signBlockEntity.setAllowedPlayerEditor(null);
        }
    }

    public SoundEvent getSignInteractionFailedSoundEvent() {
        return SoundEvents.WAXED_SIGN_INTERACT_FAIL;
    }
}

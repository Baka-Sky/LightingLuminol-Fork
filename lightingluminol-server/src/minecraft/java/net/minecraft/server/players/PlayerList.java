package net.minecraft.server.players;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.notifications.NotificationService;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.FileUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class PlayerList {
    public static final File USERBANLIST_FILE = new File("banned-players.json");
    public static final File IPBANLIST_FILE = new File("banned-ips.json");
    public static final File OPLIST_FILE = new File("ops.json");
    public static final File WHITELIST_FILE = new File("whitelist.json");
    public static final Component CHAT_FILTERED_FULL = Component.translatable("chat.filtered_full");
    public static final Component DUPLICATE_LOGIN_DISCONNECT_MESSAGE = Component.translatable("multiplayer.disconnect.duplicate_login");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SEND_PLAYER_INFO_INTERVAL = 600;
    private static final ThreadLocal<SimpleDateFormat> BAN_DATE_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z", Locale.ROOT)); // Folia - region threading - SDF is not thread-safe
    private final MinecraftServer server;
    private final List<ServerPlayer> players = new java.util.concurrent.CopyOnWriteArrayList(); // CraftBukkit - ArrayList -> CopyOnWriteArrayList: Iterator safety
    private final Map<UUID, ServerPlayer> playersByUUID = new java.util.concurrent.ConcurrentHashMap<>(); // Folia - region threading - change to CHM - Note: we do NOT expect concurrency PER KEY!
    private final UserBanList bans;
    private final IpBanList ipBans;
    private final ServerOpList ops;
    private final UserWhiteList whitelist;
    // CraftBukkit start
    // private final Map<UUID, ServerStatsCounter> stats = Maps.newHashMap();
    // private final Map<UUID, PlayerAdvancements> advancements = Maps.newHashMap();
    // CraftBukkit end
    public final PlayerDataStorage playerIo;
    private final LayeredRegistryAccess<RegistryLayer> registries;
    private int viewDistance;
    private int simulationDistance;
    private boolean allowCommandsForAllPlayers;
    private int sendAllPlayerInfoIn;

    // CraftBukkit start
    private org.bukkit.craftbukkit.CraftServer cserver;
    private final Map<String,ServerPlayer> playersByName = new java.util.HashMap<>();
    public @Nullable String collideRuleTeamName; // Paper - Configurable player collision

    // Folia start - region threading
    private final Object connectionsStateLock = new Object();
    private final Map<String, Connection> connectionByName = new java.util.HashMap<>();
    private final Map<UUID, Connection> connectionById = new java.util.HashMap<>();
    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Connection> usersCountedAgainstLimit = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

    public boolean pushPendingJoin(String userName, UUID byId, Connection conn) {
        userName = userName.toLowerCase(java.util.Locale.ROOT);
        Connection conflictingName, conflictingId;
        synchronized (this.connectionsStateLock) {
            conflictingName = this.connectionByName.get(userName);
            conflictingId = this.connectionById.get(byId);

            if (conflictingName == null && conflictingId == null) {
                // Folia start - max concurrent login
                int loggedInCount = 0;
                for (Connection value : this.connectionById.values()) {
                    if (value.getPacketListener() instanceof ServerGamePacketListenerImpl) {
                        ++loggedInCount;
                    }
                }
                if ((this.connectionById.size() - loggedInCount) >= io.papermc.paper.configuration.GlobalConfiguration.get().misc.maxJoinsPerTick) {
                    return false;
                }
                // Folia end - max concurrent login
                this.connectionByName.put(userName, conn);
                this.connectionById.put(byId, conn);
            }
        }

        Component message = Component.translatable("multiplayer.disconnect.duplicate_login", new Object[0]);

        if (conflictingId != null || conflictingName != null) {
            if (conflictingName != null && conflictingName.isPlayerConnected()) {
                conflictingName.disconnectSafely(message, io.papermc.paper.connection.DisconnectionReason.DUPLICATE_LOGIN_MESSAGE);
            }
            if (conflictingName != conflictingId && conflictingId != null && conflictingId.isPlayerConnected()) {
                conflictingId.disconnectSafely(message, io.papermc.paper.connection.DisconnectionReason.DUPLICATE_LOGIN_MESSAGE);
            }
        }

        return conflictingName == null && conflictingId == null;
    }

    public void removeConnection(String userName, UUID byId, Connection conn) {
        userName = userName.toLowerCase(java.util.Locale.ROOT);
        synchronized (this.connectionsStateLock) {
            this.connectionByName.remove(userName, conn);
            this.connectionById.remove(byId, conn);
            this.usersCountedAgainstLimit.remove(conn);
        }
    }

    private boolean countConnection(Connection conn, int limit) {
        synchronized (this.connectionsStateLock) {
            int count = this.usersCountedAgainstLimit.size();
            if (count >= limit) {
                return false;
            }
            this.usersCountedAgainstLimit.add(conn);
            return true;
        }
    }
    // Folia end - region threading

    public PlayerList(
        final MinecraftServer server,
        final LayeredRegistryAccess<RegistryLayer> registries,
        final PlayerDataStorage playerIo,
        final NotificationService notificationService
    ) {
        this.cserver = server.server = new org.bukkit.craftbukkit.CraftServer((net.minecraft.server.dedicated.DedicatedServer) server, this);
        server.console = new com.destroystokyo.paper.console.TerminalConsoleCommandSender(); // Paper
        // CraftBukkit end
        this.server = server;
        this.registries = registries;
        this.playerIo = playerIo;
        this.whitelist = new UserWhiteList(WHITELIST_FILE, notificationService);
        this.ops = new ServerOpList(OPLIST_FILE, notificationService);
        this.bans = new UserBanList(USERBANLIST_FILE, notificationService);
        this.ipBans = new IpBanList(IPBANLIST_FILE, notificationService);
    }

    abstract public void loadAndSaveFiles(); // Paper - fix converting txt to json file; moved from DedicatedPlayerList constructor

    public void placeNewPlayer(final Connection connection, final ServerPlayer player, final CommonListenerCookie cookie) {
        player.isRealPlayer = true; // Paper
        player.loginTime = System.currentTimeMillis(); // Paper - Replace OfflinePlayer#getLastPlayed
        NameAndId gameProfile = player.nameAndId();
        UserNameToIdResolver profileCache = this.server.services().nameToIdCache();
        Optional<NameAndId> oldProfile = profileCache.get(gameProfile.id());
        String oldName = oldProfile.map(NameAndId::name).orElse(gameProfile.name());
        if (player.lastKnownName != null) { oldName = player.lastKnownName; player.lastKnownName = null; } // CraftBukkit - Better rename detection
        profileCache.add(gameProfile);
        ServerLevel level = player.level();
        String address = connection.getLoggableAddress(this.server.logIPs());
        LevelData levelData = level.getLevelData();
        ServerGamePacketListenerImpl playerConnection = new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
        // Folia start - rewrite login process
        // only after setting the connection listener to game type, add the connection to this regions list
        level.getCurrentWorldData().connections.add(connection);
        // Folia end - rewrite login process
        connection.setupInboundProtocol(
            GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess()), playerConnection), playerConnection
        );
        playerConnection.suspendFlushing();
        GameRules gameRules = level.getGameRules();
        boolean immediateRespawn = gameRules.get(GameRules.IMMEDIATE_RESPAWN);
        boolean reducedDebugInfo = gameRules.get(GameRules.REDUCED_DEBUG_INFO);
        boolean doLimitedCrafting = gameRules.get(GameRules.LIMITED_CRAFTING);
        playerConnection.send(
            new ClientboundLoginPacket(
                player.getId(),
                levelData.isHardcore(),
                this.server.levelKeys(),
                this.getMaxPlayers(),
                io.papermc.paper.FeatureHooks.getViewDistance(level), // Paper - view distance
                io.papermc.paper.FeatureHooks.getSimulationDistance(level), // Paper - simulation distance
                reducedDebugInfo,
                !immediateRespawn,
                doLimitedCrafting,
                player.createCommonSpawnInfo(level),
                this.server.usesAuthentication(),
                this.server.enforceSecureProfile()
            )
        );
        player.getBukkitEntity().sendSupportedChannels(); // CraftBukkit
        playerConnection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        playerConnection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        playerConnection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        RecipeManager recipeManager = this.server.getRecipeManager();
        playerConnection.send(
            new ClientboundUpdateRecipesPacket(recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes())
        );
        this.sendPlayerPermissionLevel(player);
        player.getStats().markAllDirty();
        player.getRecipeBook().sendInitialRecipeBook(player);
        //this.updateEntireScoreboard(level.getScoreboard(), player); // Folia - region threading
        this.server.invalidateStatus();
        MutableComponent component;
        if (player.getGameProfile().name().equalsIgnoreCase(oldName)) {
            component = Component.translatable("multiplayer.player.joined", player.getDisplayName());
        } else {
            component = Component.translatable("multiplayer.player.joined.renamed", player.getDisplayName(), oldName);
        }

        // CraftBukkit start
        component.withStyle(ChatFormatting.YELLOW);
        Component joinMessage = component; // Paper - Adventure
        playerConnection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        ServerStatus status = this.server.getStatus();
        if (status != null && !cookie.transferred()) {
            player.sendServerStatus(status);
        }

        // player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(this.players)); // CraftBukkit - replaced with loop below
        this.players.add(player);
        this.playersByName.put(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT), player); // Spigot
        this.playersByUUID.put(player.getUUID(), player);
        // this.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player))); // CraftBukkit - replaced with loop below
        // Paper start - Fire PlayerJoinEvent when Player is actually ready; correctly register player BEFORE PlayerJoinEvent, so the entity is valid and doesn't require tick delay hacks
        player.suppressTrackerForLogin = true;
        this.sendLevelInfo(player, level);
        level.addNewPlayer(player);
        this.server.getCustomBossEvents().onPlayerConnect(player); // see commented out section below serverLevel.addPlayerJoin(player);
        // Paper end - Fire PlayerJoinEvent when Player is actually ready
        player.initInventoryMenu();
        // CraftBukkit start
        org.bukkit.craftbukkit.entity.CraftPlayer bukkitPlayer = player.getBukkitEntity();

        // Ensure that player inventory is populated with its viewer
        player.containerMenu.transferTo(player.containerMenu, bukkitPlayer);

        org.bukkit.event.player.PlayerJoinEvent playerJoinEvent = new org.bukkit.event.player.PlayerJoinEvent(bukkitPlayer, io.papermc.paper.adventure.PaperAdventure.asAdventure(component)); // Paper - Adventure
        this.cserver.getPluginManager().callEvent(playerJoinEvent);

        if (!player.connection.isAcceptingMessages()) {
            //return; // Folia - region threading - must still allow the player to connect, as we must add to chunk map before handling disconnect
        }

        final net.kyori.adventure.text.Component jm = playerJoinEvent.joinMessage();

        if (jm != null && !jm.equals(net.kyori.adventure.text.Component.empty())) { // Paper - Adventure
            joinMessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(jm); // Paper - Adventure
            this.server.getPlayerList().broadcastSystemMessage(joinMessage, false); // Paper - Adventure
        }
        // CraftBukkit end

        // CraftBukkit start - sendAll above replaced with this loop
        ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)); // Paper - Add Listing API for Player

        final List<ServerPlayer> onlinePlayers = Lists.newArrayListWithExpectedSize(this.players.size() - 1); // Paper - Use single player info update packet on join
        for (ServerPlayer entityplayer1 : this.players) { // Folia - region threading

            if (entityplayer1.getBukkitEntity().canSee(bukkitPlayer)) {
                // Paper start - Add Listing API for Player
                if (entityplayer1.getBukkitEntity().isListed(bukkitPlayer)) {
                    // Paper end - Add Listing API for Player
                    entityplayer1.connection.send(packet);
                    // Paper start - Add Listing API for Player
                } else {
                    entityplayer1.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(player, false));
                }
                // Paper end - Add Listing API for Player
            }

            if (entityplayer1 == player || !bukkitPlayer.canSee(entityplayer1.getBukkitEntity())) { // Paper - Use single player info update packet on join; Don't include joining player
                continue;
            }

            onlinePlayers.add(entityplayer1); // Paper - Use single player info update packet on join
        }
        // Paper start - Use single player info update packet on join
        if (!onlinePlayers.isEmpty()) {
            player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(onlinePlayers, player)); // Paper - Add Listing API for Player
        }
        // Paper end - Use single player info update packet on join
        player.sentListPacket = true;
        player.suppressTrackerForLogin = false; // Paper - Fire PlayerJoinEvent when Player is actually ready
        player.level().getChunkSource().addEntity(player); // Paper - Fire PlayerJoinEvent when Player is actually ready; track entity now
        // CraftBukkit end

        //player.refreshEntityData(player); // CraftBukkit - BungeeCord#2321, send complete data to self on spawn // Paper - THIS IS NOT NEEDED ANYMORE

        this.sendLevelInfo(player, level);

        // CraftBukkit start - Only add if the player wasn't moved in the event
        if (player.level() == level && !level.players().contains(player)) {
            level.addNewPlayer(player);
            this.server.getCustomBossEvents().onPlayerConnect(player);
        }

        level = player.level(); // CraftBukkit - Update in case join event changed it
        // CraftBukkit end
        this.sendActivePlayerEffects(player);
        // Paper - move loading pearls / parent vehicle up
        player.initInventoryMenu();
        this.server.notificationManager().playerJoined(player);
        playerConnection.resumeFlushing();
        // Paper start - Configurable player collision; Add to collideRule team if needed
        final net.minecraft.world.scores.Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
        final PlayerTeam collideRuleTeam = scoreboard.getPlayerTeam(this.collideRuleTeamName);
        if (false && this.collideRuleTeamName != null && collideRuleTeam != null && player.getTeam() == null) { // Folia - region threading
            scoreboard.addPlayerToTeam(player.getScoreboardName(), collideRuleTeam);
        }
        // Paper end - Configurable player collision
        // CraftBukkit start - moved down
        LOGGER.info(
            "{}[{}] logged in with entity id {} at ([{}]{}, {}, {})", // Paper - add world identifier
            player.getPlainTextName(),
            address,
            player.getId(),
            level.dimension().identifier(), // Paper - add world identifier
            player.getX(),
            player.getY(),
            player.getZ()
        );
        // CraftBukkit end - moved down
        // Paper start - Send empty chunk, so players aren't stuck in the world loading screen with our chunk system not sending chunks when dead
        if (player.isDeadOrDying()) {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> plains = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                new net.minecraft.world.level.chunk.EmptyLevelChunk(level, player.chunkPosition(), plains),
                level.getLightEngine(), (java.util.BitSet)null, (java.util.BitSet) null, true) // Paper - Anti-Xray
            );
        }
        // Paper end - Send empty chunk
    }

    public void updateEntireScoreboard(final ServerScoreboard scoreboard, final ServerPlayer player) {
        Set<Objective> objectives = Sets.newHashSet();

        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            player.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }

        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null && !objectives.contains(objective)) {
                for (Packet<?> packet : scoreboard.getStartTrackingPackets(objective)) {
                    player.connection.send(packet);
                }

                objectives.add(objective);
            }
        }
    }

    // Paper start - virtual world border API
    private void broadcastWorldborder(Packet<?> packet, ResourceKey<Level> dimension) {
        for (ServerPlayer serverPlayer : this.players) {
            if (serverPlayer.level().dimension() == dimension && serverPlayer.getBukkitEntity().getWorldBorder() == null) {
                serverPlayer.connection.send(packet);
            }
        }
    }
    // Paper end - virtual world border API
    public void addWorldborderListener(final ServerLevel level) {
        level.getWorldBorder().addListener(new BorderChangeListener() {
            @Override
            public void onSetSize(final WorldBorder border, final double newSize) {
                PlayerList.this.broadcastWorldborder(new ClientboundSetBorderSizePacket(border), level.dimension()); // Paper - virtual world border API
            }

            @Override
            public void onLerpSize(final WorldBorder border, final double fromSize, final double targetSize, final long ticks, final long gameTime) {
                PlayerList.this.broadcastWorldborder(new ClientboundSetBorderLerpSizePacket(border), level.dimension()); // Paper - virtual world border API
            }

            @Override
            public void onSetCenter(final WorldBorder border, final double x, final double z) {
                PlayerList.this.broadcastWorldborder(new ClientboundSetBorderCenterPacket(border), level.dimension()); // Paper - virtual world border API
            }

            @Override
            public void onSetWarningTime(final WorldBorder border, final int time) {
                PlayerList.this.broadcastWorldborder(new ClientboundSetBorderWarningDelayPacket(border), level.dimension()); // Paper - virtual world border API
            }

            @Override
            public void onSetWarningBlocks(final WorldBorder border, final int blocks) {
                PlayerList.this.broadcastWorldborder(new ClientboundSetBorderWarningDistancePacket(border), level.dimension()); // Paper - virtual world border API
            }

            @Override
            public void onSetDamagePerBlock(final WorldBorder border, final double damagePerBlock) {
            }

            @Override
            public void onSetSafeZone(final WorldBorder border, final double safeZone) {
            }
        });
    }

    public Optional<CompoundTag> loadPlayerData(final NameAndId nameAndId) {
        UUID lastSingleplayerOwnerUUID = this.server.getWorldData().getSinglePlayerUUID();
        if (this.server.isSingleplayerOwner(nameAndId) && lastSingleplayerOwnerUUID != null) {
            LOGGER.debug("loading single player");
            return this.playerIo.load(new NameAndId(lastSingleplayerOwnerUUID, "<singleplayer owner>"));
        } else {
            return this.playerIo.load(nameAndId);
        }
    }

    protected void save(final ServerPlayer player) {
        if (!player.getBukkitEntity().isPersistent()) return; // CraftBukkit
        player.lastSave = System.nanoTime(); // Folia - region threading - changed to nanoTime tracking
        this.playerIo.save(player);
        ServerStatsCounter stats = player.getStats(); // CraftBukkit
        if (stats != null) {
            stats.save();
        }

        PlayerAdvancements advancements = player.getAdvancements(); // CraftBukkit
        if (advancements != null) {
            advancements.save();
        }
    }

    public net.kyori.adventure.text.@Nullable Component remove(final ServerPlayer player) { // CraftBukkit - return string // Paper - return Component
        // Paper start - Fix kick event leave message not being sent
        return this.remove(player, net.kyori.adventure.text.Component.translatable("multiplayer.player.left", net.kyori.adventure.text.format.NamedTextColor.YELLOW, io.papermc.paper.configuration.GlobalConfiguration.get().messages.useDisplayNameInQuitMessage ? player.getBukkitEntity().displayName() : io.papermc.paper.adventure.PaperAdventure.asAdventure(player.getDisplayName())));
    }
    public net.kyori.adventure.text.@Nullable Component remove(final ServerPlayer player, final net.kyori.adventure.text.Component leaveMessage) {
        // Paper end - Fix kick event leave message not being sent
        ServerLevel level = player.level();
        player.awardStat(Stats.LEAVE_GAME);
        // CraftBukkit start - Quitting must be before we do final save of data, in case plugins need to modify it
        // See SPIGOT-5799, SPIGOT-6145
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer(org.bukkit.event.inventory.InventoryCloseEvent.Reason.DISCONNECT); // Paper - Inventory close reason
        }

        org.bukkit.event.player.PlayerQuitEvent playerQuitEvent = new org.bukkit.event.player.PlayerQuitEvent(player.getBukkitEntity(), leaveMessage, player.quitReason); // Paper - Adventure & Add API for quit reason
        this.cserver.getPluginManager().callEvent(playerQuitEvent);
        player.getBukkitEntity().disconnect();

        // CraftBukkit end

        // Paper start - Configurable player collision; Remove from collideRule team if needed
        if (false && this.collideRuleTeamName != null) { // Folia - region threading
            final net.minecraft.world.scores.Scoreboard scoreBoard = this.server.getLevel(Level.OVERWORLD).getScoreboard();
            final PlayerTeam team = scoreBoard.getPlayersTeam(this.collideRuleTeamName);
            if (player.getTeam() == team && team != null) {
                scoreBoard.removePlayerFromTeam(player.getScoreboardName(), team);
            }
        }
        // Paper end - Configurable player collision

        // Paper - Drop carried item when player has disconnected
        if (!player.containerMenu.getCarried().isEmpty()) {
            net.minecraft.world.item.ItemStack carried = player.containerMenu.getCarried();
            player.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
            player.drop(carried, false);
        }
        // Paper end - Drop carried item when player has disconnected
        this.save(player);
        if (player.isPassenger()) {
            Entity vehicle = player.getRootVehicle();
            if (vehicle.hasExactlyOnePlayerPassenger()) {
                LOGGER.debug("Removing player mount");
                player.stopRiding();
                vehicle.getPassengersAndSelf().forEach(e -> {
                    // Paper start - Fix villager boat exploit
                    if (e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager) {
                        final net.minecraft.world.entity.player.Player human = villager.getTradingPlayer();
                        if (human != null) {
                            villager.setTradingPlayer(null);
                        }
                    }
                    // Paper end - Fix villager boat exploit
                    e.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLAYER_QUIT); // CraftBukkit - add Bukkit remove cause
                });
            }
        }

        player.unRide();

        for (ThrownEnderpearl enderpearl : player.getEnderPearls()) {
            // Paper start - Allow using old ender pearl behavior
            if (!enderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
                enderpearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLAYER_QUIT); // CraftBukkit - add Bukkit remove cause
            }
            // Paper end - Allow using old ender pearl behavior
        }

        level.removePlayerImmediately(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        player.retireScheduler(); // Paper - Folia schedulers
        player.getBukkitEntity().packetProcessor.close(); // Folia - region threading
        player.getAdvancements().clearTriggers();
        this.players.remove(player);
        this.playersByName.remove(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT)); // Spigot
        this.server.getCustomBossEvents().onPlayerDisconnect(player);
        UUID uuid = player.getUUID();
        ServerPlayer serverPlayer = this.playersByUUID.get(uuid);
        if (serverPlayer == player) {
            this.playersByUUID.remove(uuid);
            // CraftBukkit start
            // this.stats.remove(uuid);
            // this.advancements.remove(uuid);
            // CraftBukkit end
            this.server.notificationManager().playerLeft(player);
        }

        // CraftBukkit start
        // this.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
        for (ServerPlayer otherPlayer : this.players) { // Folia - region threading

            if (otherPlayer.getBukkitEntity().canSee(player.getBukkitEntity())) {
                otherPlayer.connection.send(packet);
            } else {
                otherPlayer.getBukkitEntity().onEntityRemove(player);
            }
        }
        // This removes the scoreboard (and player reference) for the specific player in the manager
        this.cserver.getScoreboardManager().removePlayer(player.getBukkitEntity());
        // CraftBukkit end
        return playerQuitEvent.quitMessage(); // Paper - Adventure
    }

    // Paper start - PlayerLoginEvent
    public record LoginResult(@Nullable Component message, org.bukkit.event.player.PlayerLoginEvent.Result result) {
        public static LoginResult ALLOW = new net.minecraft.server.players.PlayerList.LoginResult(null, org.bukkit.event.player.PlayerLoginEvent.Result.ALLOWED);

        public boolean isAllowed() {
            return this == ALLOW;
        }
    }
    // Paper end - PlayerLoginEvent
    public LoginResult canPlayerLogin(final SocketAddress address, final NameAndId nameAndId, final Connection connection) { // Paper - PlayerLoginEvent // Folia - region threading - add connection parameter
        LoginResult whitelistEventResult; // Paper
        // Paper start - Fix MC-158900
        UserBanListEntry ban1;
        if (this.bans.isBanned(nameAndId) && (ban1 = this.bans.get(nameAndId)) != null) {
            UserBanListEntry ban = ban1;
            // Paper end - Fix MC-158900
            MutableComponent reason = Component.translatable("multiplayer.disconnect.banned.reason", ban.getReasonMessage());
            if (ban.getExpires() != null) {
                reason.append(Component.translatable("multiplayer.disconnect.banned.expiration", BAN_DATE_FORMAT.get().format(ban.getExpires()))); // Folia - region threading - SDF is not thread-safe
            }

            return new LoginResult(reason, org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED); // Paper - PlayerLoginEvent
        } else {
            if ((whitelistEventResult = this.isWhiteListedLogin(nameAndId)).result == org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST) { // Paper - whitelist event
                return whitelistEventResult; // Paper - whitelist event
            }

            if (this.ipBans.isBanned(address)) {
                IpBanListEntry ban = this.ipBans.get(address);
                MutableComponent reason = Component.translatable("multiplayer.disconnect.banned_ip.reason", ban.getReasonMessage());
                if (ban.getExpires() != null) {
                    reason.append(Component.translatable("multiplayer.disconnect.banned_ip.expiration", BAN_DATE_FORMAT.get().format(ban.getExpires()))); // Folia - region threading - SDF is not thread-safe
                }

                return new LoginResult(reason, org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED); // Paper - PlayerLoginEvent
            } else {
                return this.canBypassFullServerLogin(nameAndId, new LoginResult(Component.translatable("multiplayer.disconnect.server_full"), org.bukkit.event.player.PlayerLoginEvent.Result.KICK_FULL), connection); // Paper - PlayerServerFullCheckEvent // Folia - region threading - add connection parameter
            }
        }
    }

    public boolean disconnectAllPlayersWithProfile(final GameProfile profile) { // Paper - validate usernames
        UUID playerId = profile.id(); // Paper - validate usernames
        Set<ServerPlayer> dupes = Sets.newIdentityHashSet();

        for (ServerPlayer player : this.players) {
            if (player.getUUID().equals(playerId) || (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode() && player.getGameProfile().name().equalsIgnoreCase(profile.name()))) { // Paper - validate usernames
                dupes.add(player);
            }
        }

        ServerPlayer serverPlayer = this.playersByUUID.get(playerId);
        if (serverPlayer != null) {
            dupes.add(serverPlayer);
        }

        // Folia - region threading

        return !dupes.isEmpty();
    }

    // Paper start - respawn event
    public ServerPlayer respawn(final ServerPlayer serverPlayer, final boolean keepAllPlayerData, final Entity.RemovalReason removalReason, final org.bukkit.event.player.PlayerRespawnEvent.RespawnReason respawnReason) {
        // Folia start - region threading
        if (true) {
            throw new UnsupportedOperationException("Must use teleportAsync while in region threading");
        }
        // Folia end - region threading
        ServerPlayer.RespawnResult result = serverPlayer.findRespawnPositionAndUseSpawnBlock0(!keepAllPlayerData, TeleportTransition.DO_NOTHING, respawnReason);
        if (result == null) { // disconnected player during the respawn event
            return serverPlayer;
        }
        TeleportTransition respawnInfo = result.transition();
        Level fromLevel = serverPlayer.level();
        // Paper end - respawn event
        this.players.remove(serverPlayer);
        this.playersByName.remove(serverPlayer.getScoreboardName().toLowerCase(java.util.Locale.ROOT)); // Paper
        serverPlayer.level().removePlayerImmediately(serverPlayer, removalReason);
        ServerLevel level = respawnInfo.newLevel();
        ServerPlayer player = serverPlayer; // Paper - TODO - recreate instance
        player.connection = serverPlayer.connection;
        player.restoreFrom(serverPlayer, keepAllPlayerData);
        player.setId(serverPlayer.getId());
        player.setMainArm(serverPlayer.getMainArm());
        if (false && !respawnInfo.missingRespawnBlock()) { // Paper - Once we not reuse the player entity, this can be flipped again but without the events being fired
            player.copyRespawnPosition(serverPlayer);
        }

        for (String tag : serverPlayer.entityTags()) {
            player.addTag(tag);
        }

        // Paper start - Once we not reuse the player entity we can remove this.
        if (!keepAllPlayerData) serverPlayer.reset();
        player.setServerLevel(level);
        player.unsetRemoved();
        player.setShiftKeyDown(false);
        // Paper end
        Vec3 pos = respawnInfo.position();
        player.snapTo(pos.x, pos.y, pos.z, respawnInfo.yRot(), respawnInfo.xRot());
        player.connection.resetPosition(); // Paper - Fix SPIGOT-1903, MC-98153
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.POST_TELEPORT, new net.minecraft.world.level.ChunkPos(net.minecraft.util.Mth.floor(pos.x()) >> 4, net.minecraft.util.Mth.floor(pos.z()) >> 4), 1); // Paper - post teleport ticket type
        if (respawnInfo.missingRespawnBlock()) {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
            player.setRespawnPosition(null, false, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN); // CraftBukkit - SPIGOT-5988: Clear respawn location when obstructed
        }

        byte dataToKeep = keepAllPlayerData ? ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS : 0;
        ServerLevel playerLevel = player.level();
        LevelData levelData = playerLevel.getLevelData();
        player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(playerLevel), dataToKeep));
        player.connection.internalTeleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()); // Paper
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        this.sendActivePlayerEffects(player);
        this.sendLevelInfo(player, level);
        this.sendPlayerPermissionLevel(player);
        level.addRespawnedPlayer(player);
        this.players.add(player);
        this.playersByName.put(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT), player); // Paper
        this.playersByUUID.put(player.getUUID(), player);
        player.initInventoryMenu();
        player.setHealth(player.getHealth());
        // Paper start - Once we not reuse the player entity we can remove this.
        // But we have to resend the player info as it's not marked as dirty
        this.sendAllPlayerInfo(serverPlayer); // Update health
        serverPlayer.onUpdateAbilities(); // Update inventory, etc
        // Paper end
        ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
        if (!keepAllPlayerData && respawnConfig != null) {
            LevelData.RespawnData respawnData = respawnConfig.respawnData();
            ServerLevel respawnLevel = this.server.getLevel(respawnData.dimension());
            if (respawnLevel != null) {
                BlockPos respawnPosition = respawnData.pos();
                BlockState blockState = respawnLevel.getBlockState(respawnPosition);
                if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
                    player.connection
                        .send(
                            new ClientboundSoundPacket(
                                SoundEvents.RESPAWN_ANCHOR_DEPLETE,
                                SoundSource.BLOCKS,
                                respawnPosition.getX(),
                                respawnPosition.getY(),
                                respawnPosition.getZ(),
                                1.0F,
                                1.0F,
                                level.getRandom().nextLong()
                            )
                        );
                }
            }
        }

        // Paper start
        // Save player file again if they were disconnected
        if (player.connection.isDisconnected()) {
            this.save(player);
        }

        // It's possible for respawn to be in a diff dimension
        if (fromLevel != level) {
            new org.bukkit.event.player.PlayerChangedWorldEvent(player.getBukkitEntity(), fromLevel.getWorld()).callEvent();
            player.triggerDimensionChangeTriggers(level);
        }

        // Call post respawn event
        new com.destroystokyo.paper.event.player.PlayerPostRespawnEvent(
            player.getBukkitEntity(),
            org.bukkit.craftbukkit.util.CraftLocation.toBukkit(respawnInfo.position(), level, respawnInfo.yRot(), respawnInfo.xRot()),
            result.isBedSpawn(),
            result.isAnchorSpawn(),
            respawnInfo.missingRespawnBlock(),
            respawnReason
        ).callEvent();
        // Paper end

        return player;
    }

    public void sendActivePlayerEffects(final ServerPlayer player) {
        this.sendActiveEffects(player, player.connection);
    }

    public void sendActiveEffects(final LivingEntity livingEntity, final ServerGamePacketListenerImpl connection) {
        // Paper start - collect packets
        this.sendActiveEffects(livingEntity, connection::send);
    }
    public void sendActiveEffects(final LivingEntity livingEntity, final java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> packetConsumer) {
        // Paper end - collect packets
        for (MobEffectInstance effect : livingEntity.getActiveEffects()) {
            packetConsumer.accept(new ClientboundUpdateMobEffectPacket(livingEntity.getId(), effect, false)); // Paper - collect packets
        }
    }

    public void sendPlayerPermissionLevel(final ServerPlayer player) {
        // Paper start - avoid recalculating permissions if possible
        this.sendPlayerPermissionLevel(player, true);
    }

    public void sendPlayerPermissionLevel(final ServerPlayer player, final boolean recalculatePermissions) {
        // Paper end - avoid recalculating permissions if possible
        LevelBasedPermissionSet permissions = this.server.getProfilePermissions(player.nameAndId());
        this.sendPlayerPermissionLevel(player, permissions, recalculatePermissions); // Paper - avoid recalculating permissions if possible
    }

    public void tick() {
        if (++this.sendAllPlayerInfoIn > 600) {
            // CraftBukkit start
            ServerPlayer[] players = this.players.toArray(new ServerPlayer[0]); // Folia - region threading
            for (final ServerPlayer target : players) { // Folia - region threading

                target.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), com.google.common.collect.Collections2.filter(java.util.Arrays.asList(players),t -> target.getBukkitEntity().canSee(t.getBukkitEntity())))); // Folia - region threading
            }
            // CraftBukkit end
            this.sendAllPlayerInfoIn = 0;
        }
    }

    // CraftBukkit start - add a world/entity limited version
    public void broadcastAll(Packet packet, net.minecraft.world.entity.player.Player entityhuman) {
        for (ServerPlayer entityplayer : this.players) { // Paper - replace for i with for each for thread safety
            if (entityhuman != null && !entityplayer.getBukkitEntity().canSee(entityhuman.getBukkitEntity())) {
                continue;
            }
            ((ServerPlayer) entityplayer).connection.send(packet); // Paper - replace for i with for each for thread safety
        }
    }

    public void broadcastAll(Packet packet, Level world) {
        for (net.minecraft.world.entity.player.Player player : world.players()) { // Folia - region threading
            ((ServerPlayer) player).connection.send(packet); // Folia - region threading
        }

    }
    // CraftBukkit end

    public void broadcastAll(final Packet<?> packet) {
        for (ServerPlayer player : this.players) {
            player.connection.send(packet);
        }
    }

    public void broadcastAll(final Packet<?> packet, final ResourceKey<Level> dimension) {
        for (ServerPlayer player : this.players) {
            if (player.level().dimension() == dimension) {
                player.connection.send(packet);
            }
        }
    }

    public void broadcastSystemToTeam(final Player player, final Component message) {
        Team team = player.getTeam();
        if (team != null) {
            for (String name : team.getPlayers()) {
                ServerPlayer teamPlayer = this.getPlayerByName(name);
                if (teamPlayer != null && teamPlayer != player) {
                    teamPlayer.sendSystemMessage(message);
                }
            }
        }
    }

    public void broadcastSystemToAllExceptTeam(final Player player, final Component message) {
        Team team = player.getTeam();
        if (team == null) {
            this.broadcastSystemMessage(message, false);
        } else {
            for (ServerPlayer targetPlayer : this.players) { // Folia - region threading
                if (targetPlayer.getTeam() != team) {
                    targetPlayer.sendSystemMessage(message);
                }
            }
        }
    }

    public String[] getPlayerNamesArray() {
        List<ServerPlayer> players = new java.util.ArrayList<>(this.players); // Folia - region threading
        String[] names = new String[players.size()]; // Folia - region threading

        for (int i = 0; i < players.size(); i++) { // Folia - region threading
            names[i] = players.get(i).getGameProfile().name(); // Folia - region threading
        }

        return names;
    }

    public UserBanList getBans() {
        return this.bans;
    }

    public IpBanList getIpBans() {
        return this.ipBans;
    }

    public void op(final NameAndId nameAndId) {
        this.op(nameAndId, Optional.empty(), Optional.empty());
    }

    public void op(final NameAndId nameAndId, final Optional<LevelBasedPermissionSet> permissions, final Optional<Boolean> canBypassPlayerLimit) {
        this.ops
            .add(
                new ServerOpListEntry(
                    nameAndId, permissions.orElse(this.server.operatorUserPermissions()), canBypassPlayerLimit.orElse(this.ops.canBypassPlayerLimit(nameAndId))
                )
            );
        ServerPlayer player = this.getPlayer(nameAndId.id());
        if (player != null) {
            player.getBukkitEntity().taskScheduler.schedule((ServerPlayer serverPlayer) -> { // Folia - region threading
            this.sendPlayerPermissionLevel(serverPlayer); // Folia - region threading
            }, null, 1L); // Folia - region threading
        }
    }

    public void deop(final NameAndId nameAndId) {
        if (this.ops.remove(nameAndId)) {
            ServerPlayer player = this.getPlayer(nameAndId.id());
            if (player != null) {
                // Folia start - region threading
                player.getBukkitEntity().taskScheduler.schedule((ServerPlayer serverPlayer) -> {
                    this.sendPlayerPermissionLevel(serverPlayer);
                }, null, 1L);
                // Folia end - region threading
            }
        }
    }

    private void sendPlayerPermissionLevel(final ServerPlayer player, final LevelBasedPermissionSet permissions) {
        // Paper start - Add sendOpLevel API
        this.sendPlayerPermissionLevel(player, permissions, true);
    }

    public void sendPlayerPermissionLevel(final ServerPlayer player, final LevelBasedPermissionSet permissions, final boolean recalculatePermissions) {
        // Paper end - Add sendOpLevel API
        if (player.connection != null) {
            byte eventId = switch (permissions.level()) {
                case ALL -> EntityEvent.PERMISSION_LEVEL_ALL;
                case MODERATORS -> EntityEvent.PERMISSION_LEVEL_MODERATORS;
                case GAMEMASTERS -> EntityEvent.PERMISSION_LEVEL_GAMEMASTERS;
                case ADMINS -> EntityEvent.PERMISSION_LEVEL_ADMINS;
                case OWNERS -> EntityEvent.PERMISSION_LEVEL_OWNERS;
            };
            player.connection.send(new ClientboundEntityEventPacket(player, eventId));
        }

        if (recalculatePermissions) { // Paper - Add sendOpLevel API
        player.getBukkitEntity().recalculatePermissions(); // CraftBukkit
        this.server.getCommands().sendCommands(player);
        } // Paper - Add sendOpLevel API
    }

    // Paper start - whitelist verify event / login event
    public LoginResult canBypassFullServerLogin(final NameAndId nameAndId, final LoginResult currentResult, final Connection connection) { // Folia - region threading - add connection parameter
        final boolean shouldKick = !this.countConnection(connection, this.getMaxPlayers()) && !this.canBypassPlayerLimit(nameAndId); // Folia - region threading - we control connection state here now async, not player list size
        final io.papermc.paper.event.player.PlayerServerFullCheckEvent fullCheckEvent = new io.papermc.paper.event.player.PlayerServerFullCheckEvent(
            new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId),
            io.papermc.paper.adventure.PaperAdventure.asAdventure(currentResult.message),
            shouldKick
        );

        fullCheckEvent.callEvent();
        if (fullCheckEvent.isAllowed()) {
            return net.minecraft.server.players.PlayerList.LoginResult.ALLOW;
        } else {
            return new net.minecraft.server.players.PlayerList.LoginResult(
                io.papermc.paper.adventure.PaperAdventure.asVanilla(fullCheckEvent.kickMessage()), currentResult.result
            );
        }
    }

    public LoginResult isWhiteListedLogin(NameAndId nameAndId) {
        boolean isOp = this.ops.contains(nameAndId);
        boolean isWhitelisted = !this.isUsingWhitelist() || isOp || this.whitelist.contains(nameAndId);

        final net.kyori.adventure.text.Component configuredMessage = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.whitelistMessage);
        final com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent event
            = new com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent(new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId), this.isUsingWhitelist(), isWhitelisted, isOp, configuredMessage);
        event.callEvent();
        if (!event.isWhitelisted()) {
            return new net.minecraft.server.players.PlayerList.LoginResult(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage() == null ? configuredMessage : event.kickMessage()), org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST);
        }

        return net.minecraft.server.players.PlayerList.LoginResult.ALLOW;
    }
    // Paper end

    @io.papermc.paper.annotation.DoNotUse // Paper
    public boolean isWhiteListed(final NameAndId nameAndId) {
        return !this.isUsingWhitelist() || this.ops.contains(nameAndId) || this.whitelist.contains(nameAndId);
    }

    public boolean isOp(final NameAndId nameAndId) {
        if (this.ops.contains(nameAndId)) {
            return true;
        } else {
            return this.server.isSingleplayerOwner(nameAndId) ? this.server.getWorldData().isAllowCommands() : this.allowCommandsForAllPlayers;
        }
    }

    public @Nullable ServerPlayer getPlayerByName(final String name) {
        return this.playersByName.get(name.toLowerCase(java.util.Locale.ROOT)); // Spigot
    }

    public void broadcast(
        final @Nullable Player except,
        final double x,
        final double y,
        final double z,
        final double range,
        final ResourceKey<Level> dimension,
        final Packet<?> packet
    ) {
        for (ServerPlayer player : this.players) { // Folia - region threading
            // CraftBukkit start - Test if player receiving packet can see the source of the packet
            if (except != null && !player.getBukkitEntity().canSee(except.getBukkitEntity())) {
               continue;
            }
            // CraftBukkit end
            if (player != except && player.level().dimension() == dimension) {
                double xd = x - player.getX();
                double yd = y - player.getY();
                double zd = z - player.getZ();
                if (xd * xd + yd * yd + zd * zd < range * range) {
                    player.connection.send(packet);
                }
            }
        }
    }

    public void saveAll() {
        // Paper start - Incremental chunk and player saving
        this.saveAll(-1);
    }
    public void saveAll(final int interval) {
        // Paper end - Incremental chunk and player saving
        //io.papermc.paper.util.MCUtil.ensureMain("Save Players" , () -> { // Paper - Ensure main // Folia - region threading
            final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        // Paper start - Incremental chunk and player saving
            int numSaved = 0;
            final long now = System.nanoTime(); // Folia - region threading
            final long timeInterval = (long)interval * io.papermc.paper.threadedregions.TickRegionScheduler.TIME_BETWEEN_TICKS; // Folia - region threading
            for (final ServerPlayer player : this.players) {
            // Folia start - region threading
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) {
                continue;
            }
            // Folia end - region threading
            if (interval == -1 || now - player.lastSave >= timeInterval) { // Folia - region threading
                profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_SAVE); try { // Folia - profiler
                this.save(player);
                } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_SAVE); } // Folia - profiler
                if (interval != -1 && ++numSaved >= io.papermc.paper.configuration.GlobalConfiguration.get().playerAutoSave.maxPerTick()) {
                    break;
                }
            }
            }
        // Paper end - Incremental chunk and player saving
        //return null; }); // Paper - ensure main // Folia - region threading
    }

    public UserWhiteList getWhiteList() {
        return this.whitelist;
    }

    public String[] getWhiteListNames() {
        return this.whitelist.getUserList();
    }

    public ServerOpList getOps() {
        return this.ops;
    }

    public String[] getOpNames() {
        return this.ops.getUserList();
    }

    public void reloadWhiteList() {
    }

    public void sendLevelInfo(final ServerPlayer player, final ServerLevel level) {
        WorldBorder worldBorder = level.getWorldBorder();
        player.connection.send(new ClientboundInitializeBorderPacket(worldBorder));
        player.connection.send(level.clockManager().createFullSyncPacket(player)); // Paper - per-world time; per-player time
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        // Paper start - view distances
        //player.connection.send(new ClientboundSetChunkCacheRadiusPacket(io.papermc.paper.FeatureHooks.getViewDistance(level))); // Paper - rewrite chunk system
        //player.connection.send(new ClientboundSetSimulationDistancePacket(io.papermc.paper.FeatureHooks.getSimulationDistance(level))); // Paper - rewrite chunk system
        // Paper end - view distances
        if (level.isRaining()) {
            // CraftBukkit start - handle player weather
            // player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F));
            // player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0F)));
            // player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0F)));
            player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL, false);
            player.updateWeather(-level.rainLevel, level.rainLevel, -level.thunderLevel, level.thunderLevel);
            // CraftBukkit end
        }

        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
        this.server.tickRateManager().updateJoiningPlayer(player);
    }

    public void sendAllPlayerInfo(final ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
        // entityplayer.resetSentInfo();
        // Paper start - send all attributes
        // needs to be done because the ServerPlayer instance is being reused on respawn instead of getting replaced like on vanilla
        java.util.Collection<net.minecraft.world.entity.ai.attributes.AttributeInstance> syncableAttributes = player.getAttributes().getSyncableAttributes();
        player.getBukkitEntity().injectScaledMaxHealth(syncableAttributes, true);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket(player.getId(), syncableAttributes));
        // Paper end - send all attributes
        player.refreshEntityData(player); // CraftBukkit - SPIGOT-7218: sync metadata
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        // CraftBukkit start - from GameRules
        int i = player.level().getGameRules().get(GameRules.REDUCED_DEBUG_INFO) ? 22 : 23;
        player.connection.send(new ClientboundEntityEventPacket(player, (byte) i));
        float immediateRespawn = player.level().getGameRules().get(GameRules.IMMEDIATE_RESPAWN) ? 1.0F: 0.0F;
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN, immediateRespawn));
        // CraftBukkit end
    }

    public int getPlayerCount() {
        return this.players.size();
    }

    public int getMaxPlayers() {
        return this.server.getMaxPlayers();
    }

    public boolean isUsingWhitelist() {
        return this.server.isUsingWhitelist();
    }

    public List<ServerPlayer> getPlayersWithAddress(final String ip) {
        List<ServerPlayer> result = Lists.newArrayList();

        for (ServerPlayer player : this.players) {
            if (player.getIpAddress().equals(ip)) {
                result.add(player);
            }
        }

        return result;
    }

    public int getViewDistance() {
        return this.viewDistance;
    }

    public int getSimulationDistance() {
        return this.simulationDistance;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public void setAllowCommandsForAllPlayers(final boolean allowCommands) {
        this.allowCommandsForAllPlayers = allowCommands;
    }

    public void removeAll() {
        // Paper start - Extract method to allow for restarting flag
        this.removeAll(false);
    }

    public void removeAll(boolean isRestarting) {
        // Folia start - region threading
        // just send disconnect packet, don't modify state
        for (ServerPlayer player : this.players) {
            final Component shutdownMessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(this.server.server.shutdownMessage()); // Paper - Adventure
            // CraftBukkit end

            player.connection.send(new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(shutdownMessage), net.minecraft.network.PacketSendListener.thenRun(() -> {
                player.connection.connection.disconnect(shutdownMessage);
            }));
        }
        if (true) {
            return;
        }
        // Folia end - region threading
        // Paper end
        // CraftBukkit start - disconnect safely
        for (ServerPlayer player : this.players) {
            if (isRestarting) player.connection.disconnect(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.restartMessage), org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN); else // Paper - kick event cause (cause is never used here)
            player.connection.disconnect(java.util.Objects.requireNonNullElseGet(this.server.server.shutdownMessage(), net.kyori.adventure.text.Component::empty)); // CraftBukkit - add custom shutdown message // Paper - Adventure
        }
        // CraftBukkit end

        // Paper start - Configurable player collision; Remove collideRule team if it exists
        if (false && this.collideRuleTeamName != null) { // Folia - region threading
            final net.minecraft.world.scores.Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
            final PlayerTeam team = scoreboard.getPlayersTeam(this.collideRuleTeamName);
            if (team != null) scoreboard.removePlayerTeam(team);
        }
        // Paper end - Configurable player collision
    }

    public void broadcastSystemMessage(final Component message, final boolean overlay) {
        this.broadcastSystemMessage(message, player -> message, overlay);
    }

    public void broadcastSystemMessage(final Component message, final Function<ServerPlayer, Component> playerMessages, final boolean overlay) {
        this.server.sendSystemMessage(message);

        for (ServerPlayer player : this.players) {
            Component playerMessage = playerMessages.apply(player);
            if (playerMessage != null) {
                player.sendSystemMessage(playerMessage, overlay);
            }
        }
    }

    public void broadcastChatMessage(final PlayerChatMessage message, final CommandSourceStack sender, final ChatType.Bound chatType) {
        this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender.getPlayer(), chatType);
    }

    public void broadcastChatMessage(final PlayerChatMessage message, final ServerPlayer sender, final ChatType.Bound chatType) {
        // Paper start
        this.broadcastChatMessage(message, sender, chatType, null);
    }
    public void broadcastChatMessage(final PlayerChatMessage message, final ServerPlayer sender, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender, chatType, unsignedFunction); // Paper
    }

    private void broadcastChatMessage(
        final PlayerChatMessage message, final Predicate<ServerPlayer> isFiltered, final @Nullable ServerPlayer senderPlayer, final ChatType.Bound chatType
    ) {
        // Paper start
        this.broadcastChatMessage(message, isFiltered, senderPlayer, chatType, null);
    }
    public void broadcastChatMessage(final PlayerChatMessage message, final Predicate<ServerPlayer> isFiltered, final @Nullable ServerPlayer senderPlayer, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        boolean trusted = this.verifyChatTrusted(message);
        this.server.logChatMessage((unsignedFunction == null ? message.decoratedContent() : unsignedFunction.apply(this.server.console)), chatType, trusted ? null : "Not Secure"); // Paper
        OutgoingChatMessage tracked = OutgoingChatMessage.create(message);
        boolean wasFullyFiltered = false;

        Packet<?> disguised = senderPlayer != null && unsignedFunction == null ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(tracked.content(), chatType) : null; // Paper - don't send player chat packets from vanished players
        for (ServerPlayer player : this.players) {
            boolean filtered = isFiltered.test(player);
            // Paper start - don't send player chat packets from vanished players
            if (senderPlayer != null && !player.getBukkitEntity().canSee(senderPlayer.getBukkitEntity())) {
                player.connection.send(unsignedFunction != null
                    ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(unsignedFunction.apply(player.getBukkitEntity()), chatType)
                    : disguised);
                continue;
            }
            player.sendChatMessage(tracked, filtered, chatType, unsignedFunction == null ? null : unsignedFunction.apply(player.getBukkitEntity()));
            // Paper end
            wasFullyFiltered |= filtered && message.isFullyFiltered();
        }

        if (wasFullyFiltered && senderPlayer != null) {
            senderPlayer.sendSystemMessage(CHAT_FILTERED_FULL);
        }
    }

    public boolean verifyChatTrusted(final PlayerChatMessage message) {
        return message.hasSignature() && !message.hasExpiredServer(Instant.now());
    }

    // CraftBukkit start
    public ServerStatsCounter getPlayerStats(final ServerPlayer player) {
        GameProfile gameProfile = player.getGameProfile();
        ServerStatsCounter playerStatsCounter = player.getStats();
        if (playerStatsCounter == null) {
            return this.getPlayerStats(gameProfile);
        } else {
            return playerStatsCounter;
        }
    }
    public ServerStatsCounter getPlayerStats(final GameProfile gameProfile) {
            Path targetFile = this.locateStatsFile(gameProfile);
            return new ServerStatsCounter(this.server, targetFile);
    }
    // CraftBukkit end

    private Path locateStatsFile(final GameProfile gameProfile) {
        Path statFolder = this.server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        Path uuidStatsFile = statFolder.resolve(gameProfile.id() + ".json");
        if (Files.exists(uuidStatsFile)) {
            return uuidStatsFile;
        }

        String playerNameStatsFile = gameProfile.name() + ".json";
        if (FileUtil.isValidPathSegment(playerNameStatsFile)) {
            Path playerNameStatsPath = statFolder.resolve(playerNameStatsFile);
            if (Files.isRegularFile(playerNameStatsPath)) {
                try {
                    return Files.move(playerNameStatsPath, uuidStatsFile);
                } catch (IOException e) {
                    LOGGER.warn("Failed to copy file {} to {}", playerNameStatsFile, uuidStatsFile);
                    return playerNameStatsPath;
                }
            }
        }

        return uuidStatsFile;
    }

    public PlayerAdvancements getPlayerAdvancements(final ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerAdvancements result = player.getAdvancements(); // CraftBukkit
        if (result == null) {
            Path uuidStatsFile = this.server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json");
            result = new PlayerAdvancements(this.server.getFixerUpper(), this, this.server.getAdvancements(), uuidStatsFile, player);
            // this.advancements.put(uuid, result); // CraftBukkit
        }

        result.setPlayer(player);
        return result;
    }

    public void setViewDistance(final int viewDistance) {
        this.viewDistance = viewDistance;
        //this.broadcastAll(new ClientboundSetChunkCacheRadiusPacket(viewDistance)); // Paper - rewrite chunk system

        for (ServerLevel level : this.server.getAllLevels()) {
            level.getChunkSource().setViewDistance(viewDistance);
        }
    }

    public void setSimulationDistance(final int simulationDistance) {
        this.simulationDistance = simulationDistance;
        //this.broadcastAll(new ClientboundSetSimulationDistancePacket(simulationDistance)); // Paper - rewrite chunk system

        for (ServerLevel level : this.server.getAllLevels()) {
            level.getChunkSource().setSimulationDistance(simulationDistance);
        }
    }

    public List<ServerPlayer> getPlayers() {
        return this.players;
    }

    public Map<UUID, ServerPlayer> getPlayersByUUID() {
        return this.playersByUUID;
    }

    public @Nullable ServerPlayer getPlayer(final UUID uuid) {
        return this.playersByUUID.get(uuid);
    }

    public @Nullable ServerPlayer getPlayer(final String playerName) {
        for (ServerPlayer player : this.players) {
            if (player.getGameProfile().name().equalsIgnoreCase(playerName)) {
                return player;
            }
        }

        return null;
    }

    public boolean canBypassPlayerLimit(final NameAndId nameAndId) {
        return false;
    }

    public void reloadResources() {
        // Paper start - API for updating recipes on clients
        this.reloadAdvancementData();
        this.reloadTagData();
        this.reloadRecipes();
    }
    public void reloadAdvancementData() {
        // Paper end - API for updating recipes on clients
        // CraftBukkit start
        // for (PlayerAdvancements playerAdvancements : this.advancements.values()) {
        //     playerAdvancements.reload(this.server.getAdvancements());
        // }
        for (ServerPlayer player : this.players) {
            player.getAdvancements().reload(this.server.getAdvancements());
            player.getAdvancements().flushDirty(player, false); // CraftBukkit - trigger immediate flush of advancements
        }
        // CraftBukkit end

        // Paper start - API for updating recipes on clients
    }
    public void reloadTagData() {
        this.broadcastAll(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(this.registries)));
        // CraftBukkit start
        // this.reloadRecipes(); // Paper - do not reload recipes just because tag data was reloaded
        // Paper end - API for updating recipes on clients
    }

    public void reloadRecipes() {
        // CraftBukkit end
        RecipeManager recipeManager = this.server.getRecipeManager();
        ClientboundUpdateRecipesPacket recipes = new ClientboundUpdateRecipesPacket(
            recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes()
        );

        for (ServerPlayer player : this.players) {
            player.connection.send(recipes);
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
    }

    public boolean isAllowCommandsForAllPlayers() {
        return this.allowCommandsForAllPlayers;
    }
}

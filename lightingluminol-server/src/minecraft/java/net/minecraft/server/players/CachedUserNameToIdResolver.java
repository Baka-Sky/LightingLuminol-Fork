package net.minecraft.server.players;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.util.StringUtil;
import org.slf4j.Logger;

public class CachedUserNameToIdResolver implements UserNameToIdResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GAMEPROFILES_MRU_LIMIT = 1000;
    private static final int GAMEPROFILES_EXPIRATION_MONTHS = 1;
    private boolean resolveOfflineUsers = true;
    private final Map<String, CachedUserNameToIdResolver.GameProfileInfo> profilesByName = new ConcurrentHashMap<>();
    private final Map<UUID, CachedUserNameToIdResolver.GameProfileInfo> profilesByUUID = new ConcurrentHashMap<>();
    private final GameProfileRepository profileRepository;
    private final Gson gson = new GsonBuilder().create();
    private final File file;
    private final AtomicLong operationCount = new AtomicLong();
    // Paper start - Fix GameProfileCache concurrency
    protected final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
    protected final java.util.concurrent.locks.ReentrantLock lookupLock = new java.util.concurrent.locks.ReentrantLock();
    // Paper end - Fix GameProfileCache concurrency

    public CachedUserNameToIdResolver(final GameProfileRepository profileRepository, final File file) {
        this.profileRepository = profileRepository;
        this.file = file;
        Lists.reverse(this.load()).forEach(this::safeAdd);
    }

    private void safeAdd(final CachedUserNameToIdResolver.GameProfileInfo profileInfo) {
        try { this.stateLock.lock(); // Paper - Fix GameProfileCache concurrency
        NameAndId nameAndId = profileInfo.nameAndId();
        profileInfo.setLastAccess(this.getNextOperation());
        this.profilesByName.put(nameAndId.name().toLowerCase(Locale.ROOT), profileInfo);
        this.profilesByUUID.put(nameAndId.id(), profileInfo);
        } finally { this.stateLock.unlock(); } // Paper - Fix GameProfileCache concurrency
    }

    private Optional<NameAndId> lookupGameProfile(final GameProfileRepository profileRepository, final String name) {
        if (!StringUtil.isValidPlayerName(name)) {
            return this.createUnknownProfile(name);
        }

        final boolean shouldLookup = !org.apache.commons.lang3.StringUtils.isBlank(name) // Paper - Don't lookup a profile with a blank name
            && io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode(); // Paper - Add setting for proxy online mode status
        Optional<NameAndId> profile = shouldLookup ? profileRepository.findProfileByName(name).map(NameAndId::new) : Optional.empty(); // Paper - Don't lookup a profile with a blank name
        return profile.isEmpty() ? this.createUnknownProfile(name) : profile;
    }

    private Optional<NameAndId> createUnknownProfile(final String name) {
        return this.resolveOfflineUsers && !io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode() ? Optional.of(NameAndId.createOffline(name)) : Optional.empty(); // Paper - Add setting for proxy online mode status
    }

    @Override
    public void resolveOfflineUsers(final boolean value) {
        this.resolveOfflineUsers = value;
    }

    @Override
    public void add(final NameAndId nameAndId) {
        this.addInternal(nameAndId);
    }

    private CachedUserNameToIdResolver.GameProfileInfo addInternal(final NameAndId profile) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        c.setTime(new Date());
        c.add(Calendar.MONTH, 1);
        Date expirationDate = c.getTime();
        CachedUserNameToIdResolver.GameProfileInfo profileInfo = new CachedUserNameToIdResolver.GameProfileInfo(profile, expirationDate);
        this.safeAdd(profileInfo);
        if (!org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly) this.save(true); // Spigot - skip saving if disabled // Paper - Perf: Async GameProfileCache saving
        return profileInfo;
    }

    private long getNextOperation() {
        return this.operationCount.incrementAndGet();
    }

    // Paper start
    @Override
    public @org.jspecify.annotations.Nullable NameAndId getIfCached(final String name) {
        try { this.stateLock.lock(); // Paper - Fix GameProfileCache concurrency
        CachedUserNameToIdResolver.GameProfileInfo entry = this.profilesByName.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) {
            return null;
        }
        entry.setLastAccess(this.getNextOperation());
        return entry.nameAndId();
        } finally { this.stateLock.unlock(); } // Paper - Fix GameProfileCache concurrency
    }
    // Paper end

    @Override
    public Optional<NameAndId> get(final String name) {
        String userName = name.toLowerCase(Locale.ROOT);
        boolean stateLocked = true; try { this.stateLock.lock(); // Paper - Fix GameProfileCache concurrency
        CachedUserNameToIdResolver.GameProfileInfo profileInfo = this.profilesByName.get(userName);
        boolean needsSave = false;
        if (profileInfo != null && new Date().getTime() >= profileInfo.expirationDate.getTime()) {
            this.profilesByUUID.remove(profileInfo.nameAndId().id());
            this.profilesByName.remove(profileInfo.nameAndId().name().toLowerCase(Locale.ROOT));
            needsSave = true;
            profileInfo = null;
        }

        Optional<NameAndId> result;
        if (profileInfo != null) {
            profileInfo.setLastAccess(this.getNextOperation());
            result = Optional.of(profileInfo.nameAndId());
            stateLocked = false; this.stateLock.unlock(); // Paper - Fix GameProfileCache concurrency
        } else {
            Optional<NameAndId> profile;
            stateLocked = false; this.stateLock.unlock(); // Paper - Fix GameProfileCache concurrency
            try { this.lookupLock.lock(); // Paper - Fix GameProfileCache concurrency
                profile = this.lookupGameProfile(this.profileRepository, name); // CraftBukkit - use correct case for offline players
            } finally { this.lookupLock.unlock(); } // Paper - Fix GameProfileCache concurrency
            if (profile.isPresent()) {
                result = Optional.of(this.addInternal(profile.get()).nameAndId());
                needsSave = false;
            } else {
                result = Optional.empty();
            }
        }

        if (needsSave && !org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly) { // Spigot - skip saving if disabled
            this.save(true); // Paper - Perf: Async GameProfileCache saving
        }

        return result;
        } finally { if (stateLocked) { this.stateLock.unlock(); } } // Paper - Fix GameProfileCache concurrency
    }

    @Override
    public Optional<NameAndId> get(final UUID id) {
        try { this.stateLock.lock(); // Paper - Fix GameProfileCache concurrency
        CachedUserNameToIdResolver.GameProfileInfo profileInfo = this.profilesByUUID.get(id);
        if (profileInfo == null) {
            return Optional.empty();
        }

        profileInfo.setLastAccess(this.getNextOperation());
        return Optional.of(profileInfo.nameAndId());
        } finally { this.stateLock.unlock(); } // Paper - Fix GameProfileCache concurrency
    }

    private static DateFormat createDateFormat() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
    }

    private List<CachedUserNameToIdResolver.GameProfileInfo> load() {
        List<CachedUserNameToIdResolver.GameProfileInfo> result = Lists.newArrayList();

        try (Reader reader = Files.newReader(this.file, StandardCharsets.UTF_8)) {
            JsonArray entryList = this.gson.fromJson(reader, JsonArray.class);
            if (entryList == null) {
                return result;
            }

            DateFormat dateFormat = createDateFormat();
            entryList.forEach(element -> readGameProfile(element, dateFormat).ifPresent(result::add));
        } catch (FileNotFoundException var7) {
            // Spigot start
        } catch (com.google.gson.JsonSyntaxException | NullPointerException _) {
            LOGGER.warn("Usercache.json is corrupted or has bad formatting. Deleting it to prevent further issues.");
            this.file.delete();
            // Spigot end
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Failed to load profile cache {}", this.file, e);
        }

        return result;
    }

    @Override
    public void save() {
        // Paper start - Perf: Async GameProfileCache saving
        this.save(false);
    }

    @Override
    public void save(final boolean asyncSave) {
        // Paper end - Perf: Async GameProfileCache saving
        JsonArray entryList = new JsonArray();
        DateFormat dateFormat = createDateFormat();
        this.listTopMRUProfiles(org.spigotmc.SpigotConfig.userCacheCap).forEach(entry -> entryList.add(writeGameProfile(entry, dateFormat))); // Spigot // Paper - Fix GameProfileCache concurrency
        String toSave = this.gson.toJson(entryList);

        Runnable save = () -> { // Paper - Perf: Async GameProfileCache saving
        try (Writer writer = Files.newWriter(this.file, StandardCharsets.UTF_8)) {
            writer.write(toSave);
        } catch (IOException var9) {
        }
        // Paper start - Perf: Async GameProfileCache saving
        };
        if (asyncSave) {
            io.papermc.paper.util.MCUtil.scheduleAsyncTask(save);
        } else {
            save.run();
        }
        // Paper end - Perf: Async GameProfileCache saving
    }

    private Stream<CachedUserNameToIdResolver.GameProfileInfo> getTopMRUProfiles(final int limit) {
        // Paper start - Fix GameProfileCache concurrency
        return this.listTopMRUProfiles(limit).stream();
    }

    private List<CachedUserNameToIdResolver.GameProfileInfo> listTopMRUProfiles(final int limit) {
        try {
            this.stateLock.lock();
            return this.profilesByUUID.values()
                .stream()
                .sorted(Comparator.comparing(CachedUserNameToIdResolver.GameProfileInfo::lastAccess).reversed())
                .limit(limit)
                .toList();
        } finally {
            this.stateLock.unlock();
        }
    }
    // Paper end - Fix GameProfileCache concurrency

    private static JsonElement writeGameProfile(final CachedUserNameToIdResolver.GameProfileInfo src, final DateFormat dateFormat) {
        JsonObject object = new JsonObject();
        src.nameAndId().appendTo(object);
        object.addProperty("expiresOn", dateFormat.format(src.expirationDate()));
        return object;
    }

    private static Optional<CachedUserNameToIdResolver.GameProfileInfo> readGameProfile(final JsonElement json, final DateFormat dateFormat) {
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            NameAndId nameAndId = NameAndId.fromJson(object);
            if (nameAndId != null) {
                JsonElement expirationElement = object.get("expiresOn");
                if (expirationElement != null) {
                    String dateAsString = expirationElement.getAsString();

                    try {
                        Date expirationDate = dateFormat.parse(dateAsString);
                        return Optional.of(new CachedUserNameToIdResolver.GameProfileInfo(nameAndId, expirationDate));
                    } catch (ParseException e) {
                        LOGGER.warn("Failed to parse date {}", dateAsString, e);
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static class GameProfileInfo {
        private final NameAndId nameAndId;
        private final Date expirationDate;
        private volatile long lastAccess;

        private GameProfileInfo(final NameAndId nameAndId, final Date expirationDate) {
            this.nameAndId = nameAndId;
            this.expirationDate = expirationDate;
        }

        public NameAndId nameAndId() {
            return this.nameAndId;
        }

        public Date expirationDate() {
            return this.expirationDate;
        }

        public void setLastAccess(final long currentOperation) {
            this.lastAccess = currentOperation;
        }

        public long lastAccess() {
            return this.lastAccess;
        }
    }
}

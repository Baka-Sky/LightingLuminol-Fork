package net.minecraft.server.players;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Objects;
import net.minecraft.server.notifications.NotificationService;

public class UserWhiteList extends StoredUserList<NameAndId, UserWhiteListEntry> {
    public UserWhiteList(final File file, final NotificationService notificationService) {
        super(file, notificationService);
    }

    @Override
    protected StoredUserEntry<NameAndId> createEntry(final JsonObject object) {
        return new UserWhiteListEntry(object);
    }

    public boolean isWhiteListed(final NameAndId user) {
        return this.contains(user);
    }

    @Override
    public boolean add(final UserWhiteListEntry infos) {
        // Paper start - Add whitelist events
        if (!new io.papermc.paper.event.server.WhitelistStateUpdateEvent(com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(infos.getUser().toUncompletedGameProfile()), io.papermc.paper.event.server.WhitelistStateUpdateEvent.WhitelistStatus.ADDED).callEvent()) {
            return false;
        }
        // Paper end - Add whitelist events
        if (super.add(infos)) {
            if (infos.getUser() != null) {
                this.notificationService.playerAddedToAllowlist(infos.getUser());
            }

            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean remove(final NameAndId user) {
        // Paper start - Add whitelist events
        if (!new io.papermc.paper.event.server.WhitelistStateUpdateEvent(com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(user.toUncompletedGameProfile()), io.papermc.paper.event.server.WhitelistStateUpdateEvent.WhitelistStatus.REMOVED).callEvent()) {
            return false;
        }
        // Paper end - Add whitelist events
        if (super.remove(user)) {
            this.notificationService.playerRemovedFromAllowlist(user);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void clear() {
        for (UserWhiteListEntry user : this.getEntries()) {
            if (user.getUser() != null) {
                this.notificationService.playerRemovedFromAllowlist(user.getUser());
            }
        }

        super.clear();
    }

    @Override
    public String[] getUserList() {
        return this.getEntries().stream().map(StoredUserEntry::getUser).filter(Objects::nonNull).map(NameAndId::name).toArray(String[]::new);
    }

    @Override
    protected String getKeyForUser(final NameAndId user) {
        return user.id().toString();
    }
}

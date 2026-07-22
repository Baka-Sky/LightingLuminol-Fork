package io.papermc.paper.plugin.manager;

import com.google.common.collect.ImmutableSet;
import io.papermc.paper.plugin.PermissionManager;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * See
 * {@link StupidSPMPermissionManagerWrapper}
 */
abstract class PaperPermissionManager implements PermissionManager {

    public abstract Map<String, Permission> permissions();

    public abstract Map<Boolean, Set<Permission>> defaultPerms();

    public abstract Map<String, Map<Permissible, Boolean>> permSubs();

    public abstract Map<Boolean, Map<Permissible, Boolean>> defSubs();

    @Override
    @Nullable
    public Permission getPermission(@NotNull String name) {
        synchronized (this) { // Folia - synchronized
        return this.permissions().get(name.toLowerCase(java.util.Locale.ENGLISH));
        } // Folia - synchronized
    }

    @Override
    public void addPermission(@NotNull Permission perm) {
        this.addPermission(perm, true);
    }

    @Override
    public void addPermissions(@NotNull List<Permission> permissions) {
        for (Permission permission : permissions) {
            this.addPermission(permission, false);
        }
        this.dirtyPermissibles();
    }

    // Allow suppressing permission default calculations
    private void addPermission(@NotNull Permission perm, boolean dirty) {
        String name = perm.getName().toLowerCase(java.util.Locale.ENGLISH);

        Boolean recalc; // Folia - synchronized
        synchronized (this) { // Folia - synchronized
        if (this.permissions().containsKey(name)) {
            throw new IllegalArgumentException("The permission " + name + " is already defined!");
        }

        this.permissions().put(name, perm);
        recalc = this.calculatePermissionDefault(perm, dirty);
        } // Folia - synchronized
        // Folia start - synchronize this class - we hold a lock now, prevent deadlock by moving this out
        if (recalc != null) {
            if (recalc.booleanValue()) {
                this.dirtyPermissibles(true);
            } else {
                this.dirtyPermissibles(false);
            }
        }
        // Folia end - synchronize this class - we hold a lock now, prevent deadlock by moving this out
    }

    @Override
    @NotNull
    public Set<Permission> getDefaultPermissions(boolean op) {
        return ImmutableSet.copyOf(this.defaultPerms().get(op));
    }


    @Override
    public void removePermission(@NotNull Permission perm) {
        this.removePermission(perm.getName());
    }


    @Override
    public void removePermission(@NotNull String name) {
        this.permissions().remove(name.toLowerCase(java.util.Locale.ENGLISH));
    }

    @Override
    public void recalculatePermissionDefaults(@NotNull Permission perm) {
        Boolean recalc = null; // Folia - synchronized
        synchronized (this) { // Folia - synchronized
        // we need a null check here because some plugins for some unknown reason pass null into this?
        if (perm != null && this.permissions().containsKey(perm.getName().toLowerCase(Locale.ROOT))) {
            this.defaultPerms().get(true).remove(perm);
            this.defaultPerms().get(false).remove(perm);

            recalc = this.calculatePermissionDefault(perm, true); // Folia - synchronized
        }
        } // Folia - synchronized
        // Folia start - synchronize this class - we hold a lock now, prevent deadlock by moving this out
        if (recalc != null) {
            if (recalc.booleanValue()) {
                this.dirtyPermissibles(true);
            } else {
                this.dirtyPermissibles(false);
            }
        }
        // Folia end - synchronize this class - we hold a lock now, prevent deadlock by moving this out
    }

    private Boolean calculatePermissionDefault(@NotNull Permission perm, boolean dirty) { // Folia - synchronize this class
        if ((perm.getDefault() == PermissionDefault.OP) || (perm.getDefault() == PermissionDefault.TRUE)) {
            this.defaultPerms().get(true).add(perm);
            if (dirty) {
                return Boolean.TRUE; // Folia - synchronize this class - we hold a lock now, prevent deadlock by moving this out
            }
        }
        if ((perm.getDefault() == PermissionDefault.NOT_OP) || (perm.getDefault() == PermissionDefault.TRUE)) {
            this.defaultPerms().get(false).add(perm);
            if (dirty) {
                return Boolean.FALSE; // Folia - synchronize this class - we hold a lock now, prevent deadlock by moving this out
            }
        }
        return null; // Folia - synchronize this class
    }


    @Override
    public void subscribeToPermission(@NotNull String permission, @NotNull Permissible permissible) {
        synchronized (this) { // Folia - synchronized
        String name = permission.toLowerCase(java.util.Locale.ENGLISH);
        Map<Permissible, Boolean> map = this.permSubs().computeIfAbsent(name, k -> new WeakHashMap<>());

        map.put(permissible, true);
        } // Folia - synchronized
    }

    @Override
    public void unsubscribeFromPermission(@NotNull String permission, @NotNull Permissible permissible) {
        String name = permission.toLowerCase(java.util.Locale.ENGLISH);
        synchronized (this) { // Folia - synchronized
        Map<Permissible, Boolean> map = this.permSubs().get(name);

        if (map != null) {
            map.remove(permissible);

            if (map.isEmpty()) {
                this.permSubs().remove(name);
            }
        }
        } // Folia - synchronized
    }

    @Override
    @NotNull
    public Set<Permissible> getPermissionSubscriptions(@NotNull String permission) {
        synchronized (this) { // Folia - synchronized
        String name = permission.toLowerCase(java.util.Locale.ENGLISH);
        Map<Permissible, Boolean> map = this.permSubs().get(name);

        if (map == null) {
            return ImmutableSet.of();
        } else {
            return ImmutableSet.copyOf(map.keySet());
        }
        } // Folia - synchronized
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, @NotNull Permissible permissible) {
        synchronized (this) { // Folia - synchronized
        Map<Permissible, Boolean> map = this.defSubs().computeIfAbsent(op, k -> new WeakHashMap<>());

        map.put(permissible, true);
        } // Folia - synchronized
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, @NotNull Permissible permissible) {
        synchronized (this) { // Folia - synchronized
        Map<Permissible, Boolean> map = this.defSubs().get(op);

        if (map != null) {
            map.remove(permissible);

            if (map.isEmpty()) {
                this.defSubs().remove(op);
            }
        }
        } // Folia - synchronized
    }

    @Override
    @NotNull
    public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        synchronized (this) { // Folia - synchronized
        Map<Permissible, Boolean> map = this.defSubs().get(op);

        if (map == null) {
            return ImmutableSet.of();
        } else {
            return ImmutableSet.copyOf(map.keySet());
        }
        } // Folia - synchronized
    }

    @Override
    @NotNull
    public Set<Permission> getPermissions() {
        synchronized (this) { // Folia - synchronized
        return new HashSet<>(this.permissions().values());
        } // Folia - synchronized
    }

    @Override
    public void clearPermissions() {
        synchronized (this) { // Folia - synchronized
        this.permissions().clear();
        this.defaultPerms().get(true).clear();
        this.defaultPerms().get(false).clear();
        } // Folia - synchronized
    }


    void dirtyPermissibles(boolean op) {
        Set<Permissible> permissibles = this.getDefaultPermSubscriptions(op);

        for (Permissible p : permissibles) {
            p.recalculatePermissions();
        }
    }

    void dirtyPermissibles() {
        this.dirtyPermissibles(true);
        this.dirtyPermissibles(false);
    }
}

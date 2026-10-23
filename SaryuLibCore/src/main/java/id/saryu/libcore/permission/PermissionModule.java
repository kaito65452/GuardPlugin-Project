package id.saryu.libcore.permission;

import id.saryu.libcore.SaryuLib;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Permission helpers: scan installed plugins' plugin.yml permissions and each
 * command's permission, plus LuckPerms group weight/track (via reflection, since
 * LuckPerms is an optional softdepend), to determine whether a permission is
 * "above member" (staff/admin). Used by auto-blacklist.
 */
public class PermissionModule {

    private final SaryuLib plugin;
    private final Set<String> adminKeywords = new HashSet<>();
    private final Set<String> memberSafeCommands = new HashSet<>();
    private final boolean luckpermsAvailable;
    private final List<Object> luckPermsSubscriptions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PermissionModule(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        this.luckpermsAvailable = classPresent("net.luckperms.api.LuckPerms");
        if (luckpermsAvailable) {
            plugin.getLogger().info("LuckPerms API detected; LuckPerms scanning enabled.");
        }

        // Default admin keywords (heuristic, can be disabled by the caller).
        adminKeywords.add("admin");
        adminKeywords.add("staff");
        adminKeywords.add("moderator");
        adminKeywords.add("manage");
        adminKeywords.add("reload");
        adminKeywords.add("gamemode");
        adminKeywords.add("whitelist");
        adminKeywords.add("bypass");
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void addAdminKeyword(String k) {
        adminKeywords.add(k.toLowerCase(Locale.ROOT));
    }

    public void addMemberSafeCommand(String c) {
        memberSafeCommands.add(c.toLowerCase(Locale.ROOT));
    }

    public boolean hasLuckPerms() {
        return luckpermsAvailable;
    }

    /**
     * Subscribe to LuckPerms mutation/recalculation events without a compile-time
     * dependency. The callback may run on a LuckPerms thread; callers must
     * debounce and schedule their own work. Returns the number of event types
     * successfully subscribed (0 when LuckPerms is absent or unsupported).
     */
    public int registerChangeListener(@NotNull Runnable callback) {
        if (!luckpermsAvailable) return 0;
        try {
            Object lp = luckPermsInstance();
            if (lp == null) return 0;
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
            Object bus = api.getMethod("getEventBus").invoke(lp);
            if (bus == null) return 0;
            Class<?> busType = Class.forName("net.luckperms.api.event.EventBus");
            java.lang.reflect.Method subscribe = busType.getMethod(
                    "subscribe", Class.class, java.util.function.Consumer.class);
            java.util.function.Consumer<Object> consumer = event -> {
                try {
                    callback.run();
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.FINE, "LuckPerms change callback failed", t);
                }
            };
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    PermissionModule.class.getClassLoader(),
                    new Class<?>[]{java.util.function.Consumer.class},
                    (p, method, args) -> {
                        if (method.getName().equals("accept")) {
                            consumer.accept(args == null || args.length == 0 ? null : args[0]);
                            return null;
                        }
                        if (method.getName().equals("toString")) return "AntiChatManager-LuckPermsListener";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                        if (method.getName().equals("equals")) return p == (args == null ? null : args[0]);
                        return null;
                    });
            int count = 0;
            for (String eventClass : List.of(
                    "net.luckperms.api.event.node.NodeMutateEvent",
                    "net.luckperms.api.event.group.GroupDataRecalculateEvent",
                    "net.luckperms.api.event.user.UserDataRecalculateEvent")) {
                try {
                    Class<?> type = Class.forName(eventClass);
                    Object subscription = subscribe.invoke(bus, type, proxy);
                    if (subscription != null) {
                        luckPermsSubscriptions.add(subscription);
                        count++;
                    }
                } catch (Throwable ignored) {
                    // Event class differs between LuckPerms versions; try the next.
                }
            }
            if (count > 0) {
                plugin.getLogger().info("LuckPerms change events subscribed (" + count
                        + "); conflict/audit rescans are debounced.");
            }
            return count;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Could not subscribe to LuckPerms events: " + t.getMessage());
            return 0;
        }
    }

    /** Close LuckPerms event subscriptions held by this module. */
    public void unregisterChangeListeners() {
        for (Object subscription : luckPermsSubscriptions) {
            try {
                subscription.getClass().getMethod("close").invoke(subscription);
            } catch (Throwable ignored) {
                try {
                    subscription.getClass().getMethod("unsubscribe").invoke(subscription);
                } catch (Throwable ignored2) {
                    // Best-effort; the plugin is shutting down anyway.
                }
            }
        }
        luckPermsSubscriptions.clear();
    }

    /* ----------------------------------------------------------------------
     * LuckPerms snapshots used by conflict detection and the security audit.
     * Reflection keeps LuckPerms optional: no class is loaded when it is absent.
     * ---------------------------------------------------------------------- */

    /** Immutable permission-holder snapshot (group or user). */
    public static final class HolderSnapshot {
        public final String type;
        public final String name;
        public final String identifier;
        public final String primaryGroup;
        public final int weight;
        public final Map<String, Boolean> nodes;
        public final long expiresAt;
        public final boolean defaultGroup;

        HolderSnapshot(String type, String name, String identifier, String primaryGroup,
                       int weight, Map<String, Boolean> nodes, long expiresAt, boolean defaultGroup) {
            this.type = type;
            this.name = name;
            this.identifier = identifier;
            this.primaryGroup = primaryGroup;
            this.weight = weight;
            this.nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
            this.expiresAt = expiresAt;
            this.defaultGroup = defaultGroup;
        }

        /**
         * LuckPerms applies explicit nodes first and wildcard nodes next. A
         * negative exact node always wins over a positive wildcard.
         */
        public boolean grants(@NotNull String permission) {
            if (permission == null || permission.isEmpty()) return false;
            Boolean exact = nodes.get(permission);
            if (Boolean.FALSE.equals(exact)) return false;
            if (Boolean.TRUE.equals(exact)) return true;
            if (permission.indexOf('.') >= 0) {
                String current = permission;
                while (current.contains(".")) {
                    current = current.substring(0, current.lastIndexOf('.'));
                    Boolean inherited = nodes.get(current + ".*");
                    if (Boolean.FALSE.equals(inherited)) return false;
                    if (Boolean.TRUE.equals(inherited)) return true;
                }
            }
            Boolean global = nodes.get("*");
            return Boolean.TRUE.equals(global);
        }

        public boolean hasBypass(@NotNull String bypassNode) {
            return grants(bypassNode)
                    || grants("saryuantichatmanager.*")
                    || grants("*");
        }
    }

    public @NotNull List<HolderSnapshot> snapshotGroups(@NotNull String defaultGroup) {
        List<HolderSnapshot> result = new java.util.ArrayList<>();
        if (!luckpermsAvailable) return result;
        try {
            Object lp = luckPermsInstance();
            Object gm = groupManager(lp);
            if (gm == null) return result;
            int defaultWeight = groupWeight(groupByName(gm, defaultGroup));
            Class<?> gmIface = Class.forName("net.luckperms.api.model.group.GroupManager");
            Object rawGroups = gmIface.getMethod("getLoadedGroups").invoke(gm);
            if (!(rawGroups instanceof Collection<?> groups)) return result;
            Class<?> groupIface = Class.forName("net.luckperms.api.model.group.Group");
            for (Object group : groups) {
                Object nameObj = groupIface.getMethod("getName").invoke(group);
                String name = nameObj == null ? "" : nameObj.toString();
                if (name.isBlank()) continue;
                Map<String, Boolean> nodes = groupNodes(group);
                int weight = groupWeight(group);
                result.add(new HolderSnapshot("GROUP", name, name, name, weight,
                        nodes == null ? Map.of() : nodes, 0,
                        name.equalsIgnoreCase(defaultGroup) || weight == defaultWeight));
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "LuckPerms group snapshot failed: " + t.getMessage(), t);
        }
        return result;
    }

    /** Group names for admin tab completion; empty when LuckPerms is absent. */
    public @NotNull List<String> getLoadedGroupNames() {
        List<String> result = new java.util.ArrayList<>();
        if (!luckpermsAvailable) return result;
        try {
            Object gm = groupManager(luckPermsInstance());
            if (gm == null) return result;
            Class<?> gmIface = Class.forName("net.luckperms.api.model.group.GroupManager");
            Object rawGroups = gmIface.getMethod("getLoadedGroups").invoke(gm);
            if (rawGroups instanceof Collection<?> groups) {
                Class<?> groupIface = Class.forName("net.luckperms.api.model.group.Group");
                for (Object group : groups) {
                    Object nameObj = groupIface.getMethod("getName").invoke(group);
                    if (nameObj != null) result.add(nameObj.toString());
                }
            }
            java.util.Collections.sort(result);
        } catch (Throwable ignored) {
        }
        return result;
    }

    public @NotNull List<HolderSnapshot> snapshotUsers() {
        List<HolderSnapshot> result = new java.util.ArrayList<>();
        if (!luckpermsAvailable) return result;
        try {
            Object lp = luckPermsInstance();
            if (lp == null) return result;
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
            Object userManager = api.getMethod("getUserManager").invoke(lp);
            Class<?> umIface = Class.forName("net.luckperms.api.model.user.UserManager");
            Object rawUsers;
            try {
                rawUsers = umIface.getMethod("getLoadedUsers").invoke(userManager);
            } catch (NoSuchMethodException ex) {
                rawUsers = null;
            }
            if (!(rawUsers instanceof Collection<?> users)) return result;
            Class<?> userIface = Class.forName("net.luckperms.api.model.user.User");
            for (Object user : users) {
                Object uuidObj = userIface.getMethod("getUniqueId").invoke(user);
                Object nameObj = userIface.getMethod("getUsername").invoke(user);
                Object primaryObj = userIface.getMethod("getPrimaryGroup").invoke(user);
                String uuid = uuidObj == null ? "" : uuidObj.toString();
                String name = nameObj == null ? uuid : nameObj.toString();
                String primary = primaryObj == null ? "" : primaryObj.toString();
                Map<String, Boolean> nodes = holderNodes(user);
                result.add(new HolderSnapshot("USER", name, uuid, primary, 0,
                        nodes == null ? Map.of() : nodes, 0, false));
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "LuckPerms user snapshot failed: " + t.getMessage(), t);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Boolean> holderNodes(@NotNull Object holder) throws Exception {
        Object nodes;
        try {
            Class<?> holderIface = Class.forName("net.luckperms.api.model.PermissionHolder");
            Object queryOptions = holderIface.getMethod("getQueryOptions").invoke(holder);
            Class<?> queryIface = Class.forName("net.luckperms.api.query.QueryOptions");
            nodes = holderIface.getMethod("resolveInheritedNodes", queryIface).invoke(holder, queryOptions);
        } catch (Throwable ignored) {
            Class<?> holderIface = Class.forName("net.luckperms.api.model.PermissionHolder");
            nodes = holderIface.getMethod("getNodes").invoke(holder);
        }
        if (nodes instanceof Map<?, ?> map) {
            Map<String, Boolean> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), Boolean.TRUE.equals(e.getValue()));
            }
            return result;
        }
        if (nodes instanceof Collection<?> collection) {
            Map<String, Boolean> result = new java.util.HashMap<>();
            Class<?> nodeIface = Class.forName("net.luckperms.api.node.Node");
            for (Object node : collection) {
                Object key = nodeIface.getMethod("getKey").invoke(node);
                Object value = nodeIface.getMethod("getValue").invoke(node);
                if (key != null) {
                    result.put(key.toString(), value instanceof Boolean b ? b : Boolean.TRUE);
                }
            }
            return result;
        }
        return null;
    }

    /**
     * Whether a permission string looks like an admin-level permission.
     */
    public boolean isAdminPermission(@NotNull String perm) {
        if (perm == null || perm.isEmpty()) return false;
        String p = perm.toLowerCase(Locale.ROOT);
        if (memberSafeCommands.contains(p)) return false;
        if (p.equals("*") || p.contains(".*")) return true;
        for (String k : adminKeywords) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    /**
     * Scan a plugin's declared permissions for admin-level ones.
     */
    public @NotNull Set<String> scanPluginPermissions(@NotNull Plugin plugin) {
        Set<String> found = new HashSet<>();
        org.bukkit.plugin.PluginDescriptionFile desc = plugin.getDescription();
        // desc.getPermissions() has changed shape between Paper versions:
        // older builds return Map<String, Permission>, newer ones List<Permission>.
        Object raw = desc.getPermissions();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (permissionIsAdmin(name, entry.getValue())) found.add(name);
            }
        } else if (raw instanceof Collection<?> collection) {
            for (Object permObj : collection) {
                String name = readName(permObj);
                if (name != null && permissionIsAdmin(name, permObj)) found.add(name);
            }
        }
        // Also scan the permission attached to this plugin's commands.
        try {
            org.bukkit.command.CommandMap commandMap = plugin.getServer().getCommandMap();
            for (Command cmd : commandMap.getKnownCommands().values()) {
                if (!belongsTo(cmd, plugin)) continue;
                String perm = cmd.getPermission();
                if (perm != null && !perm.isEmpty() && isAdminPermission(perm)) {
                    found.add(perm);
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private boolean belongsTo(Command cmd, Plugin plugin) {
        if (cmd instanceof PluginCommand pc) {
            return pc.getPlugin() == plugin;
        }
        // Some server commands (vanilla) have no owning plugin; attribute
        // "minecraft.*" commands to the server itself.
        return cmd.getName().equals("minecraft") && plugin.getName().equals("Paper");
    }

    private @Nullable String readName(Object permObj) {
        if (permObj == null) return null;
        try {
            Object name = permObj.getClass().getMethod("getName").invoke(permObj);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean permissionIsAdmin(String name, Object permObj) {
        String def = readDefault(permObj);
        if (def != null) {
            String d = def.toUpperCase(Locale.ROOT);
            // TRUE / NOT_OP are available to ordinary players; OP / FALSE are not.
            if (d.equals("TRUE") || d.equals("NOT_OP")) return false;
            return true;
        }
        // No default information: fall back to the keyword heuristic.
        return isAdminPermission(name);
    }

    private @Nullable String readDefault(Object permObj) {
        if (permObj == null) return null;
        try {
            Object def = permObj.getClass().getMethod("getDefault").invoke(permObj);
            return def != null ? def.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ----------------------------------------------------------------------
     * LuckPerms (reflection, using the public API interfaces)
     * ---------------------------------------------------------------------- */

    private @Nullable Object luckPermsInstance() {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            return provider.getMethod("get").invoke(null);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "LuckPerms provider unavailable: " + t.getMessage());
            return null;
        }
    }

    private @Nullable Object groupManager(@Nullable Object luckPerms) throws Exception {
        if (luckPerms == null) return null;
        Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
        return api.getMethod("getGroupManager").invoke(luckPerms);
    }

    private @Nullable Object groupByName(@Nullable Object groupManager, @NotNull String name) throws Exception {
        if (groupManager == null) return null;
        Class<?> gm = Class.forName("net.luckperms.api.model.group.GroupManager");
        return gm.getMethod("getGroup", String.class).invoke(groupManager, name);
    }

    private int groupWeight(@Nullable Object group) throws Exception {
        if (group == null) return 0;
        Class<?> groupIface = Class.forName("net.luckperms.api.model.group.Group");
        Object weight = groupIface.getMethod("getWeight").invoke(group);
        // LuckPerms returns OptionalInt (older builds returned int).
        if (weight instanceof java.util.OptionalInt optional) {
            return optional.orElse(0);
        }
        return weight instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Boolean> groupNodes(@Nullable Object group) throws Exception {
        if (group == null) return null;
        Class<?> groupIface = Class.forName("net.luckperms.api.model.group.Group");
        Object nodes;
        try {
            // Include permissions inherited from parent groups.
            Class<?> holderIface = Class.forName("net.luckperms.api.model.PermissionHolder");
            Object queryOptions = holderIface.getMethod("getQueryOptions").invoke(group);
            Class<?> queryIface = Class.forName("net.luckperms.api.query.QueryOptions");
            nodes = holderIface.getMethod("resolveInheritedNodes", queryIface).invoke(group, queryOptions);
        } catch (Throwable ignored) {
            nodes = groupIface.getMethod("getNodes").invoke(group);
        }
        if (nodes instanceof Map<?, ?> map) {
            Map<String, Boolean> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), Boolean.TRUE.equals(e.getValue()));
            }
            return result;
        }
        if (nodes instanceof Collection<?> collection) {
            Map<String, Boolean> result = new java.util.HashMap<>();
            Class<?> nodeIface = Class.forName("net.luckperms.api.node.Node");
            for (Object node : collection) {
                Object key = nodeIface.getMethod("getKey").invoke(node);
                Object value = nodeIface.getMethod("getValue").invoke(node);
                if (key != null) {
                    result.put(key.toString(), value instanceof Boolean b ? b : Boolean.TRUE);
                }
            }
            return result;
        }
        return null;
    }

    /**
     * LuckPerms integration: determine groups above the default group by weight
     * and return the permissions that only those groups hold directly.
     */
    public @NotNull Set<String> scanLuckPermsStaffPermissions(@NotNull String defaultGroup, int staffMinWeight) {
        Set<String> staffPerms = new HashSet<>();
        if (!luckpermsAvailable) return staffPerms;
        try {
            Object lp = luckPermsInstance();
            if (lp == null) return staffPerms;
            Object gm = groupManager(lp);
            if (gm == null) return staffPerms;

            Class<?> gmIface = Class.forName("net.luckperms.api.model.group.GroupManager");
            Object defaultGroupObj = groupByName(gm, defaultGroup);
            int defaultWeight = groupWeight(defaultGroupObj);

            Object rawGroups = gmIface.getMethod("getLoadedGroups").invoke(gm);
            if (!(rawGroups instanceof Collection<?> groups)) {
                plugin.getLogger().warning("LuckPerms scan: getLoadedGroups() did not return a collection.");
                return staffPerms;
            }
            Class<?> groupIface = Class.forName("net.luckperms.api.model.group.Group");
            int groupsAbove = 0;
            for (Object group : groups) {
                Object nameObj = groupIface.getMethod("getName").invoke(group);
                String name = nameObj != null ? nameObj.toString() : "";
                if (name.equalsIgnoreCase(defaultGroup)) continue;
                int weight = groupWeight(group);
                boolean isStaff = weight > defaultWeight && weight >= staffMinWeight;
                if (!isStaff) continue;
                groupsAbove++;
                Map<String, Boolean> nodes = groupNodes(group);
                if (nodes == null) continue;
                for (Map.Entry<String, Boolean> node : nodes.entrySet()) {
                    if (Boolean.TRUE.equals(node.getValue())) {
                        String perm = node.getKey();
                        if (perm != null && !perm.isEmpty()) staffPerms.add(perm);
                    }
                }
            }
            plugin.getLogger().info("LuckPerms scan: " + groups.size() + " groups loaded, "
                    + groupsAbove + " above '" + defaultGroup + "' (weight > " + defaultWeight
                    + ", min " + staffMinWeight + "), " + staffPerms.size() + " staff permissions.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "LuckPerms reflection scan failed", t);
        }
        return staffPerms;
    }

    /**
     * Whether the player is above the default (member) group in LuckPerms.
     */
    public boolean isPlayerAboveMember(@NotNull Player player, @NotNull String defaultGroup) {
        if (!luckpermsAvailable) return player.isOp();
        try {
            Object lp = luckPermsInstance();
            if (lp == null) return player.isOp();

            Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
            Object userManager = api.getMethod("getUserManager").invoke(lp);
            Class<?> umIface = Class.forName("net.luckperms.api.model.user.UserManager");
            Object user = umIface.getMethod("getUser", java.util.UUID.class)
                    .invoke(userManager, player.getUniqueId());
            if (user == null) return player.isOp();

            Object gm = groupManager(lp);
            int defaultWeight = groupWeight(groupByName(gm, defaultGroup));

            Class<?> userIface = Class.forName("net.luckperms.api.model.user.User");
            Object primaryGroup = userIface.getMethod("getPrimaryGroup").invoke(user);
            int weight = defaultWeight;
            if (primaryGroup != null) {
                weight = Math.max(weight, groupWeight(groupByName(gm, primaryGroup.toString())));
            }

            Object nodesObj = userIface.getMethod("getNodes").invoke(user);
            if (nodesObj instanceof Map<?, ?> nodes) {
                for (Map.Entry<?, ?> e : nodes.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue()) && "*".equals(String.valueOf(e.getKey()))) {
                        return true;
                    }
                }
            }
            return weight > defaultWeight || player.isOp();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "LuckPerms reflection check failed: " + t.getMessage());
            return player.isOp();
        }
    }
}

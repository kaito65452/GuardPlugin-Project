package id.saryu.libcore.punishment;

import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.database.DatabaseModule;
import id.saryu.libcore.scheduler.SchedulerModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Punishment service: auto-detects the ban provider, parses durations, and
 * performs kick / ban / tempban through the best available adapter.
 */
public class PunishmentService {

    public enum Action { WARN, KICK, BAN, TEMPBAN }

    private final SaryuLib plugin;
    private BanProvider provider;
    private ProviderType providerType = ProviderType.VANILLA;
    private final DurationParser durationParser = new DurationParser();

    public PunishmentService(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        detectProvider();
    }

    private void detectProvider() {
        String configured = plugin.getConfigModule().getString("ban.provider", "AUTO");
        if (configured.equalsIgnoreCase("CUSTOM")) {
            provider = new CustomProvider(plugin);
            providerType = ProviderType.CUSTOM;
            logProvider("CUSTOM (configured)");
            return;
        }
        if (!configured.equalsIgnoreCase("AUTO")) {
            ProviderType forced = parseProvider(configured);
            provider = buildProvider(forced);
            if (provider != null && provider.isAvailable()) {
                providerType = forced;
                logProvider(forced.name() + " (forced)");
                return;
            }
            plugin.getLogger().warning("Forced ban provider " + configured + " unavailable; falling back to auto.");
        }
        // Priority: LiteBans -> LibertyBans -> AdvancedBan -> BanManager -> EssentialsX -> Vanilla.
        for (ProviderType type : new ProviderType[]{
                ProviderType.LITEBANS, ProviderType.LIBERTYBANS, ProviderType.ADVANCEDBAN,
                ProviderType.BANMANAGER, ProviderType.ESSENTIALS, ProviderType.VANILLA}) {
            provider = buildProvider(type);
            if (provider != null && provider.isAvailable()) {
                providerType = type;
                logProvider(type.name());
                return;
            }
        }
        // Fallback: never crash.
        provider = new VanillaProvider(plugin);
        providerType = ProviderType.VANILLA;
        logProvider("VANILLA (fallback)");
    }

    public void reloadProvider() {
        detectProvider();
    }

    public @NotNull ProviderType getProviderType() {
        return providerType;
    }

    public @NotNull BanProvider getProvider() {
        return provider;
    }

    public @NotNull DurationParser getDurationParser() {
        return durationParser;
    }

    /**
     * Issue a punishment. Runs on the main thread safely (dispatches DB writes async).
     */
    public void punish(@NotNull Player target, @NotNull Action action, @NotNull String duration,
                       @NotNull String reason, @NotNull String issuedBy) {
        long now = System.currentTimeMillis();
        long expires = -1;
        String displayDuration = duration;
        if (action == Action.BAN || action == Action.TEMPBAN) {
            DurationParser.ParsedDuration pd = durationParser.parse(duration);
            if (pd.permanent) {
                expires = -1;
                displayDuration = "perm";
            } else if (pd.millis > 0) {
                expires = now + pd.millis;
                displayDuration = duration;
            }
        }

        // Execute via provider.
        boolean executed = provider.execute(target, action, duration, reason);

        // Record to DB async.
        SchedulerModule sched = plugin.getSchedulerModule();
        String uuid = target.getUniqueId().toString();
        String name = target.getName();
        final String fd = displayDuration;
        final long exp = expires;
        final boolean exec = executed;
        schedulerModuleAsync(sched, () -> {
            try (Connection c = plugin.getDatabaseModule().getDataSource().getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO acm_punishments (player_uuid, player_name, action, duration, reason, issued_by, issued_at, expires_at) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, uuid);
                    ps.setString(2, name);
                    ps.setString(3, action.name());
                    ps.setString(4, fd);
                    ps.setString(5, reason);
                    ps.setString(6, issuedBy);
                    ps.setLong(7, now);
                    ps.setLong(8, exp);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to record punishment", e);
            }
        });

        // Notify staff + webhook handled by caller.
        plugin.getLogger().info("Punishment: " + action + " " + name + " (" + uuid + ") reason=" + reason + " duration=" + displayDuration + " by=" + issuedBy + " executed=" + executed);
    }

    public void kick(@NotNull Player target, @NotNull String reason, @NotNull String issuedBy) {
        punish(target, Action.KICK, "", reason, issuedBy);
    }

    public void ban(@NotNull Player target, @NotNull String reason, @NotNull String issuedBy) {
        punish(target, Action.BAN, "perm", reason, issuedBy);
    }

    public void tempban(@NotNull Player target, @NotNull String duration, @NotNull String reason, @NotNull String issuedBy) {
        punish(target, Action.TEMPBAN, duration, reason, issuedBy);
    }

    public void warn(@NotNull Player target, @NotNull String reason, @NotNull String issuedBy) {
        punish(target, Action.WARN, "", reason, issuedBy);
    }

    private void schedulerModuleAsync(SchedulerModule sched, java.lang.Runnable r) {
        sched.runAsync(r);
    }

    private ProviderType parseProvider(String name) {
        try {
            return ProviderType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ProviderType.VANILLA;
        }
    }

    @Nullable
    private BanProvider buildProvider(ProviderType type) {
        switch (type) {
            case LITEBANS -> { return isPlugin("LiteBans") ? new LiteBansProvider(plugin) : null; }
            case LIBERTYBANS -> { return isPlugin("LibertyBans") ? new LibertyBansProvider(plugin) : null; }
            case ADVANCEDBAN -> { return isPlugin("AdvancedBan") ? new AdvancedBanProvider(plugin) : null; }
            case BANMANAGER -> { return isPlugin("BanManager") ? new BanManagerProvider(plugin) : null; }
            case ESSENTIALS -> { return isPlugin("EssentialsX") ? new EssentialsProvider(plugin) : null; }
            case VANILLA -> { return new VanillaProvider(plugin); }
            case CUSTOM -> { return new CustomProvider(plugin); }
        }
        return null;
    }

    private boolean isPlugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name) != null;
    }

    private void logProvider(String label) {
        plugin.getLogger().info("Ban provider: " + label);
    }

    public enum ProviderType { LITEBANS, LIBERTYBANS, ADVANCEDBAN, BANMANAGER, ESSENTIALS, VANILLA, CUSTOM }

    /**
     * Adapter interface for ban plugins. Each provider exposes console command
     * templates that admins can customize.
     */
    public interface BanProvider {
        boolean isAvailable();
        boolean execute(Player target, Action action, String duration, String reason);
    }

    /** Base provider using Bukkit commands dispatched to console. */
    public abstract class ConsoleCommandProvider implements BanProvider {
        protected final SaryuLib plugin;
        protected String banCmd = "ban {PLAYER} {REASON}";
        protected String tempbanCmd = "tempban {PLAYER} {DURATION} {REASON}";
        protected String kickCmd = "kick {PLAYER} {REASON}";

        protected ConsoleCommandProvider(SaryuLib plugin) {
            this.plugin = plugin;
            loadTemplates();
        }

        protected void loadTemplates() {
            // Override in subclasses to read provider-specific config keys.
        }

        protected String fill(String template, Player target, Action action, String duration, String reason) {
            return template
                    .replace("{PLAYER}", safeValue(target.getName(), 64))
                    .replace("{DURATION}", safeValue(duration, 32))
                    .replace("{REASON}", safeValue(reason, 256));
        }

        /**
         * Never let a player-controlled reason inject a second command, a line
         * break, or a control sequence into a console command. Collections are
         * deliberately limited to printable text.
         */
        private String safeValue(String value, int max) {
            if (value == null) return "";
            String cleaned = value.replaceAll("[\\r\\n\\u0000-\\u001F\\u007F]", " ")
                    .replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Zs}]", "?")
                    .trim();
            return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
        }

        @Override
        public boolean execute(Player target, Action action, String duration, String reason) {
            String cmd;
            switch (action) {
                case BAN -> cmd = banCmd;
                case TEMPBAN -> cmd = tempbanCmd;
                case KICK -> cmd = kickCmd;
                default -> cmd = banCmd;
            }
            dispatchConsole(fill(cmd, target, action, duration, reason));
            return true;
        }

        protected void dispatchConsole(String command) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    public class LiteBansProvider extends ConsoleCommandProvider {
        public LiteBansProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return Bukkit.getPluginManager().getPlugin("LiteBans") != null; }
        @Override protected void loadTemplates() {
            banCmd = "ban {PLAYER} {REASON}";
            tempbanCmd = "tempban {PLAYER} {DURATION} {REASON}";
            kickCmd = "kick {PLAYER} {REASON}";
        }
    }

    public class LibertyBansProvider extends ConsoleCommandProvider {
        public LibertyBansProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return Bukkit.getPluginManager().getPlugin("LibertyBans") != null; }
        @Override protected void loadTemplates() {
            banCmd = "lb ban {PLAYER} perm {REASON}";
            tempbanCmd = "lb tempban {PLAYER} {DURATION} {REASON}";
            kickCmd = "lb kick {PLAYER} {REASON}";
        }
    }

    public class AdvancedBanProvider extends ConsoleCommandProvider {
        public AdvancedBanProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return Bukkit.getPluginManager().getPlugin("AdvancedBan") != null; }
        @Override protected void loadTemplates() {
            banCmd = "ab ban {PLAYER} {REASON}";
            tempbanCmd = "ab tempban {PLAYER} {DURATION} {REASON}";
            kickCmd = "ab kick {PLAYER} {REASON}";
        }
    }

    public class BanManagerProvider extends ConsoleCommandProvider {
        public BanManagerProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return Bukkit.getPluginManager().getPlugin("BanManager") != null; }
        @Override protected void loadTemplates() {
            banCmd = "ban {PLAYER} {REASON}";
            tempbanCmd = "tempban {PLAYER} {DURATION} {REASON}";
            kickCmd = "kick {PLAYER} {REASON}";
        }
    }

    public class EssentialsProvider extends ConsoleCommandProvider {
        public EssentialsProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return Bukkit.getPluginManager().getPlugin("EssentialsX") != null || Bukkit.getPluginManager().getPlugin("Essentials") != null; }
        @Override protected void loadTemplates() {
            banCmd = "m:ban {PLAYER} {REASON}";
            tempbanCmd = "m:tempban {PLAYER} {DURATION} {REASON}";
            kickCmd = "m:kick {PLAYER} {REASON}";
        }
    }

    public class VanillaProvider implements BanProvider {
        protected final SaryuLib plugin;
        public VanillaProvider(SaryuLib plugin) { this.plugin = plugin; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute(Player target, Action action, String duration, String reason) {
            String reasonFinal = (reason == null || reason.isBlank()) ? "Blacklisted command abuse" : reason;
            if (action == Action.BAN || action == Action.TEMPBAN) {
                DurationParser.ParsedDuration pd = durationParser.parse(duration == null ? "perm" : duration);
                Date expires = null;
                if (!pd.permanent) {
                    long millis = pd.millis > 0 ? pd.millis : 86400000L;
                    expires = new Date(System.currentTimeMillis() + millis);
                }
                // Paper 26.2 removed name-based profile bans: BanList.Type.NAME now
                // delegates to CraftProfileBanList, so passing a String causes a
                // ClassCastException. Player#ban() resolves the PlayerProfile and
                // kicks the player in one step.
                target.ban(reasonFinal, expires, "AntiChatManager", true);
            } else if (action == Action.KICK) {
                Player pl = Bukkit.getPlayer(target.getUniqueId());
                if (pl != null) pl.kick(ComponentText.legacy(reasonFinal));
            }
            return true;
        }
    }

    public class CustomProvider extends ConsoleCommandProvider {
        public CustomProvider(SaryuLib p) { super(p); }
        @Override public boolean isAvailable() { return true; }
        @Override protected void loadTemplates() {
            banCmd = plugin.getConfigModule().getString("ban.custom.ban", "ban {PLAYER} {REASON}");
            tempbanCmd = plugin.getConfigModule().getString("ban.custom.tempban", "tempban {PLAYER} {DURATION} {REASON}");
            kickCmd = plugin.getConfigModule().getString("ban.custom.kick", "kick {PLAYER} {REASON}");
        }
    }
}

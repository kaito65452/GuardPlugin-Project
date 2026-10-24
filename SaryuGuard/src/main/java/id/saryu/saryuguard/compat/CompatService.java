package id.saryu.saryuguard.compat;

import id.saryu.saryuguard.SaryuGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Detects anti-cheats and companion plugins, verifying plugin names against
 * their plugin.yml descriptions and active state.
 *
 * <p>Requirements:
 * 3.1 Detect: GrimAC, Vulcan, Themis, NovaAC, Sonar, AuthMe, and others via PluginManager.
 * Plugin name verified from each plugin.yml. Considered present only if isEnabled().
 * Warning if two heavy anti cheats are active. Results shared via CompatService.
 * AuthMe present: auth commands not blocked and arguments never saved;
 * 2FA starts AFTER successful AuthMe login.</p>
 */
public class CompatService {

    public enum KnownAntiCheat {
        GRIM_AC("GrimAC", true),
        VULCAN("Vulcan", true),
        THEMIS("Themis", false),
        NOVA_AC("NovaAC", true),
        SONAR("Sonar", true),
        MATRIX("Matrix", true),
        SPARTAN("Spartan", true),
        INTAVE("Intave", true);

        private final String pluginName;
        private final boolean heavy;

        KnownAntiCheat(String pluginName, boolean heavy) {
            this.pluginName = pluginName;
            this.heavy = heavy;
        }

        public String getPluginName() {
            return pluginName;
        }

        public boolean isHeavy() {
            return heavy;
        }
    }

    private final SaryuGuardPlugin plugin;
    private final Set<KnownAntiCheat> detectedAntiCheats = new HashSet<>();
    private boolean authMePresent = false;
    private boolean sonarPresent = false;

    private static final Set<String> AUTH_COMMANDS = Set.of(
            "login", "l", "register", "reg", "changepassword", "cp",
            "email", "authme", "unregister", "2fa", "totp"
    );

    public CompatService(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void scan() {
        detectedAntiCheats.clear();
        authMePresent = false;
        sonarPresent = false;

        PluginManager pm = Bukkit.getPluginManager();

        // 1. Check Anti-Cheats
        int heavyAcCount = 0;
        StringBuilder heavyAcNames = new StringBuilder();

        for (KnownAntiCheat ac : KnownAntiCheat.values()) {
            Plugin p = pm.getPlugin(ac.getPluginName());
            if (p != null && p.isEnabled() && verifyPluginIdentity(p, ac.getPluginName())) {
                detectedAntiCheats.add(ac);
                if (ac == KnownAntiCheat.SONAR) {
                    sonarPresent = true;
                }
                if (ac.isHeavy()) {
                    heavyAcCount++;
                    if (heavyAcNames.length() > 0) heavyAcNames.append(", ");
                    heavyAcNames.append(ac.getPluginName());
                }
            }
        }

        // Warn if two or more heavy anti-cheats are active concurrently
        if (heavyAcCount >= 2) {
            plugin.getLogger().warning("[COMPAT WARNING] Terdeteksi " + heavyAcCount
                    + " anti-cheat berat aktif secara bersamaan (" + heavyAcNames
                    + "). Hal ini dapat menyebabkan konflik performa, false positives, dan latensi tick.");
        }

        // 2. Check AuthMe
        Plugin authMe = pm.getPlugin("AuthMe");
        if (authMe != null && authMe.isEnabled() && verifyPluginIdentity(authMe, "AuthMe")) {
            authMePresent = true;
            plugin.getLogger().info("[COMPAT] AuthMe terdeteksi dan aktif. Perintah autentikasi akan diproteksi dan dikecualikan dari logging.");
        }
    }

    /**
     * Verifies that the plugin is genuine by inspecting its plugin.yml declaration.
     */
    private boolean verifyPluginIdentity(@NotNull Plugin p, @NotNull String expectedName) {
        try {
            return p.getDescription() != null && expectedName.equalsIgnoreCase(p.getDescription().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized boolean isAntiCheatActive(@NotNull KnownAntiCheat ac) {
        return detectedAntiCheats.contains(ac);
    }

    public synchronized @NotNull Set<KnownAntiCheat> getActiveAntiCheats() {
        return Collections.unmodifiableSet(new HashSet<>(detectedAntiCheats));
    }

    public synchronized boolean isAuthMePresent() {
        return authMePresent;
    }

    public synchronized boolean isSonarPresent() {
        return sonarPresent;
    }

    /**
     * Check if a command is an authentication command (e.g. /login, /register).
     */
    public boolean isAuthCommand(@Nullable String command) {
        if (command == null) return false;
        String clean = command.trim().toLowerCase(Locale.ROOT);
        while (clean.startsWith("/")) clean = clean.substring(1);
        int space = clean.indexOf(' ');
        String root = (space > 0) ? clean.substring(0, space) : clean;
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        return AUTH_COMMANDS.contains(root);
    }

    /**
     * Redact authentication command arguments to prevent logging plaintext passwords.
     * E.g. "/login mySecretPassword123" -> "/login [REDACTED]"
     */
    public @NotNull String redactAuthCommand(@NotNull String rawCommand) {
        if (!isAuthCommand(rawCommand)) {
            return rawCommand;
        }
        String clean = rawCommand.trim();
        int space = clean.indexOf(' ');
        if (space > 0) {
            return clean.substring(0, space) + " [REDACTED]";
        }
        return clean;
    }
}

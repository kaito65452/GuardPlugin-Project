package id.saryu.libcore.integration;

import id.saryu.libcore.SaryuLib;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Integrations: PlaceholderAPI placeholders, Floodgate/Geyser bedrock detection,
 * and Discord webhook notifications. All optional; degrade gracefully. Uses
 * reflection so these are soft dependencies (no compile-time requirement).
 */
public class IntegrationModule {

    private final SaryuLib plugin;
    private final boolean placeholderApi;
    private final boolean floodgate;

    public IntegrationModule(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        boolean pa = false;
        boolean fg = false;
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
            pa = true;
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            fg = true;
        } catch (ClassNotFoundException ignored) {}
        this.placeholderApi = pa;
        this.floodgate = fg;
        if (placeholderApi) plugin.getLogger().info("PlaceholderAPI integration enabled (reflection).");
        if (floodgate) plugin.getLogger().info("Floodgate integration enabled (reflection).");
    }

    public boolean hasPlaceholderApi() { return placeholderApi; }
    public boolean hasFloodgate() { return floodgate; }

    public boolean isBedrock(@NotNull Player player) {
        if (!floodgate) return false;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return false;
            Object linked = api.getClass().getMethod("isLinked", java.util.UUID.class)
                    .invoke(api, player.getUniqueId());
            return linked != null && (boolean) linked;
        } catch (Exception ignored) {}
        return false;
    }

    public void registerPlaceholder(@NotNull String identifier) {
        if (!placeholderApi) return;
        plugin.getLogger().info("PlaceholderAPI expansion '" + identifier + "' noted (stub; implement via me.clip placeholderapi).");
    }

    /**
     * Send a Discord webhook embed (best-effort, async).
     */
    public void sendWebhook(@NotNull String url, @NotNull String content) {
        if (url == null || url.isEmpty()) return;
        plugin.getSchedulerModule().runAsync(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);
                String json = "{\"content\":\"" + escapeJson(content) + "\"}";
                byte[] b = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                con.setFixedLengthStreamingMode(b.length);
                con.getOutputStream().write(b);
                int code = con.getResponseCode();
                if (code >= 400) {
                    plugin.getLogger().warning("Discord webhook returned " + code);
                }
                con.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

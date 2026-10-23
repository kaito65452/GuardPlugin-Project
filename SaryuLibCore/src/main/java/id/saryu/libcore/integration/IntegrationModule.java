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
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !(host.equals("discord.com") || host.equals("discordapp.com")
                    || host.endsWith(".discord.com") || host.endsWith(".discordapp.com"))) {
                plugin.getLogger().warning("Webhook rejected: only HTTPS Discord hosts are allowed.");
                return;
            }
            String json = "{\"content\":\"" + escapeJson(content)
                    + "\",\"allowed_mentions\":{\"parse\":[]}}";
            plugin.getRepositoryClient().post(url, "Saryu-AntiChatManager", json)
                    .exceptionally(e -> {
                        plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().warning("Webhook rejected: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}

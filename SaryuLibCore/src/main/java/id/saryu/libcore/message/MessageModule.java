package id.saryu.libcore.message;

import id.saryu.libcore.SaryuLib;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Message & component utilities: hex colors (&#RRGGBB / <#RRGGBB>), gradients,
 * legacy '&' codes, MiniMessage, and placeholder escaping so player text cannot
 * inject MiniMessage tags.
 */
public class MessageModule {

    private final SaryuLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageModule(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
    }

    /**
     * Parse a raw string into a Component. Supports MiniMessage tags plus legacy
     * '&' color codes. Player input should be escaped via {@link #escape(String)}
     * before being inserted.
     */
    public @NotNull Component parse(@NotNull String input) {
        if (input == null) {
            return Component.empty();
        }
        String s = input;
        // &#RRGGBB -> <#RRGGBB>
        s = s.replaceAll("&#([A-Fa-f0-9]{6})", "<#$1>");
        // Legacy & codes -> MiniMessage tags.
        s = legacyToMiniMessage(s);
        try {
            return miniMessage.deserialize(s);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to parse message: " + input + " -> " + ex.getMessage());
            return Component.text(sanitizePlain(s));
        }
    }

    private @NotNull String legacyToMiniMessage(@NotNull String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                String tag = switch (code) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Serialize a Component to plain text (strips all formatting).
     */
    public @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Escape player-supplied text so it cannot inject MiniMessage tags.
     */
    public @NotNull String escape(@NotNull String input) {
        if (input == null) return "";
        // Escape MiniMessage tag delimiters. '&' is harmless in MiniMessage and is
        // left alone so legacy text from config keeps working.
        return input.replace("<", "\\<");
    }

    /**
     * Build a component from a template string replacing {@code {KEY}} placeholders.
     * Player values are escaped before substitution.
     */
    public @NotNull Component template(@NotNull String tmpl, @NotNull Map<String, String> vars) {
        String result = tmpl;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", escape(e.getValue()));
        }
        return parse(result);
    }

    /**
     * Legacy color helper: &f -> white, etc.
     */
    public @NotNull Component legacy(@NotNull String input) {
        return LegacyComponentSerializer.legacySection().deserialize(input);
    }

    /**
     * Gradient between two hex colors applied to the whole text.
     */
    public @NotNull Component gradient(@NotNull String text, @NotNull String fromHex, @NotNull String toHex) {
        return miniMessage.deserialize("<gradient:#" + cleanHex(fromHex) + ":#" + cleanHex(toHex) + ">"
                + escape(text) + "</gradient>");
    }

    public @NotNull MiniMessage miniMessage() {
        return miniMessage;
    }

    private @NotNull String cleanHex(@NotNull String h) {
        h = h.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (!h.matches("[0-9A-Fa-f]{6}")) h = "FFFFFF";
        return h;
    }

    private @NotNull String sanitizePlain(@NotNull String s) {
        return s.replace("<", "").replace(">", "");
    }

    /**
     * Placeholder helper namespace so call sites read clearly:
     * {@code Placeholder.unparsed(playerName)}.
     */
    public static final class Placeholder {
        public Placeholder() {}

        /** Escape text that comes from a player so it can't inject tags. */
        public @NotNull String unparsed(@Nullable String text) {
            if (text == null) return "";
            return text.replace("<", "\\<");
        }
    }
}

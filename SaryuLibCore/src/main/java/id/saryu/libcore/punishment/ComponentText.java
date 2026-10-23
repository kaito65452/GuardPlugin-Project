package id.saryu.libcore.punishment;

import net.kyori.adventure.text.Component;

/**
 * Small text helper used by punishment providers (kept here to avoid adding
 * Adventure dependency elsewhere).
 */
final class ComponentText {
    private ComponentText() {}
    static Component legacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text);
    }
}

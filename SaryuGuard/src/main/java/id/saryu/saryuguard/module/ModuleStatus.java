package id.saryu.saryuguard.module;

import org.jetbrains.annotations.NotNull;

public enum ModuleStatus {
    ACTIVE("<green>ACTIVE</green>"),
    DEGRADED("<gold>DEGRADED</gold>"),
    DISABLED("<gray>DISABLED</gray>"),
    ERROR("<red>ERROR</red>");

    private final String formatted;

    ModuleStatus(String formatted) {
        this.formatted = formatted;
    }

    public @NotNull String label() {
        return name();
    }

    public @NotNull String formatted() {
        return formatted;
    }
}

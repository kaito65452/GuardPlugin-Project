package id.saryu.antichatmanager.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after AntiChatManager blocks a command/chat attempt. This is a
 * notification event; cancellation is intentionally not supported because the
 * command has already been stopped by the packet/Bukkit layer.
 */
public class BlockedCommandEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String rawInput;
    private final String matchedCommand;
    private final String source;

    public BlockedCommandEvent(@NotNull Player player, @NotNull String rawInput,
                               @NotNull String matchedCommand, @NotNull String source) {
        this.player = player;
        this.rawInput = rawInput;
        this.matchedCommand = matchedCommand;
        this.source = source;
    }

    public @NotNull Player getPlayer() { return player; }
    public @NotNull String getRawInput() { return rawInput; }
    public @NotNull String getMatchedCommand() { return matchedCommand; }
    public @NotNull String getSource() { return source; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

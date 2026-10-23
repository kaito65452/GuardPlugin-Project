package id.saryu.antichatmanager.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player tries to execute a blacklisted command.
 *
 * <p>The command has already been stopped by the packet/Bukkit layer when this
 * event runs, so cancelling cannot un-block it. Cancelling suppresses the
 * alert, punishment escalation and subsequent {@link BlockedCommandEvent};
 * use it for custom alerting or for trusted automation that should not create
 * a punishment. The blocked attempt is still logged.</p>
 */
public class CommandBlacklistedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String rawInput;
    private final String matchedCommand;
    private final String source;
    private boolean cancelled;

    public CommandBlacklistedEvent(@NotNull Player player, @NotNull String rawInput,
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
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

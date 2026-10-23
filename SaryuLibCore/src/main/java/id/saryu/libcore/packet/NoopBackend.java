package id.saryu.libcore.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Set;

/**
 * No-op packet layer used when PacketEvents is absent.
 */
class NoopBackend implements PacketLayer.PacketBackend {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean isPlayerBedrock(Player player) {
        return false;
    }

    @Override
    @Nullable
    public String getPlayerAddress(Player player) {
        return null;
    }

    @Override
    public void registerCommandInterceptor(PacketLayer.CommandInterceptor interceptor) {
    }

    @Override
    public void unregisterCommandInterceptor(PacketLayer.CommandInterceptor interceptor) {
    }

    @Override
    public void sendTabListFilter(Player player, Set<String> hidden) {
    }
}

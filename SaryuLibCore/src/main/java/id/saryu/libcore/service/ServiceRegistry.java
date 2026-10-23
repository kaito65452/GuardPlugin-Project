package id.saryu.libcore.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Central service registry. SaryuLibCore registers its modules here so any
 * consuming plugin can look up shared services via {@code SaryuLib.getService(...)}.
 */
public class ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public void register(@NotNull Class<?> type, @NotNull Object service) {
        services.put(type, service);
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(@NotNull Class<T> type) {
        return (T) services.get(type);
    }

    public void unregister(@NotNull Class<?> type) {
        services.remove(type);
    }

    public boolean has(@NotNull Class<?> type) {
        return services.containsKey(type);
    }

    public void clear() {
        services.clear();
    }
}

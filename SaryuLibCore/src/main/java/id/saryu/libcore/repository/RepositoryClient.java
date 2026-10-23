package id.saryu.libcore.repository;

import id.saryu.libcore.SaryuLib;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Small, defensive async HTTP client for Modrinth/Hangar/GitHub/Discord.
 *
 * <p>Only fixed HTTPS hosts are allowed. Redirects are followed manually and
 * only to the same allowlist, private/loopback addresses are rejected after DNS
 * resolution, response bodies are capped, and requests time out. This is the
 * shared implementation behind the plugin-identity cache and webhooks.</p>
 */
public class RepositoryClient {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.modrinth.com",
            "hangar.papermc.io",
            "api.github.com",
            "discord.com",
            "discordapp.com"
    );
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private final SaryuLib plugin;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public RepositoryClient(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
    }

    public @NotNull CompletableFuture<String> get(@NotNull String url, @NotNull String userAgent) {
        return request("GET", url, userAgent, null);
    }

    public @NotNull CompletableFuture<String> post(@NotNull String url, @NotNull String userAgent,
                                                   @NotNull String json) {
        return request("POST", url, userAgent, json);
    }

    private @NotNull CompletableFuture<String> request(@NotNull String method, @NotNull String url,
                                                       @NotNull String userAgent, @Nullable String body) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            URI uri = validate(url);
            send(method, uri, userAgent, body, 0, result);
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    private void send(@NotNull String method, @NotNull URI uri, @NotNull String userAgent,
                      @Nullable String body, int redirects, @NotNull CompletableFuture<String> result) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", safeUserAgent(userAgent))
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body,
                                StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                            return;
                        }
                        int status = response.statusCode();
                        if (status >= 300 && status < 400) {
                            String location = response.headers().firstValue("location").orElse(null);
                            if (location == null || redirects >= MAX_REDIRECTS) {
                                result.completeExceptionally(new IllegalStateException("Unsafe redirect"));
                                return;
                            }
                            try {
                                URI next = validate(uri.resolve(location).toString());
                                if (!sameHost(uri, next)) {
                                    result.completeExceptionally(new IllegalStateException("Cross-host redirect rejected"));
                                    return;
                                }
                                send(method, next, userAgent, body, redirects + 1, result);
                            } catch (Exception ex) {
                                result.completeExceptionally(ex);
                            }
                            return;
                        }
                        byte[] bytes = response.body();
                        if (bytes != null && bytes.length > MAX_BYTES) {
                            result.completeExceptionally(new IllegalStateException("Response too large"));
                            return;
                        }
                        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
                        if (status >= 400) {
                            result.completeExceptionally(new HttpStatusException(status, text));
                        } else {
                            result.complete(text);
                        }
                    });
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
    }

    private @NotNull URI validate(@NotNull String raw) throws Exception {
        URI uri = URI.create(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Host not allowlisted: " + host);
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Private/loopback address rejected: " + host);
            }
        }
        return uri;
    }

    private boolean sameHost(@NotNull URI a, @NotNull URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    private @NotNull String safeUserAgent(@NotNull String userAgent) {
        String clean = userAgent.replaceAll("[\\r\\n\\p{Cc}]", " ").trim();
        if (clean.isEmpty()) clean = "Saryu/AntiChatManager";
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    public static final class HttpStatusException extends RuntimeException {
        public final int status;

        public HttpStatusException(int status, @Nullable String body) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}

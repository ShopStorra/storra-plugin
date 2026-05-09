package xyz.storra.plugin.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * HTTP client for Storra's `/api/v1/plugin/*` surface.
 *
 * Each call signs the request body via {@link HmacSigner} and
 * attaches X-Server-Id / X-Timestamp / X-Nonce / X-Signature
 * headers. The pair() call is a special case — no signing yet
 * (the code IS the auth proof), and the response carries the
 * server-id + secret that subsequent requests will sign with.
 *
 * Connection timeout: 10s. Body cap: not enforced client-side
 * (Storra's per-route validators handle that). Errors surface as
 * exceptions; callers (PollService, DeliveryManager) handle
 * retry / backoff.
 */
public final class StorraApiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final Gson GSON = new Gson();
    private static final Type TASK_LIST_TYPE = new TypeToken<List<DeliveryTask>>() {}.getType();
    private static final String EMPTY_BODY = "";

    private final String baseUrl;
    private final HmacSigner signer;
    private final Logger log;

    public StorraApiClient(String baseUrl, HmacSigner signer, Logger log) {
        // Trim trailing slash so callers can pass either form.
        this.baseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        this.signer = signer;
        this.log = log;
    }

    // ── /pair (no HMAC — code IS the auth) ────────────────────────────────

    public record PairResult(String serverId, String secret) {}

    /**
     * Exchange a one-time pairing code for (server_id, secret).
     * Returns null if the server rejects the code (E8102 — invalid /
     * expired / consumed). Caller writes the returned values to
     * config.yml.
     */
    public static PairResult pair(String baseUrl, String code, Logger log) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of("code", code));
        URI uri = URI.create(rstrip(baseUrl) + "/api/v1/plugin/pair");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warning("pair: HTTP " + res.statusCode() + " — " + res.body());
            return null;
        }
        JsonObject payload = GSON.fromJson(res.body(), JsonObject.class);
        if (payload == null || !payload.has("server_id") || !payload.has("secret")) {
            log.warning("pair: malformed response");
            return null;
        }
        return new PairResult(
            payload.get("server_id").getAsString(),
            payload.get("secret").getAsString()
        );
    }

    private static String rstrip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ── Authenticated actions ─────────────────────────────────────────────

    /** GET /api/v1/plugin/pending — claim ready-to-deliver tasks. */
    public List<DeliveryTask> fetchPending() throws IOException, InterruptedException {
        HttpResponse<String> res = signedSend("/api/v1/plugin/pending", "GET", EMPTY_BODY);
        if (res.statusCode() != 200) {
            log.warning("fetchPending: HTTP " + res.statusCode() + " — " + res.body());
            return Collections.emptyList();
        }
        List<DeliveryTask> tasks = GSON.fromJson(res.body(), TASK_LIST_TYPE);
        return tasks == null ? Collections.emptyList() : tasks;
    }

    /** POST /api/v1/plugin/confirm — mark a delivery successful. */
    public boolean confirm(long taskId) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of("task_id", taskId));
        HttpResponse<String> res = signedSend("/api/v1/plugin/confirm", "POST", body);
        if (res.statusCode() != 200) {
            log.warning("confirm: HTTP " + res.statusCode() + " — " + res.body());
            return false;
        }
        return true;
    }

    /** POST /api/v1/plugin/fail — report a delivery failure. */
    public boolean fail(long taskId, String reason) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of(
            "task_id", taskId,
            "reason", reason == null ? "" : reason
        ));
        HttpResponse<String> res = signedSend("/api/v1/plugin/fail", "POST", body);
        if (res.statusCode() != 200) {
            log.warning("fail: HTTP " + res.statusCode() + " — " + res.body());
            return false;
        }
        return true;
    }

    /** POST /api/v1/plugin/heartbeat — server diagnostics. */
    public boolean heartbeat(HeartbeatStats stats) throws IOException, InterruptedException {
        String body = GSON.toJson(stats);
        HttpResponse<String> res = signedSend("/api/v1/plugin/heartbeat", "POST", body);
        if (res.statusCode() != 200) {
            log.warning("heartbeat: HTTP " + res.statusCode() + " — " + res.body());
            return false;
        }
        return true;
    }

    public record HeartbeatStats(
        @com.google.gson.annotations.SerializedName("plugin_version") String pluginVersion,
        @com.google.gson.annotations.SerializedName("player_count") int playerCount,
        double tps,
        @com.google.gson.annotations.SerializedName("memory_mb") long memoryMb,
        @com.google.gson.annotations.SerializedName("cpu_percent") double cpuPercent
    ) {}

    // ── Internals ─────────────────────────────────────────────────────────

    private HttpResponse<String> signedSend(
        String path,
        String method,
        String body
    ) throws IOException, InterruptedException {
        HmacSigner.Signed s = signer.sign(body);
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("X-Server-Id", s.serverId())
            .header("X-Timestamp", s.timestampMs())
            .header("X-Nonce", s.nonce())
            .header("X-Signature", s.signature())
            .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            // Body included in the signature even for GETs (empty
            // string signed as sha256("")). HttpRequest.GET() has no
            // body, so we bypass it for body-included signing.
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

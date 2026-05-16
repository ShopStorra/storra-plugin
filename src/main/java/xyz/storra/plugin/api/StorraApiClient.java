package xyz.storra.plugin.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.io.IOException;
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
    private static final String EMPTY_BODY = "";

    /** Wire envelope for `GET /pending`: `{ "commands": [ DeliveryTask, ... ] }`. */
    private static final class PendingResponse {
        List<DeliveryTask> commands;
    }

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

    /**
     * Helper — log non-200 responses, downgrading to INFO when the
     * status is a transient gateway error (502/503/504). Those
     * codes mean the reverse-proxy (Caddy) couldn't reach the
     * upstream app for a few seconds — typically during a Storra
     * deploy's container-recreate window. Next poll/heartbeat tick
     * retries naturally; no operator action needed.
     */
    private void logNonOk(String op, int status, String body) {
        boolean transientGateway = status == 502 || status == 503 || status == 504;
        String msg = op + ": HTTP " + status + " — " + body;
        if (transientGateway) {
            log.info(msg + " (transient — retrying on next tick)");
        } else {
            log.warning(msg);
        }
    }

    /** GET /api/v1/plugin/pending — claim ready-to-deliver tasks. */
    public List<DeliveryTask> fetchPending() throws IOException, InterruptedException {
        HttpResponse<String> res = signedSend("/api/v1/plugin/pending", "GET", EMPTY_BODY);
        if (res.statusCode() != 200) {
            logNonOk("fetchPending", res.statusCode(), res.body());
            return Collections.emptyList();
        }
        PendingResponse parsed = GSON.fromJson(res.body(), PendingResponse.class);
        if (parsed == null || parsed.commands == null) {
            return Collections.emptyList();
        }
        return parsed.commands;
    }

    /** POST /api/v1/plugin/confirm — mark a delivery successful. */
    public boolean confirm(String commandId) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of("commandId", commandId));
        HttpResponse<String> res = signedSend("/api/v1/plugin/confirm", "POST", body);
        if (res.statusCode() != 200) {
            logNonOk("confirm", res.statusCode(), res.body());
            return false;
        }
        return true;
    }

    /** POST /api/v1/plugin/fail — report a delivery failure. */
    public boolean fail(String commandId, String reason) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of(
            "commandId", commandId,
            "reason", reason == null ? "" : reason
        ));
        HttpResponse<String> res = signedSend("/api/v1/plugin/fail", "POST", body);
        if (res.statusCode() != 200) {
            logNonOk("fail", res.statusCode(), res.body());
            return false;
        }
        return true;
    }

    /**
     * POST /api/v1/plugin/heartbeat — server diagnostics.
     *
     * Server responds with 200 + `{ok: true, ...}` on success, or
     * 200 + `{ok: false, reason: "transient"}` when a downstream
     * write briefly fails (typically during a Storra deploy's
     * container-recreate window). The transient case is INFO-level
     * since the next heartbeat will retry naturally; only hard
     * non-200s warrant a WARNING.
     */
    public boolean heartbeat(HeartbeatStats stats) throws IOException, InterruptedException {
        String body = GSON.toJson(stats);
        HttpResponse<String> res = signedSend("/api/v1/plugin/heartbeat", "POST", body);
        if (res.statusCode() != 200) {
            logNonOk("heartbeat", res.statusCode(), res.body());
            return false;
        }
        // Inspect the body for the soft-failure marker.
        String responseBody = res.body();
        if (responseBody != null && responseBody.contains("\"ok\":false")) {
            String reason = responseBody.contains("\"reason\":\"")
                ? responseBody.replaceAll(".*\"reason\":\"([^\"]*)\".*", "$1")
                : "unknown";
            log.info("heartbeat: server soft-fail (" + reason
                + ") — retrying on next tick");
            return false;
        }
        return true;
    }

    /**
     * Wire shape for POST /heartbeat. Field names are camelCase to
     * match what Storra's plugin REST handler reads
     * (`src/routes/api/v1/plugin/$.ts:handleHeartbeat`) — the v1
     * snake_case shape silently landed NULLs in game_servers
     * because the server's `body.playerCount` lookup never found
     * `body.player_count`. Field set matches every column the
     * server is willing to write; null/-1 sentinels are filtered
     * server-side so partial snapshots are fine.
     */
    public record HeartbeatStats(
        String version,
        int playerCount,
        int maxPlayers,
        double tps,
        double mspt,
        long memoryUsedMb,
        long memoryMaxMb,
        double cpuPercent,
        int entityCount,
        int chunkCount
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

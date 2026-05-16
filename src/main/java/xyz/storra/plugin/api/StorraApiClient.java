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
     * Parsed shape of the /heartbeat response. Server returns the
     * latest published plugin version + an updateAvailable flag
     * computed from what the plugin reported in the request body.
     */
    public record HeartbeatResponse(
        boolean ok,
        String latestVersion,
        boolean updateAvailable
    ) {
        public static HeartbeatResponse failed() {
            return new HeartbeatResponse(false, null, false);
        }
    }

    /** Result of /checkout-url — drives `/storra checkout` + `/storra sendlink`. */
    public record CheckoutUrlResult(
        String url,
        String packageId,
        String packageName,
        String storeName,
        String error
    ) {
        public boolean ok() { return error == null; }
    }

    private static final class CheckoutUrlRaw {
        String url;
        String packageId;
        String packageName;
        String storeName;
        String error;
    }

    /** Result of /lookup — drives `/storra lookup`. */
    public record LookupResult(
        String username,
        List<LookupOrder> orders,
        String error
    ) {
        public boolean ok() { return error == null; }
    }

    public record LookupOrder(
        String transactionId,
        long totalAmount,
        String currency,
        String status,
        String createdAt,
        List<LookupItem> items
    ) {}

    public record LookupItem(String name, int qty) {}

    private static final class LookupRaw {
        String username;
        List<LookupOrder> orders;
        String error;
    }

    private static final class RawHeartbeatResponse {
        Boolean ok;
        String latestVersion;
        Boolean updateAvailable;
    }

    /**
     * POST /api/v1/plugin/checkout-url — resolve a package
     * reference (slug / uuid / name) into a clickable storefront
     * URL. Drives `/storra checkout` + `/storra sendlink`.
     */
    public CheckoutUrlResult checkoutUrl(String packageRef) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of("packageRef", packageRef));
        HttpResponse<String> res = signedSend("/api/v1/plugin/checkout-url", "POST", body);
        if (res.statusCode() != 200) {
            String responseBody = res.body() == null ? "" : res.body();
            String error = "HTTP " + res.statusCode() + " — " + responseBody;
            logNonOk("checkout-url", res.statusCode(), responseBody);
            return new CheckoutUrlResult(null, null, null, null, error);
        }
        try {
            CheckoutUrlRaw parsed = GSON.fromJson(res.body(), CheckoutUrlRaw.class);
            if (parsed == null || parsed.url == null) {
                return new CheckoutUrlResult(null, null, null, null, "malformed response");
            }
            return new CheckoutUrlResult(parsed.url, parsed.packageId, parsed.packageName, parsed.storeName, null);
        } catch (Exception ex) {
            return new CheckoutUrlResult(null, null, null, null, "parse failed: " + ex.getMessage());
        }
    }

    /**
     * GET /api/v1/plugin/lookup?username=X — recent orders for a
     * minecraft player. Drives `/storra lookup`.
     */
    public LookupResult lookup(String username) throws IOException, InterruptedException {
        String path = "/api/v1/plugin/lookup?username="
            + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpResponse<String> res = signedSend(path, "GET", EMPTY_BODY);
        if (res.statusCode() != 200) {
            String responseBody = res.body() == null ? "" : res.body();
            logNonOk("lookup", res.statusCode(), responseBody);
            return new LookupResult(username, java.util.List.of(),
                "HTTP " + res.statusCode() + " — " + responseBody);
        }
        try {
            LookupRaw parsed = GSON.fromJson(res.body(), LookupRaw.class);
            if (parsed == null) {
                return new LookupResult(username, java.util.List.of(), "malformed response");
            }
            return new LookupResult(
                parsed.username == null ? username : parsed.username,
                parsed.orders == null ? java.util.List.of() : parsed.orders,
                null
            );
        } catch (Exception ex) {
            return new LookupResult(username, java.util.List.of(),
                "parse failed: " + ex.getMessage());
        }
    }

    /** Result of /ban — drives `/storra ban`. */
    public record BanResult(String username, String error) {
        public boolean ok() { return error == null; }
    }

    /** Result of /unban — drives `/storra unban`. */
    public record UnbanResult(String username, boolean wasBanned, String error) {
        public boolean ok() { return error == null; }
    }

    private static final class BanRaw {
        Boolean ok;
        String username;
        Boolean wasBanned;
        String error;
    }

    /**
     * POST /api/v1/plugin/ban — block a minecraft username from
     * purchasing on this store. Idempotent: re-banning is a no-op.
     * Drives `/storra ban <player> [reason]`.
     */
    public BanResult ban(String username, String reason) throws IOException, InterruptedException {
        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("username", username);
        if (reason != null && !reason.isEmpty()) {
            payload.put("reason", reason);
        }
        String body = GSON.toJson(payload);
        HttpResponse<String> res = signedSend("/api/v1/plugin/ban", "POST", body);
        if (res.statusCode() != 200) {
            String responseBody = res.body() == null ? "" : res.body();
            logNonOk("ban", res.statusCode(), responseBody);
            return new BanResult(username, "HTTP " + res.statusCode() + " — " + responseBody);
        }
        try {
            BanRaw parsed = GSON.fromJson(res.body(), BanRaw.class);
            if (parsed == null) {
                return new BanResult(username, "malformed response");
            }
            return new BanResult(parsed.username == null ? username : parsed.username, null);
        } catch (Exception ex) {
            return new BanResult(username, "parse failed: " + ex.getMessage());
        }
    }

    /**
     * POST /api/v1/plugin/unban — remove a minecraft username from
     * the ban list. `wasBanned` reports whether a row was actually
     * deleted so the command can give honest feedback.
     */
    public UnbanResult unban(String username) throws IOException, InterruptedException {
        String body = GSON.toJson(Map.of("username", username));
        HttpResponse<String> res = signedSend("/api/v1/plugin/unban", "POST", body);
        if (res.statusCode() != 200) {
            String responseBody = res.body() == null ? "" : res.body();
            logNonOk("unban", res.statusCode(), responseBody);
            return new UnbanResult(username, false, "HTTP " + res.statusCode() + " — " + responseBody);
        }
        try {
            BanRaw parsed = GSON.fromJson(res.body(), BanRaw.class);
            if (parsed == null) {
                return new UnbanResult(username, false, "malformed response");
            }
            return new UnbanResult(
                parsed.username == null ? username : parsed.username,
                parsed.wasBanned != null && parsed.wasBanned,
                null
            );
        } catch (Exception ex) {
            return new UnbanResult(username, false, "parse failed: " + ex.getMessage());
        }
    }

    /**
     * POST /api/v1/plugin/heartbeat — server diagnostics.
     *
     * Server responds with 200 + `{ok: true, latestVersion,
     * updateAvailable}` on success, or 200 + `{ok: false, reason:
     * "transient"}` when a downstream write briefly fails (typically
     * during a Storra deploy's container-recreate window). The
     * transient case is INFO-level since the next heartbeat will
     * retry naturally; only hard non-200s warrant a WARNING.
     *
     * Caller (HeartbeatService) inspects `updateAvailable` on the
     * first successful heartbeat and surfaces a one-time notice to
     * the merchant console if a newer plugin version is out.
     */
    public HeartbeatResponse heartbeat(HeartbeatStats stats) throws IOException, InterruptedException {
        String body = GSON.toJson(stats);
        HttpResponse<String> res = signedSend("/api/v1/plugin/heartbeat", "POST", body);
        if (res.statusCode() != 200) {
            logNonOk("heartbeat", res.statusCode(), res.body());
            return HeartbeatResponse.failed();
        }
        String responseBody = res.body();
        if (responseBody == null || responseBody.isEmpty()) {
            return HeartbeatResponse.failed();
        }
        if (responseBody.contains("\"ok\":false")) {
            String reason = responseBody.contains("\"reason\":\"")
                ? responseBody.replaceAll(".*\"reason\":\"([^\"]*)\".*", "$1")
                : "unknown";
            log.info("heartbeat: server soft-fail (" + reason
                + ") — retrying on next tick");
            return HeartbeatResponse.failed();
        }
        try {
            RawHeartbeatResponse parsed = GSON.fromJson(responseBody, RawHeartbeatResponse.class);
            if (parsed == null) return HeartbeatResponse.failed();
            return new HeartbeatResponse(
                parsed.ok != null && parsed.ok,
                parsed.latestVersion,
                parsed.updateAvailable != null && parsed.updateAvailable
            );
        } catch (Exception ex) {
            // Malformed but 200 — count as soft success, no update info.
            return new HeartbeatResponse(true, null, false);
        }
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

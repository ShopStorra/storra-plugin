package xyz.storra.plugin.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.storra.plugin.api.StorraApiClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PlaceholderAPI expansion that exposes Storra commerce data to
 * scoreboard / tab / chat-format plugins.
 *
 * Placeholders (all keyed by the requesting OfflinePlayer's name):
 *   %storra_total_spent%         "12.34"           dollars, 2dp
 *   %storra_total_spent_raw%     "1234"            cents
 *   %storra_total_spent_pretty%  "$12.34"          formatted with $ prefix
 *   %storra_order_count%         "3"               integer
 *   %storra_last_purchase%       "VIP Pack"        product name | "none"
 *   %storra_last_purchase_at%    "2 days ago"      humanized timestamp
 *   %storra_currency%            "USD"             ISO code | "" if no orders
 *   %storra_is_customer%         "true" | "false"  has at least one delivered order
 *   %storra_store_name%          "FujiCraft"       static, from heartbeat-known store
 *   %storra_store_url%           "https://store…"  static, from config
 *
 * Caching: per-username TTL cache (default 60s) so high-frequency
 * placeholder evaluators (scoreboard ticks, tab updates) don't
 * hammer the Storra API. Cache misses kick off an async fetch and
 * return a placeholder string ("…") until the response lands —
 * never block the main thread on network I/O.
 *
 * Persist=true so PAPI never reloads the expansion mid-session
 * (stale cache lives through /papi reload, which is fine — values
 * just refresh on next TTL expiry).
 */
public final class StorraExpansion extends PlaceholderExpansion {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final String LOADING = "…";

    private final JavaPlugin plugin;
    private final StorraApiClient api;
    private final String storeUrl;
    private final Logger log;

    /**
     * Per-username cache entry. `loading` guards against multiple
     * in-flight fetches for the same username when several
     * placeholder evaluators fire in the same tick.
     */
    private static final class Entry {
        volatile StorraApiClient.PlayerStatsResult value;
        volatile Instant fetchedAt;
        volatile boolean loading;
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public StorraExpansion(JavaPlugin plugin, StorraApiClient api, String storeUrl) {
        this.plugin = plugin;
        this.api = api;
        this.storeUrl = storeUrl == null ? "" : storeUrl;
        this.log = plugin.getLogger();
    }

    @Override
    public String getIdentifier() {
        return "storra";
    }

    @Override
    public String getAuthor() {
        return "Storra";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return "";
        String key = params.toLowerCase(Locale.ROOT);

        // Static placeholders — no player lookup needed.
        switch (key) {
            case "store_url":
                return storeUrl;
            case "store_name":
                // Reserved for a future heartbeat-known store name;
                // current heartbeat response doesn't ship it. Returning
                // empty string is safer than guessing — scoreboard
                // plugins fall back to their own default text.
                return "";
            default:
                break;
        }

        // Per-player placeholders need a username to query against.
        if (player == null || player.getName() == null) return "";
        String username = player.getName();
        StorraApiClient.PlayerStatsResult stats = lookup(username);
        if (stats == null) return LOADING;
        if (!stats.ok()) return "";

        switch (key) {
            case "total_spent":
                return formatDollars(stats.totalSpent());
            case "total_spent_raw":
                return String.valueOf(stats.totalSpent());
            case "total_spent_pretty":
                return "$" + formatDollars(stats.totalSpent());
            case "order_count":
                return String.valueOf(stats.orderCount());
            case "last_purchase":
                return stats.lastPurchase() == null ? "none" : stats.lastPurchase();
            case "last_purchase_at":
                return humanizeTimestamp(stats.lastPurchaseAt());
            case "currency":
                return stats.currency() == null ? "" : stats.currency();
            case "is_customer":
                return String.valueOf(stats.isCustomer());
            default:
                return null; // unknown placeholder — PAPI shows the raw token
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────

    /**
     * Returns cached stats if fresh; else kicks off an async refresh
     * and returns the stale value (or null on first access).
     * Placeholder render returns LOADING on null so the user sees
     * "…" instead of a blank gap.
     */
    private StorraApiClient.PlayerStatsResult lookup(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        Entry entry = cache.computeIfAbsent(key, k -> new Entry());
        Instant now = Instant.now();
        boolean stale = entry.fetchedAt == null
            || Duration.between(entry.fetchedAt, now).compareTo(CACHE_TTL) > 0;
        if (stale && !entry.loading) {
            entry.loading = true;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    StorraApiClient.PlayerStatsResult result = api.playerStats(username);
                    entry.value = result;
                    entry.fetchedAt = Instant.now();
                } catch (Exception ex) {
                    log.warning("player-stats fetch failed for " + username
                        + ": " + ex.getMessage());
                } finally {
                    entry.loading = false;
                }
            });
        }
        return entry.value;
    }

    // ── Formatting helpers ────────────────────────────────────────────────

    private static String formatDollars(long cents) {
        long abs = Math.abs(cents);
        long dollars = abs / 100;
        long remainder = abs % 100;
        String sign = cents < 0 ? "-" : "";
        return sign + dollars + "." + (remainder < 10 ? "0" + remainder : remainder);
    }

    /**
     * Render an ISO-8601 instant as "3 days ago" / "5 hours ago" /
     * "just now" / "in the future" (clock skew). Returns "" when
     * the timestamp is missing.
     */
    private static String humanizeTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        Instant when;
        try {
            when = OffsetDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception ex) {
            return iso; // last resort — show the raw value
        }
        Duration delta = Duration.between(when, Instant.now());
        long seconds = delta.getSeconds();
        if (seconds < 0) return "just now"; // clock-skew defensive
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        if (days < 30) return days + (days == 1 ? " day ago" : " days ago");
        long months = days / 30;
        if (months < 12) return months + (months == 1 ? " month ago" : " months ago");
        long years = days / 365;
        return years + (years == 1 ? " year ago" : " years ago");
    }
}

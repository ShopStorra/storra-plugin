package xyz.storra.plugin.service;

import xyz.storra.plugin.api.StorraApiClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory cache of values the plugin learns from the server at
 * runtime and re-reads from multiple listeners + commands.
 *
 * Lives for the duration of a plugin enable cycle. Cleared on
 * {@link xyz.storra.plugin.StorraPlugin#stopServices()} so a
 * reload after re-pairing doesn't reuse a previous tenant's
 * cached values.
 *
 * Currently holds:
 *   - storefront URL (learned from heartbeat response) → used by
 *     /buy + /store alias listener as a fallback when the merchant
 *     hasn't pasted aliases.store-url into config.yml manually
 *   - active package list (learned on first need + periodically
 *     refreshed) → used by /storra checkout + /storra sendlink
 *     tab-complete to suggest package names without a server
 *     round-trip per keystroke
 *
 * Thread-safety: all access from the main thread + the
 * scheduler's async pool, so each slot is an AtomicReference for
 * cheap CAS-style updates without locking.
 */
public final class RuntimeState {

    private static final Duration PACKAGES_CACHE_TTL = Duration.ofSeconds(60);

    private final AtomicReference<String> storeUrl = new AtomicReference<>(null);
    private final AtomicReference<String> storeName = new AtomicReference<>(null);
    private final AtomicReference<PackagesCache> packagesCache = new AtomicReference<>(null);

    private record PackagesCache(List<StorraApiClient.PackageRow> rows, Instant fetchedAt) {}

    public void setStorefront(String url, String name) {
        if (url != null && !url.isEmpty()) storeUrl.set(url);
        if (name != null && !name.isEmpty()) storeName.set(name);
    }

    public String storeUrl() {
        return storeUrl.get();
    }

    public String storeName() {
        return storeName.get();
    }

    /**
     * Cached package list, or null when stale / never fetched. Caller
     * uses {@link #refreshPackagesAsync} to populate the cache —
     * never blocks on the network from a getter so the tab-complete
     * thread stays responsive.
     */
    public List<StorraApiClient.PackageRow> packagesCached() {
        PackagesCache cache = packagesCache.get();
        if (cache == null) return null;
        if (Duration.between(cache.fetchedAt, Instant.now()).compareTo(PACKAGES_CACHE_TTL) > 0) {
            return null;
        }
        return cache.rows;
    }

    public void setPackages(List<StorraApiClient.PackageRow> rows) {
        packagesCache.set(new PackagesCache(rows, Instant.now()));
    }

    public void clear() {
        storeUrl.set(null);
        storeName.set(null);
        packagesCache.set(null);
    }
}

package xyz.storra.plugin.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * HMAC signing for the Storra plugin REST API.
 *
 * Mirror of the Storra-side {@code verifyServer} in
 * {@code src/routes/api/v1/plugin/$.ts}: same signed material,
 * same headers. Replay-resistant via timestamp + nonce.
 *
 * Signed material: {@code sha256(server_id:ts_ms:nonce:sha256(body))}
 *
 * Headers attached:
 *   X-Server-Id   the public server-id from pairing
 *   X-Timestamp   current epoch milliseconds
 *   X-Nonce       random UUID per request
 *   X-Signature   hex-encoded HMAC-SHA256 over the signed material
 */
public final class HmacSigner {

    private final String serverId;
    private final byte[] secretBytes;

    public HmacSigner(String serverId, String secret) {
        this.serverId = serverId;
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String serverId() {
        return serverId;
    }

    /**
     * Build the signing inputs for a request body. Returns a
     * value-class so callers attach all four headers in one go.
     */
    public Signed sign(String body) {
        long timestampMs = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        return signAt(body, timestampMs, nonce);
    }

    /**
     * Test-only: deterministic variant. Production callers should
     * use {@link #sign(String)} so timestamps + nonces freshen
     * automatically. Exposed package-public so unit tests can
     * verify wire compatibility against captured fixtures.
     */
    public Signed signAt(String body, long timestampMs, String nonce) {
        String bodyHash = sha256Hex(body);
        String material = serverId + ":" + timestampMs + ":" + nonce + ":" + bodyHash;
        String signature = hmacSha256Hex(secretBytes, material);
        return new Signed(serverId, String.valueOf(timestampMs), nonce, signature);
    }

    /**
     * Bundle of values to drop onto a request as headers.
     */
    public record Signed(
        String serverId,
        String timestampMs,
        String nonce,
        String signature
    ) {}

    // ── crypto primitives ────────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String hmacSha256Hex(byte[] key, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return toHex(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(out);
    }
}

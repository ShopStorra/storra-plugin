package xyz.storra.plugin.api;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-compatibility tests for HmacSigner.
 *
 * The Storra-side verifier expects:
 *   signed_material = sha256(server_id:ts_ms:nonce:sha256(body))
 *   signature       = HMAC-SHA256(secret, signed_material), hex-encoded
 *
 * If the plugin and Storra disagree on the canonical material,
 * every request is a 401. These tests fix the contract so a
 * well-formed plugin signature always verifies on the server side.
 */
class HmacSignerTest {

    private static final String SERVER_ID = "vs-test-server";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String BODY = "{\"hello\":\"world\"}";
    private static final long TS = 1_700_000_000_000L;
    private static final String NONCE = "fixed-nonce-aaaaaaaaaaaa";

    @Test
    void produces_storra_compatible_signed_material() throws Exception {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed s = signer.signAt(BODY, TS, NONCE);

        // Compute the expected signature exactly the way the
        // Storra-side verifier does in $.ts:buildSignedMaterial.
        String bodyHash = sha256Hex(BODY);
        String material = SERVER_ID + ":" + TS + ":" + NONCE + ":" + bodyHash;
        String expected = hmacSha256Hex(SECRET, material);

        assertThat(s.serverId()).isEqualTo(SERVER_ID);
        assertThat(s.timestampMs()).isEqualTo(String.valueOf(TS));
        assertThat(s.nonce()).isEqualTo(NONCE);
        assertThat(s.signature()).isEqualTo(expected);
    }

    @Test
    void empty_body_signs_with_sha256_of_empty_string() throws Exception {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed s = signer.signAt("", TS, NONCE);
        // sha256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String emptyHash = sha256Hex("");
        assertThat(emptyHash).isEqualTo(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        String material = SERVER_ID + ":" + TS + ":" + NONCE + ":" + emptyHash;
        assertThat(s.signature()).isEqualTo(hmacSha256Hex(SECRET, material));
    }

    @Test
    void different_bodies_produce_different_signatures() {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed a = signer.signAt("body-a", TS, NONCE);
        HmacSigner.Signed b = signer.signAt("body-b", TS, NONCE);
        assertThat(a.signature()).isNotEqualTo(b.signature());
    }

    @Test
    void different_timestamps_produce_different_signatures() {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed a = signer.signAt(BODY, TS, NONCE);
        HmacSigner.Signed b = signer.signAt(BODY, TS + 1, NONCE);
        assertThat(a.signature()).isNotEqualTo(b.signature());
    }

    @Test
    void different_nonces_produce_different_signatures() {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed a = signer.signAt(BODY, TS, "nonce-a");
        HmacSigner.Signed b = signer.signAt(BODY, TS, "nonce-b");
        assertThat(a.signature()).isNotEqualTo(b.signature());
    }

    @Test
    void different_secrets_produce_different_signatures() {
        HmacSigner.Signed a = new HmacSigner(SERVER_ID, "secret-a").signAt(BODY, TS, NONCE);
        HmacSigner.Signed b = new HmacSigner(SERVER_ID, "secret-b").signAt(BODY, TS, NONCE);
        assertThat(a.signature()).isNotEqualTo(b.signature());
    }

    @Test
    void sign_uses_current_clock_for_timestamp() {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        long before = System.currentTimeMillis();
        HmacSigner.Signed s = signer.sign(BODY);
        long after = System.currentTimeMillis();
        long ts = Long.parseLong(s.timestampMs());
        assertThat(ts).isBetween(before, after);
    }

    @Test
    void sign_generates_unique_nonces_per_call() {
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        HmacSigner.Signed a = signer.sign(BODY);
        HmacSigner.Signed b = signer.sign(BODY);
        assertThat(a.nonce()).isNotEqualTo(b.nonce());
    }

    // ── helpers — replicate the Storra-side computation ────────────────

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    private static String hmacSha256Hex(String key, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

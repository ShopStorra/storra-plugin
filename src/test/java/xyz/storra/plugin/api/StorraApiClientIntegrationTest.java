package xyz.storra.plugin.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.storra.plugin.delivery.DeliveryTask;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * StorraApiClient hit against a WireMock-stubbed Storra. Exercises:
 *   - pair() happy path + 401 path
 *   - fetchPending() returns parsed DeliveryTask list
 *   - confirm() / fail() send the right body shape
 *   - heartbeat() sends every required field
 *   - signed requests carry all four HMAC headers
 *
 * Doesn't try to verify the actual signature byte-for-byte — that's
 * HmacSignerTest's job. This suite confirms the wire shape so the
 * Storra-side verifier sees what it expects.
 */
class StorraApiClientIntegrationTest {

    private WireMockServer wireMock;
    private StorraApiClient client;
    private static final String SERVER_ID = "test-srv-1";
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Logger LOG = Logger.getLogger("StorraApiClientIntegrationTest");

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        HmacSigner signer = new HmacSigner(SERVER_ID, SECRET);
        client = new StorraApiClient(
            "http://localhost:" + wireMock.port(),
            signer,
            LOG
        );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void pair_returns_server_id_and_secret_on_200() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/pair"))
            .withRequestBody(equalToJson("{\"code\":\"ABCD1234\"}"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"server_id\":\"srv-paired\",\"secret\":\"new-secret-xx\"}")
            ));

        StorraApiClient.PairResult result = StorraApiClient.pair(
            "http://localhost:" + wireMock.port(),
            "ABCD1234",
            LOG
        );

        assertThat(result).isNotNull();
        assertThat(result.serverId()).isEqualTo("srv-paired");
        assertThat(result.secret()).isEqualTo("new-secret-xx");
    }

    @Test
    void pair_returns_null_on_401() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/pair"))
            .willReturn(aResponse()
                .withStatus(401)
                .withBody("{\"error\":\"Pairing code is invalid or expired.\",\"code\":\"E8102\"}")
            ));

        StorraApiClient.PairResult result = StorraApiClient.pair(
            "http://localhost:" + wireMock.port(),
            "BADCODE0",
            LOG
        );

        assertThat(result).isNull();
    }

    @Test
    void fetchPending_parses_task_list() throws Exception {
        // v2 wire shape: { "commands": [
        //   { "id": "<uuid>", "command": "<string>",
        //     "playerName": "<name>"|null, "requireOnline": bool } ] }
        wireMock.stubFor(get(urlEqualTo("/api/v1/plugin/pending"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"commands\":[" +
                    "{\"id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"," +
                    "\"command\":\"lp user Alice parent add vip\"," +
                    "\"playerName\":\"Alice\"," +
                    "\"requireOnline\":true}]}"
                )
            ));

        List<DeliveryTask> tasks = client.fetchPending();
        assertThat(tasks).hasSize(1);
        DeliveryTask t = tasks.get(0);
        assertThat(t.commandId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(t.command()).isEqualTo("lp user Alice parent add vip");
        assertThat(t.playerName()).isEqualTo("Alice");
        assertThat(t.requireOnline()).isTrue();

        // Verify all four HMAC headers were sent
        wireMock.verify(allFourHmacHeaders(
            RequestPatternBuilder.newRequestPattern(
                com.github.tomakehurst.wiremock.http.RequestMethod.GET,
                urlEqualTo("/api/v1/plugin/pending")
            )
        ));
    }

    @Test
    void fetchPending_returns_empty_on_non_200() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/v1/plugin/pending"))
            .willReturn(aResponse().withStatus(500)));

        assertThat(client.fetchPending()).isEmpty();
    }

    @Test
    void fetchPending_returns_empty_when_envelope_has_empty_commands() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/v1/plugin/pending"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"commands\":[]}")
            ));

        assertThat(client.fetchPending()).isEmpty();
    }

    @Test
    void confirm_sends_commandId_and_returns_true_on_200() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/confirm"))
            .withRequestBody(equalToJson("{\"commandId\":\"cmd-99\"}"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        assertThat(client.confirm("cmd-99")).isTrue();
    }

    @Test
    void fail_sends_reason_and_returns_true_on_200() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/fail"))
            .withRequestBody(equalToJson("{\"commandId\":\"cmd-100\",\"reason\":\"command threw\"}"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        assertThat(client.fail("cmd-100", "command threw")).isTrue();
    }

    @Test
    void heartbeat_sends_full_stats_payload() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/heartbeat"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        StorraApiClient.HeartbeatStats stats = new StorraApiClient.HeartbeatStats(
            "0.1.0", 12, 100, 19.8, 47.3, 1024L, 4096L, 23.5, 1500, 240
        );
        assertThat(client.heartbeat(stats).ok()).isTrue();

        // camelCase to match the server's handleHeartbeat contract.
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/plugin/heartbeat"))
            .withRequestBody(equalToJson(
                "{\"version\":\"0.1.0\"," +
                "\"playerCount\":12,\"maxPlayers\":100," +
                "\"tps\":19.8,\"mspt\":47.3," +
                "\"memoryUsedMb\":1024,\"memoryMaxMb\":4096," +
                "\"cpuPercent\":23.5," +
                "\"entityCount\":1500,\"chunkCount\":240}"
            )));
    }

    @Test
    void confirm_returns_false_on_5xx() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/plugin/confirm"))
            .willReturn(aResponse().withStatus(500)));
        assertThat(client.confirm("cmd-7")).isFalse();
    }

    private static RequestPatternBuilder allFourHmacHeaders(RequestPatternBuilder base) {
        return base
            .withHeader("X-Server-Id", equalTo(SERVER_ID))
            .withHeader("X-Timestamp", matching("^[0-9]+$"))
            .withHeader("X-Nonce", matching("^[A-Za-z0-9._-]{16,128}$"))
            .withHeader("X-Signature", matching("^[a-f0-9]{64}$"));
    }
}

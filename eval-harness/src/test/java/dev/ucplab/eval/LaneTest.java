package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaneTest {

    @TempDir
    Path dir;

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    // Covers: 13.1, m4-plan section 4 (two lanes share nothing: each has its own merchant and its own proxy)
    @Test
    void lanes_haveTheirOwnMerchantAndProxy() throws Exception {
        byte[] key = key();
        try (Lane a = Lane.start(0, key, dir); Lane b = Lane.start(1, key, dir)) {
            assertThat(a.merchant().baseUrl()).isNotEqualTo(b.merchant().baseUrl());
            assertThat(a.agentFacingUrl()).isNotEqualTo(b.agentFacingUrl());
            assertThat(a.agentFacingUrl()).isNotEqualTo(a.merchant().baseUrl());

            a.merchant().setStock("item_123", 0);

            assertThat(a.groundTruth().orders()).isEmpty();
            assertThat(b.groundTruth().orders()).isEmpty();
            assertThat(a.groundTruth().session("chk_nope")).isEmpty();
        }
    }

    // Covers: §12 Safety, A.3 (through the lane's proxy, /_lab is not reachable: ground truth is read from the
    // merchant directly)
    @Test
    void lane_proxyRefusesLab_merchantAnswersIt() throws Exception {
        try (Lane lane = Lane.start(0, key(), dir); HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> viaProxy = http.send(HttpRequest.newBuilder(
                    URI.create(lane.agentFacingUrl() + "/_lab/orders")).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(viaProxy.statusCode()).isEqualTo(404);
            assertThat(lane.merchant().orders()).isEmpty();
        }
    }
}

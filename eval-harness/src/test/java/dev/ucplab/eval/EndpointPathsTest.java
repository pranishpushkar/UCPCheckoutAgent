package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class EndpointPathsTest {

    // Covers: 8.5, 12, H1 plan section 5 (proxy rule paths come from the profile's endpoint: the sample's is the origin, so
    // /checkout-sessions/...; the lab's is /ucp/v1, and its rule paths are exactly the literals the harness used before)
    @Test
    void thePathsFollowTheEndpoint_andTheLabsAreUnchanged() {
        EndpointPaths sample = EndpointPaths.ofEndpoint(URI.create("http://127.0.0.1:8123"));
        EndpointPaths lab = EndpointPaths.ofEndpoint(URI.create("http://127.0.0.1:9001/ucp/v1"));

        assertThat(sample.create()).isEqualTo("/checkout-sessions");
        assertThat(sample.complete()).isEqualTo("/checkout-sessions/*/complete");
        assertThat(sample.all()).isEqualTo("/**");
        assertThat(lab.create()).isEqualTo("/ucp/v1/checkout-sessions");
        assertThat(lab.complete()).isEqualTo("/ucp/v1/checkout-sessions/*/complete");
        assertThat(lab.all()).isEqualTo("/ucp/v1/**");
        assertThat(EndpointPaths.LAB).isEqualTo(lab);
        assertThat(Scenarios.CREATE).isEqualTo("/ucp/v1/checkout-sessions");
        assertThat(Scenarios.COMPLETE).isEqualTo("/ucp/v1/checkout-sessions/*/complete");
        assertThat(Scenarios.UCP_ALL).isEqualTo("/ucp/v1/**");
    }
}

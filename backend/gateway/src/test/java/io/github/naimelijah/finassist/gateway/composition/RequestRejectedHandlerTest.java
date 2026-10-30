package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * The handler the gateway installs for a request Spring Security's firewall rejects, called as
 * {@code FilterChainProxy} calls it.
 *
 * <p>It replaces Spring Security's default, which marks the request's observation as failed, and
 * that marking is what puts a rejection into the server's error-rate metrics and traces. Nothing
 * the browser receives would show the marking gone, so a handler that wrote the problem alone would
 * pass every HTTP-level test; this holds the marking directly, against a real registry whose
 * handler records each error it is told of. {@code GatewayApplicationIT} holds the answer the
 * running server writes.
 */
class RequestRejectedHandlerTest {

    private static final String REJECTED_PATH = "/ledger/api/v1/accounts;a=b";

    private final ObservationRegistry observations = ObservationRegistry.create();

    private final List<Throwable> recordedErrors = new ArrayList<>();

    @BeforeEach
    void recordEveryError() {
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onError(Observation.Context context) {
                recordedErrors.add(context.getError());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
    }

    @Test
    void
            Given_a_request_the_firewall_rejects_When_it_is_handled_Then_the_current_observation_is_marked_failed_and_a_400_problem_is_written()
                    throws Exception {
        RequestRejectedHandler handler = new GatewaySecurityConfiguration().requestRejectedHandler(observations);
        RequestRejectedException rejection = new RequestRejectedException("rejected by this test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Observation request = Observation.start("http.server.requests", observations);

        try (Observation.Scope scope = request.openScope()) {
            handler.handle(new MockHttpServletRequest("GET", REJECTED_PATH), response, rejection);
        } finally {
            request.stop();
        }

        assertThat(recordedErrors).as("the errors the observation was told of").containsExactly(rejection);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"MALFORMED_REQUEST\"");
    }
}

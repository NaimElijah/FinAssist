package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Host allow-list that keeps a DNS-rebound page away from the books (ADR-027).
 *
 * <p>Plain JUnit over spring-test's servlet mocks: the filter either hands a request on or refuses
 * it, and neither outcome needs a container to observe. Mutation testing covers {@code composition},
 * so a refusal's status, content type and {@code code} are each asserted. A client branches on
 * {@code code}, and a refusal that lost it would still be a 403.
 *
 * <p>The wire values are spelled out here rather than read from the filter's constants. They are the
 * contract, and a test that borrowed them would keep passing through a change to the contract itself.
 */
class HostAllowListFilterTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";
    private static final String FOREIGN_HOST = "evil.example";
    private static final JsonMapper JSON = JsonMapper.shared();

    private final HostAllowListFilter filter = new HostAllowListFilter(List.of("localhost", "127.0.0.1"));
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void Given_an_allowed_host_with_a_port_When_filtered_Then_the_request_passes_through() throws Exception {
        MockHttpServletRequest request = requestWithHost("localhost:8080");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void Given_an_allowed_host_without_a_port_When_filtered_Then_the_request_passes_through() throws Exception {
        MockHttpServletRequest request = requestWithHost("127.0.0.1");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    /** Host names are case-insensitive (RFC 9110), so a browser's capitalisation is not a refusal. */
    @Test
    void Given_an_allowed_host_in_upper_case_When_filtered_Then_the_request_passes_through() throws Exception {
        MockHttpServletRequest request = requestWithHost("LOCALHOST:8080");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    /**
     * No entry can name an IPv6 literal, as on the assistant, so a bracketed header is refused like any
     * other host that is not on the list.
     */
    @Test
    void Given_a_bracketed_ipv6_literal_with_a_port_When_filtered_Then_it_is_refused() throws Exception {
        filter.doFilter(requestWithHost("[::1]:8080"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void Given_a_foreign_host_When_filtered_Then_it_is_refused_with_a_403_problem() throws Exception {
        filter.doFilter(requestWithHost(FOREIGN_HOST + ":8080"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("type").asString()).isEqualTo("about:blank");
        assertThat(problem.path("title").asString()).isEqualTo("Forbidden");
        assertThat(problem.path("status").asInt()).isEqualTo(403);
        assertThat(problem.path("detail").asString()).isNotBlank();
        assertThat(problem.path("code").asString()).isEqualTo(HOST_NOT_ALLOWED);
    }

    /** The header is chosen by whoever sent the request; writing it back is how a refusal becomes a reflection. */
    @Test
    void Given_a_foreign_host_When_refused_Then_the_body_does_not_echo_it() throws Exception {
        filter.doFilter(requestWithHost(FOREIGN_HOST), response, chain);

        assertThat(response.getContentAsString()).doesNotContain(FOREIGN_HOST);
    }

    @Test
    void Given_no_host_header_When_filtered_Then_it_is_refused_with_a_403_problem() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        assertThat(JSON.readTree(response.getContentAsByteArray()).path("code").asString())
                .isEqualTo(HOST_NOT_ALLOWED);
    }

    /** A refusal that still called the chain would answer 403 after the handler had already run. */
    @Test
    void Given_a_refused_request_When_filtered_Then_the_chain_is_not_invoked() throws Exception {
        filter.doFilter(requestWithHost(FOREIGN_HOST), response, chain);

        assertThat(chain.getRequest()).isNull();
    }

    /**
     * A name that merely contains, starts or ends with an allowed one is a different name, and a
     * header that does not parse names nothing that could be on the list.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "localhost.evil.example",
                "evil.localhost",
                "::1",
                "[::1",
                "[::1]8080",
                "[]",
                "localhost:80a",
                "localhost:8080:evil"
            })
    void Given_a_lookalike_or_malformed_host_When_filtered_Then_it_is_refused(String host) throws Exception {
        filter.doFilter(requestWithHost(host), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    /** {@code LEDGER_ALLOWED_HOSTS} is typed by a person, so spacing and capitals must not refuse a real name. */
    @Test
    void Given_an_allow_list_entry_with_padding_and_capitals_When_its_host_arrives_Then_the_request_passes_through()
            throws Exception {
        HostAllowListFilter padded = new HostAllowListFilter(List.of(" LocalHost "));
        MockHttpServletRequest request = requestWithHost("localhost:8080");

        padded.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    /**
     * A request's host is compared with its port dropped, so an entry holding a port, a colon or a bracket
     * could never match, and a ledger started with one would refuse every request under that name. Refused
     * at start-up instead, with the entry named, as the assistant refuses one (ADR-027).
     */
    @ParameterizedTest
    @ValueSource(strings = {"localhost:8080", "::1", "[::1]", "[::1]:8080", "local]host"})
    void Given_an_allow_list_entry_with_a_colon_or_a_bracket_When_the_filter_is_built_Then_it_is_rejected(
            String entry) {
        assertThatThrownBy(() -> new HostAllowListFilter(List.of("localhost", entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(entry);
    }

    /** An empty list refuses every request, which would look like an outage rather than a configuration miss. */
    @Test
    void Given_an_allow_list_of_blank_entries_When_the_filter_is_built_Then_it_is_rejected() {
        assertThatThrownBy(() -> new HostAllowListFilter(List.of(" ", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest requestWithHost(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(HttpHeaders.HOST, host);
        return request;
    }
}

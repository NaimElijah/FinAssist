package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint, every role, every way a bearer token can be wrong — proved against a real {@code
 * JwtDecoder} pointed at {@link TestJwks} and real signed tokens from {@link TestTokens}, not a
 * mocked authentication (ADR-030).
 *
 * <p>The inventory check is what a stray {@code @GetMapping} or a mis-mapped permission would break:
 * it lists the ledger's 23 endpoints by hand, and fails if the running server answers to one more or
 * one fewer than that list plus springdoc's own two. A test that only checked the endpoints it
 * already knew about would never notice a new one arriving unprotected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LedgerAuthorizationIT extends PostgresFixture {

    private static final String RANDOM_ID = UUID.randomUUID().toString();
    private static final String JSON_BODY = "{}";

    /**
     * Read access: admitted for the owner, the viewer and the assistant (ADR-004). Paths are the
     * templates springdoc and {@code RequestMappingInfo} both report, {@code {id}} included, so the
     * inventory check compares like with like; {@link Endpoint#request} expands the placeholder.
     */
    private static final List<Endpoint> READS = List.of(
            new Endpoint(HttpMethod.GET, "/api/v1/accounts"),
            new Endpoint(HttpMethod.GET, "/api/v1/accounts/{id}/balance"),
            new Endpoint(HttpMethod.GET, "/api/v1/accounts/{id}/transactions"),
            new Endpoint(HttpMethod.GET, "/api/v1/transactions"),
            new Endpoint(HttpMethod.GET, "/api/v1/budgets"),
            new Endpoint(HttpMethod.GET, "/api/v1/budgets/{id}/review"),
            new Endpoint(HttpMethod.GET, "/api/v1/categorisation-rules"),
            new Endpoint(HttpMethod.GET, "/api/v1/scenario-comparisons"),
            new Endpoint(HttpMethod.GET, "/api/v1/planned-flows"),
            new Endpoint(HttpMethod.GET, "/api/v1/scenarios/{id}/projection"),
            new Endpoint(HttpMethod.GET, "/api/v1/scenarios"));

    /** Write access: admitted for the owner only — 403 for the viewer, and 403 for the assistant (ADR-004). */
    private static final List<Endpoint> WRITES = List.of(
            new Endpoint(HttpMethod.POST, "/api/v1/accounts"),
            new Endpoint(HttpMethod.POST, "/api/v1/transactions"),
            new Endpoint(HttpMethod.POST, "/api/v1/transactions/{id}/clear"),
            new Endpoint(HttpMethod.POST, "/api/v1/transactions/{id}/reconcile"),
            new Endpoint(HttpMethod.POST, "/api/v1/transactions/{id}/void"),
            new Endpoint(HttpMethod.POST, "/api/v1/budgets"),
            new Endpoint(HttpMethod.POST, "/api/v1/categorisation-rules"),
            new Endpoint(HttpMethod.POST, "/api/v1/planned-flows"),
            new Endpoint(HttpMethod.POST, "/api/v1/planned-flows/{id}/commit"),
            new Endpoint(HttpMethod.POST, "/api/v1/planned-flows/{id}/realise"),
            new Endpoint(HttpMethod.POST, "/api/v1/planned-flows/{id}/cancel"),
            new Endpoint(HttpMethod.POST, "/api/v1/scenarios"));

    /**
     * Every route not named above that the running server may legitimately answer: springdoc's
     * contract document, in both forms it serves. Not an ADR-030 business endpoint, so it carries no
     * permission.
     *
     * <p>{@code /error} is not part of this inventory: {@code RequestMappingHandlerMapping} — this
     * check's own data source — never maps it, in this configuration, to a {@code RequestMappingInfo}
     * at all, so it is not a route this reflection-based check could compare against either way. Its
     * denial is proven directly instead: {@code LedgerApplicationIT}'s unknown-route test reaches it
     * as the composition fallback's 404, and this class's {@code
     * dispatcherTypeMatchers(DispatcherType.ERROR)} rule is what a direct {@code GET /error} — never
     * a {@code DispatcherType.ERROR} forward — would fail to match, which is exactly why that path
     * stays behind {@code denyAll} rather than {@code permitAll} (see {@code
     * LedgerSecurityConfiguration}'s class comment).
     */
    private static final Set<Endpoint> KNOWN_NON_BUSINESS_ENDPOINTS =
            Set.of(new Endpoint(HttpMethod.GET, "/v3/api-docs"), new Endpoint(HttpMethod.GET, "/v3/api-docs.yaml"));

    @Autowired
    private MockMvc mvc;

    /**
     * Actuator registers a second {@code RequestMappingHandlerMapping}-typed bean of its own
     * ({@code controllerEndpointHandlerMapping}), so the bean name disambiguates rather than the type
     * alone.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Reads the server's own handler inventory rather than trusting the lists above to be complete:
     * {@link #READS} and {@link #WRITES} are what this test claims exist, and this is what proves
     * nothing else does. Reverting the deliberate stray {@code @GetMapping} used to prove this rule
     * fails is what restores it to green.
     */
    @Test
    void Given_the_running_ledger_When_its_handler_mappings_are_read_Then_they_equal_the_named_inventory() {
        Set<Endpoint> expected = new HashSet<>();
        expected.addAll(READS);
        expected.addAll(WRITES);
        expected.addAll(KNOWN_NON_BUSINESS_ENDPOINTS);

        Set<Endpoint> actual = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(LedgerAuthorizationIT::endpointsOf)
                .collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Stream<Endpoint> endpointsOf(RequestMappingInfo info) {
        Set<String> patterns = info.getPatternValues();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return patterns.stream()
                .flatMap(pattern ->
                        methods.stream().map(method -> new Endpoint(HttpMethod.valueOf(method.name()), pattern)));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void Given_no_token_When_an_endpoint_is_requested_Then_it_is_401_with_a_bare_bearer_challenge(Endpoint endpoint)
            throws Exception {
        mvc.perform(endpoint.request(null, JSON_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "assistant"})
    void Given_a_viewer_or_assistant_token_When_a_read_is_requested_Then_it_is_admitted(String role) throws Exception {
        String token = "viewer".equals(role) ? TestTokens.viewer() : TestTokens.assistant();
        for (Endpoint endpoint : READS) {
            MvcResult result = mvc.perform(endpoint.request(token, JSON_BODY)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%s %s with a %s token", endpoint.method(), endpoint.path(), role)
                    .isNotIn(401, 403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "assistant"})
    void Given_a_viewer_or_assistant_token_When_a_write_is_requested_Then_it_is_403(String role) throws Exception {
        String token = "viewer".equals(role) ? TestTokens.viewer() : TestTokens.assistant();
        for (Endpoint endpoint : WRITES) {
            mvc.perform(endpoint.request(token, JSON_BODY)).andExpect(status().isForbidden());
        }
    }

    @Test
    void Given_the_owner_token_When_any_endpoint_is_requested_Then_it_is_admitted() throws Exception {
        String token = TestTokens.owner();
        for (Endpoint endpoint : bothLists()) {
            MvcResult result = mvc.perform(endpoint.request(token, JSON_BODY)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%s %s with the owner token", endpoint.method(), endpoint.path())
                    .isNotIn(401, 403);
        }
    }

    @Test
    void Given_a_token_granting_no_permission_When_any_endpoint_is_requested_Then_it_is_403() throws Exception {
        String token = TestTokens.noRoles();
        for (Endpoint endpoint : bothLists()) {
            mvc.perform(endpoint.request(token, JSON_BODY)).andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @MethodSource("badTokens")
    void Given_a_bad_token_When_an_endpoint_is_requested_Then_it_is_401_invalid_token(String token) throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
    }

    @Test
    void Given_a_rejected_token_When_refused_Then_the_log_names_the_reason_and_never_the_token(CapturedOutput output)
            throws Exception {
        String expired = TestTokens.expired();

        mvc.perform(withHost(get("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        assertThat(output.getOut()).contains("lifetime");
        assertThat(output.getOut()).doesNotContain(expired);
    }

    @Test
    void Given_no_token_When_refused_Then_the_log_names_the_reason_missing(CapturedOutput output) throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")));

        assertThat(output.getOut()).contains("missing");
    }

    @Test
    void Given_the_owner_token_When_head_or_options_is_sent_Then_both_are_denied() throws Exception {
        String token = TestTokens.owner();

        mvc.perform(withHost(head("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(withHost(options("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/info",
                "/actuator/prometheus",
                "/v3/api-docs"
            })
    void Given_a_public_path_When_requested_with_no_token_Then_it_answers(String path) throws Exception {
        MvcResult result = mvc.perform(withHost(get(path))).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    /**
     * MockMvc sets no {@code Host} header of its own, and {@code HostAllowListFilter} runs ahead of
     * the security chain in this full-context test, so without this every request — the public ones
     * included — would be refused 403 {@code HOST_NOT_ALLOWED} before authorization is ever reached.
     */
    private static MockHttpServletRequestBuilder withHost(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.HOST, "localhost");
    }

    private static List<Endpoint> bothLists() {
        List<Endpoint> all = new ArrayList<>(READS);
        all.addAll(WRITES);
        return all;
    }

    private static Stream<Arguments> everyEndpoint() {
        return bothLists().stream().map(Arguments::of);
    }

    private static Stream<Arguments> badTokens() {
        return Stream.of(
                        TestTokens.expired(),
                        TestTokens.notYetValid(),
                        TestTokens.wrongAudience(),
                        TestTokens.wrongIssuer(),
                        TestTokens.unsigned(),
                        TestTokens.hmacConfused(),
                        TestTokens.foreignKey())
                .map(Arguments::of);
    }

    /**
     * One route, method and path template, with a helper that builds a MockMvc request for either.
     * {@code path} is the template exactly as {@code RequestMappingInfo} reports it — {@code {id}}
     * included — so {@link #equals} against the running server's own inventory needs no
     * normalisation; {@link #request} is what expands the placeholder, with {@link
     * #RANDOM_ID} always supplied and simply unused where there is none to fill.
     */
    private record Endpoint(HttpMethod method, String path) {

        MockHttpServletRequestBuilder request(String token, String body) {
            MockHttpServletRequestBuilder request = withHost(
                    method == HttpMethod.POST
                            ? post(path, RANDOM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                            : get(path, RANDOM_ID));
            if (token != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        }
    }
}

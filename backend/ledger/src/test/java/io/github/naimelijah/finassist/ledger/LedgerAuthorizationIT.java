package io.github.naimelijah.finassist.ledger;

import static io.github.naimelijah.finassist.ledger.ProblemAssertions.requiredProblemMembers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every endpoint, every role, every way a bearer token can be wrong — proved against a real {@code
 * JwtDecoder} pointed at {@link TestJwks} and real signed tokens from {@link TestTokens}, not a
 * mocked authentication (ADR-030).
 *
 * <p>The inventory check is what a stray {@code @GetMapping} or a mis-mapped permission would break:
 * it lists the ledger's 23 endpoints by hand, and fails if the running server answers to one more or
 * one fewer than that list plus the three routes {@link #KNOWN_NON_BUSINESS_ENDPOINTS} names. A test
 * that only checked the endpoints it already knew about would never notice a new one arriving
 * unprotected.
 *
 * <p>{@link AutoConfigureMetrics} restores the Prometheus registry Spring Boot 4 leaves out of a
 * test by default, exactly as {@code LedgerObservabilityIT} does. Without it {@code
 * /actuator/prometheus} is not served at all, and the public-path case could only assert that it is
 * not refused, never that it answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
class LedgerAuthorizationIT extends PostgresFixture {

    private static final String RANDOM_ID = UUID.randomUUID().toString();
    private static final String JSON_BODY = "{}";

    private static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String FORBIDDEN = "FORBIDDEN";

    /** The message {@code ProblemAuthenticationEntryPoint} logs every refused request under. */
    private static final String REFUSAL_MESSAGE = "Refused a request with no valid bearer token";

    private static final JsonMapper LOG_JSON = JsonMapper.shared();

    /**
     * Every field a refusal line may carry, as dotted paths to its leaves: the fixed ECS fields
     * Logback's structured format writes for any line, the trace context, and {@code reason}. A field
     * outside it is something the entry point chose to add, and the place a token would arrive.
     */
    private static final Set<String> REFUSAL_LINE_FIELDS = Set.of(
            "@timestamp",
            "log.level",
            "log.logger",
            "process.pid",
            "process.thread.name",
            "service.name",
            "service.node",
            "ecs.version",
            "message",
            "traceId",
            "spanId",
            "reason");

    /**
     * The method a mapping that names none is inventoried under. A {@code @RequestMapping} with no
     * {@code method} answers every verb, so it has no single one to name, and flat-mapping over its
     * empty method set used to emit nothing at all — such a handler vanished from this check, and
     * ADR-032's "an endpoint added without being classified fails the build" vanished with it.
     * {@code HttpMethod} is a final class over an open set of names, so a name no verb uses is a
     * safe sentinel. Inventory only: nothing in {@link #READS} or {@link #WRITES} carries it,
     * because an endpoint that answers requests is classified by the verb it is reached with.
     */
    private static final HttpMethod ANY_METHOD = HttpMethod.valueOf("ANY");

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
     * contract document, in both forms it serves, and the container's own error page. None is an
     * ADR-030 business endpoint, so none carries a permission.
     *
     * <p>{@code /error} is inventoried under {@link #ANY_METHOD} because that is the shape Boot's
     * {@code BasicErrorController} has: a {@code @Controller} whose class-level {@code
     * @RequestMapping} names the path and whose two handler methods name no HTTP method at all, so
     * {@code RequestMappingHandlerMapping} — this check's own data source — maps it with an empty
     * method condition. It is mapped. An earlier version of this comment claimed it never was; that
     * was {@link #endpointsOf}'s blind spot for a method-less mapping speaking, not the truth.
     *
     * <p>Being inventoried is not being public. A direct {@code GET /error} arrives as {@code
     * DispatcherType.REQUEST}, matches no {@code permitAll} rule and is refused 401 — proved by
     * {@code Given_no_token_When_a_direct_error_request_is_made_Then_it_is_401_with_a_problem_body}
     * below. Only the container's internal {@code DispatcherType.ERROR} forward is permitted, which
     * is why that path stays behind {@code denyAll} rather than {@code permitAll} (see {@code
     * LedgerSecurityConfiguration}'s class comment).
     */
    private static final Set<Endpoint> KNOWN_NON_BUSINESS_ENDPOINTS = Set.of(
            new Endpoint(HttpMethod.GET, "/v3/api-docs"),
            new Endpoint(HttpMethod.GET, "/v3/api-docs.yaml"),
            new Endpoint(ANY_METHOD, "/error"));

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
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        List<HttpMethod> inventoried = methods.isEmpty()
                ? List.of(ANY_METHOD)
                : methods.stream()
                        .map(method -> HttpMethod.valueOf(method.name()))
                        .toList();
        return info.getPatternValues().stream()
                .flatMap(pattern -> inventoried.stream().map(method -> new Endpoint(method, pattern)));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void Given_no_token_When_an_endpoint_is_requested_Then_it_is_401_with_a_bare_bearer_challenge(Endpoint endpoint)
            throws Exception {
        mvc.perform(endpoint.request(null, JSON_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(problem(UNAUTHENTICATED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "assistant"})
    void Given_a_viewer_or_assistant_token_When_a_read_is_requested_Then_it_is_admitted(String role) throws Exception {
        String token = "viewer".equals(role) ? TestTokens.viewer() : TestTokens.assistant();
        for (Endpoint endpoint : READS) {
            MvcResult result = mvc.perform(endpoint.request(token, JSON_BODY)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%s %s with a %s token", endpoint.method(), endpoint.path(), role)
                    .isNotIn(401, 403)
                    .isLessThan(500);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "assistant"})
    void Given_a_viewer_or_assistant_token_When_a_write_is_requested_Then_it_is_403(String role) throws Exception {
        String token = "viewer".equals(role) ? TestTokens.viewer() : TestTokens.assistant();
        for (Endpoint endpoint : WRITES) {
            mvc.perform(endpoint.request(token, JSON_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(problem(FORBIDDEN));
        }
    }

    @Test
    void Given_the_owner_token_When_any_endpoint_is_requested_Then_it_is_admitted() throws Exception {
        String token = TestTokens.owner();
        for (Endpoint endpoint : bothLists()) {
            MvcResult result = mvc.perform(endpoint.request(token, JSON_BODY)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%s %s with the owner token", endpoint.method(), endpoint.path())
                    .isNotIn(401, 403)
                    .isLessThan(500);
        }
    }

    @Test
    void Given_a_token_granting_no_permission_When_any_endpoint_is_requested_Then_it_is_403() throws Exception {
        String token = TestTokens.noRoles();
        for (Endpoint endpoint : bothLists()) {
            mvc.perform(endpoint.request(token, JSON_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(problem(FORBIDDEN));
        }
    }

    @ParameterizedTest
    @MethodSource("badTokens")
    void Given_a_bad_token_When_an_endpoint_is_requested_Then_it_is_401_invalid_token(String token) throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(token))))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(problem(UNAUTHENTICATED));
    }

    /**
     * Each shape {@link #categorisedBadTokens()} lists is refused in exactly one log line, at WARN, whose
     * {@code reason} is the category ADR-030 names for it. For those shapes it is also the category the
     * assistant's {@code test_auth.py} expects, because a rule written against one service must read the
     * other (root {@code CLAUDE.md}, "Security and operations"). Not every bad token is listed: an HS256
     * token and a malformed {@code Bearer} header are categorised differently by the two services, which
     * is #352, and neither is pinned here until it is settled. {@code unknown_key} and {@code malformed}
     * are read from Nimbus's English messages by {@code ProblemAuthenticationEntryPoint.reasonFor}, so a
     * library upgrade rewording one would fail here rather than quietly logging {@code rejected}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("categorisedBadTokens")
    void Given_a_bad_token_When_refused_Then_one_warn_line_names_its_reason_category(
            String shape, String token, String reason, CapturedOutput output) throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertTheOneRefusalLine(output, reason);
    }

    @Test
    void Given_no_token_When_refused_Then_one_warn_line_names_the_reason_missing(CapturedOutput output)
            throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")));

        assertTheOneRefusalLine(output, "missing");
    }

    private static void assertTheOneRefusalLine(CapturedOutput output, String reason) {
        List<JsonNode> refusals = output.getOut()
                .lines()
                .filter(line -> line.contains(REFUSAL_MESSAGE))
                .map(LOG_JSON::readTree)
                .toList();
        assertThat(refusals).as("refusal lines logged for one request").hasSize(1);
        JsonNode refusal = refusals.getFirst();
        assertThat(refusal.path("log").path("level").asString())
                .as("level of %s", refusal)
                .isEqualTo("WARN");
        assertThat(refusal.path("reason").asString())
                .as("reason of %s", refusal)
                .isEqualTo(reason);
        assertThat(refusal.path("message").asString())
                .as("message of %s", refusal)
                .isEqualTo(REFUSAL_MESSAGE + ": " + reason);
        assertThat(leafFields(refusal, "")).as("fields of %s", refusal).isSubsetOf(REFUSAL_LINE_FIELDS);
    }

    /** The dotted path to every leaf of a log line; an empty object, such as ECS's {@code service.node}, is a leaf. */
    private static List<String> leafFields(JsonNode node, String prefix) {
        if (!node.isObject() || node.isEmpty()) {
            return List.of(prefix);
        }
        return node.propertyStream()
                .flatMap(member -> leafFields(member.getValue(), prefix + member.getKey() + ".").stream())
                .map(path -> path.endsWith(".") ? path.substring(0, path.length() - 1) : path)
                .toList();
    }

    /**
     * Every shape in {@link #badTokens()} — a tampered signature, both {@code iat} failures and a token
     * with no {@code exp} among them — is refused without any part of itself reaching stdout or stderr.
     */
    @ParameterizedTest
    @MethodSource("badTokens")
    void Given_any_bad_token_When_refused_Then_the_log_never_contains_that_token(String token, CapturedOutput output)
            throws Exception {
        mvc.perform(withHost(get("/api/v1/accounts")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(output.getAll())
                .as("stdout and stderr while refusing a bad token")
                .doesNotContain(ownPartsOf(token).toArray(String[]::new))
                .doesNotContain(TestTokens.WRONG_ISSUER);
    }

    /**
     * What a log must never carry of a token: the whole of it, its payload and signature segments each
     * on their own, and the subject it names. The header segment is left out, since every token signed
     * by one key shares it.
     */
    private static List<String> ownPartsOf(String token) {
        String[] segments = token.split("\\.", -1);
        List<String> parts = new ArrayList<>(List.of(token));
        Arrays.stream(segments).skip(1).filter(segment -> !segment.isEmpty()).forEach(parts::add);
        JsonNode subject =
                LOG_JSON.readTree(Base64.getUrlDecoder().decode(segments[1])).path("sub");
        if (subject.isString()) {
            parts.add(subject.asString());
        }
        return parts;
    }

    /**
     * Only {@code GET} and {@code POST} are classified; every other verb falls to {@code denyAll}, even
     * for the owner, and is refused by {@code ProblemAccessDeniedHandler} — which has no test of its own:
     * this, and every other {@code FORBIDDEN} asserted in this class, is it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS", "PUT", "PATCH", "DELETE"})
    void Given_the_owner_token_When_an_unclassified_method_is_sent_Then_it_is_denied(String method) throws Exception {
        mvc.perform(withHost(request(HttpMethod.valueOf(method), "/api/v1/accounts"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON_BODY))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
                .andExpect(problem(FORBIDDEN));
    }

    /** The RFC 9457 shape every refusal the chain writes carries, and the {@code code} naming which one. */
    private static ResultMatcher problem(String code) {
        return result -> {
            content().contentType(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.code").value(code).match(result);
            requiredProblemMembers().match(result);
        };
    }

    /**
     * I3: the security property {@code dispatcherTypeMatchers(DispatcherType.ERROR)} rests on. A
     * direct {@code GET /error} arrives as {@code DispatcherType.REQUEST}, matches no {@code
     * permitAll} rule, and must be refused exactly like any other unmatched route — never treated
     * as the internal forward the container makes for a genuine failure ({@code
     * LedgerSecurityConfiguration}'s class comment explains the choice this pins).
     */
    @Test
    void Given_no_token_When_a_direct_error_request_is_made_Then_it_is_401_with_a_problem_body() throws Exception {
        mvc.perform(withHost(get("/error")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(UNAUTHENTICATED));
    }

    /**
     * One case per pattern {@code LedgerSecurityConfiguration} permits, so dropping any one of the
     * six turns this red rather than leaving a rule nothing exercises. {@code
     * /actuator/metrics/jvm.memory.used} is there for the wildcard half of {@code
     * /actuator/metrics/**}: the bare path alone would pass whether the pattern ended in {@code
     * /**} or not.
     *
     * <p>Each must answer 200, not merely escape 401 and 403: a 404 escapes both, so a rule that
     * survived while its endpoint disappeared — an Actuator exposure dropped, springdoc removed —
     * would otherwise still pass (#100).
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/info",
                "/actuator/prometheus",
                "/actuator/metrics",
                "/actuator/metrics/jvm.memory.used",
                "/v3/api-docs",
                "/v3/api-docs.yaml"
            })
    void Given_a_public_path_When_requested_with_no_token_Then_it_serves_200(String path) throws Exception {
        mvc.perform(withHost(get(path))).andExpect(status().isOk());
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

    /**
     * Every bad token, categorised or not. HS256 is refused 401, and its refusal is logged though the
     * token never is, but its category is not asserted: the ledger logs it {@code unknown_key}, from Nimbus's "Another algorithm expected,
     * or no matching key(s) found", where the assistant refuses the same shape {@code malformed}. That
     * asymmetry is #352, and is not pinned here either way until it is settled.
     */
    private static Stream<Arguments> badTokens() {
        return Stream.concat(
                categorisedBadTokens().map(shape -> Arguments.of(shape.get()[1])),
                Stream.of(Arguments.of(TestTokens.hmacConfused())));
    }

    /** Every shape of bad token, with the reason category it is logged under (ADR-030's vocabulary). */
    private static Stream<Arguments> categorisedBadTokens() {
        return Stream.of(
                Arguments.of("expired", TestTokens.expired(), "lifetime"),
                Arguments.of("not yet valid", TestTokens.notYetValid(), "lifetime"),
                Arguments.of("issued in the future", TestTokens.futureIssuedAt(), "lifetime"),
                Arguments.of("no iat", TestTokens.noIssuedAt(), "lifetime"),
                Arguments.of("no exp", TestTokens.noExpiry(), "lifetime"),
                Arguments.of("wrong audience", TestTokens.wrongAudience(), "audience"),
                Arguments.of("wrong issuer", TestTokens.wrongIssuer(), "issuer"),
                Arguments.of("no sub", TestTokens.noSubject(), "subject"),
                Arguments.of("alg none", TestTokens.unsigned(), "malformed"),
                Arguments.of("unpublished key", TestTokens.foreignKey(), "unknown_key"),
                Arguments.of("tampered payload", TestTokens.tamperedSignature(), "rejected"));
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

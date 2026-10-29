package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.support.BackendContracts;
import io.github.naimelijah.finassist.gateway.composition.support.BackendContracts.Operation;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.server.mvc.config.FilterProperties;
import org.springframework.cloud.gateway.server.mvc.config.PredicateProperties;
import org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.ServerRequest;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * Holds each relay route in {@code application.yml} to the committed contract of the service it
 * relays to (#339). The web client's generated types come from those contracts, so an operation a
 * contract publishes and the route refuses is a typed call the browser can make and never complete
 * — and nothing else in this module reads both files.
 *
 * <p>The routes are read as data, without a context, and each shortcut is parsed by the gateway's
 * own {@link PredicateProperties} and {@link FilterProperties} and built into the gateway's own
 * {@link GatewayRequestPredicates}, so a match here is the match the running relay makes.
 *
 * <p>This covers the route alone. {@code GatewaySecurityConfiguration} names its relay methods
 * apart from these predicates, and {@code GatewaySecurityTest} holds the chain to the same
 * contracts, read through the same {@link BackendContracts}.
 */
class GatewayRelayRoutesTest {

    private static final String APPLICATION_YAML = "/application.yml";

    private static final List<String> ROUTES_KEY_PATH =
            List.of("spring", "cloud", "gateway", "server", "webmvc", "routes");

    /**
     * Each relay route's id and the contract of the service behind it. Spelled out rather than
     * derived from the id, so a new route fails the test below until someone names its contract.
     */
    private static final Map<String, String> CONTRACT_BY_ROUTE =
            Map.of("ledger", "ledger-v1.openapi.json", "assistant", "assistant-v1.openapi.json");

    /** The one server both contracts declare, which makes each path the path the service serves. */
    private static final String CONTRACT_SERVER_URL = "/";

    private static final String PATH_PREDICATE = "Path";
    private static final String METHOD_PREDICATE = "Method";
    private static final String STRIP_PREFIX_FILTER = "StripPrefix";

    /** A route's predicates and filters, each by name with its shortcut arguments in order. */
    private record Route(String id, Map<String, List<String>> predicates, Map<String, List<String>> filters) {

        /** Every request path pattern the route's {@code Path=} predicate names. */
        String[] pathPatterns() {
            return predicate(PATH_PREDICATE).toArray(String[]::new);
        }

        /**
         * The path prefix a browser adds and the route removes before relaying: as many leading
         * segments of its first {@code Path=} pattern as {@code StripPrefix=} strips, and none
         * when it strips nothing.
         */
        String strippedPrefix() {
            int stripped = filters.containsKey(STRIP_PREFIX_FILTER)
                    ? Integer.parseInt(filters.get(STRIP_PREFIX_FILTER).getFirst())
                    : 0;
            List<String> segments = Arrays.stream(pathPatterns()[0].split("/"))
                    .filter(segment -> !segment.isEmpty())
                    .toList();
            return segments.subList(0, stripped).stream()
                    .map(segment -> "/" + segment)
                    .reduce("", String::concat);
        }

        RequestPredicate pathPredicate() {
            return GatewayRequestPredicates.path(pathPatterns());
        }

        RequestPredicate methodPredicate() {
            return GatewayRequestPredicates.method(predicate(METHOD_PREDICATE).stream()
                    .map(HttpMethod::valueOf)
                    .toArray(HttpMethod[]::new));
        }

        private List<String> predicate(String name) {
            assertThat(predicates)
                    .as("the %s route's predicates in %s", id, APPLICATION_YAML)
                    .containsKey(name);
            return predicates.get(name);
        }
    }

    private static Stream<String> relayedRouteIds() {
        return CONTRACT_BY_ROUTE.keySet().stream().sorted();
    }

    @Test
    void Given_the_shipped_configuration_When_its_routes_are_read_Then_each_relays_to_a_named_backend_contract() {
        assertThat(routes().keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_BY_ROUTE.keySet());
    }

    @ParameterizedTest
    @MethodSource("relayedRouteIds")
    void Given_a_backend_contract_When_its_operations_reach_the_relay_route_Then_the_method_predicate_admits_every_one(
            String routeId) {
        Route route = route(routeId);
        List<Operation> operations = BackendContracts.operations(BackendContracts.read(CONTRACT_BY_ROUTE.get(routeId)));
        RequestPredicate admitted = route.methodPredicate();

        List<Operation> refused = operations.stream()
                .filter(operation -> !admitted.test(relayed(operation.method(), operation.path())))
                .toList();

        assertThat(operations)
                .as("the operations %s publishes", CONTRACT_BY_ROUTE.get(routeId))
                .isNotEmpty();
        assertThat(refused)
                .as(
                        "operations %s publishes that the %s route's Method= predicate refuses",
                        CONTRACT_BY_ROUTE.get(routeId), routeId)
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("relayedRouteIds")
    void Given_a_backend_contract_When_its_paths_carry_the_stripped_prefix_Then_the_path_predicate_matches_every_one(
            String routeId) {
        Route route = route(routeId);
        JsonNode contract = BackendContracts.read(CONTRACT_BY_ROUTE.get(routeId));
        List<Operation> operations = BackendContracts.operations(contract);
        RequestPredicate matched = route.pathPredicate();
        String prefix = route.strippedPrefix();

        List<String> unmatched = operations.stream()
                .map(operation -> prefix + operation.concretePath())
                .distinct()
                .filter(path -> !matched.test(relayed(HttpMethod.GET, path)))
                .toList();

        assertThat(contract.path("servers")
                        .valueStream()
                        .map(server -> server.path("url").asString()))
                .as("the servers %s declares", CONTRACT_BY_ROUTE.get(routeId))
                .containsExactly(CONTRACT_SERVER_URL);
        assertThat(operations)
                .as("the operations %s publishes", CONTRACT_BY_ROUTE.get(routeId))
                .isNotEmpty();
        assertThat(unmatched)
                .as(
                        "paths %s publishes, prefixed with %s, that the %s route's Path= predicate does not match",
                        CONTRACT_BY_ROUTE.get(routeId), prefix, routeId)
                .isEmpty();
    }

    /** The request the browser sends for a relayed operation, as the route's predicates see it. */
    private static ServerRequest relayed(HttpMethod method, String path) {
        return ServerRequest.create(new MockHttpServletRequest(method.name(), path), List.of());
    }

    /** The route with this id, failing with what it looked for rather than a null further down. */
    private static Route route(String id) {
        Map<String, Route> routes = routes();
        assertThat(routes).as("the relay routes in %s", APPLICATION_YAML).containsKey(id);
        return routes.get(id);
    }

    /** The relay routes {@code application.yml} declares, by id, with their shortcuts parsed as the gateway parses them. */
    private static Map<String, Route> routes() {
        Object node;
        try (InputStream yaml = GatewayRelayRoutesTest.class.getResourceAsStream(APPLICATION_YAML)) {
            assertThat(yaml).as("%s on the test classpath", APPLICATION_YAML).isNotNull();
            node = new Yaml().load(yaml);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + APPLICATION_YAML, e);
        }
        for (String key : ROUTES_KEY_PATH) {
            node = asMap(node, key).get(key);
        }
        if (!(node instanceof List<?> routes)) {
            throw new AssertionError(APPLICATION_YAML + " has no route list at " + String.join(".", ROUTES_KEY_PATH));
        }
        Map<String, Route> byId = new LinkedHashMap<>();
        for (Object entry : routes) {
            Map<String, Object> route = asMap(entry, "a route");
            String id = (String) route.get("id");
            Map<String, List<String>> predicates = new LinkedHashMap<>();
            for (String shortcut : shortcuts(route.get("predicates"), id)) {
                PredicateProperties predicate = new PredicateProperties(shortcut);
                predicates.put(
                        predicate.getName(), List.copyOf(predicate.getArgs().values()));
            }
            // Keyed by name, so a repeated filter keeps its first arguments; only StripPrefix,
            // which a route names once, is read from this map.
            Map<String, List<String>> filters = new LinkedHashMap<>();
            for (String shortcut : shortcuts(route.get("filters"), id)) {
                FilterProperties filter = new FilterProperties(shortcut);
                filters.putIfAbsent(
                        filter.getName(), List.copyOf(filter.getArgs().values()));
            }
            byId.put(id, new Route(id, predicates, filters));
        }
        return byId;
    }

    /** A route's predicates or filters, which this file writes in the shortcut form alone. */
    private static List<String> shortcuts(Object node, String routeId) {
        if (!(node instanceof List<?> entries)) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> {
                    if (!(entry instanceof String shortcut)) {
                        throw new AssertionError("route " + routeId + " has an entry not in shortcut form: " + entry
                                + "; teach this test to read it");
                    }
                    return shortcut;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String key) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new AssertionError(APPLICATION_YAML + " has no mapping holding " + key);
        }
        return (Map<String, Object>) map;
    }
}

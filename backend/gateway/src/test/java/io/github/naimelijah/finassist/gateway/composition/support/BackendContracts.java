package io.github.naimelijah.finassist.gateway.composition.support;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the committed OpenAPI contracts of the services the gateway relays to, from the
 * repository's {@code contracts/} directory, and lists the operations each publishes.
 *
 * <p>Shared by {@code GatewayRelayRoutesTest}, which holds the relay routes to these operations,
 * and {@code GatewaySecurityTest}, which holds the security chain to them, so the two read a
 * contract the same way.
 */
public final class BackendContracts {

    /** Set by surefire and failsafe in {@code pom.xml} to the repository's {@code contracts/} directory. */
    private static final String CONTRACTS_DIR_PROPERTY = "finassist.contracts.dir";

    /**
     * Path Item members that are not operations (OpenAPI 3.x). Beside these and the {@link
     * #EXTENSION_PREFIX} extensions, every member names a method.
     */
    private static final Set<String> PATH_ITEM_NON_OPERATIONS =
            Set.of("$ref", "summary", "description", "servers", "parameters");

    /** Specification extensions, which OpenAPI allows on a Path Item and which name no method. */
    private static final String EXTENSION_PREFIX = "x-";

    /**
     * A contract path names a template, the wire a value. Substituting one keeps a match honest: a
     * literal {@code {id}} segment is not what the gateway will ever see.
     */
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^/}]+}");

    private static final String TEMPLATE_VALUE = "sample";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BackendContracts() {}

    /** One operation a contract publishes, at the path the service itself serves it on. */
    public record Operation(HttpMethod method, String path) {

        /** The path with every template variable filled with a sample value, as a request names it. */
        public String concretePath() {
            return TEMPLATE_VARIABLE.matcher(path).replaceAll(TEMPLATE_VALUE);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /**
     * Parses one committed contract.
     *
     * @param fileName the contract's file name inside {@code contracts/}
     * @return the parsed document
     * @throws IllegalStateException if the build did not say where {@code contracts/} is, rather
     *     than comparing against nothing
     */
    public static JsonNode read(String fileName) {
        String directory = System.getProperty(CONTRACTS_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(CONTRACTS_DIR_PROPERTY
                    + " is not set. Surefire sets it in pom.xml; run this test through Maven, or pass -D"
                    + CONTRACTS_DIR_PROPERTY + "=<repository>/contracts.");
        }
        return JSON.readTree(Path.of(directory, fileName).toAbsolutePath().normalize());
    }

    /**
     * Every operation a parsed contract publishes, one per method on each path.
     *
     * @param contract a document {@link #read} returned
     * @return the operations in document order; empty if the contract has no {@code paths}
     */
    public static List<Operation> operations(JsonNode contract) {
        return contract.path("paths")
                .propertyStream()
                .flatMap(pathItem -> pathItem.getValue().propertyNames().stream()
                        .filter(member -> !PATH_ITEM_NON_OPERATIONS.contains(member))
                        .filter(member -> !member.startsWith(EXTENSION_PREFIX))
                        .map(method ->
                                new Operation(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)), pathItem.getKey())))
                .toList();
    }
}

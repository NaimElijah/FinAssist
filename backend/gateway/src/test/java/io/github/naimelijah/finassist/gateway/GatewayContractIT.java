package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Holds the gateway's generated OpenAPI document to the contract committed in {@code contracts/}
 * (ADR-028, ADR-029, ADR-030). Mirrors {@code LedgerContractIT}'s mechanics: parsed trees are
 * compared, not bytes, and a mismatch writes the fresh document to {@code target/contracts/} for a
 * developer to copy over the committed file once the change is intended.
 *
 * <p><b>Why this never makes an HTTP request.</b> The gateway's security chain denies {@code
 * /v3/api-docs} — {@code GatewayOpenApiConfiguration}'s javadoc records that choice and why. So
 * this test calls springdoc's own {@link OpenApiWebMvcResource} bean directly, the same bean a real
 * request to {@code /v3/api-docs} would eventually reach, but as a plain method call rather than
 * through {@code DispatcherServlet} — which is exactly what keeps the security chain, and the
 * denial it enforces, out of the path. The document produced this way is byte-for-byte what
 * springdoc would serve if the path were ever opened, because both call the identical resource
 * bean; only the transport differs, and the transport is precisely what must not exist here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finassist.oidc.client-secret=a-test-client-secret")
class GatewayContractIT {

    /** Set by surefire and failsafe in {@code pom.xml} to the repository's {@code contracts/} directory. */
    private static final String CONTRACTS_DIR_PROPERTY = "finassist.contracts.dir";

    private static final String CONTRACT_FILE = "gateway-v1.openapi.json";
    private static final String LEDGER_CONTRACT_FILE = "ledger-v1.openapi.json";

    /** springdoc's own default; passed by hand because nothing dispatches the {@code @Value} that usually supplies it. */
    private static final String API_DOCS_URL = "/v3/api-docs";

    private static final Path ACTUAL =
            Path.of("target", "contracts", CONTRACT_FILE).toAbsolutePath();

    /**
     * What {@code @hey-api/typescript} appends to an operation's PascalCased {@code operationId} for
     * the types it derives from that operation; the bare id is listed by the empty suffix.
     */
    private static final List<String> GENERATED_TYPE_SUFFIXES =
            List.of("", "Data", "Error", "Errors", "Response", "Responses");

    private static final String INDENT = "  ";
    private static final String LINE_FEED = "\n";

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();

    /**
     * Sorted keys, two-space indentation, {@code "key": value}, and a line feed on every platform, as
     * {@code .editorconfig} asks of JSON. The line feed is explicit because Jackson's default indenter
     * uses the platform's separator, which on Windows would write a file that differs in every line.
     */
    private static final ObjectWriter CONTRACT_WRITER = JSON.writer()
            .with(new DefaultPrettyPrinter(Separators.createDefaultInstance()
                            .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                            .withObjectEmptySeparator("")
                            .withArrayEmptySeparator(""))
                    .withObjectIndenter(new DefaultIndenter(INDENT, LINE_FEED))
                    .withArrayIndenter(new DefaultIndenter(INDENT, LINE_FEED)));

    @Autowired
    private OpenApiWebMvcResource openApiResource;

    @Test
    void Given_the_gateway_When_its_openapi_document_is_generated_Then_it_equals_the_committed_contract()
            throws Exception {
        JsonNode actual = JSON.readTree(generatedDocument());
        Path committed = committedContract(CONTRACT_FILE);
        JsonNode expected = Files.isRegularFile(committed) ? JSON.readTree(committed) : MissingNode.getInstance();

        if (!actual.equals(expected)) {
            Files.createDirectories(ACTUAL.getParent());
            Files.writeString(ACTUAL, CONTRACT_WRITER.writeValueAsString(actual) + LINE_FEED);
        }

        assertThat(actual)
                .withFailMessage(
                        "The gateway's OpenAPI document differs from the committed contract.%n"
                                + "  committed: %s%n"
                                + "  actual:    %s%n"
                                + "copy the actual document over the committed one if the change is intended.",
                        committed, ACTUAL)
                .isEqualTo(expected);
    }

    /**
     * A client must not be able to tell the gateway apart from the ledger by its error envelope
     * (ADR-028, extended by ADR-030). {@code GatewayOpenApiConfiguration} builds this schema by hand
     * rather than importing the ledger's — there is no legal shared home for it (ADR-021) — so this
     * is what stops the two copies drifting apart silently.
     */
    @Test
    void Given_both_contracts_When_their_problem_schemas_are_compared_Then_they_are_identical() throws Exception {
        JsonNode gatewayProblem = problemSchema(JSON.readTree(generatedDocument()));
        JsonNode ledgerProblem = problemSchema(JSON.readTree(committedContract(LEDGER_CONTRACT_FILE)));

        assertThat(gatewayProblem).isNotEqualTo(MissingNode.getInstance());
        assertThat(gatewayProblem).isEqualTo(ledgerProblem);
    }

    /**
     * A client generated from the document trusts {@code required} to say which members every
     * response carries; without it, {@code SessionResponse} and {@code LogoutResponse} would read
     * every member as optional.
     */
    @Test
    void Given_the_generated_document_When_response_schemas_are_read_Then_they_require_what_is_always_present()
            throws Exception {
        JsonNode schemas = JSON.readTree(generatedDocument()).path("components").path("schemas");

        assertThat(requiredMembers(schemas, "SessionResponse")).containsExactlyInAnyOrder("subject", "name", "roles");
        assertThat(requiredMembers(schemas, "LogoutResponse")).containsExactly("logoutUrl");
    }

    /**
     * springdoc makes each handler method's name an {@code operationId}, and silently appends
     * {@code _1} on a collision rather than failing — so a name that is not unique gateway-wide
     * would reach the committed contract looking legitimate. {@code POST /bff/logout}'s id is
     * hand-set by {@code GatewayOpenApiConfiguration} rather than derived from a method name, and
     * this covers it exactly as it covers {@code GET /bff/me}'s springdoc-derived one, since both
     * end up in the same {@code paths} map.
     */
    @Test
    void Given_the_generated_document_When_operation_ids_are_read_Then_none_carries_a_springdoc_collision_suffix()
            throws Exception {
        JsonNode paths = JSON.readTree(generatedDocument()).path("paths");
        List<String> operationIds = paths.valueStream()
                .flatMap(JsonNode::valueStream)
                .map(operation -> operation.path("operationId"))
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .toList();

        assertThat(operationIds).isNotEmpty();
        assertThat(operationIds).noneMatch(id -> id.matches(".*_\\d+$"));
    }

    /**
     * A schema resolved by hand, as {@code LogoutResponse} is, can lose the {@code type} springdoc
     * writes for every schema it resolves itself, and a document that states it for some schemas and
     * not others reads as two shapes to a client.
     */
    @Test
    void Given_the_generated_document_When_component_schemas_are_read_Then_each_states_it_is_an_object()
            throws Exception {
        JsonNode schemas = JSON.readTree(generatedDocument()).path("components").path("schemas");

        assertThat(schemas.propertyNames()).isNotEmpty();
        assertThat(schemas.propertyNames())
                .allSatisfy(name -> assertThat(schemas.path(name).path("type").asString())
                        .as(name)
                        .isEqualTo("object"));
    }

    /**
     * The web client's generator names each operation's types after its {@code operationId} —
     * {@code <Id>Data}, {@code <Id>Response} and the like — and renames one to {@code <Id>Response2}
     * rather than failing when a schema already holds the name. So an id must leave every schema
     * name free.
     */
    @Test
    void Given_the_generated_document_When_operation_ids_are_read_Then_none_derives_a_schema_name() throws Exception {
        JsonNode document = JSON.readTree(generatedDocument());
        List<String> schemaNames = document.path("components").path("schemas").propertyNames().stream()
                .toList();
        List<String> derivedNames = operations(document)
                .map(operation -> operation.path("operationId").asString())
                .map(id -> id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1))
                .flatMap(pascal -> GENERATED_TYPE_SUFFIXES.stream().map(suffix -> pascal + suffix))
                .toList();

        assertThat(derivedNames).isNotEmpty().doesNotContainAnyElementsOf(schemaNames);
    }

    /**
     * {@code GET /bff/me} is found by springdoc and {@code POST /bff/logout} is built by hand, so
     * nothing but this keeps the two described alike: each says what it does, and neither carries
     * a tag — like the assistant's operations, since two operations need no grouping and springdoc's
     * default tag would name a Java class the hand-built one is not served by.
     */
    @Test
    void Given_the_generated_document_When_operations_are_read_Then_each_has_a_summary_and_no_tag() throws Exception {
        List<JsonNode> operations =
                operations(JSON.readTree(generatedDocument())).toList();

        assertThat(operations).hasSize(2).allSatisfy(operation -> {
            assertThat(operation.path("summary").asString()).isNotBlank();
            assertThat(operation.has("tags")).as("tags").isFalse();
        });
    }

    private static Stream<JsonNode> operations(JsonNode document) {
        return document.path("paths").valueStream().flatMap(JsonNode::valueStream);
    }

    private static List<String> requiredMembers(JsonNode schemas, String schema) {
        return schemas.path(schema)
                .path("required")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
    }

    private static JsonNode problemSchema(JsonNode document) {
        return document.path("components").path("schemas").path("Problem");
    }

    /**
     * Generates the document the way {@link OpenApiWebMvcResource#openapiJson} does internally, minus
     * the HTTP request that would have carried it here. The request's own host and port are
     * irrelevant to the result: {@code GatewayOpenApiConfiguration} fixes the document's one server
     * to {@code /}, so springdoc never falls back to deriving one from the request.
     */
    private String generatedDocument() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_DOCS_URL);
        return new String(openApiResource.openapiJson(request, API_DOCS_URL, Locale.ROOT));
    }

    /** Fails loudly rather than comparing against nothing when the build did not say where to look. */
    private static Path committedContract(String fileName) {
        String directory = System.getProperty(CONTRACTS_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(CONTRACTS_DIR_PROPERTY
                    + " is not set. Failsafe sets it in pom.xml; run this test through Maven, or pass -D"
                    + CONTRACTS_DIR_PROPERTY + "=<repository>/contracts.");
        }
        return Path.of(directory, fileName).toAbsolutePath().normalize();
    }
}

package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Holds the ledger's generated OpenAPI document to the contract committed in {@code contracts/}
 * (ADR-004, ADR-028).
 *
 * <p>springdoc generates the document from the code, so nothing else stops the two drifting apart
 * silently. Parsed trees are compared, not bytes: key order and whitespace are not the contract, and a
 * byte comparison would fail on a serialiser's formatting rather than on a change anyone made.
 *
 * <p>On a mismatch the document the code produces is written to {@code target/contracts/}, which git
 * ignores, in exactly the committed file's formatting. Accepting an intended change is then a copy, and
 * its diff shows only what changed. {@code contracts/README.md} gives the command.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerContractIT extends PostgresFixture {

    /** Set by surefire and failsafe in {@code pom.xml} to the repository's {@code contracts/} directory. */
    private static final String CONTRACTS_DIR_PROPERTY = "finassist.contracts.dir";

    private static final String CONTRACT_FILE = "ledger-v1.openapi.json";
    private static final String API_DOCS_PATH = "/v3/api-docs";
    private static final Path ACTUAL =
            Path.of("target", "contracts", CONTRACT_FILE).toAbsolutePath();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

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

    /** One client for the class, closed afterwards, for the reason {@code LedgerApplicationIT} gives. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Test
    void Given_the_running_ledger_When_its_openapi_document_is_generated_Then_it_equals_the_committed_contract()
            throws Exception {
        JsonNode actual = JSON.readTree(served(API_DOCS_PATH));
        Path committed = committedContract();
        JsonNode expected = Files.isRegularFile(committed) ? JSON.readTree(committed) : MissingNode.getInstance();

        if (!actual.equals(expected)) {
            Files.createDirectories(ACTUAL.getParent());
            Files.writeString(ACTUAL, CONTRACT_WRITER.writeValueAsString(actual) + LINE_FEED);
        }

        assertThat(actual)
                .withFailMessage(
                        "The ledger's OpenAPI document differs from the committed contract.%n"
                                + "  committed: %s%n"
                                + "  actual:    %s%n"
                                + "copy the actual document over the committed one if the change is intended.",
                        committed, ACTUAL)
                .isEqualTo(expected);
    }

    /**
     * A client generated from the document trusts {@code required} to say which members every response
     * carries; without it, every member of every response reads as optional. A transaction state's
     * {@code on} and {@code reason} are the deliberate exceptions, because a variant that lacks one omits
     * it. The names are spelled out rather than read from the transport types, because they are the
     * contract.
     */
    @Test
    void Given_the_generated_document_When_response_schemas_are_read_Then_they_require_what_is_always_present()
            throws Exception {
        JsonNode schemas =
                JSON.readTree(served(API_DOCS_PATH)).path("components").path("schemas");

        assertThat(requiredMembers(schemas, "AccountResponse"))
                .containsExactlyInAnyOrder("id", "name", "type", "currency");
        assertThat(requiredMembers(schemas, "BalanceResponse")).containsExactlyInAnyOrder("account", "balance");
        assertThat(requiredMembers(schemas, "MoneyDto")).containsExactlyInAnyOrder("amount", "currency");
        assertThat(requiredMembers(schemas, "EntryDto")).containsExactlyInAnyOrder("account", "amount");
        assertThat(requiredMembers(schemas, "TransactionResponse"))
                .containsExactlyInAnyOrder("id", "occurredOn", "description", "entries", "state");
        assertThat(requiredMembers(schemas, "TransactionStateResponse")).containsExactly("status");
        assertThat(requiredMembers(schemas, "BudgetResponse"))
                .containsExactlyInAnyOrder("id", "period", "currency", "limits", "total");
        assertThat(requiredMembers(schemas, "BudgetPeriodDto")).containsExactlyInAnyOrder("start", "end");
        assertThat(requiredMembers(schemas, "CategoryLimitDto")).containsExactlyInAnyOrder("category", "limit");
        assertThat(requiredMembers(schemas, "BudgetReviewResponse"))
                .containsExactlyInAnyOrder("budget", "period", "currency", "categories", "unbudgeted", "uncategorised");
        assertThat(requiredMembers(schemas, "CategorySpendResponse"))
                .containsExactlyInAnyOrder("category", "limit", "spent", "remaining", "overspent");
        assertThat(requiredMembers(schemas, "UnbudgetedSpendResponse")).containsExactlyInAnyOrder("category", "spent");
        assertThat(requiredMembers(schemas, "CategorisationRuleResponse"))
                .containsExactlyInAnyOrder("fragment", "category");
    }

    /**
     * springdoc makes each handler method's name an {@code operationId}, and silently appends
     * {@code _1} on a collision rather than failing — so a name that is not unique ledger-wide reaches
     * the committed contract looking legitimate (Ruling 22).
     */
    @Test
    void Given_the_generated_document_When_operation_ids_are_read_Then_none_carries_a_springdoc_collision_suffix()
            throws Exception {
        JsonNode paths = JSON.readTree(served(API_DOCS_PATH)).path("paths");
        List<String> operationIds = paths.valueStream()
                .flatMap(JsonNode::valueStream)
                .map(operation -> operation.path("operationId"))
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .toList();

        assertThat(operationIds).isNotEmpty();
        assertThat(operationIds).noneMatch(id -> id.matches(".*_\\d+$"));
    }

    private static List<String> requiredMembers(JsonNode schemas, String schema) {
        return schemas.path(schema)
                .path("required")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
    }

    private String served(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    /** Fails loudly rather than comparing against nothing when the build did not say where to look. */
    private static Path committedContract() {
        String directory = System.getProperty(CONTRACTS_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(CONTRACTS_DIR_PROPERTY
                    + " is not set. Failsafe sets it in pom.xml; run this test through Maven, or pass -D"
                    + CONTRACTS_DIR_PROPERTY + "=<repository>/contracts.");
        }
        return Path.of(directory, CONTRACT_FILE).toAbsolutePath().normalize();
    }
}

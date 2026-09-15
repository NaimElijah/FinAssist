package io.github.naimelijah.finassist.ledger.composition;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the ledger's generated OpenAPI document stable enough to commit, and complete enough to
 * generate a client from (ADR-004, ADR-028).
 *
 * <p>springdoc generates the document from the controllers, and {@code LedgerContractIT} compares it
 * with {@code contracts/ledger-v1.openapi.json}. Two things here serve that comparison:
 *
 * <ul>
 *   <li>the server is fixed to {@code /}, because springdoc otherwise embeds the URL the document was
 *       served on, which in a test is a random port;
 *   <li>the problem shape is documented on every operation, as a {@code default} response referring
 *       to one {@code Problem} schema. Types generated from a document that omitted it would not know
 *       a failure has a body. Listing it per status instead would claim a 404 or a 409 on operations
 *       that cannot return either.
 * </ul>
 *
 * <p>{@code springdoc.writer-with-order-by-keys} and {@code springdoc.paths-to-match} in {@code
 * application.yml} make up the rest: stable key order, and nothing outside {@code /api/v1} in the
 * contract.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String TITLE = "FinAssist ledger";
    private static final String VERSION = "v1";

    /** Relative, so the document holds wherever the ledger is served from, a random test port included. */
    private static final String SERVER_URL = "/";

    private static final String PROBLEM_SCHEMA = "Problem";
    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM_SCHEMA;
    private static final String PROBLEM_DESCRIPTION = "A problem (RFC 9457)";
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String ARRAY = "array";
    private static final String URI_REFERENCE = "uri-reference";

    /** The document's identity and the one server it names. */
    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info().title(TITLE).version(VERSION))
                .servers(List.of(new Server().url(SERVER_URL)));
    }

    /**
     * Adds the {@code Problem} schema, and a {@code default} response referring to it on every operation.
     * springdoc applies a customizer after it has collected the operations, so each one is reached.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return document -> {
            if (document.getComponents() == null) {
                document.components(new Components());
            }
            document.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            if (document.getPaths() != null) {
                document.getPaths().values().stream()
                        .map(PathItem::readOperations)
                        .flatMap(List::stream)
                        .forEach(OpenApiConfiguration::answerProblemsByDefault);
            }
        };
    }

    private static void answerProblemsByDefault(Operation operation) {
        if (operation.getResponses() == null) {
            operation.responses(new ApiResponses());
        }
        operation
                .getResponses()
                .addApiResponse(
                        ApiResponses.DEFAULT,
                        new ApiResponse()
                                .description(PROBLEM_DESCRIPTION)
                                .content(new Content()
                                        .addMediaType(
                                                PROBLEM_JSON,
                                                new MediaType().schema(new JsonSchema().$ref(PROBLEM_REF)))));
    }

    /** The members {@code LedgerProblemHandler} and {@code HostAllowListFilter} write; only {@code code} is always there. */
    private static Schema<?> problemSchema() {
        Schema<?> violation = ofType(OBJECT)
                .addProperty(Problems.FIELD_MEMBER, ofType(STRING))
                .addProperty(Problems.MESSAGE_MEMBER, ofType(STRING));
        return ofType(OBJECT)
                .addProperty(Problems.TYPE_MEMBER, ofType(STRING).format(URI_REFERENCE))
                .addProperty(Problems.TITLE_MEMBER, ofType(STRING))
                .addProperty(Problems.STATUS_MEMBER, ofType(INTEGER))
                .addProperty(Problems.DETAIL_MEMBER, ofType(STRING))
                .addProperty(Problems.INSTANCE_MEMBER, ofType(STRING))
                .addProperty(Problems.CODE_MEMBER, ofType(STRING))
                .addProperty(Problems.ERRORS_MEMBER, ofType(ARRAY).items(violation))
                .addRequiredItem(Problems.CODE_MEMBER);
    }

    /**
     * Every schema in the {@code Problem} shape is built here, so each states its JSON type the same way
     * and none gains a {@code format} the shape does not promise.
     */
    private static Schema<?> ofType(String type) {
        return new JsonSchema().types(Set.of(type));
    }
}

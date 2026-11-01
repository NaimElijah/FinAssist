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
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
 * <p>{@link #unboundedSizes()} corrects one thing swagger-core gets wrong: it publishes a maximum that
 * a minimum-only {@code @Size} never set.
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

    /**
     * {@code instance} is resolved by a client against the URL it called, and behind the gateway that URL
     * is not the one the answering service saw. Said in the contract, since nothing in the value shows it.
     */
    private static final String INSTANCE_DESCRIPTION = "The request's path as received by the service that"
            + " answered. Behind the gateway it omits the gateway's /ledger or /assistant prefix.";

    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String ARRAY = "array";
    private static final String URI_REFERENCE = "uri-reference";

    private static final String BEARER_AUTH_SCHEME = "bearerAuth";

    /**
     * The document's identity, the one server it names, and the {@code bearerAuth} scheme every
     * operation requires (ADR-030). A global {@link SecurityRequirement} rather than one per
     * operation: every endpoint the ledger serves under {@code /api/v1} needs a bearer token, and a
     * generated client should not have to be told that on each one.
     */
    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info().title(TITLE).version(VERSION))
                .servers(List.of(new Server().url(SERVER_URL)))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME));
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

    /**
     * Drops the maximum a minimum-only {@code @Size} publishes. Bean validation defaults {@code max} to
     * {@link Integer#MAX_VALUE}, and swagger-core copies it into {@code maxItems} or {@code maxLength}
     * whether or not it was set, so {@code RecordTransactionRequest.entries} claimed a bound nothing
     * enforces; the real one is the 64 KiB body limit. Only that default is dropped, never a maximum a
     * constraint states, and validation is untouched.
     */
    @Bean
    OpenApiCustomizer unboundedSizes() {
        return document -> {
            if (document.getComponents() != null && document.getComponents().getSchemas() != null) {
                document.getComponents().getSchemas().values().forEach(OpenApiConfiguration::dropDefaultMaximum);
            }
        };
    }

    private static void dropDefaultMaximum(Schema<?> schema) {
        if (Integer.valueOf(Integer.MAX_VALUE).equals(schema.getMaxItems())) {
            schema.setMaxItems(null);
        }
        if (Integer.valueOf(Integer.MAX_VALUE).equals(schema.getMaxLength())) {
            schema.setMaxLength(null);
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(OpenApiConfiguration::dropDefaultMaximum);
        }
        if (schema.getItems() != null) {
            dropDefaultMaximum(schema.getItems());
        }
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

    /**
     * The members every problem writer in the ledger emits. {@code type}, {@code title}, {@code status} and
     * {@code code} are on every problem, and {@code field} and {@code message} on every {@code errors} entry,
     * so the contract requires them and a client may rely on them; {@code detail}, {@code instance} and
     * {@code errors} are absent from some problems.
     */
    private static Schema<?> problemSchema() {
        Schema<?> violation = ofType(OBJECT)
                .addProperty(Problems.FIELD_MEMBER, ofType(STRING))
                .addProperty(Problems.MESSAGE_MEMBER, ofType(STRING))
                .addRequiredItem(Problems.FIELD_MEMBER)
                .addRequiredItem(Problems.MESSAGE_MEMBER);
        return ofType(OBJECT)
                .addProperty(Problems.TYPE_MEMBER, ofType(STRING).format(URI_REFERENCE))
                .addProperty(Problems.TITLE_MEMBER, ofType(STRING))
                .addProperty(Problems.STATUS_MEMBER, ofType(INTEGER))
                .addProperty(Problems.DETAIL_MEMBER, ofType(STRING))
                .addProperty(Problems.INSTANCE_MEMBER, ofType(STRING).description(INSTANCE_DESCRIPTION))
                .addProperty(Problems.CODE_MEMBER, ofType(STRING))
                .addProperty(Problems.ERRORS_MEMBER, ofType(ARRAY).items(violation))
                .addRequiredItem(Problems.TYPE_MEMBER)
                .addRequiredItem(Problems.TITLE_MEMBER)
                .addRequiredItem(Problems.STATUS_MEMBER)
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

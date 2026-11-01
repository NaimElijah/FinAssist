package io.github.naimelijah.finassist.gateway.composition;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the gateway's generated OpenAPI document stable enough to commit, and complete enough to
 * generate a client from — the gateway's half of ADR-028 and ADR-029, extended by ADR-030 for the
 * two ways the browser authenticates here.
 *
 * <p>springdoc generates the document from {@code SessionController} alone, and {@code
 * GatewayContractIT} compares it with {@code contracts/gateway-v1.openapi.json}. Four things here
 * serve that comparison, matching the ledger's {@code OpenApiConfiguration} method for method:
 *
 * <ul>
 *   <li>the server is fixed to {@code /}, for the reason the ledger's javadoc gives — springdoc
 *       otherwise embeds the URL the document was served on, which in a test is a random port;
 *   <li>the {@code Problem} schema and its {@code default} response are added to every operation.
 *       Its shape — member for member — is deliberately identical to the ledger's, which {@code
 *       GatewayContractIT} asserts by comparing the two committed contracts: a client must not be
 *       able to tell the two services apart by their error envelope. There is no shared Java type
 *       to import instead — {@code Problem} is not a class either service owns, only a shape both
 *       happen to write, the same reason {@code MoneyDto} is duplicated per ledger context rather
 *       than factored out;
 *   <li>{@code sessionCookie} and {@code xsrfToken} document the two things the browser actually
 *       holds after a sign-in: the {@code FINASSIST_SESSION} cookie {@code
 *       GatewaySecurityConfiguration} sets, and the {@code X-FINASSIST-XSRF-TOKEN} header whose
 *       value it reads from the readable {@code FINASSIST-XSRF-TOKEN} cookie. OpenAPI's {@code
 *       apiKey} scheme has no way to say "read from one place, sent in another", so that mechanics
 *       lives in each scheme's {@code description} instead of its {@code in};
 *   <li>{@code POST /bff/logout} is added by hand. It answers no controller method — {@code
 *       GatewaySecurityConfiguration}'s {@code logout()} is a Spring Security filter, invoked from
 *       {@code LogoutFilter}, never dispatched through {@code RequestMappingHandlerMapping} — so
 *       nothing in springdoc's request-mapping scan ever names it. Its response schema is resolved
 *       from {@link LogoutResponse} itself with swagger-core's own {@link ModelConverters}, the
 *       same resolver springdoc uses internally for a real controller's return type, so the
 *       hand-added path and a real endpoint's cannot describe that record two different ways.
 * </ul>
 *
 * <p><b>{@code /v3/api-docs} stays denied.</b> {@code GatewayContractIT} generates this document by
 * calling springdoc's {@code OpenApiWebMvcResource} bean directly — a plain method call, never an
 * HTTP request — so the security chain's {@code anyRequest().denyAll()} (ADR-030) is never in the
 * path and the endpoint that would otherwise serve this document stays refused in every
 * environment, including the running gateway a client actually talks to. The ledger and the
 * assistant chose the opposite trade for the same problem: both already answer {@code
 * /v3/api-docs}/{@code /openapi.json} publicly by design, so a request against the running service
 * costs them nothing extra to permit. The gateway's own design decision is narrower — apart from
 * the sign-in entry and its callback, {@code health} is its one public endpoint (ADR-030) — and the
 * document is already committed in the repository, so widening the chain to serve a second copy of
 * it would buy nothing.
 *
 * <p>{@code springdoc.writer-with-order-by-keys} and {@code springdoc.paths-to-match} in {@code
 * application.yml} make up the rest: stable key order, and nothing outside {@code /bff} in the
 * contract — in particular, never the relay routes. {@code /ledger/api/v1/**} and {@code
 * /assistant/api/v1/**} are a transparent proxy to services that each publish their own contract;
 * describing them here too would duplicate those contracts and create a second place for either to
 * drift out from under this one.
 */
@Configuration
public class GatewayOpenApiConfiguration {

    private static final String TITLE = "FinAssist gateway";
    private static final String VERSION = "v1";

    /** Relative, so the document holds wherever the gateway is served from, a random test port included. */
    private static final String SERVER_URL = "/";

    /**
     * The {@code Problem} shape, spelled out again rather than imported. See the class javadoc for
     * why: it is not a Java type either service owns, so there is no legal shared home for it
     * (ADR-007: no shared kernel between the services), and {@code GatewayContractIT} is what proves
     * the two copies stay identical.
     */
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

    private static final String TYPE_MEMBER = "type";
    private static final String TITLE_MEMBER = "title";
    private static final String STATUS_MEMBER = "status";
    private static final String DETAIL_MEMBER = "detail";
    private static final String INSTANCE_MEMBER = "instance";
    private static final String CODE_MEMBER = "code";
    private static final String ERRORS_MEMBER = "errors";
    private static final String FIELD_MEMBER = "field";
    private static final String MESSAGE_MEMBER = "message";

    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String ARRAY = "array";
    private static final String URI_REFERENCE = "uri-reference";

    private static final String SESSION_COOKIE_SCHEME = "sessionCookie";
    private static final String XSRF_TOKEN_SCHEME = "xsrfToken";

    private static final String LOGOUT_RESPONSE_SCHEMA = "LogoutResponse";

    /**
     * The document is OpenAPI 3.1, and a schema resolved for 3.0 states its {@code object} type in a
     * member the 3.1 writer does not read, so it reached the contract with no {@code type} at all.
     */
    private static final boolean OPENAPI_31 = true;

    private static final String LOGOUT_SUMMARY = "Ends the gateway's session and answers the URL that ends Keycloak's";

    /**
     * Not {@code logout}: the web client's generator derives {@code LogoutResponse} from that id for
     * the operation's response, the name {@link LogoutResponse}'s schema already holds, and renames
     * one of the two {@code LogoutResponse2} rather than failing.
     */
    private static final String LOGOUT_OPERATION_ID = "signOut";

    /** The document's identity and the one server it names. */
    @Bean
    OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info().title(TITLE).version(VERSION))
                .servers(List.of(new Server().url(SERVER_URL)));
    }

    /**
     * Adds everything springdoc could not discover on its own: the two security schemes, the
     * {@code Problem} schema and its {@code default} response on every operation, the security
     * requirement {@code GET /bff/me} actually needs, and the whole of {@code POST /bff/logout}.
     *
     * @param sessionCookieName {@code server.servlet.session.cookie.name}, read rather than
     *     restated so the documented cookie name can never drift from the one Tomcat writes
     * @return the customizer springdoc applies once it has collected the operations it found itself
     */
    @Bean
    OpenApiCustomizer gatewayDocumentCustomizer(
            @Value("${server.servlet.session.cookie.name}") String sessionCookieName) {
        return document -> {
            ensureComponents(document);
            document.getComponents()
                    .addSecuritySchemes(SESSION_COOKIE_SCHEME, sessionCookieScheme(sessionCookieName))
                    .addSecuritySchemes(XSRF_TOKEN_SCHEME, xsrfTokenScheme())
                    .addSchemas(PROBLEM_SCHEMA, problemSchema());
            addLogoutPath(document);
            requireSessionToReadMe(document);
            document.getPaths().values().stream()
                    .map(PathItem::readOperations)
                    .flatMap(List::stream)
                    .forEach(GatewayOpenApiConfiguration::answerProblemsByDefault);
        };
    }

    private static void ensureComponents(OpenAPI document) {
        if (document.getComponents() == null) {
            document.components(new Components());
        }
        if (document.getPaths() == null) {
            document.paths(new Paths());
        }
    }

    private static SecurityScheme sessionCookieScheme(String cookieName) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(cookieName)
                .description("The HttpOnly, SameSite=Lax session cookie the gateway sets once a sign-in through "
                        + "Keycloak completes (ADR-030). It carries no readable claim; the browser "
                        + "cannot read it and sends it automatically.");
    }

    private static SecurityScheme xsrfTokenScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(GatewaySecurityConfiguration.CSRF_HEADER_NAME)
                .description("The CSRF token every write must send back in this header. Its value is read by "
                        + "the browser's own script from the " + GatewaySecurityConfiguration.CSRF_COOKIE_NAME
                        + " cookie, which the session cookie above does not protect on its own (ADR-030).");
    }

    /**
     * {@code GET /bff/me} requires a session and nothing else — CSRF protects a write, and this is a
     * read. springdoc found the operation itself; only the security requirement is added by hand,
     * because nothing about the endpoint's own annotations states which scheme guards it.
     */
    private static void requireSessionToReadMe(OpenAPI document) {
        PathItem me = document.getPaths().get(BffRoutes.ME);
        if (me != null && me.getGet() != null) {
            me.getGet().addSecurityItem(new SecurityRequirement().addList(SESSION_COOKIE_SCHEME));
        }
    }

    /**
     * Builds {@code POST /bff/logout} from nothing, for the reason the class javadoc gives.
     * {@link LogoutResponse}'s own schema is resolved rather than restated, so a member added there
     * reaches the contract without a second edit here.
     */
    private static void addLogoutPath(OpenAPI document) {
        ResolvedSchema resolved = ModelConverters.getInstance(OPENAPI_31).readAllAsResolvedSchema(LogoutResponse.class);
        resolved.referencedSchemas.forEach(document.getComponents()::addSchemas);

        Operation logout = new Operation()
                .operationId(LOGOUT_OPERATION_ID)
                .summary(LOGOUT_SUMMARY)
                .addSecurityItem(
                        new SecurityRequirement().addList(SESSION_COOKIE_SCHEME).addList(XSRF_TOKEN_SCHEME))
                .responses(new ApiResponses()
                        .addApiResponse(
                                "200",
                                new ApiResponse()
                                        .description(LOGOUT_SUMMARY)
                                        .content(new Content()
                                                .addMediaType(
                                                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                                        new MediaType()
                                                                .schema(new JsonSchema()
                                                                        .$ref("#/components/schemas/"
                                                                                + LOGOUT_RESPONSE_SCHEMA))))));
        document.getPaths().addPathItem(BffRoutes.LOGOUT, new PathItem().post(logout));
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
     * Every member the ledger's problem writers emit, with {@code type}, {@code title}, {@code
     * status} and {@code code} required, and {@code field} and {@code message} on each {@code errors}
     * entry — identical, member for member, to the ledger's own {@code problemSchema()}, which is
     * exactly what {@code GatewayContractIT} asserts. Every problem {@code GatewayProblems.write}
     * builds carries the four, and none carries {@code instance} or {@code errors}.
     */
    private static Schema<?> problemSchema() {
        Schema<?> violation = ofType(OBJECT)
                .addProperty(FIELD_MEMBER, ofType(STRING))
                .addProperty(MESSAGE_MEMBER, ofType(STRING))
                .addRequiredItem(FIELD_MEMBER)
                .addRequiredItem(MESSAGE_MEMBER);
        return ofType(OBJECT)
                .addProperty(TYPE_MEMBER, ofType(STRING).format(URI_REFERENCE))
                .addProperty(TITLE_MEMBER, ofType(STRING))
                .addProperty(STATUS_MEMBER, ofType(INTEGER))
                .addProperty(DETAIL_MEMBER, ofType(STRING))
                .addProperty(INSTANCE_MEMBER, ofType(STRING).description(INSTANCE_DESCRIPTION))
                .addProperty(CODE_MEMBER, ofType(STRING))
                .addProperty(ERRORS_MEMBER, ofType(ARRAY).items(violation))
                .addRequiredItem(TYPE_MEMBER)
                .addRequiredItem(TITLE_MEMBER)
                .addRequiredItem(STATUS_MEMBER)
                .addRequiredItem(CODE_MEMBER);
    }

    /**
     * Every schema in the {@code Problem} shape is built here, so each states its JSON type the same
     * way and none gains a {@code format} the shape does not promise.
     */
    private static Schema<?> ofType(String type) {
        return new JsonSchema().types(Set.of(type));
    }
}

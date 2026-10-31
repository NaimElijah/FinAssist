package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The customizer that documents the problem shape on every operation (ADR-028).
 *
 * <p>{@code LedgerContractIT} pins the whole generated document, but as a failsafe test that needs
 * Docker. This drives the customizer against operations built by hand, including one with no
 * responses at all, so the unit suite checks the rule without a container.
 */
class OpenApiConfigurationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    /**
     * Required is a promise to every client generated from the document, so it names exactly the members
     * every problem carries: the web client reads {@code status}, and branches on {@code code}.
     */
    @Test
    void Given_a_document_When_customised_Then_its_problem_schema_requires_what_every_problem_carries() {
        OpenAPI document = new OpenAPI();

        configuration.problemResponses().customise(document);

        Schema<?> problem = document.getComponents().getSchemas().get("Problem");
        assertThat(problem.getProperties())
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "code", "errors");
        assertThat(problem.getRequired()).containsExactlyInAnyOrder("type", "title", "status", "code");
        Schema<?> errors = problem.getProperties().get("errors");
        assertThat(errors.getItems().getProperties()).containsOnlyKeys("field", "message");
        assertThat(errors.getItems().getRequired()).containsExactlyInAnyOrder("field", "message");
    }

    /**
     * Behind the gateway, a ledger problem's {@code instance} lacks the {@code /ledger} prefix the browser
     * called, so resolved against that URL it names a path nothing serves. The schema says so.
     */
    @Test
    void Given_a_document_When_customised_Then_the_problem_instance_says_whose_path_it_is() {
        OpenAPI document = new OpenAPI();

        configuration.problemResponses().customise(document);

        Schema<?> problem = document.getComponents().getSchemas().get("Problem");
        Schema<?> instance = problem.getProperties().get("instance");
        assertThat(instance.getDescription())
                .contains("as received by the service that answered")
                .contains("/ledger");
    }

    /**
     * One {@code default} response rather than one entry per status: a per-status list would claim a
     * 404 or a 409 on operations that cannot return either.
     */
    @Test
    void Given_operations_with_and_without_responses_When_customised_Then_each_answers_a_default_problem() {
        Operation read = new Operation()
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("The accounts")));
        Operation write = new Operation();
        OpenAPI document =
                new OpenAPI().path("/api/v1/accounts", new PathItem().get(read).post(write));

        configuration.problemResponses().customise(document);

        for (Operation operation : List.of(read, write)) {
            ApiResponse fallback = operation.getResponses().get("default");
            assertThat(fallback.getDescription()).isEqualTo("A problem (RFC 9457)");
            assertThat(fallback.getContent().get(PROBLEM_JSON).getSchema().get$ref())
                    .isEqualTo(PROBLEM_REF);
        }
        assertThat(read.getResponses()).containsKey("200");
    }

    @Test
    void Given_the_ledger_When_its_document_is_described_Then_it_is_titled_versioned_and_served_from_the_root() {
        OpenAPI document = configuration.ledgerOpenApi();

        assertThat(document.getInfo().getTitle()).isEqualTo("FinAssist ledger");
        assertThat(document.getInfo().getVersion()).isEqualTo("v1");
        assertThat(document.getServers())
                .singleElement()
                .satisfies(server -> assertThat(server.getUrl()).isEqualTo("/"));
    }
}

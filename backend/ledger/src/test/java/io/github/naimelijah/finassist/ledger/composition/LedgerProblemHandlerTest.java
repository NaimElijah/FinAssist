package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fallback that answers every failure no context claims as an RFC 9457 problem (ADR-028).
 *
 * <p>Standalone MockMvc: the handler and a controller that exists only to fail, with no application
 * context. What is asserted is what a client sees, which is the status, the content type and the
 * {@code code}. A client branches on {@code code}, and mutation testing covers {@code composition}.
 *
 * <p>The wire values are spelled out rather than borrowed from the handler's constants, because they
 * are the contract and a test that borrowed them would pass through a change to it.
 */
@ExtendWith(OutputCaptureExtension.class)
class LedgerProblemHandlerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final String RULE = "a budget limit must be positive";
    private static final String OWN_TYPE = "https://finassist.invalid/problems/duplicate-account-name";

    /**
     * Stands in for whatever an exception message carries. A neutral marker rather than anything shaped
     * like a credential, because the test asserts it reaches the log, and a credential never may.
     */
    private static final String MARKER = "internal-detail-marker-7f3a";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new LedgerProblemHandler())
            .build();

    @Test
    void Given_a_domain_rule_violation_When_handled_Then_it_is_a_422_problem_carrying_the_rule() throws Exception {
        mvc.perform(get("/rule-violation"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"))
                .andExpect(jsonPath("$.detail").value(RULE));
    }

    /** The web client asks for JSON; a problem is still served as one, not as a plain JSON object. */
    @Test
    void Given_a_client_accepting_json_When_a_rule_is_violated_Then_the_answer_is_still_problem_json()
            throws Exception {
        mvc.perform(get("/rule-violation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON));
    }

    @Test
    void Given_amounts_in_two_currencies_When_combined_Then_it_is_a_422_currency_mismatch_problem() throws Exception {
        mvc.perform(get("/currency-mismatch"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.detail")
                        .value(new CurrencyMismatchException(Currency.ILS, Currency.USD).getMessage()));
    }

    /**
     * The message goes to the log, where the trace id finds it, and never to the client: an exception
     * message is among the likeliest carriers of internals and data (ADR-028, ADR-019).
     */
    @Test
    void Given_an_unexpected_exception_When_handled_Then_it_is_a_500_problem_whose_message_is_logged_not_served(
            CapturedOutput output) throws Exception {
        String body = mvc.perform(get("/internal-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(MARKER);
        assertThat(output.getOut()).contains("ERROR").contains(MARKER);
    }

    @Test
    void Given_a_body_that_fails_bean_validation_When_posted_Then_it_is_a_400_problem_listing_the_field()
            throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    /** A constraint on a parameter rather than a body is reported by a different Spring exception. */
    @Test
    void Given_a_parameter_that_fails_its_constraint_When_requested_Then_it_is_a_400_problem_naming_the_parameter()
            throws Exception {
        mvc.perform(get("/count").param("value", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    /**
     * A constraint on what a handler returns is broken by the ledger, not by the request, so Spring
     * answers 500. The constraint's messages describe the ledger's own output, and stay off the wire with
     * the rest of a 5xx's detail.
     */
    @Test
    void Given_a_handler_whose_answer_fails_its_own_constraint_When_requested_Then_the_500_lists_no_errors()
            throws Exception {
        mvc.perform(get("/invalid-answer"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void Given_a_body_that_is_not_json_When_posted_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /**
     * The media types a cross-site page can post without a CORS preflight. Refusing them before a
     * controller runs is the second control ADR-027 requires.
     */
    @ParameterizedTest
    @ValueSource(strings = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    void Given_a_write_that_is_not_json_When_posted_Then_it_is_a_415_problem(String mediaType) throws Exception {
        mvc.perform(post("/validated").contentType(mediaType).content("name=groceries"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** {@code type} is written out on framework problems too, so every problem has the filter's shape. */
    @Test
    void Given_an_unknown_route_When_requested_Then_it_is_a_404_problem() throws Exception {
        mvc.perform(get("/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void Given_a_route_that_does_not_take_the_method_When_requested_Then_it_is_a_405_problem() throws Exception {
        mvc.perform(get("/validated"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void Given_an_accept_header_the_route_cannot_produce_When_requested_Then_it_is_a_406_problem() throws Exception {
        mvc.perform(get("/json-only").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    /**
     * Statuses outside ADR-028's table. A standard 4xx is named by its reason; every 5xx, and any
     * status with no standard name, is an internal error.
     */
    @ParameterizedTest
    @CsvSource({"409, CONFLICT", "503, INTERNAL_ERROR", "499, INTERNAL_ERROR"})
    void Given_a_status_outside_the_table_When_raised_Then_its_problem_still_carries_a_code(int status, String code)
            throws Exception {
        mvc.perform(get("/status/" + status))
                .andExpect(status().is(status))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    /**
     * A 5xx's detail is replaced whoever raised it, not only when this handler built the problem without
     * one. The reason given to a {@link ResponseStatusException} becomes its detail on the way in, and
     * must not survive the way out.
     */
    @Test
    void Given_a_5xx_raised_with_a_reason_When_handled_Then_the_reason_is_not_served() throws Exception {
        String body = mvc.perform(get("/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(MARKER);
    }

    /** The fallback fills in what a problem lacks, and does not overwrite what a raiser chose. */
    @Test
    void Given_a_problem_that_names_its_own_type_When_handled_Then_the_type_is_kept_and_a_code_is_added()
            throws Exception {
        mvc.perform(get("/typed"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(OWN_TYPE))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /** A body with one constraint, so a validation failure names exactly one field. */
    record Named(@NotBlank String name) {}

    /**
     * Fails on demand, one way per route.
     *
     * <p>Nested on purpose. Component scanning from {@code LedgerApplication} reaches test classes too,
     * and a top-level controller here would add these routes to every {@code @SpringBootTest}. Spring
     * Boot's {@code TestTypeExcludeFilter} skips a class enclosed by a test class, so this one never
     * leaves this test. Standalone MockMvc still needs the stereotype to map its routes.
     */
    @RestController
    static class FailingController {

        @GetMapping("/rule-violation")
        void ruleViolation() {
            throw new IllegalArgumentException(RULE);
        }

        @GetMapping("/currency-mismatch")
        void currencyMismatch() {
            throw new CurrencyMismatchException(Currency.ILS, Currency.USD);
        }

        @GetMapping("/internal-failure")
        void internalFailure() {
            throw new IllegalStateException(MARKER);
        }

        @GetMapping("/status/{value}")
        void status(@PathVariable int value) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(value));
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MARKER);
        }

        @GetMapping("/typed")
        void typed() {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            problem.setType(URI.create(OWN_TYPE));
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }

        @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Void> jsonOnly() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/count")
        ResponseEntity<Void> count(@RequestParam @Positive int value) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/invalid-answer")
        @NotBlank
        String invalidAnswer() {
            return "";
        }

        @PostMapping(value = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Void> validated(@Valid @RequestBody Named body) {
            return ResponseEntity.noContent().build();
        }
    }
}

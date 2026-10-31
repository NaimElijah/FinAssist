package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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
class LedgerProblemHandlerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final String RULE = "a budget limit must be positive";
    private static final String OWN_TYPE = "https://finassist.invalid/problems/duplicate-account-name";

    /**
     * Stands in for whatever an exception message carries. A neutral marker rather than anything shaped
     * like a credential, because the test asserts it reaches the log, and a credential never may.
     */
    private static final String MARKER = "internal-detail-marker-7f3a";

    /** Stands in for a value the caller sent that the ledger cannot read; no type here accepts it. */
    private static final String REJECTED = "rejected-input-4b2d";

    /** A well-formed id, fixed so every run sends the same body; only the line beside it is refused. */
    private static final String READABLE_ID = "3f2c8a1e-6b4d-4e7a-9c05-d1b2a3f4e5c6";

    /** A whole number one past what a Java {@code int} holds, {@code Integer.MAX_VALUE + 1}. */
    private static final String OVERFLOWING_INT = "2147483648";

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
    void Given_an_unexpected_exception_When_handled_Then_it_is_a_500_problem_whose_message_is_logged_not_served()
            throws Exception {
        String body;
        List<ILoggingEvent> events;
        try (RecordedEvents recorded = RecordedEvents.of(LedgerProblemHandler.class)) {
            body = mvc.perform(get("/internal-failure"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail").isNotEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            events = recorded.events();
        }

        assertThat(body).doesNotContain(MARKER);
        assertThat(events).as("events the handler logged").hasSize(1);
        ILoggingEvent event = events.getFirst();
        assertThat(event.getLevel()).as("level of %s", event).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("Answered 500 with a generic detail");
        assertThat(event.getThrowableProxy())
                .as("the exception logged with %s", event)
                .isNotNull()
                .extracting(IThrowableProxy::getMessage)
                .isEqualTo(MARKER);
    }

    /**
     * The events one logger emits while open, read from a Logback {@link ListAppender} rather than from
     * the console. Standalone MockMvc starts no Boot context, so the console's format depends on whatever
     * else this JVM ran first; the event itself does not.
     */
    private static final class RecordedEvents implements AutoCloseable {

        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private RecordedEvents(Logger logger) {
            this.logger = logger;
            appender.start();
            logger.addAppender(appender);
        }

        static RecordedEvents of(Class<?> loggingClass) {
            return new RecordedEvents((Logger) LoggerFactory.getLogger(loggingClass));
        }

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
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

    /**
     * Unparseable JSON has no member to name, so the entry names the body, as the assistant's does for a
     * body it cannot take whole. The text cut off mid-string is the caller's, and is not repeated.
     */
    @Test
    void Given_a_body_that_is_not_json_When_posted_Then_it_is_a_400_problem_naming_the_body() throws Exception {
        String body = mvc.perform(post("/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + REJECTED))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("is not valid JSON"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(REJECTED);
    }

    @Test
    void Given_no_body_When_posted_to_a_route_that_needs_one_Then_it_is_a_400_problem_naming_the_body()
            throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("could not be read"));
    }

    /** Valid JSON of the wrong shape at its root has no member to name either. */
    @Test
    void Given_a_body_that_is_not_an_object_When_posted_Then_it_is_a_400_problem_naming_the_body() throws Exception {
        mvc.perform(post("/shaped").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"));
    }

    /**
     * A member Jackson cannot read is named by its wire name, so a client can place the error on the field.
     * Neither the reason nor the detail repeats what was sent: Jackson's own message quotes it, and a
     * problem body is no place to reflect input back (ADR-019).
     */
    @Test
    void Given_an_invalid_body_member_When_posted_Then_the_400_names_the_member_not_the_value() throws Exception {
        String body = mvc.perform(post("/shaped")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"" + REJECTED + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("kind"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(REJECTED);
    }

    /** A nested member is named by its dotted path, an element by its index, as bean validation names them. */
    @Test
    void Given_a_nested_member_holding_no_valid_value_When_posted_Then_its_field_is_the_path_to_it() throws Exception {
        String body = mvc.perform(
                        post("/shaped").contentType(MediaType.APPLICATION_JSON).content("""
                        {"kind": "ONE", "lines": [{"id": "%s"}, {"id": "%s"}]}
                        """.formatted(
                                        READABLE_ID, REJECTED)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[1].id"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(REJECTED);
    }

    /**
     * A whole number too large for its member is valid JSON, so the member is named like any other value
     * Jackson refuses, never the body as unparseable. Jackson 3 raises it as a stream-read failure, the
     * type it also uses for text that is not JSON at all, which is why both an {@code Integer} and an
     * {@code int}, nested in an element, are pinned.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"kind\": \"ONE\", \"count\": " + OVERFLOWING_INT + "} | count",
                "{\"kind\": \"ONE\", \"tallies\": [{\"times\": " + OVERFLOWING_INT + "}]} | tallies[0].times"
            })
    void Given_a_whole_number_too_large_for_its_member_When_posted_Then_the_400_names_the_member_as_an_invalid_value(
            String json, String field) throws Exception {
        String body = mvc.perform(
                        post("/shaped").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(OVERFLOWING_INT);
    }

    /** The same overflow with no member to name, the body itself being the number, names the body. */
    @Test
    void Given_a_body_that_is_a_number_too_large_for_it_When_posted_Then_the_400_names_the_body_as_an_invalid_value()
            throws Exception {
        mvc.perform(post("/counted").contentType(MediaType.APPLICATION_JSON).content(OVERFLOWING_INT))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"));
    }

    /** Spring's own detail for a type mismatch quotes the value, so it is replaced. */
    @Test
    void Given_a_query_parameter_of_the_wrong_type_When_requested_Then_the_400_names_it_not_the_value()
            throws Exception {
        String body = mvc.perform(get("/count").param("value", REJECTED))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(REJECTED);
    }

    /**
     * A path variable is a parameter like any other. Only {@code detail} and the entry are checked for the
     * value: {@code instance} is the request's path, which holds it.
     */
    @Test
    void Given_a_path_variable_of_the_wrong_type_When_requested_Then_it_is_a_400_problem_naming_the_variable()
            throws Exception {
        mvc.perform(get("/status/" + REJECTED))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail", not(containsString(REJECTED))))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].message", not(containsString(REJECTED))))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void Given_a_required_query_parameter_left_out_When_requested_Then_it_is_a_400_problem_naming_the_parameter()
            throws Exception {
        mvc.perform(get("/count"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    /**
     * The media types a cross-site page can post without a CORS preflight. Refusing them before a
     * controller runs is the second control ADR-030 requires.
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
     * A body declaring multipart that cannot be parsed is the request's fault, so it is malformed, and its
     * message, which names the parser's internals, is neither served nor logged at ERROR.
     */
    @Test
    void Given_a_multipart_body_that_cannot_be_parsed_When_handled_Then_it_is_a_400_problem_logged_at_no_level()
            throws Exception {
        String body;
        List<ILoggingEvent> events;
        try (RecordedEvents recorded = RecordedEvents.of(LedgerProblemHandler.class)) {
            body = mvc.perform(get("/unparseable-multipart"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            events = recorded.events();
        }

        assertThat(body).doesNotContain(MARKER);
        assertThat(events).as("events the handler logged").isEmpty();
    }

    /** The narrower multipart failure keeps Spring MVC's own status and the ledger's code for it. */
    @Test
    void Given_a_multipart_upload_over_its_limit_When_handled_Then_it_is_still_a_413_problem() throws Exception {
        mvc.perform(get("/upload-too-large"))
                .andExpect(status().is(413))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
    }

    /**
     * Statuses outside ADR-028's table. The two the framework can still raise, 409 and 413, carry codes of
     * their own. Every other status, 4xx or 5xx, is an internal error: the ledger never means to send one,
     * and an {@code HttpStatus} constant's name is no stable code, since Spring renames them.
     */
    @ParameterizedTest
    @CsvSource({
        "409, CONFLICT",
        "413, CONTENT_TOO_LARGE",
        "418, INTERNAL_ERROR",
        "503, INTERNAL_ERROR",
        "499, INTERNAL_ERROR"
    })
    void Given_a_status_outside_the_table_When_raised_Then_its_problem_still_carries_a_code(int status, String code)
            throws Exception {
        mvc.perform(get("/status/" + status))
                .andExpect(status().is(status))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    /**
     * Every problem carries {@code type}, {@code title} and {@code status} (ADR-028), even one whose status
     * has no {@code HttpStatus} constant, such as 499, and so no reason phrase to serve as a title.
     */
    @ParameterizedTest
    @ValueSource(ints = {409, 413, 418, 503, 499})
    void Given_a_status_outside_the_table_When_raised_Then_its_problem_carries_a_type_a_title_and_the_status(int status)
            throws Exception {
        mvc.perform(get("/status/" + status))
                .andExpect(status().is(status))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").isString())
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(status));
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

    /**
     * A denial raised from inside a handler must answer 403, never fall through to the catch-all and
     * become a 500. Nothing in this codebase throws {@link AccessDeniedException} from inside a
     * controller today — the security filter chain answers every denial it raises itself — so this
     * proves the handler exists for the day a method-level check does.
     */
    @Test
    void Given_an_access_denial_raised_inside_a_handler_When_handled_Then_it_is_a_403_problem_not_a_500()
            throws Exception {
        mvc.perform(get("/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** The same guarantee, for an authentication failure raised from inside a handler. */
    @Test
    void Given_an_authentication_failure_raised_inside_a_handler_When_handled_Then_it_is_a_401_problem_not_a_500()
            throws Exception {
        mvc.perform(get("/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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

    /** What a {@link Shaped} body may name. */
    enum Kind {
        ONE
    }

    /** An element of a list, so a failure inside it has an index in its path. */
    record Line(UUID id) {}

    /**
     * An element holding a primitive, apart from {@link Line}: Jackson refuses a primitive left out, which
     * would otherwise fail every body that sends a line without it.
     */
    record Tally(int times) {}

    /** A body whose members Jackson itself refuses, before bean validation runs. */
    record Shaped(Kind kind, Integer count, List<Line> lines, List<Tally> tallies) {}

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

        @GetMapping("/unparseable-multipart")
        void unparseableMultipart() {
            throw new MultipartException(MARKER);
        }

        @GetMapping("/upload-too-large")
        void uploadTooLarge() {
            throw new MaxUploadSizeExceededException(1);
        }

        @GetMapping("/status/{value}")
        void status(@PathVariable int value) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(value));
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MARKER);
        }

        @GetMapping("/access-denied")
        void accessDenied() {
            throw new AccessDeniedException(MARKER);
        }

        @GetMapping("/unauthenticated")
        void unauthenticated() {
            throw new BadCredentialsException(MARKER);
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

        @PostMapping(value = "/shaped", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Void> shaped(@RequestBody Shaped body) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping(value = "/counted", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Void> counted(@RequestBody Integer count) {
            return ResponseEntity.noContent().build();
        }
    }
}

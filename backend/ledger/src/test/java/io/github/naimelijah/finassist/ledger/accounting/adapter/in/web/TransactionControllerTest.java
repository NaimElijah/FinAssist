package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.EntryCurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransactionNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransitionTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewTransactionsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The transaction routes as a client sees them: status, content type, body and {@code code} (ADR-028).
 *
 * <p>The same slice as {@code AccountControllerTest}, over this controller. Each state variant is
 * asserted member by member, because the sealed state is mapped by hand and a member a variant lacks
 * must be absent from the wire, not null.
 *
 * <p>Wire values are spelled out rather than borrowed from the adapter's constants, for the reason
 * {@code AccountControllerTest} gives.
 */
@WebMvcTest(controllers = TransactionController.class)
class TransactionControllerTest {

    private static final String TRANSACTIONS = "/api/v1/transactions";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final String REASON = "entered twice";

    private static final AccountId CASH = AccountId.generate();
    private static final AccountId GROCERIES = AccountId.generate();

    private static final String ON_MARCH_5 = """
            {"on": "2026-03-05"}
            """;
    private static final String VOID_ON_MARCH_5 = """
            {"on": "2026-03-05", "reason": "entered twice"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RecordTransactionUseCase recorder;

    @MockitoBean
    private ViewTransactionsUseCase journal;

    @MockitoBean
    private TransitionTransactionUseCase lifecycle;

    /** No {@code Location}, as for an account, and a pending state carries neither a date nor a reason. */
    @Test
    void Given_a_balanced_transaction_When_recorded_Then_it_is_answered_201_pending_with_no_date_and_no_location()
            throws Exception {
        Transaction recorded = purchase(MARCH_3, new TransactionState.Pending());
        when(recorder.record(new RecordTransactionCommand(MARCH_3, "Groceries", recorded.entries())))
                .thenReturn(recorded);

        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("12.50", "-12.50", "ILS")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(recorded.id().toString()))
                .andExpect(jsonPath("$.occurredOn").value("2026-03-03"))
                .andExpect(jsonPath("$.description").value("Groceries"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].account").value(GROCERIES.toString()))
                .andExpect(jsonPath("$.entries[0].amount.amount").value("12.50"))
                .andExpect(jsonPath("$.entries[0].amount.currency").value("ILS"))
                .andExpect(jsonPath("$.entries[1].account").value(CASH.toString()))
                .andExpect(jsonPath("$.entries[1].amount.amount").value("-12.50"))
                .andExpect(jsonPath("$.state.status").value("PENDING"))
                .andExpect(jsonPath("$.state.on").doesNotExist())
                .andExpect(jsonPath("$.state.reason").doesNotExist());
    }

    @Test
    void Given_fewer_than_two_entries_When_recording_Then_it_is_a_400_problem_listing_the_entries() throws Exception {
        mvc.perform(post(TRANSACTIONS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"occurredOn": "2026-03-03", "description": "Groceries",
                         "entries": [{"account": "%s", "amount": {"amount": "12.50", "currency": "ILS"}}]}
                        """.formatted(GROCERIES)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("entries"));

        verifyNoInteractions(recorder);
    }

    /** A null in the list is malformed like a missing field, rather than a failure while mapping it. */
    @Test
    void Given_a_null_entry_When_recording_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(TRANSACTIONS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"occurredOn": "2026-03-03", "description": "Groceries",
                         "entries": [{"account": "%s", "amount": {"amount": "12.50", "currency": "ILS"}}, null]}
                        """.formatted(GROCERIES)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(recorder);
    }

    /** An exponent is not plain decimal text, so it is refused as malformed before {@code Money} sees it. */
    @Test
    void Given_an_amount_written_with_an_exponent_When_recording_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("1e3", "-1e3", "ILS")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("entries[0].amount.amount")));

        verifyNoInteractions(recorder);
    }

    /** {@code Money} refuses precision its currency does not admit rather than rounding it away. */
    @Test
    void Given_an_amount_finer_than_its_currency_admits_When_recording_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("12.505", "-12.505", "ILS")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(recorder);
    }

    /**
     * 404 is for the resource named in the path. An account named in the body is part of what the
     * request asks for, so its absence is a refusal (ADR-028).
     */
    @Test
    void Given_an_entry_naming_an_unknown_account_When_recording_Then_it_is_a_422_unknown_account_problem()
            throws Exception {
        AccountNotFoundException missing = new AccountNotFoundException(CASH);
        when(recorder.record(any())).thenThrow(missing);

        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("12.50", "-12.50", "ILS")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("UNKNOWN_ACCOUNT"));
    }

    @Test
    void Given_entries_that_do_not_balance_When_recording_Then_it_is_a_422_rule_violated_problem() throws Exception {
        IllegalArgumentException unbalanced = new IllegalArgumentException(
                "Entries must sum to zero but sum to 1.00 ILS; the books would not balance");
        when(recorder.record(any())).thenThrow(unbalanced);

        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("12.50", "-11.50", "ILS")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(unbalanced.getMessage()))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    @Test
    void Given_an_entry_in_a_currency_its_account_lacks_When_recording_Then_it_is_a_422_currency_mismatch_problem()
            throws Exception {
        Account dollars = new Account(CASH, "Cash", AccountType.ASSET, Currency.USD);
        EntryCurrencyMismatchException mismatch = new EntryCurrencyMismatchException(dollars, Currency.ILS);
        when(recorder.record(any())).thenThrow(mismatch);

        mvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordRequest("12.50", "-12.50", "ILS")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(mismatch.getMessage()))
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    /**
     * The port's order is kept, not re-sorted, and a voided transaction is listed with its date and reason:
     * a void is history, not a deletion (ADR-020).
     */
    @Test
    void Given_transactions_in_a_period_When_listed_Then_they_are_answered_200_in_the_port_order_voided_included()
            throws Exception {
        Transaction voided = purchase(MARCH_5, new TransactionState.Voided(MARCH_5, REASON));
        Transaction cleared = purchase(MARCH_3, new TransactionState.Cleared(MARCH_5));
        when(journal.between(MARCH_1, APRIL_1)).thenReturn(List.of(voided, cleared));

        mvc.perform(get(TRANSACTIONS).param("from", "2026-03-01").param("to", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(voided.id().toString()))
                .andExpect(jsonPath("$[0].state.status").value("VOIDED"))
                .andExpect(jsonPath("$[0].state.on").value("2026-03-05"))
                .andExpect(jsonPath("$[0].state.reason").value(REASON))
                .andExpect(jsonPath("$[1].id").value(cleared.id().toString()))
                .andExpect(jsonPath("$[1].state.status").value("CLEARED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=2026-03-01", "?to=2026-04-01", "?from=01/03/2026&to=2026-04-01"})
    void Given_a_period_bound_that_is_missing_or_malformed_When_listing_Then_it_is_a_400_problem(String query)
            throws Exception {
        mvc.perform(get(TRANSACTIONS + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(journal);
    }

    /** An empty period is refused as well as an inverted one: {@code to} is exclusive. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-03-01", "2026-04-01"})
    void Given_a_period_that_does_not_start_before_it_ends_When_listing_Then_it_is_a_422_rule_violated_problem(
            String to) throws Exception {
        mvc.perform(get(TRANSACTIONS).param("from", "2026-04-01").param("to", to))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(journal);
    }

    @Test
    void Given_a_pending_transaction_When_cleared_Then_it_is_answered_200_cleared_on_that_date_with_no_reason()
            throws Exception {
        Transaction cleared = purchase(MARCH_3, new TransactionState.Cleared(MARCH_5));
        when(lifecycle.clear(cleared.id(), MARCH_5)).thenReturn(cleared);

        mvc.perform(post(TRANSACTIONS + "/" + cleared.id() + "/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ON_MARCH_5))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(cleared.id().toString()))
                .andExpect(jsonPath("$.state.status").value("CLEARED"))
                .andExpect(jsonPath("$.state.on").value("2026-03-05"))
                .andExpect(jsonPath("$.state.reason").doesNotExist());
    }

    @Test
    void Given_a_cleared_transaction_When_reconciled_Then_it_is_answered_200_reconciled_on_that_date_with_no_reason()
            throws Exception {
        Transaction reconciled = purchase(MARCH_3, new TransactionState.Reconciled(MARCH_5));
        when(lifecycle.reconcile(reconciled.id(), MARCH_5)).thenReturn(reconciled);

        mvc.perform(post(TRANSACTIONS + "/" + reconciled.id() + "/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ON_MARCH_5))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state.status").value("RECONCILED"))
                .andExpect(jsonPath("$.state.on").value("2026-03-05"))
                .andExpect(jsonPath("$.state.reason").doesNotExist());
    }

    @Test
    void Given_a_pending_transaction_When_voided_Then_it_is_answered_200_voided_with_its_date_and_reason()
            throws Exception {
        Transaction voided = purchase(MARCH_3, new TransactionState.Voided(MARCH_5, REASON));
        when(lifecycle.markVoided(voided.id(), MARCH_5, REASON)).thenReturn(voided);

        mvc.perform(post(TRANSACTIONS + "/" + voided.id() + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VOID_ON_MARCH_5))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state.status").value("VOIDED"))
                .andExpect(jsonPath("$.state.on").value("2026-03-05"))
                .andExpect(jsonPath("$.state.reason").value(REASON));
    }

    @ParameterizedTest
    @MethodSource("lifecycleCommands")
    void Given_an_unknown_transaction_When_a_lifecycle_command_is_sent_Then_it_is_a_404_transaction_not_found_problem(
            String command, String body) throws Exception {
        TransactionId unknown = TransactionId.generate();
        TransactionNotFoundException missing = new TransactionNotFoundException(unknown);
        everyTransitionThrows(missing);

        mvc.perform(post(TRANSACTIONS + "/" + unknown + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("lifecycleCommands")
    void Given_a_state_forbidding_it_When_a_lifecycle_command_is_sent_Then_it_is_a_409_illegal_transition_problem(
            String command, String body) throws Exception {
        IllegalTransitionException illegal =
                new IllegalTransitionException(new TransactionState.Reconciled(MARCH_5), command);
        everyTransitionThrows(illegal);

        mvc.perform(post(TRANSACTIONS + "/" + TransactionId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(illegal.getMessage()))
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    @ParameterizedTest
    @MethodSource("lifecycleCommands")
    void Given_a_date_too_early_When_a_lifecycle_command_is_sent_Then_it_is_a_422_rule_violated_problem(
            String command, String body) throws Exception {
        everyTransitionThrows(new IllegalArgumentException(
                "A transaction cannot be changed on 2026-03-05, before it occurred on 2026-03-06"));

        mvc.perform(post(TRANSACTIONS + "/" + TransactionId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "reconcile", "void"})
    void Given_no_date_When_a_lifecycle_command_is_sent_Then_it_is_a_400_problem_listing_the_date(String command)
            throws Exception {
        mvc.perform(post(TRANSACTIONS + "/" + TransactionId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("on")));

        verifyNoInteractions(lifecycle);
    }

    @Test
    void Given_a_void_with_a_blank_reason_When_sent_Then_it_is_a_400_problem_listing_the_reason() throws Exception {
        mvc.perform(post(TRANSACTIONS + "/" + TransactionId.generate() + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"on": "2026-03-05", "reason": " "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        verifyNoInteractions(lifecycle);
    }

    /** Every write refuses a form post, which a cross-site page can send without a preflight (ADR-027). */
    @ParameterizedTest
    @ValueSource(strings = {"", "/%s/clear", "/%s/reconcile", "/%s/void"})
    void Given_a_form_encoded_body_When_any_transaction_write_is_posted_Then_it_is_a_415_problem(String route)
            throws Exception {
        mvc.perform(post(TRANSACTIONS + route.formatted(TransactionId.generate()))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("on=2026-03-05"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(recorder, lifecycle);
    }

    /** Each lifecycle command with a body it accepts, so only the port's answer differs between cases. */
    static Stream<Arguments> lifecycleCommands() {
        return Stream.of(
                Arguments.of("clear", ON_MARCH_5),
                Arguments.of("reconcile", ON_MARCH_5),
                Arguments.of("void", VOID_ON_MARCH_5));
    }

    private void everyTransitionThrows(RuntimeException failure) {
        when(lifecycle.clear(any(), any())).thenThrow(failure);
        when(lifecycle.reconcile(any(), any())).thenThrow(failure);
        when(lifecycle.markVoided(any(), any(), any())).thenThrow(failure);
    }

    /** A grocery purchase posted on 3 March: groceries debited, cash credited, in the given amounts. */
    private static String recordRequest(String debit, String credit, String currency) {
        return """
                {"occurredOn": "2026-03-03", "description": "Groceries",
                 "entries": [
                   {"account": "%s", "amount": {"amount": "%s", "currency": "%s"}},
                   {"account": "%s", "amount": {"amount": "%s", "currency": "%s"}}]}
                """.formatted(GROCERIES, debit, currency, CASH, credit, currency);
    }

    private static Transaction purchase(LocalDate occurredOn, TransactionState state) {
        return new Transaction(
                TransactionId.generate(),
                occurredOn,
                "Groceries",
                List.of(
                        new Entry(GROCERIES, Money.of("12.50", Currency.ILS)),
                        new Entry(CASH, Money.of("-12.50", Currency.ILS))),
                state);
    }
}

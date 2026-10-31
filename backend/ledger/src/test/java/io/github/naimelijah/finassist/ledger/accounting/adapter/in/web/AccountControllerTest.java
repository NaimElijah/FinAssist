package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.WebSliceSecurity;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ListAccountsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewBalanceUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewTransactionsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The accounts routes as a client sees them: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller, both advices, Jackson and bean validation. The
 * in-ports are mocked, which is allowed because they are the ledger's own ports. The slice skips
 * scanned {@code @Configuration}, so {@code LedgerConfiguration}, its services and the database stay
 * out; {@code AccountingHttpIT} drives the same routes through all of them.
 *
 * <p>Wire values are spelled out rather than borrowed from the adapter's constants, because they are the
 * contract and a test that borrowed them would pass through a change to it.
 */
@Import(WebSliceSecurity.class)
@WebMvcTest(controllers = AccountController.class)
class AccountControllerTest {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ListAccountsUseCase chart;

    @MockitoBean
    private OpenAccountUseCase opener;

    @MockitoBean
    private ViewBalanceUseCase balances;

    @MockitoBean
    private ViewTransactionsUseCase journal;

    /** The port promises name order; the controller must keep whatever order it is given, not re-sort. */
    @Test
    void Given_accounts_in_the_chart_When_listed_Then_they_are_answered_200_in_the_order_the_port_gave()
            throws Exception {
        Account savings = account("Savings", AccountType.ASSET, Currency.USD);
        Account cash = account("Cash", AccountType.ASSET, Currency.ILS);
        when(chart.all()).thenReturn(List.of(savings, cash));

        mvc.perform(get(ACCOUNTS))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(savings.id().toString()))
                .andExpect(jsonPath("$[0].name").value("Savings"))
                .andExpect(jsonPath("$[0].type").value("ASSET"))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[1].name").value("Cash"));
    }

    /** No {@code Location}: there is no single-item read for one to name (ADR-028). */
    @Test
    void Given_a_valid_account_When_opened_Then_it_is_answered_201_with_the_account_and_no_location() throws Exception {
        Account groceries = account("Groceries", AccountType.EXPENSE, Currency.ILS);
        when(opener.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS)))
                .thenReturn(groceries);

        mvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Groceries", "type": "EXPENSE", "currency": "ILS"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(groceries.id().toString()))
                .andExpect(jsonPath("$.name").value("Groceries"))
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.currency").value("ILS"));
    }

    @Test
    void Given_a_blank_name_When_opening_an_account_Then_it_is_a_400_problem_listing_the_field() throws Exception {
        mvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": " ", "type": "ASSET", "currency": "ILS"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verifyNoInteractions(opener);
    }

    /** The entry names the member, so the form can place the error on it (ADR-028). */
    @Test
    void Given_an_unknown_account_type_When_opening_an_account_Then_it_is_a_400_problem_naming_the_type()
            throws Exception {
        mvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Cash", "type": "PIGGY_BANK", "currency": "ILS"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verifyNoInteractions(opener);
    }

    /**
     * A cross-site page can post a form, plain text or multipart without a preflight, so a write refuses
     * anything but JSON (ADR-030).
     *
     * <p>MockMvc never parses a multipart body, so the multipart case here reaches the mapping's refusal
     * without the parsing a container performs first; {@code LedgerApplicationIT} posts a well-formed
     * multipart body to the running ledger.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                MediaType.TEXT_PLAIN_VALUE,
                MediaType.MULTIPART_FORM_DATA_VALUE
            })
    void Given_a_body_that_is_not_json_When_opening_an_account_Then_it_is_a_415_problem(String mediaType)
            throws Exception {
        mvc.perform(post(ACCOUNTS).contentType(mediaType).content("name=Cash&type=ASSET&currency=ILS"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(opener);
    }

    /** Asked for as the web client asks, with {@code Accept: application/json}; a problem is still a problem. */
    @Test
    void Given_a_name_already_open_When_opening_an_account_Then_it_is_a_409_duplicate_account_name_problem()
            throws Exception {
        DuplicateAccountNameException duplicate = new DuplicateAccountNameException("Cash");
        when(opener.open(any())).thenThrow(duplicate);

        mvc.perform(post(ACCOUNTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "Cash", "type": "ASSET", "currency": "ILS"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(duplicate.getMessage()))
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT_NAME"));
    }

    @Test
    void Given_an_unknown_account_When_its_balance_is_read_Then_it_is_a_404_account_not_found_problem()
            throws Exception {
        AccountId unknown = AccountId.generate();
        AccountNotFoundException missing = new AccountNotFoundException(unknown);
        when(balances.balanceOf(unknown)).thenThrow(missing);

        mvc.perform(get(ACCOUNTS + "/" + unknown + "/balance"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void Given_a_malformed_account_id_When_its_balance_is_read_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(get(ACCOUNTS + "/not-a-uuid/balance"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(balances);
    }

    /**
     * The amount travels as a JSON string holding the currency's scale, never as a number (ADR-028). The
     * raw body is asserted because a JSON path converts what it finds to the expected type, so it would
     * accept the number {@code 1200} as the string {@code "1200"}.
     */
    @ParameterizedTest
    @CsvSource({"12.5, ILS, 12.50", "1200, JPY, 1200"})
    void Given_a_balance_When_read_Then_its_amount_is_a_string_keeping_the_currency_scale(
            String held, Currency currency, String onTheWire) throws Exception {
        AccountId id = AccountId.generate();
        when(balances.balanceOf(id)).thenReturn(new Balance(id, Money.of(held, currency)));

        mvc.perform(get(ACCOUNTS + "/" + id + "/balance"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.account").value(id.toString()))
                .andExpect(jsonPath("$.balance.currency").value(currency.name()))
                .andExpect(content().string(containsString("\"amount\":\"" + onTheWire + "\"")));
    }

    /** A voided transaction is listed with its reason, because voiding is not deleting (ADR-020). */
    @Test
    void Given_an_account_with_transactions_When_they_are_listed_Then_they_are_answered_200_in_the_port_order()
            throws Exception {
        AccountId cash = AccountId.generate();
        Transaction voided = purchase(cash, new TransactionState.Voided(MARCH_5, "entered twice"));
        Transaction pending = purchase(cash, new TransactionState.Pending());
        when(journal.forAccount(cash)).thenReturn(List.of(voided, pending));

        mvc.perform(get(ACCOUNTS + "/" + cash + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(voided.id().toString()))
                .andExpect(jsonPath("$[0].state.status").value("VOIDED"))
                .andExpect(jsonPath("$[0].state.reason").value("entered twice"))
                .andExpect(jsonPath("$[1].id").value(pending.id().toString()))
                .andExpect(jsonPath("$[1].state.status").value("PENDING"));
    }

    @Test
    void Given_an_unknown_account_When_its_transactions_are_listed_Then_it_is_a_404_account_not_found_problem()
            throws Exception {
        AccountId unknown = AccountId.generate();
        when(journal.forAccount(unknown)).thenThrow(new AccountNotFoundException(unknown));

        mvc.perform(get(ACCOUNTS + "/" + unknown + "/transactions"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    private static Account account(String name, AccountType type, Currency currency) {
        return new Account(AccountId.generate(), name, type, currency);
    }

    /** Groceries bought on 3 March, paid from the given account. */
    private static Transaction purchase(AccountId paidFrom, TransactionState state) {
        return new Transaction(
                TransactionId.generate(),
                MARCH_3,
                "Groceries",
                List.of(
                        new Entry(AccountId.generate(), Money.of("12.50", Currency.ILS)),
                        new Entry(paidFrom, Money.of("-12.50", Currency.ILS))),
                state);
    }
}

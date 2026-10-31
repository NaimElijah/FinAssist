package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import static io.github.naimelijah.finassist.ledger.ProblemAssertions.requiredProblemMembers;
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
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetNotFoundException;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetReview;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.CategorySpend;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListBudgetsUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ReviewBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.composition.StrictTextCoercion;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The budget routes as a client sees them: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller and both advices; the in-ports are mocked, the
 * same shape {@code AccountControllerTest} uses. {@code BudgetingHttpIT} drives the same routes
 * through the real services and the store.
 *
 * <p>Wire values are spelled out rather than borrowed from the adapter's constants, for the reason
 * {@code AccountControllerTest} gives.
 */
@WebMvcTest(controllers = BudgetController.class)
@Import({WebSliceSecurity.class, StrictTextCoercion.class})
class BudgetControllerTest {

    private static final String BUDGETS = "/api/v1/budgets";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final BudgetPeriod SEPTEMBER = BudgetPeriod.of(YearMonth.of(2026, 9));
    private static final BudgetPeriod AUGUST = BudgetPeriod.of(YearMonth.of(2026, 8));
    private static final Category GROCERIES = Category.of("groceries");
    private static final Category TRANSPORT = Category.of("transport");
    private static final Category COFFEE = Category.of("coffee");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DefineBudgetUseCase definer;

    @MockitoBean
    private ListBudgetsUseCase lister;

    @MockitoBean
    private ReviewBudgetUseCase reviewer;

    /** The port promises period-start-descending order; the controller must keep it, not re-sort. */
    @Test
    void Given_budgets_in_the_store_When_listed_Then_they_are_answered_200_in_the_order_the_port_gave()
            throws Exception {
        Budget september = Budget.of(SEPTEMBER, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1000.00"))));
        Budget august = Budget.of(AUGUST, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("900.00"))));
        when(lister.all()).thenReturn(List.of(september, august));

        mvc.perform(get(BUDGETS))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(september.id().toString()))
                .andExpect(jsonPath("$[1].id").value(august.id().toString()));
    }

    /** {@code Budget} holds its limits in an unordered map; the response must sort them for a stable contract. */
    @Test
    void Given_a_budgets_limits_in_no_particular_order_When_listed_Then_they_are_sorted_by_category_in_the_response()
            throws Exception {
        Budget budget = Budget.of(
                SEPTEMBER,
                Currency.ILS,
                List.of(new CategoryLimit(TRANSPORT, ils("300.00")), new CategoryLimit(GROCERIES, ils("1000.00"))));
        when(lister.all()).thenReturn(List.of(budget));

        mvc.perform(get(BUDGETS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].limits.length()").value(2))
                .andExpect(jsonPath("$[0].limits[0].category").value("groceries"))
                .andExpect(jsonPath("$[0].limits[1].category").value("transport"));
    }

    /** No {@code Location}: there is no single-item read for one to name (ADR-028). */
    @Test
    void Given_a_valid_budget_When_defined_Then_it_is_answered_201_with_the_budget_and_no_location() throws Exception {
        Budget defined = Budget.of(SEPTEMBER, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1000.00"))));
        when(definer.define(any())).thenReturn(defined);

        mvc.perform(post(BUDGETS).contentType(MediaType.APPLICATION_JSON).content(defineRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(defined.id().toString()))
                .andExpect(jsonPath("$.currency").value("ILS"))
                .andExpect(jsonPath("$.period.start").value("2026-09-01"))
                .andExpect(jsonPath("$.period.end").value("2026-10-01"))
                .andExpect(jsonPath("$.total.amount").value("1000.00"));
    }

    /** JPY admits no minor units; its amounts must not gain a decimal point crossing the wire. */
    @Test
    void Given_a_budget_in_JPY_When_defined_Then_its_amounts_are_rendered_at_scale_zero() throws Exception {
        Budget yen =
                Budget.of(SEPTEMBER, Currency.JPY, List.of(new CategoryLimit(Category.of("electronics"), yen("5000"))));
        when(definer.define(any())).thenReturn(yen);

        mvc.perform(post(BUDGETS).contentType(MediaType.APPLICATION_JSON).content("""
                                {"period": {"start": "2026-09-01", "end": "2026-10-01"}, "currency": "JPY",
                                 "limits": [{"category": "electronics", "limit": {"amount": "5000", "currency": "JPY"}}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"limit\":{\"amount\":\"5000\",\"currency\":\"JPY\"}")))
                .andExpect(content().string(containsString("\"total\":{\"amount\":\"5000\",\"currency\":\"JPY\"}")));
    }

    @Test
    void Given_a_request_missing_the_periods_start_When_defining_a_budget_Then_it_is_a_400_problem_listing_the_field()
            throws Exception {
        mvc.perform(post(BUDGETS).contentType(MediaType.APPLICATION_JSON).content("""
                                {"period": {"end": "2026-10-01"}, "currency": "ILS",
                                 "limits": [{"category": "groceries", "limit": {"amount": "1000.00", "currency": "ILS"}}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("period.start"));

        verifyNoInteractions(definer);
    }

    /** A limit is decimal text on the wire (ADR-028), so a JSON number is malformed rather than coerced. */
    @Test
    void Given_a_limit_sent_as_a_json_number_When_defining_a_budget_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(BUDGETS).contentType(MediaType.APPLICATION_JSON).content("""
                                {"period": {"start": "2026-09-01", "end": "2026-10-01"}, "currency": "ILS",
                                 "limits": [{"category": "groceries", "limit": {"amount": 1000.00, "currency": "ILS"}}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(definer);
    }

    @Test
    void Given_a_budget_the_domain_refuses_When_defined_Then_it_is_a_422_rule_violated_problem() throws Exception {
        IllegalArgumentException twoLimitsForOneCategory =
                new IllegalArgumentException("Category groceries has more than one limit in this budget");
        when(definer.define(any())).thenThrow(twoLimitsForOneCategory);

        mvc.perform(post(BUDGETS).contentType(MediaType.APPLICATION_JSON).content(defineRequest()))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(twoLimitsForOneCategory.getMessage()))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
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
    void Given_a_body_that_is_not_json_When_defining_a_budget_Then_it_is_a_415_problem(String mediaType)
            throws Exception {
        mvc.perform(post(BUDGETS).contentType(mediaType).content("currency=ILS"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(definer);
    }

    @Test
    void Given_an_unknown_budget_When_reviewed_Then_it_is_a_404_budget_not_found_problem() throws Exception {
        BudgetId unknown = BudgetId.generate();
        BudgetNotFoundException missing = new BudgetNotFoundException(unknown);
        when(reviewer.review(unknown)).thenThrow(missing);

        mvc.perform(get(BUDGETS + "/" + unknown + "/review"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("BUDGET_NOT_FOUND"))
                .andExpect(requiredProblemMembers());
    }

    @Test
    void Given_a_malformed_budget_id_When_reviewed_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(get(BUDGETS + "/not-a-uuid/review"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(reviewer);
    }

    /** {@code remaining} may be negative, and {@code overspent} says so rather than making a client infer it. */
    @Test
    void Given_a_category_over_its_limit_When_reviewed_Then_it_is_answered_overspent_with_a_negative_remaining()
            throws Exception {
        BudgetId id = BudgetId.generate();
        CategorySpend coffee = new CategorySpend(COFFEE, ils("100.00"), ils("140.00"), ils("-40.00"));
        BudgetReview review =
                new BudgetReview(id, SEPTEMBER, Currency.ILS, List.of(coffee), Map.of(), Money.zero(Currency.ILS));
        when(reviewer.review(id)).thenReturn(review);

        mvc.perform(get(BUDGETS + "/" + id + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].category").value("coffee"))
                .andExpect(jsonPath("$.categories[0].overspent").value(true))
                .andExpect(
                        content().string(containsString("\"remaining\":{\"amount\":\"-40.00\",\"currency\":\"ILS\"}")));
    }

    /** An ordered map travels as an array, in the same order, because RFC 8259 leaves object order unspecified. */
    @Test
    void Given_spend_for_unbudgeted_categories_When_reviewed_Then_they_are_answered_as_an_array_in_order()
            throws Exception {
        BudgetId id = BudgetId.generate();
        Map<Category, Money> unbudgeted = new LinkedHashMap<>();
        unbudgeted.put(COFFEE, ils("18.00"));
        unbudgeted.put(TRANSPORT, ils("30.00"));
        BudgetReview review =
                new BudgetReview(id, SEPTEMBER, Currency.ILS, List.of(), unbudgeted, Money.zero(Currency.ILS));
        when(reviewer.review(id)).thenReturn(review);

        mvc.perform(get(BUDGETS + "/" + id + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unbudgeted").isArray())
                .andExpect(jsonPath("$.unbudgeted[0].category").value("coffee"))
                .andExpect(jsonPath("$.unbudgeted[0].spent.amount").value("18.00"))
                .andExpect(jsonPath("$.unbudgeted[1].category").value("transport"));
    }

    private static String defineRequest() {
        return """
                {"period": {"start": "2026-09-01", "end": "2026-10-01"}, "currency": "ILS",
                 "limits": [{"category": "groceries", "limit": {"amount": "1000.00", "currency": "ILS"}}]}
                """;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static Money yen(String amount) {
        return Money.of(amount, Currency.JPY);
    }
}

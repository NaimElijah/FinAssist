package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.WebSliceSecurity;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.AddCategorisationRuleUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListCategorisationRulesUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The categorisation-rule routes as a client sees them: status, content type, body and {@code code}
 * (ADR-028).
 *
 * <p>The same slice shape as {@code BudgetControllerTest}, over {@link CategorisationRuleController}.
 */
@Import(WebSliceSecurity.class)
@WebMvcTest(controllers = CategorisationRuleController.class)
class CategorisationRuleControllerTest {

    private static final String RULES = "/api/v1/categorisation-rules";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AddCategorisationRuleUseCase adder;

    @MockitoBean
    private ListCategorisationRulesUseCase lister;

    /** A review takes the first matching rule, so insertion order is the port's contract, not an accident. */
    @Test
    void Given_rules_added_out_of_alphabetical_order_When_listed_Then_they_are_answered_200_in_insertion_order()
            throws Exception {
        CategorisationRule superpharm = new CategorisationRule("superpharm", Category.of("groceries"));
        CategorisationRule cafe = new CategorisationRule("cafe", Category.of("coffee"));
        when(lister.rules()).thenReturn(List.of(superpharm, cafe));

        mvc.perform(get(RULES))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fragment").value("superpharm"))
                .andExpect(jsonPath("$[0].category").value("groceries"))
                .andExpect(jsonPath("$[1].fragment").value("cafe"));
    }

    /** No {@code Location}: a rule has no id at all (ADR-028). */
    @Test
    void Given_a_rule_with_mixed_case_When_added_Then_it_is_answered_201_lower_cased_and_with_no_location()
            throws Exception {
        mvc.perform(post(RULES).contentType(MediaType.APPLICATION_JSON).content("""
                                {"fragment": "SuperPharm", "category": "Groceries"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fragment").value("superpharm"))
                .andExpect(jsonPath("$.category").value("groceries"));

        verify(adder).add(new CategorisationRule("SuperPharm", Category.of("Groceries")));
    }

    @Test
    void Given_a_blank_fragment_When_adding_a_rule_Then_it_is_a_400_problem_listing_the_field() throws Exception {
        mvc.perform(post(RULES).contentType(MediaType.APPLICATION_JSON).content("""
                                {"fragment": " ", "category": "groceries"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("fragment"));

        verifyNoInteractions(adder);
    }

    /** A cross-site page can post a form without a preflight, so a write refuses anything but JSON (ADR-030). */
    @Test
    void Given_a_form_encoded_body_When_adding_a_rule_Then_it_is_a_415_problem() throws Exception {
        mvc.perform(post(RULES)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("fragment=cafe"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(adder);
    }
}

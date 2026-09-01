package io.github.naimelijah.finassist.ledger.budgeting.domain.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Categories are values, and rules match the shouty descriptions banks actually produce. */
class CategorisationRuleTest {

    private static final Category GROCERIES = Category.of("Groceries");

    @Test
    void Given_names_differing_only_in_case_or_padding_When_compared_Then_they_are_one_category() {
        assertThat(Category.of("  Groceries ")).isEqualTo(Category.of("groceries"));
    }

    @Test
    void Given_a_blank_name_When_a_category_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> Category.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Given_an_uppercase_bank_description_When_matched_Then_the_rule_still_fires() {
        CategorisationRule rule = new CategorisationRule("supermarket", GROCERIES);
        assertThat(rule.matches("SUPERMARKET LTD 4432 TEL AVIV")).isTrue();
    }

    @Test
    void Given_a_description_that_does_not_contain_the_fragment_When_matched_Then_it_does_not_fire() {
        CategorisationRule rule = new CategorisationRule("supermarket", GROCERIES);
        assertThat(rule.matches("PETROL STATION 19")).isFalse();
    }

    @Test
    void Given_a_blank_fragment_When_a_rule_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new CategorisationRule("  ", GROCERIES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment");
    }

    @Test
    void Given_a_category_When_rendered_Then_it_reads_as_its_normalised_name() {
        assertThat(Category.of("  Groceries  ").toString()).isEqualTo("groceries");
    }
}

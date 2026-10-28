package io.github.naimelijah.finassist.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The minor-units table, which nothing pinned before.
 *
 * <p>Mirrors {@code backend/assistant/tests/test_currency.py}. ADR-010 forbids a shared kernel
 * across languages, so this enum and its Python counterpart are kept honest by equivalent tests
 * rather than by a compiler. The Python side additionally has to prove its constants are distinct:
 * its values are {@code (code, minor_units)} pairs precisely because bare ints would make {@code
 * Currency.USD is Currency.ILS} true through Enum aliasing, and no mismatch could ever be detected.
 * Java's enum cannot alias, so that half of the mirror is structurally unnecessary here.
 *
 * <p>This pins the Java table only; it cannot see Python's. That the two agree is {@code
 * MoneyVectorsTest}'s job, through the {@code currencies} group both languages read, and that the
 * ledger's schema agrees is {@code SchemaBoundariesIT}'s.
 */
class CurrencyTest {

    @Test
    void Given_the_currency_table_When_read_Then_every_currency_declares_its_minor_units() {
        assertThat(Currency.ILS.minorUnits()).isEqualTo(2);
        assertThat(Currency.USD.minorUnits()).isEqualTo(2);
        assertThat(Currency.EUR.minorUnits()).isEqualTo(2);
        assertThat(Currency.GBP.minorUnits()).isEqualTo(2);
        assertThat(Currency.JPY.minorUnits()).isEqualTo(0);
    }

    /**
     * The table, pinned by name and order, so adding a currency is a deliberate change here as well as
     * in {@code contracts/money-vectors.v1.json}, {@code test_currency.py} and every currency {@code
     * CHECK} in the schema.
     */
    @Test
    void Given_the_currency_table_When_counted_Then_it_holds_exactly_the_five_supported_currencies() {
        assertThat(Currency.values()).hasSize(5);
        assertThat(Currency.values()).extracting(Enum::name).containsExactly("ILS", "USD", "EUR", "GBP", "JPY");
    }

    @Test
    void Given_a_zero_decimal_currency_When_compared_with_the_others_Then_it_is_the_only_one() {
        assertThat(Currency.values())
                .filteredOn(currency -> currency.minorUnits() == 0)
                .containsExactly(Currency.JPY);
    }
}

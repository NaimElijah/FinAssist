"""The currency table, and the aliasing trap its shape exists to avoid.

Mirrors ``backend/ledger/src/test/java/.../shared/CurrencyTest.java``. ADR-010 forbids a
shared kernel across languages and names equivalent tests on both sides as what keeps the two
honest; until now nothing tested ``Currency`` on either side, so that claim rested on a
docstring.

The aliasing case is the one this file exists for. ``Currency``'s value is a
``(code, minor_units)`` pair rather than the minor units alone, because ``Enum`` collapses
members that share a value into aliases. Java's enum cannot express that mistake, so it is a
hazard on this side only.
"""

from finassist_assistant.shared import Currency


def Given_the_currency_table_When_read_Then_every_currency_declares_its_minor_units() -> None:
    assert Currency.ILS.minor_units == 2
    assert Currency.USD.minor_units == 2
    assert Currency.EUR.minor_units == 2
    assert Currency.GBP.minor_units == 2
    assert Currency.JPY.minor_units == 0


def Given_the_currency_table_When_read_Then_every_currency_carries_its_iso_code() -> None:
    assert Currency.ILS.code == "ILS"
    assert Currency.JPY.code == "JPY"
    assert [currency.code for currency in Currency] == [currency.name for currency in Currency]


def Given_two_currencies_with_the_same_minor_units_When_compared_Then_they_are_distinct() -> None:
    # The regression test for the trap the docstring describes. With a bare int value, ILS, USD,
    # EUR and GBP would all become aliases of one member: `Currency.USD is Currency.ILS` would be
    # True, `len(Currency)` would be 2, and Money's mismatch guard - which compares members by
    # identity - could never fire between any two of them.
    # Written over the members rather than as `Currency.USD is not Currency.ILS`, which mypy
    # folds to a constant from the current source and rejects as a non-overlapping identity
    # check. Iterating also matches what aliasing actually does: an alias is not a member, so
    # it disappears from iteration entirely.
    by_minor_units: dict[int, list[str]] = {}
    for currency in Currency:
        by_minor_units.setdefault(currency.minor_units, []).append(currency.name)

    assert sorted(by_minor_units[2]) == ["EUR", "GBP", "ILS", "USD"]
    assert len({currency.name for currency in Currency}) == 5


def Given_the_currency_table_When_counted_Then_it_holds_what_the_java_table_holds() -> None:
    # A guard against a currency being added on one side of the language split and not the other.
    # If this fails, CurrencyTest.java needs the same entry before the count is changed here.
    assert len(Currency) == 5
    assert [currency.name for currency in Currency] == ["ILS", "USD", "EUR", "GBP", "JPY"]


def Given_a_zero_decimal_currency_When_compared_with_the_others_Then_it_is_the_only_one() -> None:
    assert [c.name for c in Currency if c.minor_units == 0] == ["JPY"]

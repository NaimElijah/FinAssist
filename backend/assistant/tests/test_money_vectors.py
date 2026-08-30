"""Money checked against the vectors the Java kernel is checked against.

ADR-010 keeps the two implementations honest through "equivalent tests on both sides". That
was mirrored prose until now, and it had already drifted - ``times_rounded`` raised on a large
product here where Java's ``timesRounded`` returned a value, and no test on either side could
have noticed. These read one shared file, so a behavioural difference fails a build instead of
waiting to be spotted. See ADR-018.

The file is data. It contains no behaviour, is imported by nothing at runtime, and holds string
forms rather than expressions, so it creates no shared kernel - only a shared specification.
"""

import json
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from finassist_assistant.shared import Currency, CurrencyMismatchError, Money

# Anchored on this file, never on the working directory or pytest's rootdir, both of which vary
# with how the suite is invoked.
VECTORS_PATH = Path(__file__).resolve().parents[3] / "contracts" / "money-vectors.v1.json"

# Language-neutral categories to this language's exception types. Java raises
# IllegalArgumentException where this raises ValueError; the file names neither.
ERRORS: dict[str, type[Exception]] = {
    "PRECISION_EXCEEDED": ValueError,
    "CURRENCY_MISMATCH": CurrencyMismatchError,
}


def _load() -> dict[str, Any]:
    assert VECTORS_PATH.is_file(), (
        f"shared Money vectors not found at {VECTORS_PATH}. Both language suites read this "
        "file; a missing one must fail loudly rather than silently check nothing."
    )
    data: dict[str, Any] = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))
    return data


VECTORS = _load()


def _cases(group: str) -> list[Any]:
    return list(VECTORS[group])


def _ids(group: str) -> list[str]:
    return [str(case["id"]) for case in VECTORS[group]]


# Every group this suite knows how to read. Pinned, so a group added to the file that neither
# language reads fails here rather than sitting in the file specifying nothing.
GROUPS = ("currencies", "construct", "binary", "scalar")


def Given_the_vector_file_When_loaded_Then_it_holds_vectors_in_every_group() -> None:
    # The anti-vacuity guard the architecture rules use, for the same reason: an empty or
    # truncated file would otherwise make every case below pass by checking nothing.
    assert VECTORS["version"] == 1
    assert len(VECTORS["currencies"]) == len(Currency)
    for group in GROUPS:
        assert _cases(group), f"vector group '{group}' is empty"


def Given_the_vector_file_When_read_Then_it_holds_no_group_this_suite_ignores() -> None:
    # The file guards against being empty or truncated; without this it does not guard against
    # growing a section nothing reads. `about` and `version` are prose and metadata, not vectors.
    groups = set(VECTORS) - {"about", "version"}

    assert groups == set(GROUPS), (
        f"the vector file holds groups this suite does not read: {sorted(groups - set(GROUPS))}. "
        "Both languages must read every group, or the file specifies something nothing checks."
    )


def Given_the_vector_file_When_read_Then_every_id_is_unique() -> None:
    ids = [case["id"] for group in ("construct", "binary", "scalar") for case in _cases(group)]

    assert len(ids) == len(set(ids)), "vector ids must be unique so a failure names one case"


@pytest.mark.parametrize("case", _cases("currencies"), ids=lambda c: str(c["code"]))
def Given_a_currency_vector_When_looked_up_Then_the_minor_units_agree(case: Any) -> None:
    assert Currency[case["code"]].minor_units == case["minorUnits"]


@pytest.mark.parametrize("case", _cases("construct"), ids=_ids("construct"))
def Given_a_construction_vector_When_applied_Then_it_matches(case: Any) -> None:
    currency = Currency[case["currency"]]

    if "error" in case:
        with pytest.raises(ERRORS[case["error"]]):
            Money(Decimal(case["amount"]), currency)
        return

    assert str(Money(Decimal(case["amount"]), currency)) == case["expect"]


@pytest.mark.parametrize("case", _cases("binary"), ids=_ids("binary"))
def Given_a_binary_vector_When_applied_Then_it_matches(case: Any) -> None:
    left = Money(Decimal(case["left"]), Currency[case["currency"]])
    right = Money(Decimal(case["right"]), Currency[case.get("rightCurrency", case["currency"])])
    operation = {"plus": Money.plus, "minus": Money.minus}[case["op"]]

    if "error" in case:
        with pytest.raises(ERRORS[case["error"]]):
            operation(left, right)
        return

    assert str(operation(left, right)) == case["expect"]


@pytest.mark.parametrize("case", _cases("scalar"), ids=_ids("scalar"))
def Given_a_scalar_vector_When_applied_Then_it_matches(case: Any) -> None:
    amount = Money(Decimal(case["amount"]), Currency[case["currency"]])
    multiplier = Decimal(case["multiplier"])
    operation = {"times": Money.times, "timesRounded": Money.times_rounded}[case["op"]]

    if "error" in case:
        with pytest.raises(ERRORS[case["error"]]):
            operation(amount, multiplier)
        return

    assert str(operation(amount, multiplier)) == case["expect"]

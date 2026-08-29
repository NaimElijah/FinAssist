"""In-language kernel every context may use: Money and Currency.

Deliberately NOT shared with the Java ledger. A shared kernel across two languages means two
implementations obliged to stay bit-identical, which costs more than the duplication (ADR-007).
What keeps them honest instead is that ``tests/test_money.py`` mirrors ``MoneyTest.java``
case for case.
"""

from finassist_assistant.shared.currency import Currency
from finassist_assistant.shared.money import CurrencyMismatchError, Money

__all__ = ["Currency", "CurrencyMismatchError", "Money"]

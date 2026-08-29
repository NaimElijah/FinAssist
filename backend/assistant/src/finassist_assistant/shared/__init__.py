"""In-language kernel every context may use: Money, Currency, Period, Decimal helpers.

Deliberately NOT shared with the Java ledger. A shared kernel across two languages means two
implementations obliged to stay bit-identical, which costs more than the duplication (ADR-007).
"""

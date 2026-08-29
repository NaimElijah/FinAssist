"""The analysis core: pure functions and immutable values, no I/O and no framework.

Deliberately not modelled with aggregates or repositories. Computing a return over a series
protects no invariant and has no lifecycle, so the tactical DDD patterns the ledger uses
would add ceremony here without buying anything.
"""

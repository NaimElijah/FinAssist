"""Immutable values the analysis operates on: Holding, Position, Series, Period.

These are the assistant's own vocabulary, not the ledger's. Ledger DTOs are translated into
these types by the anti-corruption layer in adapters/outbound/ledger and never reach this
package in their wire form.
"""

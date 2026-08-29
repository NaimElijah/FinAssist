"""LLM assistant, portfolio analysis and market data for FinAssist.

Organised by bounded context, one package each: portfolio, assistance, marketdata. A context
owns a complete hexagon and may not import another context - only `shared`. See
docs/ADRs/ADR-010-bounded-context-map.md, enforced by tests/test_architecture.py.

Unlike the ledger this service carries no aggregates: its cores are pure functions over
frozen dataclasses, because it protects no invariants (ADR-007).
"""

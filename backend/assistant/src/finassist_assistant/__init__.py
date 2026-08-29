"""LLM assistant and portfolio analysis for FinAssist.

This service is the downstream side of the context map: it consumes ledger data and owns
none of it. Unlike the ledger it carries no aggregates, because it protects no invariants —
its core is pure transformation. See docs/ADRs/ADR-007-asymmetric-hexagonal-architecture.md.
"""

"""Anti-corruption layer over the ledger's published language.

Ledger DTOs become portfolio.domain.model types HERE and nowhere else. A DTO reaching the
application or domain layer would make the ledger's wire shape this context's model and
couple two services across a language boundary. See ADR-004.
"""

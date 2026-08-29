"""Anti-corruption layer over the ledger's published language.

This is the single most important boundary in the system. The ledger's OpenAPI DTOs are
translated into finassist_assistant.domain.model types HERE and nowhere else. A ledger DTO
that reaches the application or domain layer has defeated the purpose of the split: the
ledger's wire shape would become this service's model, coupling two services across a
language boundary.

See docs/ADRs/ADR-004-http-openapi-service-contract.md.
"""

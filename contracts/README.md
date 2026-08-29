# Contracts

The wire contract between the two services, versioned and reviewed like code.

Nothing lives here yet. When the ledger gains its first endpoint, its OpenAPI document is
committed here as `ledger-v1.openapi.yaml`, and the assistant is generated from or checked
against it rather than hand-written against a remembered shape.

Rules, per [ADR-004](../docs/ADRs/ADR-004-http-openapi-service-contract.md) (currently
**Proposed** — ratify it before the first endpoint):

- The committed document is the contract. If the implementation and this directory
  disagree, that is a defect in the implementation.
- Versioned in the URL path (`/api/v1/...`). A breaking change is a new version served
  alongside the old, never an edit to a released one.
- CI must compare the running document against the committed one once an endpoint exists;
  until then, drift is silent.

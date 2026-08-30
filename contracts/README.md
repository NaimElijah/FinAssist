# Contracts

What the two services agree on, versioned and reviewed like code. Two kinds of artefact live
here, and only one of them exists so far.

**The wire contract.** When the ledger gains its first endpoint, its OpenAPI document is
committed here as `ledger-v1.openapi.yaml`, and the assistant is generated from or checked
against it rather than hand-written against a remembered shape. Nothing here yet.

**Value-semantics conformance vectors.** `money-vectors.v1.json` is the behaviour both `Money`
implementations must agree on, read by `MoneyTest`/`MoneyVectorsTest` in the ledger and
`test_money_vectors.py` in the assistant. ADR-010 forbids a shared kernel across languages and
asks that equivalent tests keep the two honest; this is what makes "equivalent" checkable
rather than a claim in a docstring. See
[ADR-018](../docs/ADRs/ADR-018-cross-language-money-vectors.md).

Rules, per [ADR-004](../docs/ADRs/ADR-004-http-openapi-service-contract.md) (currently
**Proposed** — ratify it before the first endpoint):

- The committed document is the contract. If the implementation and this directory
  disagree, that is a defect in the implementation.
- Versioned in the URL path (`/api/v1/...`). A breaking change is a new version served
  alongside the old, never an edit to a released one.
- CI must compare the running document against the committed one once an endpoint exists;
  until then, drift is silent.
- A vector file is **data**: values and language-neutral error categories, never expressions
  and never code. Both suites assert that every vector in the file was consumed, so adding
  one to the file without teaching both sides to read it fails the build.

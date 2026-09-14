# Contracts

What the two services agree on, versioned and reviewed like code. Two kinds of artefact live
here, and only one of them exists so far.

**The wire contracts.** When each service gains its first endpoint, its OpenAPI document is
committed here: `ledger-v1.openapi.json` for the ledger and `assistant-v1.openapi.json` for the
assistant ([ADR-028](../docs/ADRs/ADR-028-http-wire-conventions.md)). The web client's types are
generated from both, and the assistant is checked against the ledger's rather than hand-written
against a remembered shape. Nothing here yet.

**Value-semantics conformance vectors.** `money-vectors.v1.json` is the behaviour both `Money`
implementations must agree on, read by `MoneyTest`/`MoneyVectorsTest` in the ledger and
`test_money_vectors.py` in the assistant. ADR-010 forbids a shared kernel across languages and
asks that equivalent tests keep the two honest; this is what makes "equivalent" checkable
rather than a claim in a docstring. See
[ADR-018](../docs/ADRs/ADR-018-cross-language-money-vectors.md).

Rules, per [ADR-004](../docs/ADRs/ADR-004-http-openapi-service-contract.md) (**Accepted**) and
[ADR-028](../docs/ADRs/ADR-028-http-wire-conventions.md):

- The committed document is the contract. If the implementation and this directory
  disagree, that is a defect in the implementation.
- Versioned in the URL path (`/api/v1/...`). A breaking change is a new version served
  alongside the old, never an edit to a released one.
- Each service's suite compares the document its code generates with the committed one, as
  parsed trees rather than bytes. On a mismatch the test writes the generated document to a
  git-ignored path and fails, naming the file to copy. That test lands with the first endpoint;
  until then nothing is compared, and drift is silent.
- A vector file is **data**: values and language-neutral error categories, never expressions
  and never code. Both suites assert that every vector in the file was consumed, so adding
  one to the file without teaching both sides to read it fails the build.

# Contracts

What the services and the web client agree on, versioned and reviewed like code. Two kinds of
artefact live here.

**The wire contracts.** Each service's OpenAPI document is committed here:
`ledger-v1.openapi.json` for the ledger, `assistant-v1.openapi.json` for the assistant and
`gateway-v1.openapi.json` for the gateway
([ADR-028](../docs/ADRs/ADR-028-http-wire-conventions.md)). The web client's types are generated
from all three. The assistant's and the gateway's `Problem` schemas are each checked against the
ledger's document, as described below. The assistant has no ledger client yet, so that schema is
all of its document that is checked against the ledger's.

- `ledger-v1.openapi.json` exists. springdoc generates it from the ledger's code, and
  `LedgerContractIT` pins it. It now carries every accounting, budgeting and planning path
  alongside `info`, `servers` and the `Problem` schema that every operation's `default` response
  refers to.
- `assistant-v1.openapi.json` exists. FastAPI generates it from the assistant's routes, and
  `tests/test_contract.py` pins it. It carries the portfolio paths alongside `info`, `servers`
  and a `Problem` schema that the same suite holds identical to the ledger's, so one client parses
  both services' failures alike.
- `gateway-v1.openapi.json` exists. springdoc generates it from the gateway's code, and
  `GatewayContractIT` pins it. It carries the gateway's own `GET /bff/me` and `POST /bff/logout`
  alongside `info`, `servers` and a `Problem` schema that the same test holds identical to the
  ledger's. The gateway serves no `/v3/api-docs`: its security chain denies that path, so the test
  asks springdoc for the document directly
  ([ADR-030](../docs/ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md)).

**Value-semantics conformance vectors.** `money-vectors.v1.json` is the behaviour both `Money`
implementations must agree on, read by `MoneyVectorsTest` in the ledger and
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
  parsed trees rather than bytes, so key order and whitespace never fail it. On a mismatch the
  test writes the generated document to a git-ignored path and fails, naming the file to copy.
  The ledger's test is `LedgerContractIT`, the assistant's is `tests/test_contract.py`, and the
  gateway's is `GatewayContractIT`.
- A vector file is **data**: values and language-neutral error categories, never expressions
  and never code. Both suites assert that every vector in the file was consumed, so adding
  one to the file without teaching both sides to read it fails the build.

## Regenerating the ledger's document

Any change to an endpoint changes the generated document, and `LedgerContractIT` fails until the
committed one agrees. From `backend/ledger`, with Docker running, because the test boots the
ledger against PostgreSQL in Testcontainers:

```bash
./mvnw -B verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LedgerContractIT
```

On a mismatch the test writes the document the code produces to
`backend/ledger/target/contracts/ledger-v1.openapi.json`, with sorted keys, two-space indentation
and a trailing newline. If the change is intended, copy that file over the committed one and run
the test again:

```bash
cp target/contracts/ledger-v1.openapi.json ../../contracts/ledger-v1.openapi.json
```

Review the copied file's diff before committing it. That diff is the change to the contract.

## Regenerating the assistant's document

Any change to a route or a transport model changes the generated document, and
`tests/test_contract.py` fails until the committed one agrees. From `backend/assistant`; no
container is needed, because the test builds the app in-process:

```bash
uv run pytest tests/test_contract.py
```

On a mismatch the test writes the document the code produces to
`backend/assistant/.contract-drift/assistant-v1.openapi.json`, formatted as the ledger's is:
sorted keys, two-space indentation and a trailing newline. If the change is intended, copy that
file over the committed one and run the test again:

```bash
cp .contract-drift/assistant-v1.openapi.json ../../contracts/assistant-v1.openapi.json
```

Review the copied file's diff before committing it, as for the ledger's.

## Regenerating the gateway's document

Any change to one of the gateway's own endpoints or its response types changes the generated
document, and `GatewayContractIT` fails until the committed one agrees. From `backend/gateway`; no
container is needed:

```bash
./mvnw -B verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GatewayContractIT
```

On a mismatch the test writes the document the code produces to
`backend/gateway/target/contracts/gateway-v1.openapi.json`, formatted as the ledger's is. If the
change is intended, copy that file over the committed one and run the test again:

```bash
cp target/contracts/gateway-v1.openapi.json ../../contracts/gateway-v1.openapi.json
```

Review the copied file's diff before committing it, as for the ledger's.

## Regenerating the web client's types

The web client's types are generated from all three documents. After any of them changes, run
`make generate-web-api` (`cd frontend/web && npm run generate:api`) and commit the result with
the document.

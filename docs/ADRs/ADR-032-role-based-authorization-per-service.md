# ADR-032: Role-based authorization, owned by each service

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

[ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md) proves who is calling. The gateway
signs the browser in, and each service validates a bearer token. Neither step says what the caller
may do.

**The owner fixed three roles on 2026-09-16:**

| Role | Who holds it | What it may do |
|---|---|---|
| `owner` | The owner | Read and write |
| `viewer` | Anyone the owner adds | Read only |
| `assistant` | The assistant's service principal | Read the ledger |

**[ADR-004](ADR-004-http-openapi-service-contract.md) already says the assistant reads ledger data
and the ledger is the sole writer.** Nothing enforces that today. The requirements row for it says
that nothing holds the assistant to the API, and the assistant has no ledger client yet (#62).
The requirements also left open *"What authenticates the assistant to the ledger?"*.

**The codebase constrains where the rules can live.**

- **The ledger has no legal home for a separate security package.** The slice rule
  `contexts_do_not_depend_on_each_other` treats every top-level ledger package as a context, so a
  new `security` package would be one more context. It could not use the composition root either,
  where the problem responses are built, because nothing outside `composition` may depend on it
  ([ADR-022](ADR-022-ledger-composition-root.md)). The composition root is the one package allowed
  to see the framework and every context.
- **The assistant has an equivalent home.** Its composition root, `adapters/`, is the only package
  that may import FastAPI outside a context's own adapters. Its router is a factory that takes its
  collaborators, and its handlers are synchronous
  ([ADR-021](ADR-021-application-layer-shape.md)).
- **The assistant will call the ledger directly** (#62), without passing through the gateway.

**Keycloak's default token shape nests realm roles under `realm_access.roles`**, a structure
specific to Keycloak ([ADR-031](ADR-031-keycloak-as-the-identity-provider.md)).

**The assistant cannot verify a JWT with what it has installed.** Python's standard library has no
JOSE support; `hashlib` and `hmac` cannot verify an RSA signature. None of the assistant's current
dependencies verifies tokens.

## Decision

### Roles and permissions

**Roles arrive in a flat `roles` claim**: a JSON array of strings, in both the access token and the
ID token. The ID token carries it too, so the gateway can show the signed-in user's roles. A realm
mapper produces the claim (ADR-031), so no service parses Keycloak's nested shape.

**Each service maps roles to permissions in its own table, in its own composition root.**

- **Ledger:** `composition/LedgerPermissions`.
- **Assistant:** `adapters/auth.py`.

A rule is written against a permission, never against a role name.

| Role | Ledger permissions | Assistant permissions |
|---|---|---|
| `owner` | `ledger:read`, `ledger:write` | `portfolio:read` |
| `viewer` | `ledger:read` | `portfolio:read` |
| `assistant` | `ledger:read` | none, and its token's audience is not the assistant anyway |
| an unknown role, no `roles` claim, or a `roles` claim that is not a list | none | none |

**Pricing a disposal needs only `portfolio:read`.** It is a calculation that records nothing
([ADR-028](ADR-028-http-wire-conventions.md)).

**Audiences are separate for each service**: `finassist-ledger` and `finassist-assistant`. A service
refuses a token that does not name its own audience. The browser user's token names both (an
accepted cost of ADR-030). The assistant's service-account token names only the ledger.

### Deny by default

**Anything no rule admits is denied.**

**On the ledger, URL rules decide, in one place, and the first match wins:**

| Request | Requirement |
|---|---|
| `GET /api/v1/**` | `ledger:read` |
| `POST /api/v1/**` | `ledger:write` |
| `/actuator/{health/**, info, prometheus, metrics/**}`, `/v3/api-docs(.yaml)`, `/error` | public |
| anything else | denied, HEAD and OPTIONS included |

- **Where the code lives.** The rules and the role table live in `composition`. Controllers see
  only `java.security.Principal`, so no context imports Spring Security.
- **A test holds the endpoint list.** It compares every handler mapping with an explicit list of
  endpoints, so an endpoint added without being classified fails the build.

**On the assistant, a middleware and a per-route permission decide.**

- **The middleware** denies every request without a valid token, except `/health`, `/ready` and
  `/openapi.json`.
- **Each API route** declares exactly one permission dependency, which is passed into the router
  factory. The dependency reads only the request scope, and it stays synchronous.
- **On the disposal route**, the permission check runs before the media-type check. A caller with
  no permission gets 403, not 415.
- **A test fails the build** if any API route declares no permission, or more than one.

**A refused permission is 403 `FORBIDDEN`**, with `WWW-Authenticate: Bearer
error="insufficient_scope"` (ADR-030's table). A missing or bad token is 401, never 403.

### What the gateway and the web client decide

**The gateway authorises nothing beyond "has a session".** If it made permission decisions, it
would need each service's table. The assistant's call to the ledger bypasses the gateway anyway,
so the ledger must enforce the rules itself.

**The web client hides write controls from a session without `owner`.** That is a courtesy to the
user, not a control. The server refuses the write regardless.

### The assistant's principal

**The assistant authenticates to the ledger with the client credentials grant**, as the
confidential client `finassist-assistant`.

- Its service-account user holds the `assistant` role.
- Its token names only the audience `finassist-ledger`.
- The ledger grants that token `ledger:read` and nothing else, so a write is 403.

This enforces ADR-004's sole-writer rule in the ledger, rather than relying on the assistant to
behave. It also answers the requirements' open question. The work that follows this record builds
the Keycloak client and the ledger-side rule; the HTTP client that uses them is #62.

### Dependencies

**The ledger adds Spring Security's resource-server starter**,
`spring-boot-starter-security-oauth2-resource-server`.

- It is managed by the Spring Boot BOM and Apache-2.0 licensed.
- It brings Nimbus JOSE + JWT (Apache-2.0) for signature verification.
- No other JOSE library is added.

**The assistant adds PyJWT, pinned as `PyJWT[crypto]==2.14.0`.** The reasons for this choice:

- **What it replaces.** Nothing. The assistant verifies no token today.
- **Why the standard library is not enough.** It has no JOSE support and cannot verify an RS256
  signature.
- **Why not an existing dependency.** None verifies a JWT.
- **Why not `authlib`.** It is a full OAuth client and server framework. The assistant needs only
  to verify a token against a key set.
- **Licence and maintenance.**
  - PyJWT is MIT licensed and actively maintained, with three releases in 2026 so far.
  - 2.14.0 was its current release, published on 2026-09-11.
  - It is pinned exactly, as [ADR-003](ADR-003-python-fastapi-for-assistant-and-analysis.md)
    requires.
- **What it brings.**
  - The `crypto` extra brings `cryptography`, which RS256 needs. `cryptography` is new to the
    lockfile; it is licensed Apache-2.0 OR BSD-3-Clause and maintained by the Python Cryptographic
    Authority.
  - PyJWT's `PyJWKClient` fetches and caches the key set, so no JWKS client has to be written.
- **The architecture rule must name the imports explicitly.** The forbidden list in
  `tests/test_architecture.py` is derived from distribution names. PyJWT's import root is `jwt`, so
  `jwt` and `cryptography` are added to the explicit set, and the rule is proven to fail against a
  planted import.

## Consequences

**Makes easy**

- **Each service's rules read in one place.** The ledger's are one class of URL rules and one role
  table; the assistant's are one table and one dependency per route.
- **A new role is one row in each service's table.**
- **The assistant's read-only access is enforced by the ledger**, not by the assistant's good
  behaviour. ADR-004's sole-writer rule stops being an unchecked promise.
- **An unclassified endpoint fails the build** instead of shipping open.
- **No context imports a security type**, so the slice rules and the framework rules still hold.

**Makes hard**

- **Two tables in two languages.** They must agree on the role names, and nothing but tests and
  review ties them together.
- **Permission names are strings, shared by convention only.**
- **Every new ledger endpoint touches the inventory list** as well as its controller.
- **A role added in Keycloak does nothing** until a service's table lists it.
- **The web client shows roles as the ID token carried them at sign-in**, so a role change reaches
  it only at the next sign-in.
- **Two more packages for the assistant to keep current.** `cryptography` releases often, and
  `pip-audit` now scans both packages.

**Accepted cost**

- **Permissions are coarse.** `ledger:write` covers every write in all three ledger contexts, and a
  viewer can read everything. There is no per-account and no per-context permission, because there
  is one dataset and one owner.
- **The `assistant` role can read the whole ledger.** Its future use cases need most of it, and
  nothing finer exists to grant.

## Alternatives considered

**Scopes as permissions.** Rejected. A scope is what a client asks for and a user consents to; it
is not what the user holds.

- The rule would live in the realm's scope configuration, not in the service that enforces it.
- An owner and a viewer signed in through the same client would receive the same scopes unless
  Keycloak policy split them, which moves authorisation into the identity provider.

**`@PreAuthorize` on each controller method.** Rejected.

- The annotations would be scattered across three contexts, and a method without one is open
  unless something else denies it.
- Every controller would import Spring Security, which the contexts are kept free of.
- URL rules in `composition`, plus an inventory test, keep the whole policy in one class and fail
  the build for an unclassified endpoint.

**Authorising at the gateway.** Rejected, for three reasons:

- the gateway would need both services' permission tables, and would start to know ledger
  semantics;
- the assistant's call to the ledger does not pass through it;
- a service reached without the gateway, such as through its own published loopback port, would
  be open.

**Rules written against role names, with no permission table.** Rejected: role names would spread
through every rule, and adding a role would mean editing each rule rather than adding a row.

**Reading Keycloak's `realm_access.roles` directly.** No realm mapper would be needed. Rejected:
both services would parse a provider-specific shape, and a change of provider would touch both.

**Per-resource authorisation**, such as Keycloak Authorization Services or attribute-based rules.
Rejected: one owner and one dataset leave nothing to scope per resource, and the requirements rule
out multi-tenancy.

**`authlib` for the assistant.** Rejected, as above: a whole OAuth framework to verify one kind of
token.

## Revisit when

- **Someone needs access to only part of the data**, such as one account or one context. That also
  tests the non-goal on multi-tenancy.
- **A principal needs to write one ledger context but not another.** `ledger:write` would then
  split by context.
- **The assistant needs to write to the ledger.** ADR-004 changes first, and this table follows it.
- **A role change must take effect without a new sign-in.**
- **PyJWT stops being maintained, or the assistant needs more of JOSE than signature
  verification**, such as encrypted tokens. A broader library then earns its place.

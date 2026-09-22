# ADR-036: Deny each Keycloak client the realm's full scope

- **Status:** Accepted
- **Date:** 2026-09-22

## Context

**[ADR-031](ADR-031-keycloak-as-the-identity-provider.md) gave the realm three roles — `owner`,
`viewer` and `assistant` — and two clients, `finassist-gateway` and `finassist-assistant`, each
carrying a flat `roles` claim through the same `finassist-roles` protocol mapper
(`oidc-usermodel-realm-role-mapper`).** Neither client's `fullScopeAllowed` was ever set in the
realm file, so Keycloak left it at its own default, `true`.

**`fullScopeAllowed: true` puts every realm role, and every composite role, into a client's
scope, whatever that client's own tokens are meant to carry.** A run against the real image
showed exactly that: `finassist-gateway`'s realm-role scope held `offline_access`, `owner`,
`default-roles-finassist`, `assistant`, `viewer` and `uma_authorization`. Four of those —
`offline_access`, `default-roles-finassist`, `uma_authorization`, and `assistant`, the assistant
service principal's own role — had no business in a browser session's scope at all.

**Nothing was exploitable.** `owner` and `viewer` already decide what a token may do
([ADR-032](ADR-032-role-based-authorization-per-service.md)), and every consumer —
`LedgerPermissions`, the assistant's `require()` factory, the web client's `writeAccessFor` —
reads the roles it recognises and ignores the rest. An unrecognised role reaching a token grants
nothing anywhere in this codebase today. What was wrong was the claim's meaning, not an open
door: a token should say what its bearer may do here and nothing else, and the owner's own rules
ask for least privilege by default for every credential, role, bucket, queue and container — a
default the realm was missing only because nobody had turned it off.

**ADR-031 is append-only, so this finding could not be written into its body.** Its Status line
now records only that it was extended, and points here for what that means.

## Decision

**`fullScopeAllowed: false` on both `finassist-gateway` and `finassist-assistant`, plus a
realm-level `scopeMappings` naming exactly the roles each client's tokens need:**

- `owner` and `viewer` for `finassist-gateway` — the two roles a signed-in person can hold;
- `assistant` alone for `finassist-assistant` — its service account's own role.

**Enforced two ways, on the file and on a running server:**

- **`KeycloakRealmTest` reads the file.**
  `Given_both_clients_When_read_Then_neither_is_allowed_the_realms_full_scope` asserts
  `fullScopeAllowed` is `false` on both clients, and
  `Given_the_realm_file_When_read_Then_each_client_is_scoped_to_only_the_roles_it_needs` asserts
  `scopeMappings` holds exactly `owner` and `viewer` for the gateway and exactly `assistant` for
  the assistant client — closed sets, so a third role added to either fails the test that finds
  it rather than passing unnoticed.
- **`KeycloakRealmIT` proves it against a running Keycloak**, built from
  `ops/keycloak/Dockerfile` in Testcontainers.
  `Given_the_realm_as_imported_When_each_client_is_read_Then_neither_has_full_scope` reads each
  client's `fullScopeAllowed` back from Keycloak's admin API, and
  `Given_the_realm_as_imported_When_each_clients_scope_is_read_Then_it_holds_only_that_clients_own_roles`
  reads each client's composite realm-role scope from `/scope-mappings/realm/composite` and
  asserts the same closed sets. The file states an intent; this states the result.

**Keycloak 26.7.4's `evaluate-scopes/generated-access-token` admin endpoint answers `404` for
that path**, which would otherwise let a test mint and inspect a would-be token directly.
`KeycloakRealmIT` reads `/scope-mappings/realm/composite` instead — the closest evidence a
Testcontainers run can produce: it says what a client's scope contains, not what one issued token
carries. The gateway client also permits only the authorization-code flow, so nothing here can
mint a real browser user token to check either claim directly. A real sign-in producing
`roles: ["owner"]` in the browser's own token remains a by-hand check, exactly as
`KeycloakRealmTest` already documents for the `basic` scope.

## Consequences

**Makes easy**

- The `roles` claim on a token now says exactly what the owner's rules ask for: what its bearer
  may do here, and nothing else. Nothing unexpected can reach either service through a role
  reader that later stops ignoring what it does not recognise.
- Both tests fail loudly — at build time, and at a real import — the moment a client's scope
  drifts from what it should be, in either direction.

**Makes hard**

- **A role added to the realm reaches a client's token only if `scopeMappings` names it there
  too.** Forgetting that step is silent at the point of the mistake — the new role simply never
  appears in a token — and is caught only the next time `KeycloakRealmTest`'s or
  `KeycloakRealmIT`'s closed-set assertions run, not by anything at import time.
- **The change takes effect only on an empty `keycloak-db-data` volume, or through
  `kc.sh import --override true`** (ADR-031's own limit). An owner who already has a Keycloak
  volume running gets none of this until one of those happens, and recreating the volume costs
  the owner their identity, exactly as ADR-031 already accepts.

**Accepted cost**

- This is a claim-hygiene fix, not a vulnerability fix, and is described as one: the two roles a
  browser session needed were already correct, and what changes is that four roles nothing
  granted anything for no longer ride along beside them.

## Alternatives considered

**Leave `fullScopeAllowed` at Keycloak's default (`true`).** Rejected: nothing was exploitable
today, but a claim naming a role this product never defined invites a future consumer to trust
it, and the owner's rules ask for least privilege by default, not only once a use of the extra
scope is found. Leaving it as found would have been a considered choice to keep an insecure
default; turning it off costs one line per client and two tests.

**Give each client its own client-level roles, rather than sharing the realm's `owner` /
`viewer` / `assistant`.** This would make each client's scope exactly its own roles by
construction, with no `scopeMappings` to keep in step. Rejected: ADR-031 and ADR-032 already
built the authorization model on one flat `roles` claim, read identically by the ledger and the
assistant through the same `finassist-roles` realm-role mapper. A client role does not populate
that mapper without a protocol mapper of its own per client, so this would mean re-deriving
machinery ADR-031 already has working, to solve a problem — which client may acquire a role —
that `scopeMappings` already solves without touching how a role reaches the claim.

**Fold this into ADR-031's Decision.** Rejected: ADR-031 is Accepted and append-only, and this is
a decision in its own right — what belongs in a client's scope, not which identity provider to
run. It deserves a record a future maintainer can find by its own name, rather than by reading
past the Status line of a different one.

## Revisit when

- **A client is added, or an existing client is meant to acquire a role it does not have today.**
  The scope mapping must be extended by hand, in the same commit, or the new capability never
  reaches a token. `KeycloakRealmTest`'s and `KeycloakRealmIT`'s closed-set assertions failing on
  the client that was not updated is the intended signal, not a defect to work around.
- **Keycloak's admin API stops answering `404` for `evaluate-scopes/generated-access-token`**, or
  a later version resolves it. A real issued token could then be inspected directly instead of a
  client's computed scope, and the gateway's own signed-in `roles` claim could finally be asserted
  here rather than only by hand.

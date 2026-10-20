# ADR-034: The Compose authentication smoke check stays manual, out of CI

- **Status:** Accepted, extended by [ADR-040](ADR-040-the-smoke-check-trigger-fired.md)
- **Date:** 2026-09-22

## Context

**Each service's own suite proves its own logic, and none of them proves the wiring between
them.** `LedgerAuthorizationIT` proves the ledger's 401 and 403 answers, `GatewayLoginFlowIT`
the gateway's sign-in and relay, and `KeycloakRealmIT` the claims in a token from the real
Keycloak image. Each one boots its service alone, with its own test settings. None of them sees
what `docker-compose.yml` hands the running stack: the service names and ports, each issuer and
JWKS URI, each Host allow-list, and the public URLs. None of them sees whether Keycloak, when a
container reaches it over the backchannel, still puts the public issuer in a token
([ADR-031](ADR-031-keycloak-as-the-identity-provider.md)).

**`ops/smoke/auth_smoke.py` checks exactly that wiring.** It runs as the `auth-smoke` Compose
service, behind its own `smoke` profile, and reaches every service by its Compose DNS name. It
makes eight checks of what [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)
through [ADR-032](ADR-032-role-based-authorization-per-service.md) promise, and stops at the
first one that fails. It uses the standard library only, and `make smoke-auth` runs it.

**Today CI checks the script, but never runs it.**

- The `compose` job validates the service definition with `docker compose --profile smoke config
  --quiet`.
- The assistant job lints and type-checks the script with `make lint-smoke`.
- No CI job starts the Compose stack. `KeycloakRealmIT`, in the gateway job, is the only CI test
  that starts Keycloak, and it starts Keycloak alone, through Testcontainers.

**A real run costs minutes, not seconds.**

- Every image the stack builds is built first: the ledger, the gateway, the assistant and
  Keycloak.
- Keycloak then imports the realm into an empty `keycloak-db-data` volume before it reports
  ready. On the development machine, with the rest of the stack starting alongside it, this took
  about 100 seconds, and about 150 seconds on a slower run. `docker-compose.yml` allows 240
  seconds (`start_period`).
- Nobody has measured it on a CI runner.

**The first real run found a defect that no CI job would have caught.**

- **What it was.** `LedgerSecurityConfiguration` read `finassist.security.jwk-set-uri`, but
  `application.yml` defines only `finassist.security.jwks-uri`, bound to `LEDGER_OIDC_JWKS_URI`.
  The ledger could not start on its own configuration, natively or under Compose. It was
  committed in `26505db`.
- **Why nothing caught it.** The ledger's whole suite, the one CI runs, passed with the defect in
  place. Every `*IT` extends `PostgresFixture`, and its `@DynamicPropertySource` supplied the same
  wrong key that the code read. The binding in the real `application.yml` was never exercised.
- **The fix.** `6a530f8` corrected the key. It also added `ApplicationYmlPlaceholdersTest`, a
  unit test that needs no Docker and runs in CI's ledger job. It reads the ledger's bytecode,
  finds every `@Value` placeholder, and resolves each one against `application.yml` alone. That
  closes this class of defect for the ledger. It covers nothing in the gateway or the assistant.

**The same work exposed a second defect that only a real run shows.** Compose called Keycloak
healthy before Keycloak could issue a token. The healthcheck searched `/health/ready`'s body for
any `"status":"UP"`. A sub-check reports `UP` within seconds of boot, long before the realm
import has run. `e8d05d6` fixed the check to read the HTTP status line instead. No CI job runs a
Compose healthcheck, so nothing in CI guards this fix.

**Neither defect reached `dev`.** Both were found and fixed on the feature branch.

## Decision

**The smoke check stays opt-in and manual.**

- `make smoke-auth` runs it, after `make run` reports every service healthy.
- A bare `docker compose up` never starts it, because `auth-smoke` sits behind the `smoke`
  profile.
- No CI job runs it: not on a push, not on a schedule, not on a label.

**CI keeps checking the script without running it.** The `compose` job goes on validating the
service definition, and the assistant job goes on running `make lint-smoke`. A syntax error or a
broken Compose reference therefore still fails the build.

**A defect class the check finds gets a cheap CI guard where one exists.**
`ApplicationYmlPlaceholdersTest` is that guard for the JWKS defect. It runs on every push, needs
no container, and names the key that fails to resolve.

**Nothing requires a smoke run before a merge.** Running it is the owner's choice. This record
does not pretend otherwise.

## Consequences

**Makes easy**

- **CI stays as fast as it is.** No push waits for four image builds and a realm import.
- **The check stays trustworthy between runs.** A script that no longer parses, type-checks or
  lints fails CI, just as a broken Compose reference does.
- **The defect that justified the check is guarded in CI**, by a unit test that costs seconds,
  not by a stack that costs minutes.

**Makes hard**

- **A wiring defect reaches `dev` unless someone runs the check.** Compose DNS names, issuers,
  JWKS URIs, Host allow-lists and public URLs have no automated check in CI.
- **The Keycloak healthcheck has no guard at all.** `KeycloakRealmIT` waits on `/health/ready`
  with a wait strategy of its own, so it never exercises the one Compose uses.

**Accepted cost**

- **The check can drift in behaviour between runs.** Lint catches a broken script, but not a
  check whose expectation no longer matches the stack. Only a real run shows that.
- **The check cannot see a realm change that was never imported.** Keycloak imports the realm
  only into an empty `keycloak-db-data` volume. Check 7 compares the gateway's redirect with the
  configured public URLs, not with the redirect URIs Keycloak actually stored. A stale volume
  therefore passes.

## Alternatives considered

**Run it in CI on every push.** This would catch a wiring defect before it merged. Rejected
because of the cost on every push: four image builds, then a realm import that took 100 to 150
seconds on the development machine. That comes on top of the gateway job, which already builds
the Keycloak image once for `KeycloakRealmIT`. The one defect of this kind found so far is now
guarded by a unit test that costs seconds.

**Run it nightly, or on pull requests that carry a label.** Either is cheaper than every push.
Rejected for now:

- A nightly failure arrives detached from the change that caused it.
- A label works only if someone remembers to apply it, which is the same discipline the manual
  step already asks for.
- Either way, it is a CI job to keep green, and on one owner's project it would guard wiring that
  rarely changes.

**Fold the checks into each service's own suite.** Rejected: impossible by construction. What the
check exists to see is the wiring between the services, and no single-service suite boots the
others.

**Start it with every `docker compose up`.** Rejected. Every start would wait for it and fail with
it, and a long-running stack would carry one more container holding `ASSISTANT_CLIENT_SECRET`.

## Revisit when

- **A defect that only this check, or a real `docker compose up`, would have caught reaches
  `dev`.** Both defects found during this work were caught on the feature branch. One that gets
  past it shows that the manual step is not being run when it matters.
- **CI gains a job that already brings the Compose stack up for another reason.** The images and
  the realm import are then paid for already, and the check costs one more `compose run`.

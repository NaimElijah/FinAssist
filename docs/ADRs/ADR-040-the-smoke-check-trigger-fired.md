# ADR-040: ADR-034's first trigger fired. The smoke check stays manual, and CI starts the packaged gateway

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

**[ADR-034](ADR-034-keep-the-compose-smoke-check-manual.md) kept the Compose smoke check manual, and
named a trigger that would revisit that choice:** "A defect that only this check, or a real `docker
compose up`, would have caught reaches `dev`." It added that such a defect "shows that the manual
step is not being run when it matters."

**That trigger has fired** ([#233](https://github.com/NaimElijah/FinAssist/issues/233)).

- **The defect.** On 2026-09-23, the day after ADR-034, bfbb96c declared `snakeyaml` at test
  scope. By Maven's nearest-wins mediation that overrode the compile scope it had arrived with, so
  it left the gateway's repackaged jar. Boot then could not read `application.yml`, and the gateway
  could not start under Compose or natively.
- **How long it lasted.** It reached `dev` and `main`, and stayed there until 2026-10-13.
- **How it was found.** Starting the stack, in an isolated Compose project, while verifying
  [#142](https://github.com/NaimElijah/FinAssist/issues/142). `auth-smoke` would have stopped at the
  gateway's `service_healthy`.
- **Why CI never saw it.** Every gateway suite runs on Maven's test classpath, where `snakeyaml` was
  present.

**ADR-034 had already set the rule for this case:** "A defect class the check finds gets a cheap CI
guard where one exists." The first time, that guard was `ApplicationYmlPlaceholdersTest`. This
time the class is "the artefact a service ships cannot start", and a guard exists for the gateway
that costs seconds. The gateway needs nothing else running to start, because it builds its
Keycloak registration by hand and runs no discovery
([ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)).

**Running the check in CI still costs what it did.** Every push would pay for four image builds,
plus a realm import that takes 100 to 150 seconds on the development machine.

## Decision

**The smoke check stays manual**, as ADR-034 decided, and **that trigger is recorded as fired**
rather than left silent.

**CI now starts the packaged gateway on every push.** `GatewayPackagedJarIT` runs the repackaged
jar that `package` wrote with `java -jar`. That is the jar the image ships. It gives the jar only a
client secret and a free port, and requires `/actuator/health/readiness` to answer 200.

- **What it covers.** The whole class, whichever library or setting goes missing, not only the
  `snakeyaml` that went missing in #233.
- **How it was proved.** With the scope flipped back to `test`, it fails in about 2 seconds,
  quoting "snakeyaml was not found on the classpath". On the real pom it passes in about 13
  seconds.

**The ledger's and the assistant's shipped artefacts get the same check, as
[#234](https://github.com/NaimElijah/FinAssist/issues/234).** Neither is started by any CI job
today. The ledger's jar needs PostgreSQL to start, and the assistant's image installs without the
dev extras its tests run with.

## Consequences

**Makes easy**

- **The class that slipped through now fails CI, for the gateway**, in seconds and with the reason
  quoted.
- **CI stays as fast as it was**, apart from those seconds.

**Makes hard**

- **Wiring defects stay unguarded.** That covers Compose service names, issuers, JWKS URIs, Host
  allow-lists and public URLs, and the smoke check is still the only thing that sees them.
- **The ledger and the assistant stay unguarded until #234 lands.**

**Accepted cost**

- **The manual step was not run when it mattered once, and nothing here makes it run.** A smoke
  run before merge is still the owner's choice, as ADR-034 said. What changed is that the defect
  class it found no longer depends on that choice.

## Alternatives considered

- **Run the smoke check in CI on every push.** Rejected for now, for ADR-034's reasons: four image
  builds and a realm import per push, to guard wiring that rarely changes. The defect that fired
  the trigger was not wiring. It is now caught by a test that costs seconds.
- **Run it nightly, or on a label.** Rejected, for ADR-034's reasons: a nightly failure arrives
  detached from its cause, and a label needs the same discipline the manual step lacked.
- **Require a smoke run before every merge, as a written rule.** Rejected. This trigger fired
  because a manual step was skipped, and a rule that nothing enforces is the same step again.
- **Guard only `snakeyaml`, by listing the jar's libraries.** Rejected. That was this test's first
  version, and review found it guarded one library rather than the class.

## Revisit when

- **A second defect that only the smoke check, or a real `docker compose up`, would have caught
  reaches `dev`.** Then the manual step has failed twice, and the check moves into CI, on every
  push or on a schedule, whatever it costs.
- **CI gains a job that already brings the Compose stack up**, for #65's image builds or #188's
  post-deploy smoke run. The check then costs one more `compose run`, which is ADR-034's own second
  trigger.

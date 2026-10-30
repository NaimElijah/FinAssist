# ADR-052: `ops/` holds operator code, one directory per tool

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-008](ADR-008-backend-frontend-root-layout.md). Its revisit trigger fired three times
without a record. The answer to it is that the split stays and `ops/` is its third, named
category.

## Context

**ADR-008's trigger has fired, and no record says so**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). It reads: "Flatten back to the root if
a third deployable appears that is neither backend nor frontend — a CLI, a scheduled job, a mobile
client — because that is the point at which the tier split stops classifying and starts requiring
a judgement call for each new component." Three things appeared that are neither:

| Tool | Commit | What it is |
|---|---|---|
| `ops/smoke/auth_smoke.py` | `6f7a4d0`, 2026-09-22 | A Compose one-shot behind the `smoke` profile |
| `ops/backup/backup.py` | `e17ce99`, 2026-10-13 | An operator CLI, run on the host with `uv run` |
| `ops/secret_check/secret_check.py` | `7ca2d7a`, 2026-10-18 | A Compose one-shot that every `up` runs first |

A fourth, `ops/compose_check/compose_check.py`, arrived with
[ADR-049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md), as a check that
`make check-compose` and CI run over the resolved Compose file. It follows the layout below from
the start.

**ADR-030 weighed the trigger once, for two other things.** It decided the gateway is a backend
deployable and belongs in `backend/gateway`, and that Keycloak is not this repository's code, so
its image definition and realm sit under `ops/keycloak/`. It said the trigger "does not fire". It
did not weigh a script.

**Each tool follows the same conventions, and the conventions are written down only in
`ops/CLAUDE.md`.**

- **Layout.** One directory, `ops/<tool>/`, holding the script and, where there is logic that runs
  without Docker, its tests in `ops/<tool>/tests`. `ops/backup`, `ops/secret_check` and
  `ops/compose_check` have them.
  `ops/smoke` has none: the smoke check is itself a test, kept manual by
  [ADR-034](ADR-034-keep-the-compose-smoke-check-manual.md), and each service's own suite covers
  its logic alone.
- **Toolchain.** None has one of its own. They run under the assistant's pinned Python, ruff, mypy
  and pytest settings: `lint-smoke`, `lint-backup`, `lint-secret-check`, `lint-compose-check`,
  `test-backup`, `test-secret-check` and `test-compose-check`, with `make format` covering all
  four ([ADR-038](ADR-038-back-up-each-database-as-a-logical-dump.md)).
- **Standard library only**, with no dependency of their own.
- **They print their report.** Each one's report on stdout is its product, written by one `print`
  marked `# noqa: T201` with its reason. The operator CLIs are the one exception to the rule that
  nothing writes output except the logging boundary.
- **They are not images.** The two one-shots run in the stock `python:3.13.8-slim-trixie` image
  with the script bind-mounted. The backup CLI and the Compose check are not containerised at all.

**None of them is a service.** Nothing listens on a port, nothing is scaled, and no release ships
them. The word "deployable" in ADR-008's trigger was written for something that is.

## Decision

**The backend/frontend split stays.** The tier grouping still classifies every service FinAssist
deploys, and a tool is not one.

**Operator tools and Compose one-shots live under `ops/<tool>/`, and infrastructure
configuration sits in `ops/` beside them.**

- **One directory per tool.** The script, and its tests in `ops/<tool>/tests` where it has logic
  that runs without Docker.
- **No toolchain of its own.** A tool runs under the assistant's, and is linted, tested and
  formatted through targets named for it.
- **Infrastructure configuration sits beside them**: the Keycloak image and realm, the collector,
  Prometheus and Grafana provisioning. It is tested where its toolchain lives. The realm file is
  pinned by `KeycloakRealmTest` and `KeycloakRealmIT` in the gateway module.

**None of these is a deployable service.** A thing earns a directory under `backend/` or
`frontend/` by being one: a long-running process of this repository's, with its own CI job and
Compose service. A script or a one-shot does not.

## Consequences

**Makes easy**

- **A new operator tool has a home and a template.** The next one follows the layout without a
  decision.
- **ADR-008's trigger is answered, in the repository's own words.** A reader who finds three
  non-service components under `ops/` finds the reason here.
- **`backend/` and `frontend/` keep their meaning.** Neither collects scripts.

**Makes hard**

- **Tools borrow the assistant's toolchain.** A change to the assistant's ruff or mypy settings
  can break a tool's lint. `make lint` covers all of them, but it couples two things.
- **`ops/` is a catch-all.** It holds Dockerfiles, JSON, YAML, dashboards and Python. The rule
  above says what belongs there, and nothing enforces it.
- **The smoke check has no suite of its own.** That is by ADR-034, and it means this record's
  "tests where there is Docker-free logic" has a stated exception.

**Accepted cost**

- **"Not a deployable" is a judgement.** A one-shot that every `up` runs first, as `secret-check`
  does, is closer to a service than a script run by hand.

## Alternatives considered

- **Flatten the root, as ADR-008's trigger says.** Rejected: the trigger says the split stops
  classifying. It still classifies every service. Three scripts needed one new directory, `ops/`,
  not a new layout.
- **A top-level `tools/` or `scripts/` directory for the operator CLIs.** Rejected: `ops/` already
  exists, holds the Compose-adjacent configuration the same tools work against, and is named in
  the root `CLAUDE.md` as "infrastructure that is not application code".
- **Give each tool its own `pyproject.toml` and toolchain.** Rejected: three more lockfiles and
  three more lint configurations for scripts with no dependency. Borrowing the assistant's was the
  smoke check's choice ([#38](https://github.com/NaimElijah/FinAssist/issues/38)). ADR-038 followed
  it for the backup CLI, and the secret check followed both.
- **Put the operator CLIs under `backend/`.** Rejected: they are not services and are not part of
  the assistant, and `backend/` would stop meaning "what runs".

## Revisit when

- **A tool grows a dependency or a long-running mode.** It then wants a toolchain of its own, or
  it has become a service and moves under `backend/`.
- **A scheduled job runs in production**, such as a backup on a timer. That is a deployable in
  ADR-008's sense, and it needs a decision of its own.
- **A tool needs a second language.** It no longer fits the assistant's toolchain.

## What lands with this record

No code. Documentation only:

- **Status line and README.** ADR-008 reads `Accepted, extended by ADR-052`, with a row and a note
  in `docs/ADRs/README.md`.
- **`ops/CLAUDE.md`.** Its Layout section cites this record for the one-directory-per-tool rule.

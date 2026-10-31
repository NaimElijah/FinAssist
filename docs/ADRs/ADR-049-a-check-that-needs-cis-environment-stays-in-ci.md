# ADR-049: A check that needs CI's throwaway environment stays in CI, and every other check is a Make target

- **Status:** Accepted, extended by
  [ADR-061](ADR-061-the-stack-refuses-a-configuration-that-weakens-a-credential.md) and
  [ADR-063](ADR-063-every-container-holds-only-the-privilege-it-uses.md)
- **Date:** 2026-10-29

Extends [ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md). Its Decision says "CI invokes
the same targets a developer does, so the pipeline and the local commands cannot drift", and its
Consequences say "The pipeline is defined once, in the Makefile". That is true of almost every CI
step, and false of two. This record names the two kinds of exception, and makes only one of them
an exception.

## Context

**`.github/workflows/ci.yml` held logic that no Make target exposed, at `1d4f7ba`**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). Every other step calls a target such
as `make lint-ledger` or `make test-web`. Two jobs did not.

- **The web job's contract-types check.** It ran `npm run generate:api`, then
  fails if `git status --porcelain -- src/shared/api/contracts` lists anything, untracked files
  included. A developer has `make generate-web-api` and has to look at `git status` themselves.
- **The compose job**, in three parts:
  1. It built an `.env` from `.env.example` with eight `sed` lines, then ran
     `docker compose config --quiet` for the monitoring profile, the smoke profile and both
     together.
  2. It checked the resolved Compose file with about thirty lines of Python embedded in the YAML.
     Every long-running service must take the restart policy and log bound exactly as the `x-`
     blocks set them, a one-shot must take neither, and four healthchecks must probe readiness
     ([#142](https://github.com/NaimElijah/FinAssist/issues/142),
     [ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md)).
  3. It ran the published-secret refusal scenario
     ([ADR-044](ADR-044-compose-refuses-a-published-secret.md)): `docker compose run --rm
     secret-check` against published values, then `docker compose up -d db`, which must not start
     the database.

**The two kinds of step differ in what they need.**

- **The contract-types check and the Compose conventions check need no special environment.** They
  read files and run `config`, which starts no container. A developer machine can run them. The
  Python had no tests of its own, and CI was the only place it ran.
- **The refusal scenario starts containers on published values.** `docker-compose.yml` sets
  `name: finassist`, so on a developer machine the default Compose project is the owner's own. A
  failed refusal would start `db` inside the owner's own project. ADR-044 runs it in CI, against
  that job's scratch `.env`, and this record keeps it there for that reason.

**`make` is not installed on the development machine** (ADR-005). Any new target is also written
out as a raw command in the README's Development table, which the root `CLAUDE.md` keeps in step.

**The operator tools already have a layout.** `ops/backup` and `ops/secret_check` each hold one
standard-library script and its tests under `ops/<tool>/tests`. They are linted and tested under
the assistant's ruff, mypy and pytest settings through `lint-<tool>` and `test-<tool>` targets
([ADR-038](ADR-038-back-up-each-database-as-a-logical-dump.md), ADR-044).

## Decision

**A check that needs CI's throwaway environment stays in CI. Every other check is a Make target
that CI calls.**

**Two new targets.**

- **`make check-web-api`.** It runs `npm run generate:api` in `frontend/web`, then fails and lists
  the files if anything under `src/shared/api/contracts` differs from what is committed, untracked
  files included. CI's web job calls it in place of its inline step.
- **`make check-compose`.** It runs the three `config --quiet` validations and the conventions
  check. CI's compose job calls it in place of its first two parts.

**The Python moves out of the YAML.** It becomes `ops/compose_check/compose_check.py`, in the
layout above.

- **Standard library only, and it never runs Docker.** `problems` is a function over the resolved
  configuration as JSON, so its tests feed it crafted files and need no Docker. `make
  check-compose` runs `docker compose ... config --format json` and pipes the result into the
  script's standard input.
- **Tests, lint and format.** `ops/compose_check/tests`, with `test-compose-check` and
  `lint-compose-check` targets under the assistant's settings, added to `make test` and
  `make lint`, and the script to `make format`, as the other `ops/` tools are.
- **It never reads `.env`.** Every Compose call passes `--env-file .env.example` alone, and with an
  explicit `--env-file` Compose ignores the project's `.env` and `COMPOSE_ENV_FILES`. The throwaway
  values come from a committed file, `ops/compose_check/placeholders.env`, which gives a value to
  each variable the Compose file requires with `${VAR:?}`. A test in the check's suite keeps that
  file's names equal to the Compose file's, and each value a plain word, because a shell sources
  the file.
- **A secret exported in a developer's shell cannot reach it either.** Compose ranks a variable
  exported in the shell above every env file, and the README has the owner export
  `LEDGER_DB_PASSWORD` and `GATEWAY_CLIENT_SECRET` for a native run, so passing the placeholders as
  a second `--env-file` would not hold. Each Compose call runs in a shell of its own that first
  sources them, `set -a && . ./ops/compose_check/placeholders.env && set +a`, so every `:?`
  variable resolves to its placeholder whatever the caller exported. Any other variable the caller
  exported, such as a port, still outranks `.env.example`.
- **The script runs on the assistant's pinned Python.** Its PEP 723 `requires-python` is a minimum
  and the repository root has no `.python-version`, so the recipe passes
  `uv run --python "$(cat backend/assistant/.python-version)"`, locally and in CI, to match what
  `lint-compose-check` type-checks for.
- **It refuses an Actuator override in Compose.** The exposure pins read the test JVM's
  environment, never Compose's, so a `MANAGEMENT_ENDPOINTS_WEB_*` key, `SPRING_APPLICATION_JSON`,
  a `SPRING_CONFIG_*` key such as `SPRING_CONFIG_IMPORT`, or a `-D` in `JAVA_TOOL_OPTIONS`,
  `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS` in the ledger's or the gateway's `environment:`, or a
  `command:` or `entrypoint:` whose arguments reach `SpringApplication.run`, would pass every test
  ([ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md), item 5). The check
  fails such a key, under any spelling Spring's relaxed binding reads, naming it and never its
  value.
- **The report never quotes a value.** The resolved file carries every secret Compose
  interpolated, so the report names a service, an `x-` block, a probe path or an environment key
  and nothing else. Input nested too deeply to parse exits 2 with one line. The script is also
  tested as a process, pinning exit codes 0, 1 and 2 with no secret in either stream, because the
  `sys.exit` line is the one CI depends on.

**Only steps that need a disposable runner stay inline.** Two do:

- **The published-secret refusal scenario.** It starts containers on published values under the
  default project name, `finassist`, which on a developer machine is the owner's own.
- **Seeding `.env` from `.env.example` with CI-only values.** It starts nothing. It stays inline
  only because the refusal step needs that `.env`, and a developer machine must never get one.

Each carries a comment in `ci.yml` saying why it is inline.

## Consequences

**Makes easy**

- **A developer reproduces both checks with one command each.** A contract-type drift or a Compose
  convention failure no longer shows up first on a pushed branch.
- **The conventions check gets tests.** Today it is thirty lines of Python in YAML that only a CI
  run can exercise.
- **ADR-005's claim is true again, with its exceptions named.** A new check is a target unless it
  needs the disposable runner, and the comment says so.

**Makes hard**

- **`check-web-api` rewrites files in the working tree.** It regenerates in place, because
  `openapi-ts.config.ts` fixes the output paths. The other check targets never rewrite a file
  (`make lint`'s help text says so). In a dirty tree it reports uncommitted regenerated types as
  drift, which is also what CI would say once they are committed.
- **One more tool to lint, format and test**, with two more `ci.yml` steps in the assistant job on
  the pattern of `lint-secret-check` and `test-secret-check`, and a `setup-uv` step in the compose
  job.
- **The README's Development table, and the root `CLAUDE.md`'s Commands, gain two raw commands**
  for a machine without `make`.
- **A sourced file is shell.** `placeholders.env` is read by `/bin/sh`, so each value stays a plain
  word, which a test in the check's suite holds.
- **The exception list has to be policed.** Nothing but review stops a new check going inline
  because it was quicker.

**Accepted cost**

- **The refusal scenario still cannot run on a developer machine safely.** CI is its only runner,
  as ADR-044 already said. `make test-secret-check` covers the script, not the Compose wiring.

## Alternatives considered

- **Record all three as CI-only exceptions, with no code change.** Rejected: only the refusal
  scenario needs the disposable runner. Leaving the other two inline leaves thirty lines of
  untested Python that no developer can run, which is the drift ADR-005 was written to prevent.
- **Defer to [#342](https://github.com/NaimElijah/FinAssist/issues/342)**, the
  infrastructure-reviewer scan over containers, Compose and CI. Not unreasonable. Rejected because
  the scan needs a rule to review against, and ADR-005 now states one that CI breaks.
- **Make the refusal scenario a target too, in an isolated Compose project**, as `make
  restore-check` does with `-p finassist-restore-check`. Rejected for now: `db` publishes
  `127.0.0.1:${POSTGRES_PORT:-5432}`, so a failed refusal on a developer machine would collide with
  the owner's database unless the ports are moved as well, and the scenario already runs on every
  push.
- **Leave the checks in YAML and only comment them.** Rejected: a comment does not make a step
  runnable or testable.

## Revisit when

- **A third check needs the disposable runner.** Give the runner-only steps a script of their own
  rather than more inline YAML.
- **CI starts building the images or the stack** ([#65](https://github.com/NaimElijah/FinAssist/issues/65),
  [#188](https://github.com/NaimElijah/FinAssist/issues/188)). The compose job then has more steps,
  and each is sorted by this record's rule again.
- **The refusal scenario needs debugging on a developer machine.** That is the case for an
  isolated-project target.

## What lands with this record

- **Status line and README.** ADR-005's Status line and README row name this record as an
  extension, and a note in `docs/ADRs/README.md` records the change.
- **`Makefile`.** `check-web-api`, `check-compose`, `lint-compose-check` and `test-compose-check`.
- **`ops/compose_check/`.** `compose_check.py`, `placeholders.env` and `tests/`. The placeholders
  file is the only source of the check's throwaway values.
- **`.github/workflows/ci.yml`.** The web job calls `make check-web-api`, the compose job calls
  `make check-compose`, and the assistant job lints and tests the new tool. The two inline compose
  steps carry a comment saying why they stay.
- **Documentation.** `ops/CLAUDE.md`, the root `.claude/CLAUDE.md` and the README's Development
  table name the two targets and their raw commands.

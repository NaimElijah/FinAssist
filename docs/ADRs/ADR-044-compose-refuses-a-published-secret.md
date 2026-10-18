# ADR-044: Compose refuses to start on a secret that a file in the repository holds

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

**The repository publishes throwaway secrets on purpose, and nothing stopped a real `.env` from
reusing one** ([#302](https://github.com/NaimElijah/FinAssist/issues/302)).

- CI's compose job writes `ci-only` into every secret of a scratch `.env`.
- The ledger's and the gateway's integration tests give their containers values of their own,
  such as `it-db-password` and `a-test-client-secret`.

The repository has been public since 2026-10-17, so anyone can read every one of them. A `.env`
whose `POSTGRES_PASSWORD` is one of them started the stack, and it ran normally.

**Where a check runs decides whether it can work.**

- PostgreSQL applies `POSTGRES_PASSWORD` only when it initialises an empty volume.
- Keycloak copies its admin's password and its clients' secrets into its database only when it
  first starts.

So any check that runs after either has started comes too late for that volume: the published
value is already the stored one. The check has to finish before `db` and `keycloak-db` first
start.

**`make` is not installed on the development machine**
([ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md)). A check reachable only through
`make run` would not run where the stack is actually started.

**ADR-043 left generic secrets uncovered**
([ADR-043](ADR-043-github-secret-scanning-and-dependabot-alerts.md)). GitHub's secret scanning
matches providers' token formats, and a password or a client secret matches none of them.

## Decision

**A one-shot Compose service, `secret-check`, runs before any database starts. It refuses a
secret that a file in the clone holds.**

- **Wiring.** `db`, `keycloak-db` and `grafana` depend on it with
  `condition: service_completed_successfully`, and every other service depends on one of those.
  It has no profile. So each of these runs it first, every time:
  - a plain `docker compose up`;
  - `up -d --wait db keycloak-db`;
  - `docker compose run --rm keycloak …`;
  - `make run-identity`;
  - the restore check's own `up`.
- **What it compares.**
  - It is handed the eight secrets `docker-compose.yml` requires through `${VAR:?}`, as
    `${VAR:-}`, and reads the clone, bind-mounted read-only at `/repo`.
  - It searches every file git would not ignore for each value as a whole token. "Would not
    ignore" follows the `.gitignore` files and `.git/info/exclude`.
  - `.env`, the backups and the build output are never opened. An untracked file is opened,
    because the next `git add .` would publish it.
- **What it reports.**
  - It names each variable whose value it found, and never the value or the file: in a public
    repository, naming the file gives the value away.
  - Exit 1 is a refusal, and exit 2 means it could not search the clone. Either one keeps the
    databases from starting.
  - An error it did not foresee is reported by its type alone, since its message could quote what
    was being read.
- **How it runs.** `ops/secret_check/secret_check.py` is standard library only. It runs:
  - in the `python:3.13.8-slim-trixie` image that `auth-smoke` already pins;
  - as `nobody`;
  - with `network_mode: none`.
- **What proves it.**
  - `ops/secret_check/tests` drives it through its command line against clones on disk.
  - The tests compare its walk with `git ls-files`, on a synthetic repository and on the checkout.
  - The tests pin the variables it checks to the ones the Compose file requires and hands it.
  - CI's compose job also runs the real service against that job's own `ci-only` `.env`. All eight
    variables must be refused, `ci-only` never printed, and `db` never started. Fresh values must
    then pass.

## Consequences

**Makes easy:**

- A published secret can no longer reach a new volume through Compose.
- Every route into the stack runs the check, so nobody has to remember it.
- A refusal says what to do next: write a new value into `.env` and rotate it in place
  ([Rotating a secret](../operations/operations.md#rotating-a-secret),
  [#140](https://github.com/NaimElijah/FinAssist/issues/140)).

**Makes hard, or costs:**

- **Every `up` waits for it.** On the owner's machine on 2026-10-18, it took 3.8 s from start to
  exit, over 691 files, reading on eight threads. Over Docker Desktop's bind mount, reading the
  same files one at a time took 5.7 s for the reads alone.
- **A stack whose secret is published stops starting.** If its databases are already running,
  they are left running, since their volumes were initialised long ago. But nothing else that
  waits on the check starts until each value is rotated. That is the point, and it lands on the
  owner's own `.env` (#302).
- **It promises more than "committed".** An untracked file that holds a secret counts, and so
  does a file that only a global git ignore excludes, which the container cannot see. Either
  case refuses a secret that is not yet published. It does so loudly, never silently.
- **Bypasses remain:**
  - `--no-deps` skips the check. The isolated trial for #302 used exactly that, to seed volumes
    with published values.
  - After Docker itself restarts, it restarts the containers without `depends_on`
    ([ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md)), so the check does
    not run again. It passed when those containers were first started.
  - A native run, such as `./mvnw spring-boot:run`, reads no `.env` and is not covered.
  - History is not searched. A value committed once and removed since is not found.
- **A second one-shot.** ADR-039's restart and log rules exempt it, as they exempt `auth-smoke`,
  and CI's compose job names both.
- **Running it in CI does not erode ADR-034.**
  [ADR-034](ADR-034-keep-the-compose-smoke-check-manual.md) keeps the smoke check out of CI
  because it needs the whole stack, which takes minutes. This step starts one container and no
  database, and takes seconds.

## Alternatives considered

- **A check in the ledger's start-up.** Rejected for three reasons:
  - it runs after `db` has initialised its volume;
  - it sees only `LEDGER_DB_PASSWORD`;
  - its image's build context, `backend/ledger`, cannot see `ci.yml` or the gateway's tests.
- **A host CLI in `ops/`, called by `make setup` and `make run`.** Rejected: `make` is not
  installed on the development machine, so the check would run only when someone remembered it.
- **A committed denylist of the published values, with a test keeping it complete.** Rejected for
  two reasons:
  - completeness could only be tested by heuristics over every place a fixture can hide;
  - a denylist cannot catch the owner's real secret once it has been committed.
- **`git grep` in a container that carries git.** Rejected: one more image to pin and maintain,
  for what a walk over the `.gitignore` rules does in the standard library.
- **Reading `.git/index` for the exact tracked set.** Rejected for two reasons:
  - in a worktree, `.git` is a file that points outside the bind mount;
  - the index format has versions and extensions that a parser would have to follow.

## Revisit when

- **FinAssist deploys somewhere other than Compose**
  ([#61](https://github.com/NaimElijah/FinAssist/issues/61)). Secrets then come from a secret
  store ([#177](https://github.com/NaimElijah/FinAssist/issues/177)), and the check belongs where
  that store is filled.
- **The check takes more than 10 s on the owner's machine.**
- **A false positive is reported from a file that only a global git ignore excludes.**
- **A secret is added to `docker-compose.yml`.** It takes `:?` where a service uses it and `:-` in
  `secret-check`. The drift test fails until both are done.

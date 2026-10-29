# CLAUDE.md — ops

Infrastructure that is not this repository's application code. The root `.claude/CLAUDE.md` binds
here too, including its rules for `docker-compose.yml`; this file adds what binds `ops/` alone.

## Layout

One directory per tool, none of them a deployable service of this repository's (ADR-052).

- `ops/keycloak/` — `Dockerfile`, which builds the optimized Keycloak image that
  `docker-compose.yml` runs, and `finassist-realm.json`, the `finassist` realm imported at
  start-up.
- `ops/backup/backup.py` — a standard-library operator CLI, run on the host with `uv run`; its PEP
  723 block declares Python 3.13 and no dependency. It backs up and restores each database on its
  own, and proves a backup restores (ADR-038). Its tests are in `ops/backup/tests/`.
- `ops/secret_check/secret_check.py` — a standard-library check, one file with no dependency,
  that refuses a secret that any file git does not ignore holds (#302). It names a variable,
  never a value or a file. It runs as the one-shot `secret-check` service that both databases
  and Grafana wait on (ADR-044). Its tests are in `ops/secret_check/tests/`.
- `ops/compose_check/compose_check.py` — a standard-library check, run on the host with
  `uv run`, of the resolved Compose file it reads on stdin: each long-running service's restart
  and log bound, the one-shots' lack of either, four readiness probes (#142, ADR-039), and no
  `MANAGEMENT_ENDPOINTS_WEB_*`, `SPRING_CONFIG_*` or `SPRING_APPLICATION_JSON` key, in any
  spelling, nor `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS`, in the ledger's or the
  gateway's environment, and no `command` or `entrypoint` on either, each of which would reopen
  the Actuator exposure their code pins and their tests cannot see. It names a service, a key or
  a member, never a value. `placeholders.env` beside it holds a throwaway value for each `:?`
  variable, which `make check-compose` exports, so the file resolves without `.env` or the
  shell's secrets (ADR-049). Its tests are in `ops/compose_check/tests/`.
- `ops/smoke/auth_smoke.py` — the only file behind the `smoke` Compose profile (ADR-034).
- The collector, Prometheus and Grafana provisioning that the `monitoring` Compose profile mounts.

## Commands

From the repository root unless stated:

- **Run identity only** — `make run-identity`: `docker compose up -d keycloak-db keycloak`, for
  signing in or testing against Keycloak without the rest of the stack.
- **Back up** — `make backup`: `uv run ops/backup/backup.py backup`, with `db` and `keycloak-db`
  already running (it never starts a container). It writes a custom-format `pg_dump` of each, read
  back in full, plus a manifest of SHA-256s and a digest of the books' rows (never an amount),
  which a restore must reproduce, into the git-ignored `backups/<UTC time>/` or under `BACKUP_DIR`
  (ADR-038). A dump holds the books, password hashes and the owner's TOTP seed.
- **Restore** — `make restore BACKUP=<folder> [DATABASE=ledger|keycloak]`:
  `uv run ops/backup/backup.py restore <folder> [--database …]`. Restores only into a database
  that holds no table, with `ledger` and `keycloak` stopped, each in one transaction.
  `docs/operations/operations.md`'s "Backing up and restoring" gives the recovery, realm-undo and
  major-upgrade recipes.
- **Prove a backup restores** — `make restore-check BACKUP=<folder>`:
  `uv run ops/backup/backup.py check <folder>`. Only ever as the Compose project
  `finassist-restore-check`, on ports 15432/18080/18081, so it runs beside the owner's stack; it
  builds the ledger and Keycloak images under that name on its first run. Manual, never in CI
  (ADR-038).
- **Test the backup CLI** — `make test-backup`: `cd backend/assistant && uv run pytest -c
  pyproject.toml ../../ops/backup/tests`. Its Docker-free logic only; `make restore-check` proves
  the rest.
- **Test the secret check** — `make test-secret-check`: `cd backend/assistant && uv run pytest
  -c pyproject.toml ../../ops/secret_check/tests`. Real `git`, never Docker.
- **Check the Compose file** — `make check-compose`, which CI's `compose` job runs. Each of its
  four Compose calls runs in a shell of its own, as `sh -c 'set -a && .
  ./ops/compose_check/placeholders.env && set +a && docker compose --env-file .env.example
  <arguments>'`, so the exports never reach the caller's shell. The arguments are `--profile
  monitoring config --quiet`, the same with `--profile smoke` and with both, then `--profile
  monitoring --profile smoke config --format json`, piped into `uv run --python
  "$(cat backend/assistant/.python-version)" ops/compose_check/compose_check.py`, so the script
  runs on the assistant's pinned Python, not the newest its `requires-python` minimum allows.
  `config` starts no container, and `.env` is never read. Compose ranks the shell above any
  `--env-file`, so the placeholders are exported, not passed as one: each `:?` variable resolves
  to its placeholder even when the caller exported a real value, as the native run does. Any
  other variable the caller exported, a port, a username or a flag, still outranks `.env.example`.
- **Test the Compose check** — `make test-compose-check`: `cd backend/assistant && uv run
  pytest -c pyproject.toml ../../ops/compose_check/tests`. Crafted resolved files, never Docker.
- **Smoke-test authentication** (opt-in, manual) — `make smoke-auth`:
  `docker compose --profile smoke run --rm auth-smoke`, after `make run` reports every service
  healthy. Run cold, `compose run` starts the stack itself, but from existing images, without
  building.
- **Lint** — from `backend/assistant`, `uv run ruff check`, `uv run ruff format --check` and
  `uv run mypy --strict` over `../../ops/smoke` (`make lint-smoke`), `../../ops/backup`
  (`make lint-backup`), `../../ops/secret_check` (`make lint-secret-check`) and
  `../../ops/compose_check` (`make lint-compose-check`), each its own CI step in the assistant
  job. `make format` formats all four alongside the assistant (`backend/assistant/CLAUDE.md`).

## Rules

- **The operator CLIs print their report.** `ops/smoke/auth_smoke.py`, `ops/backup/backup.py`,
  `ops/secret_check/secret_check.py` and `ops/compose_check/compose_check.py` are not services:
  each one's report on stdout is its product, written by one `print` marked `# noqa: T201` with
  its reason.
- **The Compose check's report never quotes a resolved value.** The file it reads carries every
  secret Compose interpolated; it names a service, an `x-` block, a probe path or an environment
  key, and an input it cannot read by the error's type alone, or as nested too deeply.
  `ops/compose_check/tests` pins this, in-process and with the script run as its own process.
- **The secret check's report names a variable, never its value, and never the file holding it.**
  In a public repository, naming the file gives the value away. An error it did not foresee is
  reported by its type alone, since its message could quote what was being read.
- **`backup.py`'s report never carries an amount, a name, an id or a secret.** Every PostgreSQL
  message it repeats goes through `_withheld`, which withholds quoted and parenthesised spans,
  UUIDs and bare numbers, and drops continuation lines. `ops/backup/tests` pins this.
- **A restore only ever writes into an empty database.** `backup.py` refuses to restore into a
  database holding a table, or while the ledger or Keycloak is running, since under
  `unless-stopped` either would migrate or import into the empty database first. `restore` targets
  the owner's project only in a real recovery.
- **The restore check only ever runs as `finassist-restore-check`.** `check` passes
  `-p finassist-restore-check` on every Compose call and removes only volumes carrying that
  project's label and name prefix, so a verification run never touches the owner's `finassist`
  project. Never `docker compose down -v` (ADR-038).
- **The smoke check stays manual** (ADR-034). It is standard library only, runs as an unprivileged
  user inside the Compose network, and is never started by `docker compose up`. Each service's own
  suite already tests its logic alone; this checks the wiring Compose gives them together: the
  issuer Keycloak puts in a token fetched over the backchannel, the ledger's read and write rules
  for the assistant's token, the assistant's refusal of a token with the ledger's audience (there
  is no positive assistant check: that needs an interactive sign-in), and the gateway's 401-not-302
  and login redirect (ADR-030 through ADR-032). CI validates the service definition
  (`--profile smoke config`) and lints the script, but never runs the check itself.

## The Keycloak realm

- **The realm imports only into an empty `keycloak-db-data` volume.** Edit `finassist-realm.json`,
  or a secret it takes from `.env`, and nothing changes for an existing volume until the realm is
  imported again. `docs/operations/operations.md`'s "Changing the realm or its secrets" gives
  both recipes — a re-import with `kc.sh import --override true`, or a recreated
  `keycloak-db-data` — and both delete the realm's users. A changed secret takes neither: its
  "Rotating a secret" changes each one in place, `.env` first, and keeps every user. A locked-out
  owner or a lost admin password is restored with `kcadm.sh` or `kc.sh bootstrap-admin`, never by
  recreating `keycloak-db-data`.
- **Every secret and credential in the realm is a `${VAR}` placeholder**, pinned by
  `KeycloakRealmTest`. `KeycloakRealmIT` imports the file into a real Keycloak and reads back the
  sign-in protection, the owner's id and required actions, and each client's scope and audience;
  not every key. Both live in the gateway module.
- **Neither client is allowed the realm's full scope.** `fullScopeAllowed: false` on both, plus a
  realm-level `scopeMappings` entry naming exactly the roles each client's tokens need — `owner`
  and `viewer` for the gateway, `assistant` for the assistant. Left at Keycloak's default, every
  role in the realm reaches the `roles` claim. `KeycloakRealmTest` pins the file, and
  `KeycloakRealmIT` reads the scope back from a running Keycloak through its admin API, because the
  gateway's client permits no grant this build can mint a token with.

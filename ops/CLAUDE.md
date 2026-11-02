# CLAUDE.md — ops

Infrastructure that is not this repository's application code. The root `.claude/CLAUDE.md` binds
here too, including its rules for `docker-compose.yml`; this file adds what binds `ops/` alone.

## Layout

One directory per tool, none of them a deployable service of this repository's (ADR-052).

- `ops/keycloak/` — `Dockerfile`, which builds the optimized Keycloak image that
  `docker-compose.yml` runs; `finassist-realm.json`, the `finassist` realm imported at start-up;
  and `master-realm.json` with `apply-master-realm.sh`, the settings Keycloak's own `master` realm
  takes and the script that merges them into it, which the image carries under `/opt/finassist/`
  (ADR-074).
- `ops/backup/backup.py` — a standard-library operator CLI, run on the host with `uv run`; its PEP
  723 block declares Python 3.13 and no dependency. It backs up and restores each database on its
  own, and proves a backup restores (ADR-038). Its tests are in `ops/backup/tests/`.
- `ops/secret_check/secret_check.py` — a standard-library check, one file with no dependency, that
  refuses a secret that any file git does not ignore holds (#302), in UTF-8 or UTF-16, or one
  shorter than its `MIN_SECRET_LENGTH`, 32 characters, and a `LEDGER_DB_PASSWORD` that differs from
  `POSTGRES_PASSWORD` while `LEDGER_DB_USERNAME` and `POSTGRES_USER` name one role, which `db` would
  accept and the ledger then fail on (ADR-071); Compose hands it the two usernames, which are not
  secrets, with `db`'s and the ledger's own defaults. It names a variable, never a value, its
  length, how two values differ, or a file. On a mismatch, and only when neither of the pair is
  published or short, it tells the owner to keep the value both passwords held when the ledger last
  started successfully: undo whichever of the two changed, or rotate the role in place first. Its
  pass line says `LEDGER_DB_PASSWORD` equals `POSTGRES_PASSWORD`, since it compares two `.env`
  strings and cannot read the role. It runs as the one-shot `secret-check` service that both
  databases and Grafana wait on (ADR-044); a mismatch therefore blocks every service behind the
  gate, `make run-identity` included. CI's compose job gives a fresh pair one value and runs the
  service on a mismatched pair too, requiring the refusal. Its tests are in
  `ops/secret_check/tests/`.
- `ops/compose_check/compose_check.py` — a standard-library check, run on the host with
  `uv run`, of the resolved Compose file it reads on stdin: each long-running service's restart
  and log bound, as the `x-restart-policy` and `x-logging` blocks set them, those blocks pinned to
  ADR-039's `unless-stopped` and three 10 MB `json-file` files, the one-shots' lack of either;
  each long-running service's `stop_grace_period` as the `x-stop-grace-period` block sets it, that
  block pinned to `30s`, and none on a one-shot (#349, ADR-070); no `stop_signal` on any
  service, so the PostgreSQL images' own `SIGINT` stays in force; four readiness probes, each one
  that runs `curl` running it directly in exec form (`["CMD", "curl", …]`) and passing `-f`,
  `--fail` or `--fail-with-body`, never `--no-fail` (#142, ADR-039), every published port's
  `host_ip` exactly `127.0.0.1` and no port on `keycloak-db` (ADR-030), `auth-smoke` behind
  exactly `profiles: [smoke]` and `secret-check` and `keycloak-master` behind none, `db`,
  `keycloak-db` and `grafana` each depending on `secret-check`, and `gateway` on `keycloak-master`,
  with `condition: service_completed_successfully` (ADR-034, ADR-044, ADR-074); the `x-hardened` block's `cap_drop: [ALL]` and
  `no-new-privileges:true` on every service but the two databases, and `read_only: true` on every
  one but those and Keycloak, the script naming the exemptions so that a new service is held to
  both; on no service, the databases included, `cap_add`, `privileged`, `pid`, `ipc`, `userns_mode`,
  `uts`, `devices`, a root `user` or a bind of a file named `docker.sock` (the other routes to the
  host are #389); `db` and `keycloak-db` each on a network of its own shared with its one client
  alone, `books` with the ledger and `identity` with Keycloak, compared by the Docker network each
  key resolves to, so no other key may resolve to either, neither one `external`, and `identity`
  `internal` (ADR-063) — which keeps every other container from resolving or reaching either over a
  Compose network, though on Docker Desktop any container with a route off its networks still
  reaches `db`'s loopback-published port through `host.docker.internal`, behind its password alone;
  no `network_mode` on any service but `secret-check`, whose is exactly `none`; exactly one
  `--web.cors.origin=` in Prometheus's `command`, the empty regex, and no `entrypoint` on it
  (ADR-057); and no `MANAGEMENT_ENDPOINTS_WEB_*`, `SPRING_CONFIG_*` or `SPRING_APPLICATION_JSON`
  key, in any spelling, nor `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS`, in the
  ledger's or the gateway's environment, and no `command` or `entrypoint` on either, nor `volumes`,
  `volumes_from`, `configs`, `secrets` or `working_dir`, which could put an `application.*` in its
  working directory or that directory's `config/`, where Spring reads one, each of which would
  reopen the Actuator exposure their code pins and their tests cannot see; nor, in either's
  environment and in any spelling, `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE`, `SERVER_SHUTDOWN`,
  `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` or `SPRING_HTTP_CLIENTS_READ_TIMEOUT`, which would
  override the shutdown, connection-wait and relay read-timeout bounds that
  `*ShippedConfigurationTest` and the gateway's `GatewayApplicationIT` pin from `application.yml`
  alone (ADR-070); nor a `LOGGING_LEVEL_*`,
  `LOGGING_GROUP_*`, `LOGGING_CONFIG`, `DEBUG` or `TRACE` key, in any spelling, on the gateway,
  whose Spring Web and Security loggers write credentials below INFO. It names a service, a key or a
  member, never a value. `placeholders.env` beside it holds a throwaway value for each `:?`
  variable, which `make check-compose` exports, so the file resolves without `.env` or the shell's
  secrets (ADR-049). Its tests are in `ops/compose_check/tests/`.
- `ops/smoke/auth_smoke.py` — the only file behind the `smoke` Compose profile (ADR-034).
- The collector, Prometheus and Grafana provisioning that the `monitoring` Compose profile mounts.

## Commands

From the repository root unless stated:

- **Run identity only** — `make run-identity`: `docker compose up -d --wait keycloak-db keycloak`,
  then `docker compose run --rm keycloak-master`, which returns the one-shot's exit code, so the
  target fails when `master`'s settings could not be applied. For signing in or testing against
  Keycloak without the rest of the stack.
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
  secret Compose interpolated; it names a service, an `x-` block, a probe path, an environment key
  or a member, and an input it cannot read by the error's type alone, or as nested too deeply.
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
  imported again. `docs/operations/operations.md`'s "Changing the realm or its secrets" gives both
  recipes — a re-import with `kc.sh import --override true`, or a recreated `keycloak-db-data` — and
  both delete the realm's users. A setting `kcadm.sh` can change, and a changed secret, take
  neither, and keep every user: its "Applying a realm setting in place" applies a changed setting to
  the running realm (its worked example is the event listeners, the offline-session cap and
  `admin-cli`'s flows), and its "Rotating a secret" changes each secret in place, `.env` first. A
  locked-out owner or a lost admin password is restored with `kcadm.sh` or `kc.sh bootstrap-admin`,
  never by recreating `keycloak-db-data`.
- **`KEYCLOAK_ADMIN_PASSWORD` must equal the administrator's live password** (ADR-074). The
  `keycloak-master` one-shot signs in with it on every `up`, and the gateway waits on that, so a
  password changed anywhere but `.env` first stops the gateway, as five wrong ones do while the
  administrator is locked out (ADR-073). `master`'s policy holds every password set for it to 32
  characters, a temporary one from `kc.sh bootstrap-admin` included.
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
- **The realm and the image state what Keycloak would otherwise default** (ADR-060). The file sets
  `"eventsListeners": []`, so no listener writes sign-ins to the log; `KeycloakRealmTest` and
  `KeycloakRealmIT` read it back. It declares `admin-cli` explicitly, a public client with every
  flow off (`standardFlowEnabled`, `implicitFlowEnabled`, `directAccessGrantsEnabled`,
  `serviceAccountsEnabled`) and `fullScopeAllowed: false`, so Keycloak's default entry, which
  permits the password grant, is not the one in force. It caps offline sessions at 36,000 s
  (`offlineSessionMaxLifespanEnabled`, equal to `ssoSessionMaxLifespan`), so none outlives a
  browser session.
- **`master` is configured from `master-realm.json`, merged in place, never imported** (ADR-074).
  Keycloak creates `master` before it imports anything, so `--import-realm` would never read a
  `master` file, and the file stays out of `data/import`. `apply-master-realm.sh` runs `kcadm.sh
  update realms/master --file … --merge`, which changes only what the file names and keeps every
  user, signed in for that one call with `--no-config` and the password from `KC_CLI_PASSWORD`,
  never an argument. The file holds ADR-073's lockout policy, which `KeycloakRealmTest` holds equal
  to `finassist`'s, the password policy `length(32) and notUsername`, and no event listener. A
  setting removed from the file keeps its last value in `master`; set it explicitly instead.
- **Keycloak logs JSON, and builds without its deprecated features.** `docker-compose.yml` sets
  `KC_LOG_CONSOLE_OUTPUT=json`, as every other service writes JSON (ADR-019), pinned by
  `KeycloakRealmTest`. `ops/keycloak/Dockerfile` sets `KC_FEATURES_DISABLED`
  (`twitter-broker,identity-brokering-api`) at `kc.sh build`, which freezes the feature set, so
  changing it means rebuilding the image; `KeycloakRealmIT` reads every deprecated feature back from
  the built image as disabled.

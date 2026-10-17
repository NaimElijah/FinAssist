# Operations

How to run, check, back up and recover FinAssist on the one machine it runs on. The
[README](../../README.md) takes a stranger from a clean clone to a running stack; this guide is for
everything after that. Nothing here is deployed anywhere: every command runs against the local
Compose stack ([ADR-005](../ADRs/ADR-005-docker-compose-as-the-run-mechanism.md)).

> [!CAUTION]
> Never run `docker compose down -v`. It deletes every volume the project has: the ledger's books
> in `db-data`, and the owner's identity in `keycloak-db-data`.

## Contents

[Restarts and logs](#restarts-and-logs) ·
[Upgrading an existing ledger volume](#upgrading-an-existing-ledger-volume) ·
[Smoke-testing authentication](#smoke-testing-authentication) ·
[Changing the realm or its secrets](#changing-the-realm-or-its-secrets) ·
[Backing up and restoring](#backing-up-and-restoring) ·
[Running the telemetry backend](#running-the-telemetry-backend)

## Restarts and logs

**A service that crashes comes back on its own, and so does the stack after Docker restarts.**
Every long-running service runs under `restart: unless-stopped`, so only a deliberate stop keeps
it down: Ctrl+C on `docker compose up`, `docker compose stop`, or `make stop`. A container started
with `-d` and never stopped, such as the README's `docker compose up -d db keycloak-db keycloak`,
is running again after Docker Desktop restarts, and holds its port. After a Docker restart, Docker
restarts the containers itself, without Compose's `depends_on` ordering:

- A service whose database is not ready yet may exit and be restarted a few times until it is:
  the ledger, which runs its Flyway migrations against `db` at start-up, or Keycloak, which needs
  `keycloak-db`. `docker compose ps` shows a service caught in a loop as `Restarting`.
- While Keycloak is still starting, nobody can sign in. The ledger and the assistant answer a
  token-bearing request 503 rather than restarting, because their key source is unreachable
  ([ADR-030](../ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md)).

Each container keeps at most 30 MB of logs, three 10 MB files, and `docker compose logs` reads
only what is left ([ADR-039](../ADRs/ADR-039-compose-restarts-services-and-bounds-their-logs.md)).

## Upgrading an existing ledger volume

**Already have a `db-data` volume from before `V1_2`? Back it up before its first start on a
newer ledger.** The release that added `V1_2` added a migration to the ledger's `accounting`
schema and turned on `spring.flyway.out-of-order` so it applies to a database that already holds
the books
([ADR-035](../ADRs/ADR-035-flyway-runs-out-of-order.md)). `LedgerUpgradeIT` rehearses exactly
that upgrade, and Flyway validates before it migrates, so nothing is expected to go wrong — a
backup is cheap insurance, not a warning:

```bash
docker compose up -d --wait db keycloak-db
make backup      # raw: uv run ops/backup/backup.py backup
```

`--wait` blocks until each database's healthcheck reports healthy, since Compose otherwise
returns as soon as the container starts, and a dump of a starting server fails. `make backup`
writes both databases into a git-ignored `backups/<UTC time>/`, and fails unless each dump reads
back in full; see [Backing up and restoring](#backing-up-and-restoring).

## Smoke-testing authentication

`ops/smoke/auth_smoke.py` is an opt-in, manually run check of the authentication and RBAC
wiring (ADR-030 through ADR-032) against the stack actually running under Compose. It is not a
substitute for `make test-ledger`, `make test-gateway` or `make test-assistant`, and most of what
it asserts those suites already prove, each against one service with its own test settings.
What it adds is Compose's own wiring. It runs as the `auth-smoke` service, behind its own
`smoke` Compose profile, and reaches Keycloak, the ledger, the assistant and the gateway by their
Compose DNS names, so it sees the service names and ports, the issuer, JWKS URI, Host
allow-list and public URLs `docker-compose.yml` hands each service, and the issuer Keycloak
puts in a token fetched over the backchannel.

It gets no positive answer from the assistant. Every token it can obtain carries the ledger's
audience, so it can only show that the assistant refuses one; a token for the assistant needs an
interactive sign-in.

Standard library only, so it runs unmodified in the pinned `python:3.13.8-slim-trixie` image
with no install step. It runs as an unprivileged user and never prints a token.

Run it after `make run` reports every service healthy:

```bash
make smoke-auth
```

On a stack that is not up, `docker compose run` starts the services the check depends on and
waits for them to report healthy, but from whatever images already exist, which may predate
your last change; `make run` is what builds them.

It exits non-zero on the first of its eight checks to fail, naming which one and why. It is
never started by a bare `docker compose up`. CI validates that the service definition parses
(`docker compose --profile smoke config`) and lints the script (`make lint-smoke`); running the
check against a live stack stays a manual step.
[ADR-034](../ADRs/ADR-034-keep-the-compose-smoke-check-manual.md) records why, and what would
change that.

It sees only part of a stale realm. Keycloak imports the realm only into an empty
`keycloak-db-data` volume, so what it holds can lag behind the file and `.env`. An
`ASSISTANT_CLIENT_SECRET` changed in `.env` but never re-imported fails check 1, because the check
authenticates with the `.env` value while Keycloak still holds the old one. It cannot see a stale
`GATEWAY_CLIENT_SECRET`, which it never exchanges — only a real sign-in does — nor stale redirect
URIs: the login-redirect check compares the gateway's redirect with the public URLs Compose
configured, not with the redirect URIs Keycloak stored.

## Changing the realm or its secrets

Keycloak imports `ops/keycloak/finassist-realm.json` at start-up only into an empty
`keycloak-db-data` volume, filling its `${VAR}` placeholders from `.env` as it does. An edit to
the file, or to `GATEWAY_CLIENT_SECRET`, `ASSISTANT_CLIENT_SECRET` or
`FINASSIST_OWNER_INITIAL_PASSWORD` in `.env`, changes nothing in Keycloak until the realm is
imported again. There are two ways to do that.

**Both delete every user in the `finassist` realm**: the owner's password and TOTP enrolment, and
every viewer added by hand. Take `make backup` first: restoring its `keycloak.dump` is the only
undo (see [Backing up and restoring](#backing-up-and-restoring)). Afterwards the owner signs in again with
`FINASSIST_OWNER_INITIAL_PASSWORD`, sets a new password and enrols TOTP again, as at
[first sign-in](../../README.md#first-sign-in), and each viewer is created again. The owner keeps the same `sub`,
because the realm pins the owner's id, so every journal row the owner wrote still names them.
**Neither touches `db-data`, the ledger's volume.**

**Re-import the realm in place.** This keeps Keycloak's database, and with it Keycloak's own admin
account:

```bash
docker compose build keycloak      # the realm file is copied into the image at build time
docker compose stop keycloak       # Keycloak requires every node stopped before an override import
docker compose run --rm keycloak import --optimized --file /opt/keycloak/data/import/finassist-realm.json --override true
docker compose up -d keycloak gateway   # recreates each whose image or configuration changed
```

`docker compose run` keeps the image's `kc.sh` entrypoint and replaces only its `start` command,
runs with the `keycloak` service's own environment, so the placeholders fill from `.env`, publishes
no port, and starts `keycloak-db` first if it is not already running. `--override true` makes
Keycloak delete the existing `finassist` realm and import the file in its place.

**Or recreate `keycloak-db-data`.** This deletes Keycloak's whole database, its own admin account
included, which it creates again from `KEYCLOAK_ADMIN_USERNAME` and `KEYCLOAK_ADMIN_PASSWORD`:

```bash
docker compose rm --stop --force keycloak keycloak-db   # these two containers only
docker volume rm finassist_keycloak-db-data             # Keycloak's volume only, never finassist_db-data
docker compose build keycloak
docker compose up -d keycloak gateway                   # imports the realm into the new, empty volume
```

`docker volume rm` names the one volume it deletes; the ledger's books are in
`finassist_db-data`, a different volume, which none of these commands touches. Never do this with
`docker compose down -v`: it deletes every volume the project has, `db-data` included.

**The realm's secret placeholders are guarded.** `docker-compose.yml`'s `:?` on each of the
realm's three secret placeholders — the two client secrets and the owner's initial password — is
what keeps the realm import safe; its two other
placeholders, `GATEWAY_PUBLIC_URL` and `FINASSIST_OWNER_USERNAME`, are not secret and take `:-`
defaults. Run `ops/keycloak/Dockerfile` outside that guarded path with one of the secrets unset,
and Keycloak imports the literal, unresolved `${VAR}` text as the credential rather than refusing
to start or leaving it blank — for the owner's initial password, that means a publicly known
value, not a missing one.

## Backing up and restoring

The books live in the `db-data` volume. The owner's identity lives in `keycloak-db-data`: their
password hash, their TOTP enrolment, and the pinned `sub` that every journal row names. Each is
backed up on its own, as a `pg_dump` custom-format archive, and only when you ask: there is no
schedule, because no recovery-point target has been set yet
([ADR-038](../ADRs/ADR-038-back-up-each-database-as-a-logical-dump.md)).

```bash
docker compose up -d --wait db keycloak-db   # if they are not running already
make backup                                  # raw: uv run ops/backup/backup.py backup
```

`make backup` prints the Compose project it reads from, then writes `ledger.dump`,
`keycloak.dump` and a `manifest.json` into `backups/<UTC time>/`. When `BACKUP_DIR` is set, the
folder goes under it instead. The manifest records:

- each file's size and SHA-256;
- a digest of the books: every account and journal row, sorted and hashed, never an amount.

The digest is taken before and after the dumps. If the two differ, the books changed mid-backup,
and the backup fails rather than record a digest that might not match the dump.

`make backup` never starts a container itself. It fails, and removes the partial folder, if:

- a database is not running;
- a dump comes out empty;
- `pg_restore` cannot read a dump to its end.

A database that holds no table yet, such as `keycloak-db` before Keycloak first ran, is reported
rather than refused. The command is the same in PowerShell, where `make` is not installed: `uv
run` supplies the Python 3.13 the script declares.

**A backup is as sensitive as the stack itself.**

- **What the dumps hold.** `ledger.dump` holds the books. `keycloak.dump` holds every password
  hash and, in clear, the owner's TOTP seed: anyone holding it can generate the owner's codes.
- **Who can read them.** `backups/` and `*.dump` are git-ignored. On Linux and macOS the folder is
  readable by you alone. On Windows it inherits the permissions of the folder it is created in.
  The default `backups/` sits inside your user profile, so only you, and administrators, can read
  it. A `BACKUP_DIR` outside your profile, such as `C:\backups`, inherits that folder's
  permissions, which on a default `C:\` let every local user read the dumps. Keep `BACKUP_DIR`
  inside your profile.
- **What they protect against.** They sit on the same disk as the volumes, so they cover a bad
  migration, a deleted volume or a mistaken realm re-import, not the loss of the machine.
- **Copying them elsewhere** is your call. Whether a copy may leave the machine at all is
  [#195](https://github.com/NaimElijah/FinAssist/issues/195)'s question.

**A backup is not proven until it has been restored.** `make restore-check` restores one into a
Compose project of its own and checks that the stack comes back on it:

```bash
make restore-check BACKUP=backups/<UTC time>   # raw: uv run ops/backup/backup.py check backups/<UTC time>
```

**Where it runs.** Always as `finassist-restore-check`, which it passes as `-p` on every call. Its
ports are 15432, 18080 and 18081, so it runs beside your stack and never touches your project or
its volumes. It needs `.env` complete, as `docker compose up` does.

**What it does.** It restores both dumps into fresh volumes, then starts Keycloak and the ledger
on them. The first run builds both images under that project's name, so expect several minutes.

**What it checks:**

1. Both dumps match their manifest. This is read before any container starts, so a mistyped
   folder or a changed dump costs nothing.
2. Both dumps restore into empty databases, and the restored books match the digest the backup
   recorded: the same rows, so the same balances.
3. The ledger becomes ready on the restored Flyway history.
4. Both `recorded_by` checks exist and are still `NOT VALID`.
5. The ledger, asked with the assistant's read-only token, lists exactly the accounts the
   database holds, and answers every account's balance in its own currency. A backup with no
   account fails rather than passing on nothing.
6. The owner, under the pinned id, has an OTP credential.
7. That credential is the same one after Keycloak has started. This is what tells a restored
   owner from one the realm import just re-created.

A backup taken before the owner's first sign-in therefore fails check 6. It brings back the books,
but not a usable identity.

**What it reports.** Counts, never an amount, a name, an id or a secret.

**How it cleans up.** It removes its own containers and volumes before it starts and after it
ends, found by Compose's project label, never with `down -v`. Only the two images it built remain.

**Restoring writes only into an empty database, with the service that uses it stopped.** The
ledger runs its Flyway migrations, and Keycloak imports a fresh realm, the moment either starts on
an empty database. Under `restart: unless-stopped` either would do so on its own, so stop both
first:

```bash
docker compose stop ledger keycloak            # each would migrate or import into an empty database
docker compose up -d --wait db keycloak-db     # a volume that is gone comes back empty
make restore BACKUP=backups/<UTC time>         # raw: uv run ops/backup/backup.py restore backups/<UTC time>
docker compose up -d
```

`make restore` restores nothing until every one of these holds, for each database it is asked to
restore (both, unless `DATABASE=` names one):

- its dump still matches the SHA-256 in its manifest;
- the service that uses it is stopped, not merely between crash-loop restarts: `ledger` for
  `db`, `keycloak` for `keycloak-db`;
- the database is running, and holds no table.

Each database is then restored in one transaction, so it ends up either whole or still empty.
After the books are restored, their digest must match the one the backup recorded, or `make
restore` fails and says not to start the ledger on them. A restore never needs an `UPDATE` or a `VALIDATE CONSTRAINT`: the journal's triggers and the
`recorded_by` checks come back exactly as they were dumped. `DATABASE=ledger` or
`DATABASE=keycloak` restores that database alone; the raw form is `--database ledger`.

**Emptying a volume is a separate, deliberate step, never part of the restore.** If a volume still
exists but holds data you mean to replace, remove that database's container and volume yourself,
and only once `make restore-check` has passed for the backup you are about to restore:

- for the books: `docker compose rm --stop --force ledger db`, then
  `docker volume rm finassist_db-data`;
- for the identity store: `docker compose rm --stop --force keycloak keycloak-db`, then
  `docker volume rm finassist_keycloak-db-data`.

Never use `docker compose down -v`.

**A restored Keycloak keeps the dump's own admin account and client secrets, not `.env`'s.** If
`KEYCLOAK_ADMIN_PASSWORD`, `GATEWAY_CLIENT_SECRET` or `ASSISTANT_CLIENT_SECRET` changed in `.env`
since the backup, set them back. Otherwise change them in Keycloak to match, or the gateway
cannot sign anyone in.

**Undoing a realm re-import** means restoring Keycloak's database alone. A re-import deletes the
owner's password and TOTP enrolment (see
[Changing the realm or its secrets](#changing-the-realm-or-its-secrets)), and the books are not
involved:

```bash
docker compose rm --stop --force keycloak keycloak-db
docker volume rm finassist_keycloak-db-data          # Keycloak's volume only, never finassist_db-data
docker compose up -d --wait keycloak-db
make restore BACKUP=backups/<UTC time> DATABASE=keycloak
docker compose up -d keycloak gateway
```

**Moving a database to a new PostgreSQL major version is a dump, a new volume, and a restore.**
The new major cannot start on the old one's data, and from 18 the official image refuses to start
on an empty data directory whenever the volume also holds an older cluster or is mounted at the old
`/var/lib/postgresql/data` path. It stops with "in 18+, these Docker images are configured to store
database data in a…", and writes nothing. So the new major gets a volume of its own, and the old
volume stays exactly as it was, as the way back. These steps have not been rehearsed against a real
major upgrade yet.

1. Take `make backup`, and run `make restore-check` on it.
2. Check that what uses the database supports the new major:
   - for `db`, Flyway and the PostgreSQL JDBC driver the ledger ships;
   - for `keycloak-db`, Keycloak's
     [supported configurations](https://www.keycloak.org/server/supported-configurations).
3. Stop the service that uses the database, and remove the database's container:
   - `docker compose stop ledger`, then `docker compose rm --stop --force db`;
   - or `docker compose stop keycloak`, then `docker compose rm --stop --force keycloak-db`.
4. In `docker-compose.yml`, change the database's image tag, and point it at a new volume under
   a new key, declared under `volumes:` and mounted at `/var/lib/postgresql`, the layout the 18+
   image expects:
   - for example, `db-data-19:/var/lib/postgresql` for `db`;
   - for `keycloak-db`, `keycloak-db-data-18:/var/lib/postgresql`. The mount path changes too,
     from 17's `/var/lib/postgresql/data`.
5. Run `docker compose up -d --wait db` or `keycloak-db`. The new major starts on an empty volume.
6. Run `make restore BACKUP=backups/<UTC time> DATABASE=ledger`, or `DATABASE=keycloak`.
7. Run `docker compose up -d`.
8. Once a fresh `make backup` passes `make restore-check` on the new major, the old volume can go:
   `docker volume rm finassist_db-data` or `finassist_keycloak-db-data`. Until then, putting the
   old image tag and volume key back is the way back.

## Running the telemetry backend

Run the telemetry backend with `docker compose --profile monitoring up`: Grafana on
[localhost:3000](http://localhost:3000), Prometheus on
[localhost:9090](http://localhost:9090), and an OTLP collector on `localhost:4318`, each published
on `127.0.0.1` only. Point the
ledger at it with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORT_ENABLED=true ./mvnw spring-boot:run`. Traces are printed by the collector
(`docker compose logs otel-collector`) rather than stored — there is no trace backend, which
[ADR-019](../ADRs/ADR-019-opentelemetry-and-structured-logging.md) records as a deliberate
limit.

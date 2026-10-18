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
[Rotating a secret](#rotating-a-secret) ·
[Restoring the owner's sign-in](#restoring-the-owners-sign-in) ·
[Recovering Keycloak's admin account](#recovering-keycloaks-admin-account) ·
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
imported again. There are two ways to do that. **To change a secret, do neither:**
[rotate it in place](#rotating-a-secret), which keeps every user. A re-import is for a change to
the realm file itself.

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

## Rotating a secret

**A secret is changed where it is stored, then the service that reads it is restarted — never by
re-importing the realm or recreating a volume.** Each secret in `.env` is copied into its store
once:

- PostgreSQL sets a role's password only when it initialises an empty volume.
- Keycloak copies the two client secrets, and its own admin's password, only into an empty
  database.
- Grafana sets its admin's password only on its first start.

After that, editing `.env` alone changes nothing in the store. A service that reads the value on
every start — the ledger, Keycloak or the gateway — then can no longer sign in to it. A re-import
would apply the client secrets, but it deletes every user in the realm
([above](#changing-the-realm-or-its-secrets)). Each recipe below changes the value in place
instead, so the books, the owner's password, their TOTP enrolment and their `sub` all survive.

**`.env` comes first in every recipe.** Write the new value into `.env`. Then recreate the
container that holds the store, so its environment carries the new value, and apply the value
from inside that container. No new value is ever typed into a command line or kept in shell
history.

Before you start:

- **Take `make backup`.** Restoring it is the undo (see
  [Backing up and restoring](#backing-up-and-restoring)).
- **Generate each value with `openssl rand -hex 16`**, which Git Bash carries.
- **Run the recipes in Git Bash, or another POSIX shell, after `export MSYS_NO_PATHCONV=1`.**
  - Without `MSYS_NO_PATHCONV`, Git Bash rewrites an argument that starts with `/`, such as
    `/opt/keycloak/bin/kcadm.sh`, into a Windows path before Docker sees it.
  - Windows PowerShell 5.1 does not work at all: it strips the double quotes inside the
    `sh -c '…'` arguments.

### The ledger's database password

`POSTGRES_PASSWORD` and `LEDGER_DB_PASSWORD` hold the same value. Compose creates the role from
the first, and the ledger signs in with the second. Set both to the new value in `.env`, then:

```bash
docker compose up -d --wait db       # recreated with the new value, which an initialised volume ignores
docker compose exec -T db sh -c 'printf "%s\n%s\n" "$POSTGRES_PASSWORD" "$POSTGRES_PASSWORD" | psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\password $POSTGRES_USER"'
docker compose up -d --wait ledger   # recreated, and signs in with the new value
```

`psql` connects over the container's own socket, which the image trusts, so it needs no old
password. `\password` reads the new value from the container's environment and sends the server
only its hash. Its two "Enter new password" prompts are printed and then answered by the pipe.
From then on the old password is refused over the network, and the ledger's healthcheck passes on
the new one.

### Keycloak's database password

The same recipe, on `keycloak-db`. Inside that container `POSTGRES_PASSWORD` is
`KEYCLOAK_DB_PASSWORD`, under the image's own name, so the command changes only in the service it
names. Set `KEYCLOAK_DB_PASSWORD` in `.env`, then:

```bash
docker compose up -d --wait keycloak-db
docker compose exec -T keycloak-db sh -c 'printf "%s\n%s\n" "$POSTGRES_PASSWORD" "$POSTGRES_PASSWORD" | psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\password $POSTGRES_USER"'
docker compose up -d --wait keycloak
```

### Signing in the admin CLI

The Keycloak recipes run `kcadm.sh`, Keycloak's admin CLI, inside the `keycloak` container. Sign
it in once for each container. It keeps its token in `/tmp/kcadm.config` until the container is
recreated. When the admin's password is still the `KEYCLOAK_ADMIN_PASSWORD` in `.env`, the
container already holds it:

```bash
docker compose exec -T keycloak sh -c 'KC_CLI_PASSWORD="$KC_BOOTSTRAP_ADMIN_PASSWORD" /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --config /tmp/kcadm.config'
```

When the two differ, give the current password from your terminal instead. That happens while the
admin's own password is being rotated, or after it was changed in the console.

```bash
read -rs KC_CLI_PASSWORD && export KC_CLI_PASSWORD   # the admin's current password; nothing is echoed
docker compose exec -T -e KC_CLI_PASSWORD keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --config /tmp/kcadm.config'
unset KC_CLI_PASSWORD
```

`-e KC_CLI_PASSWORD` with no value hands the variable from your shell to the container, so the
password never appears on a command line. `kcadm.sh` reads its sign-in password from that
variable. If the admin's password is lost altogether, see
[Recovering Keycloak's admin account](#recovering-keycloaks-admin-account).

### Keycloak's admin password

Keycloak reads `KEYCLOAK_ADMIN_PASSWORD`, as `KC_BOOTSTRAP_ADMIN_PASSWORD`, only when it first
creates its `master` realm, and ignores it afterwards. Set the new value in `.env`, then:

```bash
docker compose up -d --wait keycloak   # now carries the new value; the admin still has the old one
```

Sign the admin CLI in with the current password from your terminal, as
[above](#signing-in-the-admin-cli). Then set the new one:

```bash
docker compose exec -T keycloak sh -c '/opt/keycloak/bin/kcadm.sh set-password -r master --username "$KC_BOOTSTRAP_ADMIN_USERNAME" --new-password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config /tmp/kcadm.config'
```

You can use the admin console instead:

1. Sign in at `http://localhost:8081/admin` with the current password.
2. Choose the `master` realm under "Manage realms".
3. Open Users → `admin` → Credentials → "Reset password".
4. Enter the new value, with "Temporary" switched off.

### A client secret

`GATEWAY_CLIENT_SECRET` and `ASSISTANT_CLIENT_SECRET` reach Keycloak only at the realm's first
import. Put the new value in `.env`, then:

```bash
docker compose up -d --wait keycloak   # now carries the new value in its environment
```

Sign the admin CLI in, as [above](#signing-in-the-admin-cli). Then set the secret, and restart
the gateway, which signs in to Keycloak with it:

```bash
docker compose exec -T keycloak sh -c 'k() { /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }; id=$(k get clients -r finassist -q clientId=finassist-gateway --fields id --format csv --noquotes) && k update "clients/$id" -r finassist -s "secret=$GATEWAY_CLIENT_SECRET"'
docker compose up -d --wait gateway
```

Between the last two commands, sign-in fails, because Keycloak expects the new secret while the
running gateway still sends the old one. Restarting the gateway also signs everyone out, since it
keeps its sessions in memory.

Only a real sign-in proves the gateway's secret: [the smoke check](#smoke-testing-authentication)
never exchanges it. For the assistant's secret, run the same command with `finassist-assistant`
and `$ASSISTANT_CLIENT_SECRET`. The assistant does not use that secret yet, so there is nothing to
restart. Prove it with `make smoke-auth`, whose first check fails until the secret is set.

With the stack running, the admin console can do it the other way round. Open Clients → the client
→ Credentials, choose "Regenerate", then "Copy to clipboard", and paste the value into `.env`.
Then run `docker compose up -d --wait keycloak gateway`.

### The owner's initial password

`FINASSIST_OWNER_INITIAL_PASSWORD` is read once, at the realm's first import. It is the owner's
password only until their first sign-in replaces it. After that, change it in `.env` and nothing
else. Keycloak skips the import of a realm that already exists, so the owner's password stays as
it is.

Before that first sign-in, set a temporary password in Keycloak instead, as step 2 of
[Restoring the owner's sign-in](#restoring-the-owners-sign-in) does.

### Grafana's admin password

Grafana writes `GRAFANA_ADMIN_PASSWORD` into `grafana-data` on its first start, and ignores it
afterwards. Set the new value in `.env`, then:

```bash
docker compose --profile monitoring up -d grafana
docker compose --profile monitoring exec -T grafana sh -c 'printf %s "$GF_SECURITY_ADMIN_PASSWORD" | grafana cli admin reset-admin-password --password-from-stdin'
```

### Every secret at once

To replace every secret in one pass, run the recipes above in this order. That is the case for an
`.env` whose values were copied from somewhere public.

1. `make backup`, with `db` and `keycloak-db` running.
2. Write a new value for every secret into `.env`.
3. `docker compose up -d --wait db keycloak-db`, then both `\password` commands.
4. `docker compose up -d --wait keycloak`.
5. Sign the admin CLI in with the admin's *current* password from your terminal.
6. Set the admin's password, then both client secrets.
7. Grafana's admin password, if you run the `monitoring` profile.
8. `docker compose up -d --wait`, which recreates the ledger and the gateway with the new values.
9. Sign in through the web client, and run `make smoke-auth`.

## Restoring the owner's sign-in

**A locked-out owner is let back in by Keycloak's admin, never by recreating the realm or its
volume.** The realm closes every way back that the owner could use alone:

- **No password reset.** `resetPasswordAllowed` is off, and no mail server is configured.
- **No fallback for the second factor.** The owner's only second factor is TOTP, so a lost
  authenticator locks them out as surely as a forgotten password.
- **A lockout after five wrong passwords.** Brute-force protection then locks the account for a
  while, up to fifteen minutes. While it is locked, Keycloak answers every attempt, the right
  password included, with "Invalid username or password."

Sign the admin CLI in, as [above](#signing-in-the-admin-cli). Then read a temporary password for
the owner and run all four steps at once:

```bash
read -rs KC_CLI_PASSWORD && export KC_CLI_PASSWORD   # the owner's temporary password; nothing is echoed
docker compose exec -T -e KC_CLI_PASSWORD keycloak sh -c '
  k() { /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
  id=6c4a4be0-7c38-4145-845f-f4311ef64202
  k delete attack-detection/brute-force/users/$id -r finassist &&
  k set-password -r finassist --userid $id --temporary &&
  k get users/$id/credentials -r finassist --fields id,type --format csv --noquotes |
    grep ",otp$" | cut -d, -f1 | while read -r c; do k delete users/$id/credentials/$c -r finassist; done &&
  k update users/$id -r finassist -s "requiredActions=[\"UPDATE_PASSWORD\",\"CONFIGURE_TOTP\"]"'
unset KC_CLI_PASSWORD
```

`id` is the owner's id, which `finassist-realm.json` pins. For a viewer, use theirs instead, from
`k get users -r finassist -q exact=true -q username=<name> --fields id`. Each step also has an
admin-console route, under Users → `owner`:

1. **Clear the lockout.** In the console, switch "Temporarily locked" off on the Details tab. The
   change applies at once.
2. **Set a temporary password.** `set-password --temporary` takes it from `KC_CLI_PASSWORD`,
   which here holds the owner's new password, not the admin's. The realm's policy applies to it:
   - at least 12 characters;
   - not the username or the email;
   - none of the owner's last three passwords.

   In the console, use Credentials → "Reset password", with "Temporary" left on.
3. **Remove the lost authenticator.** This deletes every `otp` credential the owner has. In the
   console, use Credentials → the Otp row's ⋮ menu → "Delete".
4. **Ask for a new device.** This sets the required actions to "Update Password" and
   "Configure OTP". In the console, choose both under "Required user actions" on the Details tab,
   then Save.
   - **Do not skip this step.** The realm's browser flow asks for a one-time code only from a user
     who has an OTP credential. After step 3, the owner would sign in with a password alone, and
     never be asked to enrol a device again.
   - The update replaces the whole list, which is why it names both.

The owner then signs in as at [first sign-in](../../README.md#first-sign-in): the temporary
password, then a new password, then a new TOTP device. Their user is never recreated, so their id
stays the same. That id is the `sub` every journal row they wrote names
([ADR-033](../ADRs/ADR-033-the-journal-records-who.md)). A forgotten password with the device
still at hand needs steps 1 and 2 only.

## Recovering Keycloak's admin account

**A lost admin password is recovered with a temporary admin, and `keycloak-db-data` is never
touched.** Recreating that volume would bring the admin back from `.env`, but it would delete the
realm's users with it ([above](#changing-the-realm-or-its-secrets)). Instead, Keycloak's
[`bootstrap-admin`](https://www.keycloak.org/server/bootstrap-admin-recovery) command adds a
temporary admin to the existing database. Every Keycloak node must be stopped while it runs:

```bash
docker compose stop keycloak
read -rs TEMP_ADMIN_PASSWORD && export TEMP_ADMIN_PASSWORD   # a password for the temporary admin
docker compose run --rm -e TEMP_ADMIN_PASSWORD keycloak bootstrap-admin user --optimized --username temp-admin --password:env TEMP_ADMIN_PASSWORD --no-prompt
docker compose up -d --wait keycloak
```

`docker compose run` keeps the image's `kc.sh` entrypoint and the service's own database settings,
as the realm re-import above does.

**Check its output for `Created temporary admin user with username temp-admin`.** If a
`temp-admin` is left from an earlier recovery, Keycloak logs `user with username exists` and
still exits 0. In that case, run the command again with another name.

Then, as the temporary admin, set the real admin's password to `KEYCLOAK_ADMIN_PASSWORD` from
`.env`. Sign in as the real admin, and delete the temporary one:

```bash
export KC_CLI_PASSWORD="$TEMP_ADMIN_PASSWORD"
docker compose exec -T -e KC_CLI_PASSWORD keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user temp-admin --config /tmp/kcadm.config'
unset KC_CLI_PASSWORD TEMP_ADMIN_PASSWORD
docker compose exec -T keycloak sh -c '/opt/keycloak/bin/kcadm.sh set-password -r master --username "$KC_BOOTSTRAP_ADMIN_USERNAME" --new-password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config /tmp/kcadm.config'
docker compose exec -T keycloak sh -c 'k() { /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }; KC_CLI_PASSWORD="$KC_BOOTSTRAP_ADMIN_PASSWORD" k config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" && id=$(k get users -r master -q exact=true -q username=temp-admin --fields id --format csv --noquotes) && k delete "users/$id" -r master'
```

To give the admin a new password rather than the one `.env` holds, write it into `.env` before you
start. The realm's users, the owner's TOTP enrolment and the books are untouched throughout.

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
since the backup, set them back. Otherwise change them in Keycloak to match, as
[Rotating a secret](#rotating-a-secret) does, or the gateway cannot sign anyone in.

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

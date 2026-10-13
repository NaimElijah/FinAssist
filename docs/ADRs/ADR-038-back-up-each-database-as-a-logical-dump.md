# ADR-038: Back up each database as its own logical dump, on demand, proven by an isolated restore check

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

**Nothing backed up the books or the identity store, and neither had ever been restored.** The
ledger's journal lives in the Docker volume `db-data`. The owner's identity lives in
`keycloak-db-data`: their password hash, their TOTP enrolment, and the pinned `sub` that every
journal row written from `V1_2` onward names
([ADR-033](ADR-033-the-journal-records-who.md)). The only backup the repository described was a
one-off `pg_dump > finassist-before-upgrade.sql` in the README's quick start. It came with no
restore and no check, and wrote the dump into the working tree, where `*.sql` is not ignored
([#137](https://github.com/NaimElijah/FinAssist/issues/137)).

**Several constraints were already decided:**

- **The two databases share no failure domain**
  ([ADR-031](ADR-031-keycloak-as-the-identity-provider.md)). Each needs a backup of its own, and
  one must be restorable without the other. Restoring `keycloak-db` alone is the only undo for a
  realm re-import, which deletes the owner's password and TOTP.
- **A restore must never need an `UPDATE` or a `VALIDATE CONSTRAINT`.**
  - The journal's triggers refuse an `UPDATE`
    ([ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)).
  - Validating `recorded_by` would fail on rows written before `V1_2`
    ([ADR-033](ADR-033-the-journal-records-who.md)).
- **A restore test must never touch the default `finassist` Compose project.** The owner's real
  books live there.
- **A dump must never enter the repository.**

**The owner decided two questions on 2026-10-13:**

- **Recovery-point target:** none yet. Backups are taken on demand, and the requirement keeps its
  target as `TODO`.
- **Where dumps go:** a git-ignored `backups/` folder in the repository, which `BACKUP_DIR` can
  override.

**The development machine shapes the tooling.** It runs Windows and PowerShell 5.1, with no
`make` and no `sh` on its path ([ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md)).
PowerShell's `>` re-encodes a native command's output as text, which corrupts a binary
custom-format dump. `uv` is installed already, because the assistant needs it.

## Decision

**Each database is backed up on its own, as a logical dump: `pg_dump --format=custom`, run inside
that database's own container.** The client is therefore always the server's own major version.
`ledger.dump` and `keycloak.dump` land in `backups/<UTC timestamp>/`, beside a `manifest.json`
recording each file's size, SHA-256, service and image.

**A backup proves it can be read before it is called a backup.** `make backup` fails if:

- a dump is empty;
- `pg_restore` cannot read a dump from end to end;
- `db` or `keycloak-db` is not already running. It never starts a container in the owner's
  project, because `up` could recreate a database under a running ledger.

Any failure removes the partial folder.

**A restore writes only into an empty database, with the service that uses it stopped, and all
or nothing.**

- `make restore` checks each dump's SHA-256 against the manifest.
- It refuses unless:
  - the target database holds no table;
  - the service that uses that database is stopped: the ledger for `db`, Keycloak for
    `keycloak-db`. Under `restart: unless-stopped` either would otherwise migrate or import into
    the empty database first.
- It then runs `pg_restore --single-transaction`. It keeps owners and privileges, so a
  mismatched `POSTGRES_USER` fails loudly rather than restoring under the wrong role.
- A full restore loads data with `COPY`, before it creates triggers. pg_dump re-adds a
  `NOT VALID` check as `NOT VALID`. So neither the append-only triggers nor the `recorded_by`
  checks are ever in the way.
- `--database ledger|keycloak` restores one database alone.

**A restore check proves the whole system comes back from a given backup.** `make restore-check`:

- **Isolation.** It runs only in the Compose project `finassist-restore-check`, on ports of its
  own. The project name is fixed in the code, and every Compose call passes it.
- **Restore.** It restores both dumps into fresh volumes. Before Keycloak starts, it records the
  owner's OTP credentials from the restored database.
- **Then it requires:**
  - the ledger becomes ready on the restored Flyway history;
  - both `recorded_by` checks exist and are still `NOT VALID`;
  - the books are not empty, and the ledger lists exactly the accounts the database holds and
    answers every account's balance in its currency;
  - the owner keeps an OTP credential, with the same ids after Keycloak has started. That last
    clause is what tells a restored owner from one the realm import just re-created under the
    same pinned id.
- **Clean-up.** It removes its own containers and volumes, found by Compose's project label,
  never with `down -v`.
- **Run by hand, never in CI.**

**One standard-library Python CLI does all three: `ops/backup/backup.py`, run with `uv run`.**

- It passes open binary file handles straight to `docker compose exec -T`, so no shell ever
  touches a dump.
- It follows `ops/smoke/auth_smoke.py`'s conventions, and `make lint-backup` lints it under the
  assistant's ruff and `mypy --strict` settings.
- It never prints an amount, a name, an id, a token, a secret or a PostgreSQL `DETAIL:` line.

## Consequences

**Makes easy**

- **One command each:** backing up, restoring, and proving a backup restores.
- **Undoing a realm re-import.** Restore `keycloak.dump` into a recreated `keycloak-db-data`, and
  the owner's password, TOTP and viewers come back.
- **Changing PostgreSQL major version.** A dump from the old major restores into an empty
  cluster of the new one.
- **Moving the books to AWS** ([#192](https://github.com/NaimElijah/FinAssist/issues/192)) starts
  from the same dumps.

**Makes hard**

- **Dumps sit on the same disk as the volumes.** They guard against a bad migration, a deleted
  volume or a mistaken re-import, not against losing the machine. Whether a copy may leave it is
  [#195](https://github.com/NaimElijah/FinAssist/issues/195)'s question.
- **No recovery-point target means no schedule.** Work recorded since the last `make backup` is
  lost with the volume.

**Accepted cost**

- **A Keycloak dump is a credential store.** It holds every password hash, and Keycloak keeps the
  TOTP seed itself, so anyone holding `keycloak.dump` can generate the owner's codes. On Windows
  the folder inherits the user profile's permissions. On Linux and macOS the CLI makes it
  readable by the owner alone.
- **A restored Keycloak keeps the dump's admin account and client secrets, not `.env`'s.** If a
  secret changed in `.env` since the backup, the service using it fails until the two agree.
- **A restore check takes minutes.** It builds the ledger and Keycloak images under its own
  project name, and Keycloak alone takes minutes to report ready on a fresh start.
- **The CLI has no unit tests,** following [ADR-034](ADR-034-keep-the-compose-smoke-check-manual.md)'s
  precedent for the smoke check. Its boundary is Docker. It is proved by running it against real
  containers, including the refusals.

## Alternatives considered

- **Copying the Docker volumes.** Rejected. A file copy of a running cluster is inconsistent
  unless the database is stopped. The copy is tied to one PostgreSQL major version, and on
  Windows it means a helper container per volume.
- **`pg_basebackup` with WAL archiving, for point-in-time recovery.** Rejected for now. It buys a
  recovery-point target nobody has set, at the cost of archive storage, a second restore
  procedure, and a long-running process.
- **`kc.sh export` for the identity store.** Rejected. It would be a second format, taken with
  Keycloak's tooling rather than the database's, beside a `pg_dump` that already holds everything
  Keycloak stores. #137 listed it as optional.
- **Restoring each backup into a temporary container when it is taken.** Rejected. Its
  fingerprint compares `pg_restore` with itself. Reading the dump back in full catches a
  truncated file, and the restore check is the proof that it restores.
- **A scheduled job.** Rejected until a recovery-point target exists. On Windows it would also
  need Docker Desktop running at the scheduled time.
- **POSIX shell scripts.** Rejected. The owner's shell cannot run them, and its redirection would
  corrupt the dump.
- **Running the restore check in CI.** Rejected. CI holds no real backup, and the owner's backup
  is what must be proved. A job on synthetic data would prove the mechanism, not the backup, at
  ADR-034's cost of minutes per run.

## Revisit when

- **The owner sets a recovery-point target.** That brings a schedule, and if the target is under
  a day, WAL archiving.
- **The books move to AWS.** For production, AWS Backup replaces this
  ([#191](https://github.com/NaimElijah/FinAssist/issues/191)). These dumps are how the data gets
  there ([#192](https://github.com/NaimElijah/FinAssist/issues/192)).
- **A dump or a restore takes minutes.** Parallel restore needs a seekable file, so the CLI would
  copy the dump into the container.
- **The ledger gets a least-privilege role**
  ([#34](https://github.com/NaimElijah/FinAssist/issues/34)). That role must exist before a
  restore that keeps owners and grants, and the recipe must create it.
- **The CLI gains logic that can be tested without Docker.** Give that logic tests.

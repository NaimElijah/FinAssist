# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Backs up, restores, and proves a restore of the ledger's and Keycloak's databases (ADR-038).

Run from the host, never inside the stack, with `uv run ops/backup/backup.py <command>`, or through
`make backup`. The PEP 723 block above tells `uv` which Python to use and that nothing need be
installed, so the same command works from PowerShell and from a POSIX shell.

Every database command runs inside that database's own container through `docker compose exec
-T`, so the PostgreSQL client is always the server's own major version. A dump never passes
through a shell on the host: this process hands an open binary file to the subprocess. That is
the reason this is Python and not a shell script. PowerShell's `>` re-encodes a native command's
output as text and would corrupt a custom-format dump.

Every Compose call names `docker-compose.yml` explicitly, from the repository root, so a stray
override file or a `COMPOSE_FILE` in the environment can never redirect one. For `backup` and
`restore`, the project is whatever that file and the environment resolve, `finassist` by default,
and it is printed before anything is read. `check` runs only in its own project,
`finassist-restore-check`, which it passes as `-p` on every call, so nothing in the environment can
point it at the owner's.

Standard library only. The report goes to stdout, one line at a time. It never contains an amount,
a name, an id, a token or a secret. Quoted and parenthesised spans of a PostgreSQL message are
withheld, because they can quote the books (see `_withheld`).
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import IO, Final

REPO_ROOT: Final = Path(__file__).resolve().parents[2]
COMPOSE_FILE: Final = REPO_ROOT / "docker-compose.yml"
# Compose's global option, so it precedes every subcommand. Without it, `up` writes its progress
# ("Network ... Creating") to stderr before its error, and `_withheld` reports only the first line.
QUIET_PROGRESS: Final = ("--progress", "quiet")

# Where a backup goes unless BACKUP_DIR says otherwise. `/backups/` is git-ignored, and the owner
# chose this place on 2026-10-13 (ADR-038). It sits on the same disk as the Docker volumes.
DEFAULT_BACKUP_ROOT: Final = REPO_ROOT / "backups"
BACKUP_DIR_ENV: Final = "BACKUP_DIR"
TIMESTAMP_FORMAT: Final = "%Y%m%dT%H%M%SZ"
MANIFEST_NAME: Final = "manifest.json"
MANIFEST_FORMAT: Final = 1
# Owner-only where the OS honours a POSIX mode. Windows ignores it: the folder inherits its parent
# folder's ACL, which is owner-only inside the user profile, where the default backups/ is.
OWNER_ONLY_DIRECTORY: Final = 0o700
OWNER_ONLY_FILE: Final = 0o600
HASH_CHUNK_BYTES: Final = 1024 * 1024
# How many lines of a failed PostgreSQL command's own output the report repeats.
MAX_REPORTED_LINES: Final = 5


@dataclass(frozen=True)
class Database:
    """One database this CLI backs up, and the Compose services around it.

    :param key: the name the dump file, the manifest and `--database` use.
    :param service: the Compose service that runs the database.
    :param consumer: the service that migrates or imports into it on start, and so must be
        stopped while it is restored.
    """

    key: str
    service: str
    consumer: str

    @property
    def dump_name(self) -> str:
        """The dump's file name inside a backup folder."""
        return f"{self.key}.dump"


LEDGER: Final = Database(key="ledger", service="db", consumer="ledger")
KEYCLOAK: Final = Database(key="keycloak", service="keycloak-db", consumer="keycloak")
DATABASES: Final = (LEDGER, KEYCLOAK)

# Each runs inside a database container, with that container's own POSTGRES_USER and POSTGRES_DB,
# so this process never needs either.
DUMP_COMMAND: Final = (
    "sh",
    "-c",
    'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom',
)
# Reads the whole archive and writes the SQL it would run to nowhere: every data block is
# decompressed, so a truncated or corrupt dump fails here and not on the day it is needed.
READ_BACK_COMMAND: Final = ("pg_restore", "-f", "/dev/null")
# One transaction, so a restore either lands whole or leaves the database empty; it also implies
# --exit-on-error. Owners and privileges are kept, so a target whose POSTGRES_USER differs from
# the source's fails loudly rather than restoring under the wrong role.
RESTORE_COMMAND: Final = (
    "sh",
    "-c",
    'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --single-transaction',
)
# SQL arrives on stdin, so psql interpolates `:'name'` variables (passed after the script as
# `-v name=value`) as quoted literals. `-X` ignores any psqlrc, `-A -t` prints bare `|`-separated
# rows, and ON_ERROR_STOP turns an SQL error into a non-zero exit.
PSQL_COMMAND: Final = (
    "sh",
    "-c",
    'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -A -t -q -v ON_ERROR_STOP=1 "$@"',
    "psql",
)
# The books as one digest: every row of the accounts and the journal as text, prefixed with its
# table, sorted byte by byte and hashed, so two databases holding the same books agree whatever
# their physical order. Only the digest leaves the database, never an amount. Every column is
# included, so any change to a row shows, and the date style is pinned so a date reads the same in
# both databases.
BOOKS_PRESENT_SQL: Final = "SELECT to_regclass('accounting.journal_entry') IS NOT NULL;"
BOOKS_DIGEST_SQL: Final = """
SET datestyle = 'ISO, YMD';
SELECT encode(sha256(convert_to(
    coalesce(string_agg(book_row, chr(10) ORDER BY book_row COLLATE "C"), ''), 'UTF8')), 'hex')
FROM (
    SELECT 'account|' || a::text AS book_row FROM accounting.account AS a
    UNION ALL SELECT 'transaction|' || t::text FROM accounting.journal_transaction AS t
    UNION ALL SELECT 'entry|' || e::text FROM accounting.journal_entry AS e
    UNION ALL SELECT 'state|' || s::text FROM accounting.journal_transaction_state AS s
) AS books;
"""
BOOKS_DIGEST_KEY: Final = "books_sha256"

# `docker compose ps --status` matches one state exactly, so "not stopped" is a list of them.
RUNNING_STATES: Final = ("running",)
UNSTOPPED_STATES: Final = ("running", "restarting", "paused")
COUNT_TABLES_SQL: Final = (
    "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');"
)

# --- `check` only. It runs in a Compose project of its own, never the owner's, on ports of its own,
# so it can run while the owner's stack is up. It starts db, keycloak-db, Keycloak and the ledger.
CHECK_PROJECT: Final = "finassist-restore-check"
OWNER_PROJECT: Final = "finassist"
LEDGER_CHECK_PORT: Final = 18080
KEYCLOAK_CHECK_PORT: Final = 18081
CHECK_PORTS: Final = (
    ("POSTGRES_PORT", "15432"),
    ("LEDGER_PORT", str(LEDGER_CHECK_PORT)),
    ("KEYCLOAK_PORT", str(KEYCLOAK_CHECK_PORT)),
    ("ASSISTANT_PORT", "18000"),
    ("GATEWAY_PORT", "18090"),
)
CHECK_HOST: Final = "127.0.0.1"
# Keycloak's own start_period is 240 s (docker-compose.yml), and the first run also builds both
# images.
CHECK_WAIT_SECONDS: Final = "600"
PROJECT_LABEL: Final = "com.docker.compose.project"
REQUEST_TIMEOUT_SECONDS: Final = 10
HTTP_OK: Final = 200

# Fixed by ops/keycloak/finassist-realm.json: the realm, and the owner's id, pinned so that the
# `sub` every journal row names survives a re-import (ADR-033).
REALM: Final = "finassist"
OWNER_ID: Final = "6c4a4be0-7c38-4145-845f-f4311ef64202"
ASSISTANT_CLIENT_ID: Final = "finassist-assistant"
ASSISTANT_CLIENT_SECRET_ENV: Final = "ASSISTANT_CLIENT_SECRET"
TOKEN_PATH: Final = f"/realms/{REALM}/protocol/openid-connect/token"
ACCOUNTS_PATH: Final = "/api/v1/accounts"

# Keycloak's own tables. Read before Keycloak starts, so a missing realm or owner cannot be hidden
# by the realm import that an empty or partial database would trigger.
OWNER_OTP_SQL: Final = """
SELECT c.id FROM credential c
JOIN user_entity u ON u.id = c.user_id
JOIN realm r ON r.id = u.realm_id
WHERE r.name = :'realm' AND u.id = :'owner' AND c.type = 'otp'
ORDER BY c.id;
"""
OWNER_PRESENT_SQL: Final = """
SELECT count(*) FROM user_entity u JOIN realm r ON r.id = u.realm_id
WHERE r.name = :'realm' AND u.id = :'owner';
"""
# V1_2's two checks, which must never be validated (ADR-033).
RECORDED_BY_CHECKS: Final = (
    "journal_transaction_recorded_by_shape",
    "journal_transaction_state_recorded_by_shape",
)
RECORDED_BY_SQL: Final = """
SELECT conname, convalidated FROM pg_constraint
WHERE conname IN (:'first', :'second') ORDER BY conname;
"""
FLYWAY_FAILED_SQL: Final = (
    "SELECT count(*), count(*) FILTER (WHERE NOT success) FROM public.flyway_schema_history;"
)
ACCOUNTS_SQL: Final = "SELECT id, currency FROM accounting.account ORDER BY id;"


class BackupError(Exception):
    """A step of a backup, restore or check that did not do what ADR-038 requires.

    Carries only a human-readable reason that is safe to print: never an amount, a name, an id,
    a secret, or a PostgreSQL message that has not been through `_withheld`.
    """


def _report(line: str) -> None:
    """Writes one line of this CLI's report to stdout, flushed at once.

    One stream on purpose, so the order of the lines is the report. The exit status, not the
    stream, says a run failed.
    """
    # T201 exists to keep output behind the services' logging boundary (ADR-019). This is an
    # operator CLI, not a service: the report on stdout is its whole product.
    print(line, flush=True)  # noqa: T201


# A quoted span, closed or not: a value can run past the end of a line, its tail on the next.
_QUOTED: Final = re.compile(r'"[^"]*(?:"|$)')
_PARENTHESISED: Final = re.compile(r"\([^)]*(?:\)|$)")
_UUID: Final = re.compile(r"\b[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}\b")
# A bare number may be an amount: a balance trigger's message gives a transaction's total unquoted.
_NUMBER: Final = re.compile(r"\d+(?:\.\d+)?")
# Lines PostgreSQL adds to explain an error. Each can quote a row: a key's value, the statement
# that failed, or the line of input it failed on.
_EXPLANATION_MARKERS: Final = ("DETAIL:", "CONTEXT:", "HINT:", "Command was:")
# How a line a PostgreSQL tool writes itself begins. Any other line after the first continues the
# one before it: the statement after "Command was:", a quoted value's tail, psql's caret.
_MESSAGE_PREFIXES: Final = ("pg_dump:", "pg_restore:", "psql:", "ERROR:", "FATAL:", "WARNING:")


def _withheld(stderr: bytes) -> list[str]:
    """The first lines of a failed command's error output, with anything that can quote the
    books removed, in ASCII.

    PostgreSQL quotes an offending value inside a message (`invalid input syntax for type
    numeric: "..."`), puts a key's value in parentheses (`Key (id)=(...)`), and names a total
    unquoted (`its entries sum to 4876.55`). So:

    - explanation lines are dropped, and so is any line after the first that a tool did not begin
      itself, since it continues the line before;
    - every quoted or parenthesised span, closed or not, is replaced, then every UUID, then every
      number.

    What remains is the kind of failure, which is what a person needs to decide what to do next.
    """
    lines: list[str] = []
    for raw in stderr.decode("utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or any(marker in line for marker in _EXPLANATION_MARKERS):
            continue
        if lines and not line.startswith(_MESSAGE_PREFIXES):
            continue
        line = _QUOTED.sub('"..."', line)
        line = _PARENTHESISED.sub("(...)", line)
        line = _NUMBER.sub("#", _UUID.sub("<id>", line))
        lines.append(line.encode("ascii", errors="replace").decode("ascii"))
        if len(lines) == MAX_REPORTED_LINES:
            break
    return lines


def _require(
    what: str,
    command: list[str],
    *,
    environment: dict[str, str] | None = None,
    stdin: IO[bytes] | None = None,
    stdout: IO[bytes] | None = None,
    input_bytes: bytes | None = None,
) -> bytes:
    """Runs one command from the repository root and returns its captured stdout.

    Output not sent to `stdout` is captured, never inherited, so nothing a container prints
    reaches the report unless it has been through `_withheld`.

    :param what: names the step in the error, such as "pg_dump of ledger".
    :raises BackupError: if the command exits non-zero, with its output through `_withheld`.
    """
    result = subprocess.run(
        command,
        cwd=REPO_ROOT,
        env=environment,
        stdin=stdin,
        input=input_bytes,
        stdout=stdout if stdout is not None else subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = "; ".join(_withheld(result.stderr)) or "no output"
        raise BackupError(f"{what} failed with exit status {result.returncode}: {detail}")
    return result.stdout or b""


@dataclass(frozen=True)
class Compose:
    """Runs `docker compose` against this repository's file, and nothing else.

    :param project: passed as `-p` when given. When omitted, the project is whatever
        `docker-compose.yml` and the environment resolve (`finassist` by default).
    :param environment: added to this process's environment for every call, such as the moved
        ports `check` runs on.
    """

    project: str | None = None
    environment: tuple[tuple[str, str], ...] = ()

    def _command(self, *args: str) -> list[str]:
        command = ["docker", "compose", *QUIET_PROGRESS, "-f", str(COMPOSE_FILE)]
        if self.project is not None:
            command += ["-p", self.project]
        return [*command, *args]

    def require(
        self,
        what: str,
        *args: str,
        stdin: IO[bytes] | None = None,
        stdout: IO[bytes] | None = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        """Runs one `docker compose` command and returns its captured stdout.

        :param what: names the step in the error, such as "pg_dump of ledger".
        :raises BackupError: if the command exits non-zero, with its output through `_withheld`.
        """
        return _require(
            what,
            self._command(*args),
            environment=os.environ | dict(self.environment),
            stdin=stdin,
            stdout=stdout,
            input_bytes=input_bytes,
        )

    def resolved(self) -> dict[str, object]:
        """The Compose model as Compose resolves it, `.env` interpolated.

        It holds every secret `.env` sets, so nothing from it is ever reported.
        """
        model = json.loads(
            self.require("resolving the Compose model", "config", "--format", "json")
        )
        if not isinstance(model, dict):
            raise BackupError("docker compose config did not answer a Compose model")
        return model

    def project_name(self) -> str:
        """The project every other call acts on, as Compose itself resolves it."""
        name = self.resolved().get("name")
        if not isinstance(name, str) or not name:
            raise BackupError("docker compose config named no project")
        return name

    def running_services(self) -> set[str]:
        """The services of this project that have a running container."""
        return self._services_in(RUNNING_STATES)

    def unstopped_services(self) -> set[str]:
        """The services of this project whose container is running, restarting or paused.

        A service under `restart: unless-stopped` that crashes on start shows as `restarting`
        between attempts, and an attempt can still migrate or import into a database.
        """
        return self._services_in(UNSTOPPED_STATES)

    def _services_in(self, states: tuple[str, ...]) -> set[str]:
        filters = [argument for state in states for argument in ("--status", state)]
        listed = self.require("listing services", "ps", *filters, "--services")
        return set(listed.decode("utf-8").split())

    def psql(self, database: Database, sql: str, *variables: tuple[str, str]) -> list[list[str]]:
        """Runs `sql` in `database`'s own container and returns its rows, each a list of fields.

        :param variables: psql variables, each read in `sql` as `:'name'`, so a value is quoted
            by psql rather than built into the statement.
        """
        arguments = [
            argument for name, value in variables for argument in ("-v", f"{name}={value}")
        ]
        output = self.require(
            f"a query in {database.service}",
            "exec",
            "-T",
            database.service,
            *PSQL_COMMAND,
            *arguments,
            input_bytes=sql.encode("utf-8"),
        )
        return [line.split("|") for line in output.decode("utf-8").splitlines() if line]

    def table_count(self, database: Database) -> int:
        """How many tables `database` holds outside PostgreSQL's own catalogs."""
        return int(self.psql(database, COUNT_TABLES_SQL)[0][0])

    def image_of(self, service: str) -> str:
        """The image `service`'s running container was started from."""
        listed = self.require(f"inspecting {service}", "ps", "--format", "json", service)
        for container in _containers(listed.decode("utf-8")):
            if container.get("Service") == service and isinstance(container.get("Image"), str):
                return str(container["Image"])
        raise BackupError(f"no running container found for {service}")


def _containers(text: str) -> list[dict[str, object]]:
    """The containers `docker compose ps --format json` listed: one JSON object per line, or one
    array, depending on the Compose version."""
    text = text.strip()
    if not text:
        return []
    if text.startswith("["):
        listed = json.loads(text)
        return [container for container in listed if isinstance(container, dict)]
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def _books_digest(compose: Compose) -> str | None:
    """The digest of the ledger database's books, or None when it holds no journal yet."""
    if compose.psql(LEDGER, BOOKS_PRESENT_SQL)[0][0] != "t":
        return None
    return compose.psql(LEDGER, BOOKS_DIGEST_SQL)[0][0]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(HASH_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def _dump(compose: Compose, database: Database, folder: Path) -> dict[str, object]:
    """Dumps `database` into `folder`, reads the dump back in full, and describes it.

    :return: the dump's manifest entry.
    :raises BackupError: if pg_dump fails, writes nothing, or writes an archive pg_restore cannot
        read to the end.
    """
    path = folder / database.dump_name
    with path.open("xb") as dump:
        path.chmod(OWNER_ONLY_FILE)
        compose.require(
            f"pg_dump of {database.key}", "exec", "-T", database.service, *DUMP_COMMAND, stdout=dump
        )
    size = path.stat().st_size
    if size == 0:
        raise BackupError(f"pg_dump of {database.key} wrote nothing")
    with path.open("rb") as dump:
        compose.require(
            f"reading back {database.dump_name}",
            "exec",
            "-T",
            database.service,
            *READ_BACK_COMMAND,
            stdin=dump,
        )
    if compose.table_count(database) == 0:
        _report(
            f"NOTE: {database.service} holds no table yet, "
            f"so {database.dump_name} restores an empty database"
        )
    return {
        "database": database.key,
        "file": database.dump_name,
        "bytes": size,
        "sha256": _sha256(path),
        "service": database.service,
        "image": compose.image_of(database.service),
    }


def backup(compose: Compose, root: Path, now: datetime) -> Path:
    """Dumps every database into a new folder under `root`, named for `now` in UTC.

    Both database containers must already be running: this never starts one, because `up` in the
    owner's project could recreate a database under a running ledger.

    Whatever is raised once the folder exists, an interrupt included, is re-raised unchanged after
    the partial folder is removed.

    :return: the new backup folder.
    :raises BackupError: if a Compose command fails, a database is not running, a dump is empty or
        does not read back, or the books change while they are being backed up.
    :raises OSError: if `root`, the folder or a file in it cannot be created or written:
        `FileExistsError` when a backup was already taken under `root` in the same second.
    """
    project = compose.project_name()
    _report(f"Compose project: {project}")
    running = compose.running_services()
    stopped = [database.service for database in DATABASES if database.service not in running]
    if stopped:
        services = " ".join(stopped)
        raise BackupError(
            f"{services} not running in project {project}; "
            f"start with `docker compose up -d --wait {services}`"
        )
    root.mkdir(parents=True, exist_ok=True)
    folder = root / now.astimezone(UTC).strftime(TIMESTAMP_FORMAT)
    folder.mkdir(mode=OWNER_ONLY_DIRECTORY)
    try:
        # Taken before and after the dumps: when the two agree, nothing was written to the books
        # in between, so the digest describes exactly the books ledger.dump holds.
        books = _books_digest(compose)
        entries = []
        for database in DATABASES:
            entries.append(_dump(compose, database, folder))
            _report(f"{database.dump_name}: written and read back in full")
        if _books_digest(compose) != books:
            raise BackupError(
                "the books changed while they were being backed up, so the dump and the digest "
                "recorded beside it could disagree; take the backup again"
            )
        manifest = {
            "format": MANIFEST_FORMAT,
            "created": now.astimezone(UTC).strftime(TIMESTAMP_FORMAT),
            "project": project,
            BOOKS_DIGEST_KEY: books,
            "dumps": entries,
        }
        manifest_path = folder / MANIFEST_NAME
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        manifest_path.chmod(OWNER_ONLY_FILE)
    except BaseException:
        shutil.rmtree(folder)
        raise
    return folder


def _read_manifest(folder: Path) -> dict[str, object]:
    """The manifest `backup` wrote into `folder`.

    :raises BackupError: if `folder` holds no manifest this CLI could have written.
    """
    path = folder / MANIFEST_NAME
    if not path.is_file():
        raise BackupError(f"{folder} holds no {MANIFEST_NAME}, so it is not a backup this made")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            raise TypeError
        if manifest["format"] != MANIFEST_FORMAT:
            raise BackupError(f"{path} is format {manifest['format']}, not {MANIFEST_FORMAT}")
        return manifest
    # ValueError covers both JSONDecodeError and the UnicodeDecodeError of a manifest re-saved in
    # another encoding, such as PowerShell 5.1's UTF-16.
    except (ValueError, KeyError, TypeError) as error:
        raise BackupError(f"{path} is not a manifest this made ({type(error).__name__})") from None


def _manifest_entries(folder: Path) -> dict[str, dict[str, object]]:
    """The dumps the manifest in `folder` records, one entry per database key.

    :raises BackupError: if `folder` holds no manifest this CLI could have written.
    """
    manifest = _read_manifest(folder)
    try:
        dumps = manifest["dumps"]
        if not isinstance(dumps, list):
            raise TypeError
        return {str(entry["database"]): entry for entry in dumps}
    except (KeyError, TypeError) as error:
        path = folder / MANIFEST_NAME
        raise BackupError(f"{path} is not a manifest this made ({type(error).__name__})") from None


def _recorded_books_digest(folder: Path) -> str | None:
    """The digest of the books the manifest in `folder` records, or None when the backup was
    taken before the ledger had any journal.

    :raises BackupError: if the manifest records something that is not a digest.
    """
    digest = _read_manifest(folder).get(BOOKS_DIGEST_KEY)
    if digest is not None and not isinstance(digest, str):
        raise BackupError(f"{folder / MANIFEST_NAME} records no usable {BOOKS_DIGEST_KEY}")
    return digest


def _verify(folder: Path, database: Database, entries: dict[str, dict[str, object]]) -> Path:
    """Checks `database`'s dump in `folder` against the SHA-256 its manifest recorded.

    :return: the dump's path.
    :raises BackupError: if the manifest names no such dump, or the file is missing or changed.
    """
    entry = entries.get(database.key)
    path = folder / database.dump_name
    if entry is None or not path.is_file():
        raise BackupError(f"{folder} holds no {database.dump_name} that its manifest records")
    if _sha256(path) != entry.get("sha256"):
        raise BackupError(
            f"{database.dump_name} no longer matches the SHA-256 its manifest recorded, "
            "so it changed after the backup and is not restored"
        )
    return path


def restore(compose: Compose, folder: Path, databases: tuple[Database, ...]) -> None:
    """Restores each of `databases` from the backup in `folder`, each into an empty database.

    Nothing is restored until every precondition holds for every database asked for:

    - each dump matches its manifest;
    - the service that uses each database is stopped, since under `restart: unless-stopped` it
      would migrate or import into the empty database first;
    - each database is running, and holds no table.

    Each restore is one transaction, so a database is left either restored whole or empty.

    :raises BackupError: naming the first precondition that does not hold, or the restore that
        failed.
    """
    entries = _manifest_entries(folder)
    dumps = {database: _verify(folder, database, entries) for database in databases}
    _report(f"{', '.join(path.name for path in dumps.values())}: match the manifest")
    project = compose.project_name()
    _report(f"Compose project: {project}")
    unstopped = compose.unstopped_services()
    busy = " ".join(db.consumer for db in databases if db.consumer in unstopped)
    if busy:
        raise BackupError(
            f"{busy} {'are' if ' ' in busy else 'is'} not stopped in project {project} (running, "
            "restarting or paused), and would migrate or import into an empty database; "
            f"stop first with `docker compose stop {busy}`"
        )
    running = compose.running_services()
    stopped = " ".join(db.service for db in databases if db.service not in running)
    if stopped:
        raise BackupError(
            f"{stopped} not running in project {project}; "
            f"start with `docker compose up -d --wait {stopped}`"
        )
    for database in databases:
        tables = compose.table_count(database)
        if tables:
            raise BackupError(
                f"{database.service} in project {project} already holds {tables} tables; "
                "a restore writes only into an empty database (ADR-038)"
            )
    for database, path in dumps.items():
        with path.open("rb") as dump:
            compose.require(
                f"pg_restore of {path.name}",
                "exec",
                "-T",
                database.service,
                *RESTORE_COMMAND,
                stdin=dump,
            )
        _report(f"{path.name}: restored into {database.service}")
    if LEDGER in databases:
        _require_books_unchanged(compose, folder)


def _require_books_unchanged(compose: Compose, folder: Path) -> None:
    """The restored books are the ones backed up: the same digest, so the same rows, and so every
    balance the ledger derives from them reads back unchanged.

    :raises BackupError: if the digests differ; the ledger must not be started on such books.
    """
    expected = _recorded_books_digest(folder)
    if expected is None:
        _report(
            "NOTE: the backup was taken before the ledger had a journal, so no digest is compared"
        )
        return
    if _books_digest(compose) != expected:
        raise BackupError(
            "the restored books do not match the digest the backup recorded; "
            "do not start the ledger on them"
        )
    _report("the restored books match the digest the backup recorded")


class _CheckRun:
    """Numbers and reports the checks `check` runs, in the order it runs them."""

    def __init__(self) -> None:
        self.passed = 0
        self.current = "set-up"

    def set_up(self, name: str) -> None:
        """Names a step that is not a check, so a failure in it is not blamed on the last one."""
        self.current = f"set-up ({name})"

    def check[**P, R](
        self, name: str, step: Callable[P, R], /, *args: P.args, **kwargs: P.kwargs
    ) -> R:
        """Runs one check, reports it passed, and returns what it returned.

        :raises Exception: whatever `step` raised, unreported: `check` reports it against
            `current`, which already names this check.
        """
        number = self.passed + 1
        self.current = f"check {number} ({name})"
        result = step(*args, **kwargs)
        _report(f"{number}. {name}: PASS")
        self.passed = number
        return result


def _isolated(project: str) -> Compose:
    """The one `Compose` `check` may use: `project`, on the check's own ports.

    :raises BackupError: if `project` is the owner's, so a rename of `CHECK_PROJECT` can never
        point the check at the owner's books.
    """
    if project == OWNER_PROJECT:
        raise BackupError(f"the restore check must never run as {OWNER_PROJECT}")
    return Compose(project=project, environment=CHECK_PORTS)


def _clean_up(compose: Compose) -> None:
    """Removes the check's containers and volumes, and nothing else.

    Volumes are found by Compose's own project label, and each name must start with the check's
    project, so this can never remove the owner's `finassist_db-data`. Never `down -v`.
    """
    compose.require("removing the check's containers", "down", "--remove-orphans")
    listed = _require(
        "listing the check's volumes",
        ["docker", "volume", "ls", "-q", "--filter", f"label={PROJECT_LABEL}={CHECK_PROJECT}"],
    )
    volumes = listed.decode("utf-8").split()
    foreign = [volume for volume in volumes if not volume.startswith(f"{CHECK_PROJECT}_")]
    if foreign:
        raise BackupError(f"refusing to remove volumes outside {CHECK_PROJECT}: {foreign}")
    if volumes:
        _require("removing the check's volumes", ["docker", "volume", "rm", *volumes])


def _owner_otp_credentials(compose: Compose) -> tuple[str, ...] | None:
    """The restored owner's OTP credential ids, read from Keycloak's own tables.

    :return: None when the restored identity store is empty, or holds no `finassist` realm with
        the pinned owner in it; otherwise the owner's OTP credential ids, possibly none.
    """
    if compose.table_count(KEYCLOAK) == 0:
        return None
    variables = (("realm", REALM), ("owner", OWNER_ID))
    if int(compose.psql(KEYCLOAK, OWNER_PRESENT_SQL, *variables)[0][0]) != 1:
        return None
    return tuple(row[0] for row in compose.psql(KEYCLOAK, OWNER_OTP_SQL, *variables))


def _verify_backup(folder: Path) -> None:
    """Both dumps in `folder` match its manifest, read before any container is started."""
    entries = _manifest_entries(folder)
    for database in DATABASES:
        _verify(folder, database, entries)


def _restore_both(compose: Compose, folder: Path) -> tuple[str, ...] | None:
    """Restores both dumps, then reads the owner's OTP credentials before Keycloak can start.

    :return: what `_owner_otp_credentials` found, for the identity checks at the end.
    """
    restore(compose, folder, DATABASES)
    return _owner_otp_credentials(compose)


def _start_ledger(compose: Compose) -> None:
    """Starts Keycloak and the ledger on the restored databases, and waits for both to be ready.

    A ledger that becomes ready has validated the restored Flyway history and applied anything
    newer, so a migration that cannot run against these books fails here.
    """
    compose.require(
        "starting Keycloak and the ledger",
        "up",
        "-d",
        "--wait",
        "--wait-timeout",
        CHECK_WAIT_SECONDS,
        "--build",
        "ledger",
    )
    applied, failed = compose.psql(LEDGER, FLYWAY_FAILED_SQL)[0]
    if int(applied) == 0 or int(failed) != 0:
        raise BackupError(f"Flyway's history holds {applied} migrations, {failed} of them failed")


def _recorded_by_not_valid(compose: Compose) -> None:
    """Both of V1_2's `recorded_by` checks exist and are still NOT VALID (ADR-033)."""
    first, second = RECORDED_BY_CHECKS
    rows = compose.psql(LEDGER, RECORDED_BY_SQL, ("first", first), ("second", second))
    found = {row[0]: row[1] for row in rows}
    missing = [name for name in RECORDED_BY_CHECKS if name not in found]
    if missing:
        raise BackupError(f"missing: {', '.join(missing)}")
    validated = [name for name, flag in found.items() if flag != "f"]
    if validated:
        raise BackupError(f"validated, which ADR-033 forbids: {', '.join(validated)}")


def _request(
    port: int,
    method: str,
    path: str,
    *,
    token: str | None = None,
    form: dict[str, str] | None = None,
) -> tuple[int, bytes]:
    """Sends one HTTP request to the check's own published port and returns (status, body)."""
    headers: dict[str, str] = {}
    body = None
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        body = urllib.parse.urlencode(form).encode("ascii")
    connection = http.client.HTTPConnection(CHECK_HOST, port, timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def _assistant_token(compose: Compose) -> str:
    """A client-credentials token from the restored Keycloak, with the assistant's read-only role.

    The secret is the one `.env` gives Keycloak, read from the resolved Compose model. A restored
    Keycloak keeps the secret it imported originally, so a mismatch means `.env` changed since.
    """
    services = compose.resolved().get("services")
    keycloak = services.get("keycloak") if isinstance(services, dict) else None
    environment = keycloak.get("environment") if isinstance(keycloak, dict) else None
    secret = environment.get(ASSISTANT_CLIENT_SECRET_ENV) if isinstance(environment, dict) else None
    if not isinstance(secret, str) or not secret:
        raise BackupError(f"{ASSISTANT_CLIENT_SECRET_ENV} is not set for Keycloak")
    status, body = _request(
        KEYCLOAK_CHECK_PORT,
        "POST",
        TOKEN_PATH,
        form={
            "grant_type": "client_credentials",
            "client_id": ASSISTANT_CLIENT_ID,
            "client_secret": secret,
        },
    )
    if status != HTTP_OK:
        raise BackupError(
            f"Keycloak refused the assistant's client credentials with {status}; the restored "
            f"realm keeps the secret it was given, so {ASSISTANT_CLIENT_SECRET_ENV} in .env may "
            "have changed since the backup"
        )
    token = json.loads(body).get("access_token")
    if not isinstance(token, str) or not token:
        raise BackupError("Keycloak's token response carried no access_token")
    return token


def _accounts_served(compose: Compose) -> None:
    """The ledger lists exactly the accounts the restored books hold, and answers each one's
    balance in that account's currency. Reports counts, never an id, a name or an amount."""
    held = {row[0]: row[1] for row in compose.psql(LEDGER, ACCOUNTS_SQL)}
    if not held:
        raise BackupError("the restored books hold no account, so there is nothing to prove")
    token = _assistant_token(compose)
    status, body = _request(LEDGER_CHECK_PORT, "GET", ACCOUNTS_PATH, token=token)
    if status != HTTP_OK:
        raise BackupError(f"GET {ACCOUNTS_PATH} answered {status}")
    listed = {str(account.get("id")) for account in json.loads(body)}
    if listed != set(held):
        raise BackupError(
            f"the ledger lists {len(listed)} accounts and the database holds {len(held)}, "
            "and the two sets differ"
        )
    for account, currency in held.items():
        path = f"{ACCOUNTS_PATH}/{urllib.parse.quote(account, safe='')}/balance"
        status, body = _request(LEDGER_CHECK_PORT, "GET", path, token=token)
        if status != HTTP_OK:
            raise BackupError(f"an account's balance answered {status}")
        balance = json.loads(body).get("balance") or {}
        if balance.get("currency") != currency:
            raise BackupError("an account's balance came back in another currency than its own")
    _report(f"   {len(held)} accounts listed, each balance answered in its own currency")


def _owner_has_otp(before: tuple[str, ...] | None) -> None:
    """The restored identity store holds the pinned owner with an OTP credential."""
    if before is None:
        raise BackupError(
            f"keycloak.dump restores no {REALM} realm holding the pinned owner, so this backup "
            "brings back no identity; was it taken before Keycloak first ran?"
        )
    if not before:
        raise BackupError(
            "the restored owner has no OTP credential, so a sign-in would demand a fresh "
            "enrolment; was this backup taken before the owner's first sign-in?"
        )


def _otp_unchanged(compose: Compose, before: tuple[str, ...] | None) -> None:
    """The owner's OTP credentials are the same ones after Keycloak started on the restored
    database, which proves the realm import was skipped rather than re-creating the owner."""
    if _owner_otp_credentials(compose) != before:
        raise BackupError("the owner's OTP credentials changed when Keycloak started")


def _clean_up_reported(compose: Compose) -> bool:
    """`_clean_up`, reporting rather than raising, for the end of a check however it ended.

    :return: whether the check's containers and volumes are gone.
    """
    try:
        _clean_up(compose)
    except (BackupError, OSError) as failure:
        _report(f"FAILED clean-up of {CHECK_PROJECT}: {failure}")
        return False
    return True


def _run_checks(run: _CheckRun, compose: Compose, folder: Path) -> int:
    """Restores the backup into the check's project and runs every check, stopping at the first
    failure.

    :return: 0 when every check passed, else 1, with the failure already reported.
    """
    try:
        # The backup is read first, so a mistyped folder or a changed dump fails here, before a
        # single database has been started for it.
        run.check("both dumps match their manifest", _verify_backup, folder)
        run.set_up("a clean project with db and keycloak-db started")
        _clean_up(compose)
        compose.require(
            "starting db and keycloak-db",
            "up",
            "-d",
            "--wait",
            "--wait-timeout",
            CHECK_WAIT_SECONDS,
            "db",
            "keycloak-db",
        )
        before = run.check(
            "both dumps restore, the books matching the backup's digest",
            _restore_both,
            compose,
            folder,
        )
        _report("Starting Keycloak and the ledger; the first run builds both images")
        run.check("the ledger is ready on the restored Flyway history", _start_ledger, compose)
        run.check(
            "both recorded_by checks exist and are NOT VALID", _recorded_by_not_valid, compose
        )
        run.check("the ledger serves every account the books hold", _accounts_served, compose)
        run.check("the restored owner has an OTP credential", _owner_has_otp, before)
        run.check(
            "the owner's credentials survive Keycloak's start", _otp_unchanged, compose, before
        )
    except BackupError as failure:
        _report(f"FAILED {run.current}: {failure}")
        return 1
    except OSError as error:
        _report(f"FAILED {run.current}: {type(error).__name__}: {error}")
        return 1
    except Exception as error:
        # Last resort, so every failure still names its check. The type alone: a message could
        # quote a row or a response body.
        _report(f"FAILED {run.current}: unexpected {type(error).__name__}")
        return 1
    _report(f"{run.passed}/{run.passed} checks passed")
    return 0


def check(folder: Path) -> int:
    """Restores the backup in `folder` into a project of its own and proves the stack comes back.

    Every check after the restore runs whatever the identity store holds, so a backup that brings
    back the books but not the owner says exactly that. The check's containers and volumes are
    removed before it starts and after it ends, however it ends, interrupted included.

    :return: the process exit status: 0 when every check passed and clean-up succeeded, else 1.
    """
    compose = _isolated(CHECK_PROJECT)
    _report(f"Compose project: {CHECK_PROJECT}, on ports of its own; the owner's is untouched")
    status = 1
    try:
        status = _run_checks(_CheckRun(), compose, folder)
    finally:
        cleaned = _clean_up_reported(compose)
    return status if cleaned else 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="backup.py",
        description="Back up the ledger's and Keycloak's databases (ADR-038).",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "backup",
        help=f"dump both databases into ${BACKUP_DIR_ENV}, or backups/ by default",
    )
    restore_parser = commands.add_parser(
        "restore",
        help="restore a backup into empty databases, with the ledger and Keycloak stopped",
    )
    restore_parser.add_argument("folder", type=Path, help="a folder `backup` wrote")
    restore_parser.add_argument(
        "--database",
        choices=[database.key for database in DATABASES],
        help="restore this database alone, such as keycloak to undo a realm re-import",
    )
    check_parser = commands.add_parser(
        "check",
        help=f"prove a backup restores, in the isolated Compose project {CHECK_PROJECT}",
    )
    check_parser.add_argument("folder", type=Path, help="a folder `backup` wrote")
    return parser


def main(argv: list[str], now: datetime) -> int:
    """Runs one command, reading the clock nowhere but here.

    :return: the process exit status: 0 when the command did everything it promises, else 1.
    """
    arguments = _parser().parse_args(argv)
    try:
        if arguments.command == "backup":
            root = Path(os.environ.get(BACKUP_DIR_ENV) or DEFAULT_BACKUP_ROOT)
            folder = backup(Compose(), root, now)
            _report(f"Backup complete: {folder}")
        elif arguments.command == "restore":
            chosen = tuple(db for db in DATABASES if arguments.database in (None, db.key))
            restore(Compose(), arguments.folder, chosen)
            _report("Restore complete; start the stack with `docker compose up -d`")
        elif arguments.command == "check":
            return check(arguments.folder)
    except BackupError as failure:
        _report(f"FAILED: {failure}")
        return 1
    except OSError as error:
        # A missing `docker` executable or an unwritable folder: the type and errno are enough,
        # and a path is the only thing its message can quote.
        _report(f"FAILED: {type(error).__name__}: {error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], datetime.now(UTC)))

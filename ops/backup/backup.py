# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Backs up and restores the ledger's and Keycloak's databases, each on its own (ADR-038).

Run from the host, never inside the stack, with `uv run ops/backup/backup.py <command>`, or through
`make backup`. The PEP 723 block above tells `uv` which Python to use and that nothing need be
installed, so the same command works from PowerShell and from a POSIX shell.

Every database command runs inside that database's own container through `docker compose exec
-T`, so the PostgreSQL client is always the server's own major version. A dump never passes
through a shell on the host: this process hands an open binary file to the subprocess. That is
the reason this is Python and not a shell script. PowerShell's `>` re-encodes a native command's
output as text and would corrupt a custom-format dump.

Every Compose call names `docker-compose.yml` explicitly, from the repository root, so a stray
override file or a `COMPOSE_FILE` in the environment can never redirect one. The project is
whatever that file and the environment resolve, `finassist` by default, and it is printed before
anything is read.

Standard library only. The report goes to stdout, one line at a time. It never contains an amount,
a name, an id, a token or a secret. Quoted and parenthesised spans of a PostgreSQL message are
withheld, because they can quote the books (see `_withheld`).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import IO, Final

REPO_ROOT: Final = Path(__file__).resolve().parents[2]
COMPOSE_FILE: Final = REPO_ROOT / "docker-compose.yml"

# Where a backup goes unless BACKUP_DIR says otherwise. `/backups/` is git-ignored, and the owner
# chose this place on 2026-10-13 (ADR-038). It sits on the same disk as the Docker volumes.
DEFAULT_BACKUP_ROOT: Final = REPO_ROOT / "backups"
BACKUP_DIR_ENV: Final = "BACKUP_DIR"
TIMESTAMP_FORMAT: Final = "%Y%m%dT%H%M%SZ"
MANIFEST_NAME: Final = "manifest.json"
MANIFEST_FORMAT: Final = 1
# Owner-only where the OS honours a POSIX mode. Windows ignores it, and the folder inherits the
# user profile's permissions instead.
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
COUNT_TABLES_SQL: Final = (
    "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');"
)


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


_QUOTED: Final = re.compile(r'"[^"]*"')
_PARENTHESISED: Final = re.compile(r"\([^)]*\)")
# Lines PostgreSQL adds to explain an error. Each can quote a row: a key's value, the statement
# that failed, or the line of input it failed on.
_EXPLANATION_MARKERS: Final = ("DETAIL:", "CONTEXT:", "HINT:", "Command was:")
_QUOTED_INPUT_LINE: Final = re.compile(r"^(?:\S+: )?LINE \d+:")


def _withheld(stderr: bytes) -> list[str]:
    """The first lines of a PostgreSQL tool's error output, with anything that can quote data
    removed.

    PostgreSQL quotes the offending value inside a message (`invalid input syntax for type
    numeric: "..."`) and a key's value inside parentheses (`Key (id)=(...)`). So every quoted and
    parenthesised span is replaced, explanation lines are dropped, and what remains is the kind of
    failure, which is what a person needs to decide what to do next.
    """
    lines = []
    for raw in stderr.decode("utf-8", errors="replace").splitlines():
        line = raw.strip()
        explains = any(marker in line for marker in _EXPLANATION_MARKERS)
        if not line or explains or _QUOTED_INPUT_LINE.match(line):
            continue
        line = _PARENTHESISED.sub("(...)", _QUOTED.sub('"..."', line))
        lines.append(line.encode("ascii", errors="replace").decode("ascii"))
        if len(lines) == MAX_REPORTED_LINES:
            break
    return lines


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
        command = ["docker", "compose", "-f", str(COMPOSE_FILE)]
        if self.project is not None:
            command += ["-p", self.project]
        return [*command, *args]

    def run(
        self,
        *args: str,
        stdin: IO[bytes] | None = None,
        stdout: IO[bytes] | None = None,
        input_bytes: bytes | None = None,
    ) -> subprocess.CompletedProcess[bytes]:
        """Runs one `docker compose` command and returns its result, whatever its exit status.

        Output not sent to `stdout` is captured, never inherited, so nothing a container prints
        reaches the report unless a caller passes it through `_withheld`.
        """
        return subprocess.run(
            self._command(*args),
            cwd=REPO_ROOT,
            env=os.environ | dict(self.environment),
            stdin=stdin,
            input=input_bytes,
            stdout=stdout if stdout is not None else subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

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
        result = self.run(*args, stdin=stdin, stdout=stdout, input_bytes=input_bytes)
        if result.returncode != 0:
            detail = "; ".join(_withheld(result.stderr)) or "no output"
            raise BackupError(f"{what} failed with exit status {result.returncode}: {detail}")
        return result.stdout or b""

    def project_name(self) -> str:
        """The project every other call acts on, as Compose itself resolves it."""
        resolved = json.loads(
            self.require("resolving the Compose project", "config", "--format", "json")
        )
        name = resolved.get("name")
        if not isinstance(name, str) or not name:
            raise BackupError("docker compose config named no project")
        return name

    def running_services(self) -> set[str]:
        """The services of this project that have a running container."""
        listed = self.require("listing running services", "ps", "--status", "running", "--services")
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
        text = listed.decode("utf-8").strip()
        # One JSON object per line, or one array, depending on the Compose version.
        containers = (
            json.loads(text)
            if text.startswith("[")
            else [json.loads(line) for line in text.splitlines()]
        )
        for container in containers:
            if container.get("Service") == service and isinstance(container.get("Image"), str):
                return str(container["Image"])
        raise BackupError(f"no running container found for {service}")


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

    :return: the new backup folder.
    :raises BackupError: on any failure, after removing the partial folder.
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
        entries = []
        for database in DATABASES:
            entries.append(_dump(compose, database, folder))
            _report(f"{database.dump_name}: written and read back in full")
        manifest = {
            "format": MANIFEST_FORMAT,
            "created": now.astimezone(UTC).strftime(TIMESTAMP_FORMAT),
            "project": project,
            "dumps": entries,
        }
        manifest_path = folder / MANIFEST_NAME
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        manifest_path.chmod(OWNER_ONLY_FILE)
    except BaseException:
        shutil.rmtree(folder)
        raise
    return folder


def _manifest_entries(folder: Path) -> dict[str, dict[str, object]]:
    """The manifest `backup` wrote into `folder`, as one entry per database key.

    :raises BackupError: if `folder` holds no manifest this CLI could have written.
    """
    path = folder / MANIFEST_NAME
    if not path.is_file():
        raise BackupError(f"{folder} holds no {MANIFEST_NAME}, so it is not a backup this made")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        if manifest["format"] != MANIFEST_FORMAT:
            raise BackupError(f"{path} is format {manifest['format']}, not {MANIFEST_FORMAT}")
        return {str(entry["database"]): entry for entry in manifest["dumps"]}
    except (json.JSONDecodeError, KeyError, TypeError) as error:
        raise BackupError(f"{path} is not a manifest this made ({type(error).__name__})") from None


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
    running = compose.running_services()
    busy = " ".join(db.consumer for db in databases if db.consumer in running)
    if busy:
        raise BackupError(
            f"{busy} {'are' if ' ' in busy else 'is'} running in project {project}, and would "
            "migrate or import into an empty database; "
            f"stop first with `docker compose stop {busy}`"
        )
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

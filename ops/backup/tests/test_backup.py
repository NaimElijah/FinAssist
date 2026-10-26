"""The parts of `ops/backup/backup.py` that need no Docker (ADR-038).

Everything that reaches a database or a container is proved by running the CLI against real ones:
`check` is that proof for a backup. What is here is the logic a mistake in which would go unseen
there: the redaction every reported PostgreSQL message passes through, the manifest a restore
trusts, the parsing of `docker compose ps`, the guard that keeps `check` off the owner's project,
and, against a Compose project held in memory, what restore, backup and the CLI do with it.
`conftest.py` keeps every test off a real `docker`.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import IO

import backup
import pytest

AMOUNT = "4876.55"
ACCOUNT_ID = "0b7e1c2a-0000-4000-8000-000000000001"


def _withheld(*lines: str) -> list[str]:
    return backup._withheld("\n".join(lines).encode("utf-8"))


# --- _withheld: nothing that can quote the books survives -----------------------------------


def Given_a_quoted_value_When_withheld_Then_it_is_replaced() -> None:
    lines = _withheld(
        f'pg_restore: error: ERROR:  invalid input syntax for type numeric: "{AMOUNT}"'
    )

    assert lines == ['pg_restore: error: ERROR:  invalid input syntax for type numeric: "..."']


def Given_a_key_value_in_parentheses_When_withheld_Then_it_is_replaced() -> None:
    lines = _withheld(
        f"ERROR:  duplicate key value violates unique constraint Key (id)=({ACCOUNT_ID})"
    )

    assert lines == ["ERROR:  duplicate key value violates unique constraint Key (...)=(...)"]


def Given_an_unquoted_amount_and_id_When_withheld_Then_neither_survives() -> None:
    lines = _withheld(
        f"ERROR:  transaction {ACCOUNT_ID} does not balance: its entries sum to {AMOUNT} (ADR-011)"
    )

    assert AMOUNT not in " ".join(lines)
    assert ACCOUNT_ID not in " ".join(lines)
    assert lines == ["ERROR:  transaction <id> does not balance: its entries sum to # (...)"]


def Given_a_quoted_value_spanning_two_lines_When_withheld_Then_no_part_of_it_survives() -> None:
    lines = _withheld(
        'pg_restore: error: COPY failed for table "entry": '
        f'ERROR:  invalid input syntax: "{AMOUNT}',
        'secret-tail"',
    )

    assert lines == [
        'pg_restore: error: COPY failed for table "...": ERROR:  invalid input syntax: "..."'
    ]


def Given_a_statement_after_command_was_When_withheld_Then_none_of_it_is_reported() -> None:
    lines = _withheld(
        'pg_restore: error: could not execute query: ERROR:  relation "account" already exists',
        "Command was: CREATE TABLE accounting.account (",
        "    id uuid NOT NULL,",
        "    name text NOT NULL",
        ");",
        "pg_restore: error: errors ignored on restore: 1",
    )

    assert lines == [
        'pg_restore: error: could not execute query: ERROR:  relation "..." already exists',
        "pg_restore: error: errors ignored on restore: #",
    ]


def Given_detail_context_and_hint_lines_When_withheld_Then_they_are_dropped() -> None:
    lines = _withheld(
        'psql:<stdin>:3: ERROR:  insert or update on table "entry" violates foreign key',
        f"DETAIL:  Key (account_id)=({ACCOUNT_ID}) is not present in table account.",
        "CONTEXT:  COPY entry, line 1",
        "HINT:  Something to try.",
        "LINE 1: SELECT * FROM missing;",
        "                      ^",
    )

    assert lines == ['psql:<stdin>:#: ERROR:  insert or update on table "..." violates foreign key']


def Given_more_lines_than_the_budget_When_withheld_Then_only_the_first_are_reported() -> None:
    lines = _withheld(*(f"pg_dump: warning: number {n}" for n in range(10)))

    assert len(lines) == backup.MAX_REPORTED_LINES


def Given_a_message_that_is_not_ascii_When_withheld_Then_it_is_reported_in_ascii() -> None:
    lines = _withheld("FATAL:  la base de données n'existe pas")

    assert lines == ["FATAL:  la base de donn?es n'existe pas"]


def Given_a_compose_error_on_one_line_When_withheld_Then_it_is_reported() -> None:
    lines = _withheld('service "db" is not running')

    assert lines == ['service "..." is not running']


def Given_no_output_When_withheld_Then_nothing_is_reported() -> None:
    assert _withheld("", "   ") == []


# --- a failed command's own output reaches the report only through _withheld -------------------

# `_require` is called directly: every public path to it runs `docker`, which these tests never
# do. A real child process stands in for the container, so its stderr crosses a real pipe; each
# test starting one is marked `child_process`, its opt-in past conftest's `no_docker`.


def _child(script: str) -> list[str]:
    return [sys.executable, "-c", script]


@pytest.mark.child_process
def Given_a_failing_command_quoting_an_amount_When_required_Then_the_error_withholds_it() -> None:
    stderr = (
        f"pg_restore: error: ERROR:  transaction {ACCOUNT_ID} does not balance: "
        f"its entries sum to {AMOUNT}\n"
        f'DETAIL:  Failing row contains ("{AMOUNT}").\n'
    )
    command = _child(f"import sys; sys.stderr.write({stderr!r}); sys.exit(1)")

    with pytest.raises(backup.BackupError) as raised:
        backup._require("pg_restore of ledger.dump", command)

    message = str(raised.value)
    assert message.startswith("pg_restore of ledger.dump failed with exit status 1: pg_restore:")
    assert AMOUNT not in message
    assert ACCOUNT_ID not in message


@pytest.mark.child_process
def Given_a_command_that_succeeds_When_required_Then_its_stdout_is_returned() -> None:
    command = _child("import sys; sys.stdout.buffer.write(b't|f\\n')")

    assert backup._require("a query", command) == b"t|f\n"


# --- the manifest a restore trusts -------------------------------------------------------------


def _backup_folder(tmp_path: Path, manifest: object, dump: bytes = b"PGDMP-archive") -> Path:
    (tmp_path / backup.LEDGER.dump_name).write_bytes(dump)
    (tmp_path / backup.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
    return tmp_path


def _manifest_for(dump: bytes) -> dict[str, object]:
    return {
        "format": backup.MANIFEST_FORMAT,
        "dumps": [
            {
                "database": backup.LEDGER.key,
                "file": backup.LEDGER.dump_name,
                "sha256": hashlib.sha256(dump).hexdigest(),
            }
        ],
    }


def Given_a_dump_matching_its_manifest_When_verified_Then_its_path_is_returned(
    tmp_path: Path,
) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive"))

    path = backup._verify(folder, backup.LEDGER, backup._manifest_entries(folder))

    assert path == folder / backup.LEDGER.dump_name


def Given_a_dump_changed_after_the_backup_When_verified_Then_it_is_refused(tmp_path: Path) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive"), dump=b"PGDMP-archivf")

    with pytest.raises(backup.BackupError, match="no longer matches the SHA-256"):
        backup._verify(folder, backup.LEDGER, backup._manifest_entries(folder))


def Given_a_manifest_naming_no_such_dump_When_verified_Then_it_is_refused(tmp_path: Path) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive"))

    with pytest.raises(backup.BackupError, match=re.escape("holds no keycloak.dump")):
        backup._verify(folder, backup.KEYCLOAK, backup._manifest_entries(folder))


def Given_a_folder_with_no_manifest_When_read_Then_it_is_refused(tmp_path: Path) -> None:
    with pytest.raises(backup.BackupError, match=re.escape("holds no manifest.json")):
        backup._manifest_entries(tmp_path)


def Given_a_manifest_of_another_format_When_read_Then_it_is_refused(tmp_path: Path) -> None:
    folder = _backup_folder(tmp_path, {"format": backup.MANIFEST_FORMAT + 1, "dumps": []})

    with pytest.raises(backup.BackupError, match="is format"):
        backup._manifest_entries(folder)


def Given_a_manifest_that_is_not_json_When_read_Then_it_is_refused(tmp_path: Path) -> None:
    (tmp_path / backup.MANIFEST_NAME).write_text("{not json", encoding="utf-8")

    with pytest.raises(backup.BackupError, match="not a manifest this made"):
        backup._manifest_entries(tmp_path)


# --- docker compose ps --format json, in both of its shapes -----------------------------------


def Given_one_object_per_line_When_parsed_Then_each_is_a_container() -> None:
    text = '{"Service":"db","Image":"postgres:18.6-trixie"}\n{"Service":"ledger","Image":"x"}\n'

    assert backup._containers(text) == [
        {"Service": "db", "Image": "postgres:18.6-trixie"},
        {"Service": "ledger", "Image": "x"},
    ]


def Given_one_array_When_parsed_Then_each_element_is_a_container() -> None:
    text = '[{"Service":"db","Image":"postgres:18.6-trixie"}]'

    assert backup._containers(text) == [{"Service": "db", "Image": "postgres:18.6-trixie"}]


def Given_no_output_When_parsed_Then_there_is_no_container() -> None:
    assert backup._containers("  \n") == []


# --- the restore check never runs as the owner's project --------------------------------------


def Given_the_owners_project_When_isolated_Then_it_is_refused() -> None:
    with pytest.raises(backup.BackupError, match="must never run as finassist"):
        backup._isolated(backup.OWNER_PROJECT)


def Given_the_check_project_When_isolated_Then_every_command_names_it() -> None:
    compose = backup._isolated(backup.CHECK_PROJECT)

    command = compose._command("up", "-d")

    assert command[command.index("-p") + 1] == backup.CHECK_PROJECT
    assert command[-2:] == ["up", "-d"]


# --- check reads the backup before it starts any container -------------------------------------


class _RecordingCompose(backup.Compose):
    """A Compose that records each command it is asked to run, and refuses to run any."""

    def require(
        self,
        what: str,
        *args: str,
        stdin: object = None,
        stdout: object = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        _CALLS.append(what)
        raise backup.BackupError(f"no container may be started here: {what}")


_CALLS: list[str] = []


def Given_a_changed_dump_When_checked_Then_it_fails_before_any_container_starts(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    _CALLS.clear()
    manifest = _manifest_for(b"PGDMP-archive")
    folder = _backup_folder(tmp_path, manifest, dump=b"PGDMP-archivf")

    status = backup._run_checks(
        backup._CheckRun(), _RecordingCompose(project=backup.CHECK_PROJECT), folder
    )

    assert status == 1
    assert _CALLS == []
    assert "FAILED check 1 (both dumps match their manifest)" in capsys.readouterr().out


# --- a Compose project held in memory, for restore and backup ---------------------------------

PROJECT_NAME = "finassist-test"
BOOKS_DIGEST = "a" * 64
LEDGER_DUMP = b"PGDMP-ledger-archive"
KEYCLOAK_DUMP = b"PGDMP-keycloak-archive"
POSTGRES_IMAGE = "postgres:18.6-trixie"
BOTH_RUNNING = {"db": "running", "keycloak-db": "running"}


@dataclass(frozen=True)
class _Project(backup.Compose):
    """A Compose project in memory, standing in for `docker compose` behind `require`.

    It answers only the commands backup.py is known to send, records every one in `calls`, and
    fails the test on any other, so a step the CLI must never take cannot pass unseen. That
    refusal is what makes "starts no container" hold for backup and restore (ADR-038): `up`,
    `start`, `restart`, `run` and `create` are none of the shapes it answers, so a test needs no
    assertion of its own about them.

    :param states: each service's container state; a service not named has no container.
    :param tables: how many tables each database service holds; none when not named.
    :param digests: what the books' digest query answers, one per query; the last one repeats.
        Empty means the ledger database holds no journal yet.
    :param dumps: what pg_dump writes, by database service.
    :param unreadable: the database services whose read-back of a dump fails.
    """

    states: Mapping[str, str] = field(default_factory=lambda: dict(BOTH_RUNNING))
    tables: Mapping[str, int] = field(default_factory=dict)
    digests: list[str] = field(default_factory=lambda: [BOOKS_DIGEST])
    dumps: Mapping[str, bytes] = field(
        default_factory=lambda: {"db": LEDGER_DUMP, "keycloak-db": KEYCLOAK_DUMP}
    )
    unreadable: frozenset[str] = frozenset()
    calls: list[tuple[str, ...]] = field(default_factory=list)
    read_back: list[tuple[str, bytes]] = field(default_factory=list)
    restored: list[tuple[str, bytes]] = field(default_factory=list)

    def require(
        self,
        what: str,
        *args: str,
        stdin: IO[bytes] | None = None,
        stdout: IO[bytes] | None = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        self.calls.append(args)
        match args:
            case ("config", "--format", "json"):
                return json.dumps({"name": PROJECT_NAME}).encode()
            case ("ps", "--format", "json", service):
                if self.states.get(service) != "running":
                    return b""
                return json.dumps({"Service": service, "Image": POSTGRES_IMAGE}).encode()
            case ("ps", *filters, "--services"):
                assert set(filters[0::2]) == {"--status"}, f"unexpected ps filters: {filters}"
                statuses = set(filters[1::2])
                listed = [name for name, state in self.states.items() if state in statuses]
                return "\n".join(listed).encode()
            case ("exec", "-T", service, *command):
                return self._exec(what, service, tuple(command), stdin, stdout, input_bytes)
        raise AssertionError(f"backup.py sent a command this project does not expect: {args}")

    def _exec(
        self,
        what: str,
        service: str,
        command: tuple[str, ...],
        stdin: IO[bytes] | None,
        stdout: IO[bytes] | None,
        input_bytes: bytes | None,
    ) -> bytes:
        if self.states.get(service) != "running":
            # What `docker compose exec` answers when the service has no running container.
            raise backup.BackupError(f'{what} failed with exit status 1: service "..." not running')
        if command == backup.PSQL_COMMAND:
            return self._query(service, (input_bytes or b"").decode("utf-8"))
        if command == backup.DUMP_COMMAND:
            assert stdout is not None, "pg_dump must write into the dump file"
            stdout.write(self.dumps[service])
            return b""
        if command == backup.READ_BACK_COMMAND:
            assert stdin is not None, "a read-back must read the dump file"
            self.read_back.append((service, stdin.read()))
            if service in self.unreadable:
                raise backup.BackupError(
                    f"{what} failed with exit status 1: pg_restore: error: could not read input"
                )
            return b""
        if command == backup.RESTORE_COMMAND:
            assert stdin is not None, "pg_restore must read the dump file"
            self.restored.append((service, stdin.read()))
            return b""
        raise AssertionError(f"backup.py ran a command this project does not expect: {command}")

    def _query(self, service: str, sql: str) -> bytes:
        if sql == backup.COUNT_TABLES_SQL:
            return f"{self.tables.get(service, 0)}\n".encode()
        if service == backup.LEDGER.service and sql == backup.BOOKS_PRESENT_SQL:
            return b"t\n" if self.digests else b"f\n"
        if service == backup.LEDGER.service and sql == backup.BOOKS_DIGEST_SQL:
            digest = self.digests.pop(0) if len(self.digests) > 1 else self.digests[0]
            return f"{digest}\n".encode()
        raise AssertionError(f"backup.py ran a query this project does not expect in {service}")


def _written_backup(
    folder: Path,
    dumps: Mapping[backup.Database, bytes] | None = None,
    digest: object = BOOKS_DIGEST,
) -> Path:
    """A backup folder laid out as `backup` writes one, its SHA-256s computed by hashlib.

    :param dumps: the dump each database's file holds; both, by default.
    """
    if dumps is None:
        dumps = {backup.LEDGER: LEDGER_DUMP, backup.KEYCLOAK: KEYCLOAK_DUMP}
    folder.mkdir(parents=True, exist_ok=True)
    entries = []
    for database, content in dumps.items():
        (folder / database.dump_name).write_bytes(content)
        entries.append(
            {
                "database": database.key,
                "file": database.dump_name,
                "bytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
                "service": database.service,
                "image": POSTGRES_IMAGE,
            }
        )
    manifest = {
        "format": backup.MANIFEST_FORMAT,
        "created": "20261013T120000Z",
        "project": PROJECT_NAME,
        backup.BOOKS_DIGEST_KEY: digest,
        "dumps": entries,
    }
    (folder / backup.MANIFEST_NAME).write_bytes(json.dumps(manifest).encode("utf-8"))
    return folder


# --- a restore writes only into an empty database, with its consumer stopped (ADR-038) ---------


def Given_empty_databases_and_stopped_consumers_When_both_are_restored_Then_each_gets_its_dump(
    tmp_path: Path,
) -> None:
    project = _Project()

    backup.restore(project, _written_backup(tmp_path), backup.DATABASES)

    assert project.restored == [("db", LEDGER_DUMP), ("keycloak-db", KEYCLOAK_DUMP)]


def Given_a_restarting_ledger_When_its_database_is_restored_Then_the_restore_is_refused(
    tmp_path: Path,
) -> None:
    project = _Project(states=BOTH_RUNNING | {"ledger": "restarting"})

    with pytest.raises(backup.BackupError, match="ledger is not stopped"):
        backup.restore(project, _written_backup(tmp_path), (backup.LEDGER,))

    assert project.restored == []


def Given_a_running_ledger_When_its_database_is_restored_Then_it_is_refused_and_nothing_restored(
    tmp_path: Path,
) -> None:
    project = _Project(states=BOTH_RUNNING | {"ledger": "running"})

    with pytest.raises(backup.BackupError, match="ledger is not stopped"):
        backup.restore(project, _written_backup(tmp_path), (backup.LEDGER,))

    assert project.restored == []


def Given_a_paused_keycloak_When_keycloak_db_is_restored_Then_it_is_refused_and_nothing_restored(
    tmp_path: Path,
) -> None:
    project = _Project(states=BOTH_RUNNING | {"keycloak": "paused"})

    with pytest.raises(backup.BackupError, match="keycloak is not stopped"):
        backup.restore(project, _written_backup(tmp_path), (backup.KEYCLOAK,))

    assert project.restored == []


def Given_a_stopped_db_When_its_database_is_restored_Then_it_is_refused_and_nothing_restored(
    tmp_path: Path,
) -> None:
    project = _Project(states={"keycloak-db": "running"})

    with pytest.raises(backup.BackupError, match=f"^db not running in project {PROJECT_NAME}"):
        backup.restore(project, _written_backup(tmp_path), (backup.LEDGER,))

    assert project.restored == []


def Given_keycloak_db_holding_tables_When_both_are_restored_Then_neither_is_restored(
    tmp_path: Path,
) -> None:
    project = _Project(tables={"keycloak-db": 4})

    with pytest.raises(backup.BackupError, match=r"keycloak-db .* already holds 4 tables"):
        backup.restore(project, _written_backup(tmp_path), backup.DATABASES)

    assert project.restored == []


def Given_a_changed_dump_When_restored_Then_it_is_refused_before_any_compose_command(
    tmp_path: Path,
) -> None:
    folder = _written_backup(tmp_path)
    (folder / backup.LEDGER.dump_name).write_bytes(LEDGER_DUMP + b"!")
    project = _Project()

    with pytest.raises(backup.BackupError, match="no longer matches the SHA-256"):
        backup.restore(project, folder, backup.DATABASES)

    assert project.calls == []


# --- a restore trusts only a manifest this CLI could have written, and dumps that match it ------

# Larger than one read of the hash, so a hash of the first chunk alone shows.
LARGE_DUMP = bytes(range(256)) * (2 * backup.HASH_CHUNK_BYTES // 256) + b"tail"


def Given_a_dump_larger_than_one_hash_chunk_When_restored_unchanged_Then_it_matches(
    tmp_path: Path,
) -> None:
    project = _Project()

    backup.restore(
        project, _written_backup(tmp_path, {backup.LEDGER: LARGE_DUMP}), (backup.LEDGER,)
    )

    assert project.restored == [("db", LARGE_DUMP)]


def Given_a_dump_changed_only_past_its_first_hash_chunk_When_restored_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    taken = _Project(dumps={"db": LARGE_DUMP, "keycloak-db": KEYCLOAK_DUMP})
    folder = backup.backup(taken, tmp_path, NOW)
    changed = bytearray(LARGE_DUMP)
    changed[backup.HASH_CHUNK_BYTES + 10] ^= 0xFF
    (folder / backup.LEDGER.dump_name).write_bytes(bytes(changed))
    project = _Project()

    with pytest.raises(
        backup.BackupError, match=re.escape("ledger.dump no longer matches the SHA-256")
    ):
        backup.restore(project, folder, backup.DATABASES)

    assert project.calls == []


def Given_a_dump_its_manifest_does_not_record_When_restored_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    folder = _written_backup(tmp_path, {backup.LEDGER: LEDGER_DUMP})
    (folder / backup.KEYCLOAK.dump_name).write_bytes(KEYCLOAK_DUMP)
    project = _Project()

    with pytest.raises(backup.BackupError, match=re.escape("holds no keycloak.dump that its")):
        backup.restore(project, folder, (backup.KEYCLOAK,))

    assert project.calls == []


def Given_a_manifest_entry_whose_dump_is_missing_When_restored_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    folder = _written_backup(tmp_path)
    (folder / backup.KEYCLOAK.dump_name).unlink()
    project = _Project()

    with pytest.raises(backup.BackupError, match=re.escape("holds no keycloak.dump that its")):
        backup.restore(project, folder, backup.DATABASES)

    assert project.calls == []


LEDGER_SHA256 = hashlib.sha256(LEDGER_DUMP).hexdigest()
NOT_A_MANIFEST = "is not a manifest this made"


@pytest.mark.parametrize(
    ("manifest", "refusal"),
    [
        pytest.param([], NOT_A_MANIFEST, id="not-an-object"),
        pytest.param({"dumps": []}, NOT_A_MANIFEST, id="no-format"),
        pytest.param({"format": backup.MANIFEST_FORMAT}, NOT_A_MANIFEST, id="no-dumps"),
        pytest.param(
            {"format": backup.MANIFEST_FORMAT, "dumps": {}}, NOT_A_MANIFEST, id="dumps-not-a-list"
        ),
        pytest.param(
            {"format": backup.MANIFEST_FORMAT, "dumps": ["ledger"]},
            NOT_A_MANIFEST,
            id="entry-not-an-object",
        ),
        pytest.param(
            {"format": backup.MANIFEST_FORMAT, "dumps": [{"sha256": LEDGER_SHA256}]},
            NOT_A_MANIFEST,
            id="entry-without-database",
        ),
        pytest.param(
            {"format": backup.MANIFEST_FORMAT, "dumps": [{"database": "ledger"}]},
            "ledger.dump no longer matches the SHA-256",
            id="entry-without-sha256",
        ),
    ],
)
def Given_a_malformed_manifest_When_restored_Then_it_is_refused_before_any_compose_command(
    manifest: object, refusal: str, tmp_path: Path
) -> None:
    (tmp_path / backup.LEDGER.dump_name).write_bytes(LEDGER_DUMP)
    (tmp_path / backup.MANIFEST_NAME).write_bytes(json.dumps(manifest).encode("utf-8"))
    project = _Project()

    with pytest.raises(backup.BackupError, match=re.escape(refusal)):
        backup.restore(project, tmp_path, (backup.LEDGER,))

    assert project.calls == []


# --- a restore proves the books it restored are the ones backed up -----------------------------


def Given_restored_books_matching_the_digest_When_restored_Then_it_succeeds(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    project = _Project()

    backup.restore(project, _written_backup(tmp_path), (backup.LEDGER,))

    assert project.restored == [("db", LEDGER_DUMP)]
    assert "the restored books match the digest the backup recorded" in capsys.readouterr().out


def Given_restored_books_differing_from_the_digest_When_restored_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    project = _Project(digests=["b" * 64])

    with pytest.raises(backup.BackupError, match="do not match the digest the backup recorded"):
        backup.restore(project, _written_backup(tmp_path), (backup.LEDGER,))


def Given_a_backup_from_before_the_journal_When_restored_Then_no_digest_is_compared(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    project = _Project(digests=["b" * 64])

    backup.restore(project, _written_backup(tmp_path, digest=None), (backup.LEDGER,))

    assert project.restored == [("db", LEDGER_DUMP)]
    assert "no digest is compared" in capsys.readouterr().out


def _backup_with_digest(tmp_path: Path, digest: object) -> Path:
    manifest = _manifest_for(b"PGDMP-archive")
    manifest[backup.BOOKS_DIGEST_KEY] = digest
    return _backup_folder(tmp_path, manifest)


def Given_a_manifest_recording_no_usable_digest_When_read_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    folder = _backup_with_digest(tmp_path, 42)

    with pytest.raises(backup.BackupError, match="records no usable books_sha256"):
        backup._recorded_books_digest(folder)


# --- a backup proves it can be read, and never starts a container (ADR-038) --------------------

NOW = datetime(2026, 10, 13, 12, 0, tzinfo=UTC)


def Given_books_that_hold_still_When_backed_up_Then_the_manifest_records_their_digest(
    tmp_path: Path,
) -> None:
    folder = backup.backup(_Project(digests=[BOOKS_DIGEST, BOOKS_DIGEST]), tmp_path, NOW)

    manifest = json.loads((folder / backup.MANIFEST_NAME).read_text(encoding="utf-8"))
    assert manifest[backup.BOOKS_DIGEST_KEY] == BOOKS_DIGEST


def Given_running_databases_When_backed_up_Then_each_dump_is_read_back_and_no_container_started(
    tmp_path: Path,
) -> None:
    project = _Project(tables={"db": 9, "keycloak-db": 90})

    folder = backup.backup(project, tmp_path, NOW)

    assert (folder / backup.LEDGER.dump_name).read_bytes() == LEDGER_DUMP
    assert (folder / backup.KEYCLOAK.dump_name).read_bytes() == KEYCLOAK_DUMP
    assert project.read_back == [("db", LEDGER_DUMP), ("keycloak-db", KEYCLOAK_DUMP)]


def Given_books_that_change_during_the_backup_When_backed_up_Then_it_fails_and_leaves_nothing(
    tmp_path: Path,
) -> None:
    with pytest.raises(
        backup.BackupError, match="the books changed while they were being backed up"
    ):
        backup.backup(_Project(digests=[BOOKS_DIGEST, "b" * 64]), tmp_path, NOW)

    assert list(tmp_path.iterdir()) == []


def Given_an_empty_dump_When_backed_up_Then_it_fails_and_leaves_nothing(tmp_path: Path) -> None:
    project = _Project(dumps={"db": LEDGER_DUMP, "keycloak-db": b""})

    with pytest.raises(backup.BackupError, match="pg_dump of keycloak wrote nothing"):
        backup.backup(project, tmp_path, NOW)

    assert list(tmp_path.iterdir()) == []


def Given_a_failing_read_back_When_backed_up_Then_it_fails_and_leaves_nothing(
    tmp_path: Path,
) -> None:
    project = _Project(unreadable=frozenset({"db"}))

    with pytest.raises(backup.BackupError, match=re.escape("reading back ledger.dump failed")):
        backup.backup(project, tmp_path, NOW)

    assert list(tmp_path.iterdir()) == []


def Given_keycloak_db_not_running_When_backed_up_Then_it_is_refused(tmp_path: Path) -> None:
    project = _Project(states={"db": "running"})
    root = tmp_path / "backups"

    with pytest.raises(
        backup.BackupError, match=f"^keycloak-db not running in project {PROJECT_NAME}"
    ):
        backup.backup(project, root, NOW)

    assert not root.exists()
    assert not any(call[0] == "exec" for call in project.calls)


# --- what a backup writes is what a restore accepts --------------------------------------------


def Given_a_finished_backup_When_its_folder_is_restored_Then_both_dumps_match_and_are_restored(
    tmp_path: Path,
) -> None:
    folder = backup.backup(_Project(tables={"db": 9, "keycloak-db": 90}), tmp_path, NOW)
    empty = _Project()

    backup.restore(empty, folder, backup.DATABASES)

    assert empty.restored == [("db", LEDGER_DUMP), ("keycloak-db", KEYCLOAK_DUMP)]


# --- the command line, with Compose replaced by a project in memory ----------------------------


@pytest.fixture
def cli_project(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> _Project:
    """The project every `Compose()` the CLI builds stands for.

    The default backup root is moved under `tmp_path` as well, so a run that ignores
    BACKUP_DIR writes there rather than into this checkout's own `backups/`.
    """
    project = _Project()
    monkeypatch.setattr(backup, "Compose", lambda: project)
    monkeypatch.setattr(backup, "DEFAULT_BACKUP_ROOT", tmp_path / "default-root")
    return project


def Given_database_keycloak_When_restore_runs_Then_only_keycloak_db_is_touched(
    cli_project: _Project, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    folder = _written_backup(tmp_path / "backup")

    status = backup.main(["restore", str(folder), "--database", "keycloak"], NOW)

    assert status == 0
    assert cli_project.restored == [("keycloak-db", KEYCLOAK_DUMP)]
    assert {call[2] for call in cli_project.calls if call[0] == "exec"} == {"keycloak-db"}
    assert "Restore complete" in capsys.readouterr().out


def Given_no_database_option_When_restore_runs_Then_both_are_restored(
    cli_project: _Project, tmp_path: Path
) -> None:
    folder = _written_backup(tmp_path / "backup")

    status = backup.main(["restore", str(folder)], NOW)

    assert status == 0
    assert cli_project.restored == [("db", LEDGER_DUMP), ("keycloak-db", KEYCLOAK_DUMP)]


def Given_BACKUP_DIR_When_backup_runs_Then_the_folder_is_created_under_it(
    cli_project: _Project,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setenv(backup.BACKUP_DIR_ENV, str(tmp_path / "chosen"))

    status = backup.main(["backup"], NOW)

    assert status == 0
    assert (tmp_path / "chosen" / "20261013T120000Z" / backup.MANIFEST_NAME).is_file()
    assert not (tmp_path / "default-root").exists()
    assert "Backup complete" in capsys.readouterr().out


@pytest.mark.parametrize("backup_dir", [None, ""], ids=["unset", "empty"])
def Given_no_BACKUP_DIR_When_backup_runs_Then_the_folder_is_created_under_the_default_root(
    backup_dir: str | None,
    cli_project: _Project,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    if backup_dir is None:
        monkeypatch.delenv(backup.BACKUP_DIR_ENV, raising=False)
    else:
        monkeypatch.setenv(backup.BACKUP_DIR_ENV, backup_dir)

    status = backup.main(["backup"], NOW)

    assert status == 0
    assert (tmp_path / "default-root" / "20261013T120000Z" / backup.MANIFEST_NAME).is_file()


def Given_a_refusal_When_main_runs_Then_it_exits_1_reporting_FAILED(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    busy = _Project(states=BOTH_RUNNING | {"ledger": "running"})
    monkeypatch.setattr(backup, "Compose", lambda: busy)
    folder = _written_backup(tmp_path / "backup")

    status = backup.main(["restore", str(folder)], NOW)

    assert status == 1
    assert capsys.readouterr().out.splitlines()[-1].startswith("FAILED: ledger is not stopped")
    assert busy.restored == []


def Given_a_backup_dir_that_is_a_file_When_backup_runs_Then_it_exits_1_reporting_the_error_type(
    cli_project: _Project,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    occupied = tmp_path / "occupied"
    occupied.write_bytes(b"")
    monkeypatch.setenv(backup.BACKUP_DIR_ENV, str(occupied))

    status = backup.main(["backup"], NOW)

    assert status == 1
    assert capsys.readouterr().out.splitlines()[-1].startswith("FAILED: FileExistsError")

"""The parts of `ops/backup/backup.py` that need no Docker (ADR-038).

Everything that reaches a database or a container is proved by running the CLI against real ones:
`check` is that proof for a backup. What is here is the logic a mistake in which would go unseen
there: the redaction every reported PostgreSQL message passes through, the manifest a restore
trusts, the parsing of `docker compose ps`, and the guard that keeps `check` off the owner's
project.
"""

from __future__ import annotations

import json
import re
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


# --- the manifest a restore trusts -------------------------------------------------------------


def _backup_folder(tmp_path: Path, manifest: object, dump: bytes = b"PGDMP-archive") -> Path:
    (tmp_path / backup.LEDGER.dump_name).write_bytes(dump)
    (tmp_path / backup.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
    return tmp_path


def _manifest_for(dump: bytes, tmp_path: Path) -> dict[str, object]:
    (tmp_path / "probe").write_bytes(dump)
    return {
        "format": backup.MANIFEST_FORMAT,
        "dumps": [
            {
                "database": backup.LEDGER.key,
                "file": backup.LEDGER.dump_name,
                "sha256": backup._sha256(tmp_path / "probe"),
            }
        ],
    }


def Given_a_dump_matching_its_manifest_When_verified_Then_its_path_is_returned(
    tmp_path: Path,
) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive", tmp_path))

    path = backup._verify(folder, backup.LEDGER, backup._manifest_entries(folder))

    assert path == folder / backup.LEDGER.dump_name


def Given_a_dump_changed_after_the_backup_When_verified_Then_it_is_refused(tmp_path: Path) -> None:
    folder = _backup_folder(
        tmp_path, _manifest_for(b"PGDMP-archive", tmp_path), dump=b"PGDMP-archivf"
    )

    with pytest.raises(backup.BackupError, match="no longer matches the SHA-256"):
        backup._verify(folder, backup.LEDGER, backup._manifest_entries(folder))


def Given_a_manifest_naming_no_such_dump_When_verified_Then_it_is_refused(tmp_path: Path) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive", tmp_path))

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
    manifest = _manifest_for(b"PGDMP-archive", tmp_path)
    folder = _backup_folder(tmp_path, manifest, dump=b"PGDMP-archivf")

    status = backup._run_checks(
        backup._CheckRun(), _RecordingCompose(project=backup.CHECK_PROJECT), folder
    )

    assert status == 1
    assert _CALLS == []
    assert "FAILED check 1 (both dumps match their manifest)" in capsys.readouterr().out


# --- a restore refuses while the database's consumer is anything but stopped -------------------


class _RestartingLedger(backup.Compose):
    """A project whose databases run and whose ledger is caught in a crash loop."""

    def require(
        self,
        what: str,
        *args: str,
        stdin: object = None,
        stdout: object = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        if args[0] == "config":
            return b'{"name": "finassist-test"}'
        if args[0] == "ps":
            statuses = [args[i + 1] for i, arg in enumerate(args) if arg == "--status"]
            return (
                b"db\nkeycloak-db\nledger\n" if "restarting" in statuses else b"db\nkeycloak-db\n"
            )
        if args[0] == "exec":
            return b"0\n"
        raise AssertionError(f"unexpected command: {args}")


def Given_a_restarting_ledger_When_its_database_is_restored_Then_the_restore_is_refused(
    tmp_path: Path,
) -> None:
    folder = _backup_folder(tmp_path, _manifest_for(b"PGDMP-archive", tmp_path))

    with pytest.raises(backup.BackupError, match="ledger is not stopped"):
        backup.restore(_RestartingLedger(), folder, (backup.LEDGER,))


# --- a restore proves the books it restored are the ones backed up -----------------------------

BOOKS_DIGEST = "a" * 64


@dataclass(frozen=True)
class _RestorableProject(backup.Compose):
    """An empty, running project whose restored ledger database reports `digest` for its books."""

    digest: str = BOOKS_DIGEST

    def require(
        self,
        what: str,
        *args: str,
        stdin: object = None,
        stdout: object = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        if args[0] == "config":
            return b'{"name": "finassist-test"}'
        if args[0] == "ps":
            return b"db\nkeycloak-db\n"
        sql = (input_bytes or b"").decode("utf-8")
        if "to_regclass" in sql:
            return b"t\n"
        if "sha256" in sql:
            return f"{self.digest}\n".encode()
        if "pg_tables" in sql:
            return b"0\n"
        return b""


def _backup_with_digest(tmp_path: Path, digest: object) -> Path:
    manifest = _manifest_for(b"PGDMP-archive", tmp_path)
    manifest[backup.BOOKS_DIGEST_KEY] = digest
    return _backup_folder(tmp_path, manifest)


def Given_restored_books_matching_the_digest_When_restored_Then_it_succeeds(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    folder = _backup_with_digest(tmp_path, BOOKS_DIGEST)

    backup.restore(_RestorableProject(), folder, (backup.LEDGER,))

    assert "the restored books match the digest the backup recorded" in capsys.readouterr().out


def Given_restored_books_differing_from_the_digest_When_restored_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    folder = _backup_with_digest(tmp_path, BOOKS_DIGEST)

    with pytest.raises(backup.BackupError, match="do not match the digest the backup recorded"):
        backup.restore(_RestorableProject(digest="b" * 64), folder, (backup.LEDGER,))


def Given_a_backup_from_before_the_journal_When_restored_Then_no_digest_is_compared(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    folder = _backup_with_digest(tmp_path, None)

    backup.restore(_RestorableProject(digest="b" * 64), folder, (backup.LEDGER,))

    assert "no digest is compared" in capsys.readouterr().out


def Given_a_manifest_recording_no_usable_digest_When_read_Then_it_is_refused(
    tmp_path: Path,
) -> None:
    folder = _backup_with_digest(tmp_path, 42)

    with pytest.raises(backup.BackupError, match="records no usable books_sha256"):
        backup._recorded_books_digest(folder)


# --- a backup records the books' digest, and refuses books that change while it runs ----------

NOW = datetime(2026, 10, 13, 12, 0, tzinfo=UTC)


@dataclass(frozen=True)
class _RunningProject(backup.Compose):
    """A running project whose ledger database reports each of `digests` in turn."""

    digests: list[str] = field(default_factory=list)

    def require(
        self,
        what: str,
        *args: str,
        stdin: IO[bytes] | None = None,
        stdout: IO[bytes] | None = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        if args[0] == "config":
            return b'{"name": "finassist-test"}'
        if args[0] == "ps" and "--format" in args:
            return json.dumps({"Service": args[-1], "Image": "postgres:18.6-trixie"}).encode()
        if args[0] == "ps":
            return b"db\nkeycloak-db\n"
        if "pg_dump" in " ".join(args):
            assert stdout is not None
            stdout.write(b"PGDMP-archive")
            return b""
        sql = (input_bytes or b"").decode("utf-8")
        if "to_regclass" in sql:
            return b"t\n"
        if "sha256" in sql:
            return f"{self.digests.pop(0)}\n".encode()
        if "pg_tables" in sql:
            return b"1\n"
        return b""


def Given_books_that_hold_still_When_backed_up_Then_the_manifest_records_their_digest(
    tmp_path: Path,
) -> None:
    folder = backup.backup(_RunningProject(digests=[BOOKS_DIGEST, BOOKS_DIGEST]), tmp_path, NOW)

    manifest = json.loads((folder / backup.MANIFEST_NAME).read_text(encoding="utf-8"))
    assert manifest[backup.BOOKS_DIGEST_KEY] == BOOKS_DIGEST


def Given_books_that_change_during_the_backup_When_backed_up_Then_it_fails_and_leaves_nothing(
    tmp_path: Path,
) -> None:
    with pytest.raises(
        backup.BackupError, match="the books changed while they were being backed up"
    ):
        backup.backup(_RunningProject(digests=[BOOKS_DIGEST, "b" * 64]), tmp_path, NOW)

    assert list(tmp_path.iterdir()) == []

"""Puts `ops/backup` on the import path, so the tests import the CLI as the module `backup`, and
keeps every test off Docker.

The CLI is a single file run by `uv run`, not a package, and these tests run from
`backend/assistant` with its pytest settings (`make test-backup`), so nothing else would find it.
"""

import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

CHILD_PROCESS_MARKER = "child_process"


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        f"{CHILD_PROCESS_MARKER}: the test starts a real child process, never `docker`",
    )


@pytest.fixture(autouse=True)
def no_docker(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch) -> None:
    """Refuses every child process a test did not opt into, and `docker` always.

    Without this, a test calling `backup.main(["backup"])` without `cli_project` would run a real
    `docker compose exec pg_dump` against the owner's `finassist` project. The seam is
    `subprocess.run`, because it is the only process boundary backup.py has: `Compose.require`
    reaches it through `_require`, and `_clean_up` calls `_require` with `docker volume` directly,
    so replacing `Compose.require` alone would miss that. A test that replaces `subprocess.run`
    itself, as the `_isolated` test does, puts its own fake in front of this one.

    A test marked `child_process` may start a real child, such as `sys.executable`, but never
    `docker`. The refusal is `pytest.fail`, not an `AssertionError`: `_run_checks` catches every
    `Exception` to name the failing check, and would report the refusal as a failed check
    instead of failing the test.
    """
    real_run = subprocess.run
    opted_in = request.node.get_closest_marker(CHILD_PROCESS_MARKER) is not None

    def guarded(command: Sequence[str], *args: object, **options: object) -> object:
        program = Path(command[0]).stem.lower() if command else ""
        if program == "docker" or not opted_in:
            pytest.fail(
                f"a backup test tried to run {program or 'nothing'!r}: these tests never start "
                "a process, Docker above all; use the _Project double, or mark the test "
                f"{CHILD_PROCESS_MARKER} for a child that is not docker"
            )
        return real_run(command, *args, **options)  # type: ignore[call-overload]

    monkeypatch.setattr(subprocess, "run", guarded)

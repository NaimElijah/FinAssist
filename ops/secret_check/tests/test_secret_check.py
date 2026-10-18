"""The secret check, driven through its command line against clones built on disk (#302, ADR-044).

Every case runs `main` the way Compose runs the script: a scan root as the only argument and the
secrets in the environment. The walk is also compared with what `git` itself reports, on a
synthetic repository and on this checkout, because a file the walk skipped is a secret the check
could never see.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import pytest
import secret_check

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
# Fixture values only: none is, or may ever be, a real secret.
PUBLISHED = "Fixture-only-7Qv2Lk9Xw4"
FRESH = "Fixture-only-3Hn8Tc5Rm1"
# Root reads every directory whatever its mode, so an unreadable one cannot be staged as root.
if sys.platform == "win32":
    POSIX_PERMISSIONS_BIND = False
else:
    POSIX_PERMISSIONS_BIND = os.geteuid() != 0


def _clone(root: Path, files: dict[str, bytes], gitignore: bytes | None = None) -> Path:
    """Writes a clone holding the anchor file, the repository's real root `.gitignore` unless
    another is given, and `files`."""
    root.mkdir(parents=True, exist_ok=True)
    (root / secret_check.ANCHOR_FILE).write_bytes(b"services: {}\n")
    real_gitignore = (REPOSITORY_ROOT / ".gitignore").read_bytes()
    (root / ".gitignore").write_bytes(real_gitignore if gitignore is None else gitignore)
    for relative, content in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return root


def _environment(**overrides: str) -> dict[str, str]:
    """Every checked variable set to a fresh value, then `overrides`."""
    values = {name: f"{FRESH}-{index}" for index, name in enumerate(secret_check.SECRET_VARIABLES)}
    return values | overrides


def _run(
    root: Path, environment: dict[str, str], capsys: pytest.CaptureFixture[str]
) -> tuple[int, str]:
    status = secret_check.main([str(root)], environment)
    captured = capsys.readouterr()
    return status, captured.out + captured.err


# --- The verdict ---------------------------------------------------------------------------------


def Given_a_secret_a_committed_file_holds_When_checked_Then_it_is_refused_by_name_alone(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {".github/workflows/ci.yml": f"PASSWORD={PUBLISHED}/'\n".encode()})

    status, output = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "POSTGRES_PASSWORD" in output
    assert secret_check.ROTATION_GUIDE in output
    assert PUBLISHED not in output
    assert "ci.yml" not in output


def Given_no_secret_any_file_holds_When_checked_Then_it_passes_reporting_counts_only(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"Any strong value.\n"})
    environment = _environment()

    status, output = _run(root, environment, capsys)

    assert status == secret_check.EXIT_PASSED
    assert f"{len(secret_check.SECRET_VARIABLES)} secrets" in output
    assert not any(value in output for value in environment.values())


def Given_two_variables_sharing_a_published_value_When_checked_Then_both_are_named(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"docs/notes.md": f"`{PUBLISHED}`\n".encode()})
    environment = _environment(POSTGRES_PASSWORD=PUBLISHED, LEDGER_DB_PASSWORD=PUBLISHED)

    status, output = _run(root, environment, capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "POSTGRES_PASSWORD" in output
    assert "LEDGER_DB_PASSWORD" in output
    assert "KEYCLOAK_DB_PASSWORD" not in output


def Given_a_value_only_inside_a_longer_token_When_checked_Then_it_passes(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"ci.yml": f"x={PUBLISHED}-12-chars\ny=a{PUBLISHED}\n".encode()})

    status, _ = _run(root, _environment(GATEWAY_CLIENT_SECRET=PUBLISHED), capsys)

    assert status == secret_check.EXIT_PASSED


def Given_an_unset_or_empty_secret_When_checked_Then_it_is_skipped_rather_than_matched(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    environment = _environment(GRAFANA_ADMIN_PASSWORD="")
    del environment["ASSISTANT_CLIENT_SECRET"]

    status, output = _run(root, environment, capsys)

    assert status == secret_check.EXIT_PASSED
    assert f"{len(secret_check.SECRET_VARIABLES) - 2} secrets" in output


# --- Which files count ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "ignored",
    [
        ".env",
        ".env.local",
        "backups/20261018T000000Z/keycloak.dump",
        "frontend/web/node_modules/pkg/index.js",
        "backend/ledger/target/classes/application.yml",
        "backend/assistant/.hypothesis/examples/0a1b/2c3d",
        ".claude/settings.local.json",
        "server.log",
    ],
)
def Given_a_secret_only_in_a_file_git_ignores_When_checked_Then_it_passes(
    ignored: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    files = {ignored: f"POSTGRES_PASSWORD={PUBLISHED}\n".encode()}
    if ".hypothesis" in ignored:
        # Not in the root .gitignore: Hypothesis writes a .gitignore of `*` into its own folder.
        files["backend/assistant/.hypothesis/.gitignore"] = b"*\n"
    root = _clone(tmp_path, files)

    status, _ = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_PASSED


def Given_a_secret_in_the_committed_env_example_When_checked_Then_it_is_refused(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {".env.example": f"POSTGRES_PASSWORD={PUBLISHED}\n".encode()})

    status, _ = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_REFUSED


def Given_a_secret_inside_git_metadata_When_checked_Then_it_is_not_read(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(
        tmp_path,
        {
            ".git/config": f"[remote] url = {PUBLISHED}\n".encode(),
            "worktree/.git": f"gitdir: /elsewhere/{PUBLISHED}\n".encode(),
        },
    )

    status, _ = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_PASSED


def Given_a_symlink_to_a_file_holding_a_secret_When_checked_Then_it_is_not_followed(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "secret.txt").write_bytes(PUBLISHED.encode())
    root = _clone(tmp_path / "clone", {})
    try:
        (root / "link").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("this machine does not let an unprivileged user create a symlink")

    status, _ = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_PASSED


# --- Failing closed ------------------------------------------------------------------------------


def Given_a_root_that_is_not_the_repository_When_checked_Then_it_fails_without_passing(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    status, output = _run(tmp_path, _environment(), capsys)

    assert status == secret_check.EXIT_MISCONFIGURED
    assert secret_check.ANCHOR_FILE in output


@pytest.mark.skipif(
    not POSIX_PERMISSIONS_BIND, reason="needs POSIX permissions, as a non-root user"
)
def Given_an_unreadable_directory_git_does_not_ignore_When_checked_Then_it_fails_naming_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"locked/file.txt": b"x\n"})
    (root / "locked").chmod(0)
    try:
        status, output = _run(root, _environment(), capsys)
    finally:
        (root / "locked").chmod(0o755)

    assert status == secret_check.EXIT_MISCONFIGURED
    assert "locked" in output


def Given_an_unexpected_failure_When_checked_Then_only_its_type_is_reported(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})

    def failing_read(path: Path) -> bytes:
        raise RuntimeError(f"could not read a file holding {PUBLISHED}")

    monkeypatch.setattr(secret_check, "_read", failing_read)

    status, output = _run(root, _environment(), capsys)

    assert status == secret_check.EXIT_MISCONFIGURED
    assert "RuntimeError" in output
    assert PUBLISHED not in output


# --- What is checked -----------------------------------------------------------------------------


def Given_the_compose_file_When_its_secrets_are_read_Then_the_check_covers_each() -> None:
    compose = (REPOSITORY_ROOT / "docker-compose.yml").read_bytes().decode()

    # `:?` marks exactly the secrets: every variable that is not secret takes a `:-` default.
    required = set(re.findall(r"\$\{([A-Z0-9_]+):\?", compose))

    assert required == set(secret_check.SECRET_VARIABLES)


# --- The walk agrees with git --------------------------------------------------------------------


def _git(root: Path, *arguments: str) -> str:
    # An empty excludes file, so the machine's own global ignores cannot change the answer, and
    # case-sensitive matching, which Windows' `git init` would otherwise turn off.
    empty = root.parent / "empty-excludes"
    empty.write_bytes(b"")
    settings = ["-c", f"core.excludesFile={empty}", "-c", "core.ignorecase=false"]
    result = subprocess.run(
        ["git", "-C", str(root), *settings, *arguments], capture_output=True, check=True
    )
    return result.stdout.decode()


def Given_nested_negated_and_anchored_patterns_When_walked_Then_it_sees_what_git_does_not_ignore(
    tmp_path: Path,
) -> None:
    root = _clone(
        tmp_path / "repo",
        {
            "keep.txt": b"x",
            "build.log": b"x",
            "docs/keep.log.md": b"x",
            "target/out.class": b"x",
            "src/target/inner.txt": b"x",
            "anchored/only-root.txt": b"x",
            "nested/anchored/kept.txt": b"x",
            "nested/.gitignore": b"*.tmp\n!important.tmp\n/local-only/\n",
            "nested/drop.tmp": b"x",
            "nested/important.tmp": b"x",
            "nested/local-only/x.txt": b"x",
            "nested/deeper/local-only/kept.txt": b"x",
            "deep/a/b/wrapper.jar": b"x",
            "deep/.mvn/wrapper/maven-wrapper.jar": b"x",
            "cache/dropped.txt": b"x",
            "src/cache.txt": b"x",
            ".env": b"x",
            ".env.prod": b"x",
            ".env.example": b"x",
            "cfg[1].txt": b"x",
            "cfg2.txt": b"x",
        },
        gitignore=(
            b"# a comment\n\n*.log\ntarget/\n/anchored/\n*.jar\n"
            b"!**/.mvn/wrapper/maven-wrapper.jar\ncache/\n.env\n.env.*\n!.env.example\n"
            b"cfg[0-9].txt\n"
        ),
    )
    _git(root, "init", "-q")
    _git(root, "add", "keep.txt", "nested/.gitignore")
    seen_by_git = set(
        _git(root, "ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    )

    walked = set(secret_check.scanned_files(root))

    assert walked == seen_by_git


def Given_this_checkout_When_walked_Then_every_tracked_file_is_scanned() -> None:
    tracked = set(
        subprocess.run(
            ["git", "-C", str(REPOSITORY_ROOT), "ls-files"], capture_output=True, check=True
        )
        .stdout.decode()
        .splitlines()
    )

    walked = set(secret_check.scanned_files(REPOSITORY_ROOT))

    assert tracked - walked == set()

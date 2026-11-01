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
# Fixture values only: none is, or may ever be, a real secret. Each is at least MIN_SECRET_LENGTH
# characters, so a case about where a value appears is never refused for its length instead.
PUBLISHED = "Fixture-only-7Qv2Lk9Xw4-long-enough-to-pass"
FRESH = "Fixture-only-3Hn8Tc5Rm1-long-enough-to-pass"
# Root reads every directory whatever its mode, so an unreadable one cannot be staged as root.
if sys.platform == "win32":
    POSIX_PERMISSIONS_BIND = False
else:
    POSIX_PERMISSIONS_BIND = os.geteuid() != 0
# ERROR_PRIVILEGE_NOT_HELD: how Windows refuses a symlink to a user without Developer Mode.
WINDOWS_PRIVILEGE_NOT_HELD = 1314


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
    assert "REFUSED: POSTGRES_PASSWORD appears" in output
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
    assert "REFUSED: POSTGRES_PASSWORD appears" in output
    assert "REFUSED: LEDGER_DB_PASSWORD appears" in output
    assert "KEYCLOAK_DB_PASSWORD" not in output


def Given_a_value_only_inside_a_longer_token_When_checked_Then_it_passes(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"ci.yml": f"x={PUBLISHED}-12-chars\ny=a{PUBLISHED}\n".encode()})

    status, _ = _run(root, _environment(GATEWAY_CLIENT_SECRET=PUBLISHED), capsys)

    assert status == secret_check.EXIT_PASSED


# Read as a pattern, `.` takes any character, `y+` a run of them, and `(Lk9)` a group: so the
# literal text would not match it, and the near miss below would.
METACHARACTER_SECRET = "Fixture.only+7Qv2(Lk9)-long-enough-to-pass"
NEAR_MISS = "FixtureXonlyyy7Qv2Lk9-long-enough-to-pass"


def Given_a_secret_holding_regex_metacharacters_When_checked_Then_its_literal_text_is_refused(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"ci.yml": f"PASSWORD={METACHARACTER_SECRET}\n".encode()})

    status, output = _run(root, _environment(GATEWAY_CLIENT_SECRET=METACHARACTER_SECRET), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: GATEWAY_CLIENT_SECRET appears" in output


def Given_a_secret_holding_regex_metacharacters_When_only_a_near_miss_is_held_Then_it_passes(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"ci.yml": f"PASSWORD={NEAR_MISS}\n".encode()})

    status, _ = _run(root, _environment(GATEWAY_CLIENT_SECRET=METACHARACTER_SECRET), capsys)

    assert status == secret_check.EXIT_PASSED


def Given_a_non_ascii_secret_When_a_utf8_file_holds_it_Then_it_is_refused(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    secret = "Fixture-only-Ünïcødé-שלום-long-enough-to-pass"
    root = _clone(tmp_path, {"docs/notes.md": f"password: {secret}\n".encode()})

    status, output = _run(root, _environment(KEYCLOAK_ADMIN_PASSWORD=secret), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: KEYCLOAK_ADMIN_PASSWORD appears" in output
    assert secret not in output


# Windows PowerShell 5.1's `>` writes UTF-16LE with a byte-order mark, so `Get-Content .env >
# env-notes.txt` copies every secret into a file no UTF-8 search finds (ADR-038 names the shell).
@pytest.mark.parametrize(
    ("byte_order_mark", "encoding"),
    [(b"\xff\xfe", "utf-16-le"), (b"\xfe\xff", "utf-16-be")],
    ids=["utf-16-le", "utf-16-be"],
)
@pytest.mark.parametrize(
    "secret",
    [PUBLISHED, "Fixture-only-Ünïcødé-שלום-long-enough-to-pass"],
    ids=["ascii", "non-ascii"],
)
def Given_a_secret_a_utf16_file_holds_When_checked_Then_it_is_refused_by_name_alone(
    byte_order_mark: bytes,
    encoding: str,
    secret: str,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    copy = byte_order_mark + f"GRAFANA_ADMIN_PASSWORD={secret}\r\n".encode(encoding)
    root = _clone(tmp_path, {"env-notes.txt": copy})

    status, output = _run(root, _environment(GRAFANA_ADMIN_PASSWORD=secret), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: GRAFANA_ADMIN_PASSWORD appears" in output
    assert secret not in output
    assert "env-notes.txt" not in output


def Given_an_unset_or_empty_secret_When_checked_Then_it_is_skipped_rather_than_matched(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    environment = _environment(GRAFANA_ADMIN_PASSWORD="")
    del environment["ASSISTANT_CLIENT_SECRET"]

    status, output = _run(root, environment, capsys)

    assert status == secret_check.EXIT_PASSED
    assert f"{len(secret_check.SECRET_VARIABLES) - 2} secrets" in output


# --- How long a secret must be -------------------------------------------------------------------


def _of_length(length: int) -> str:
    """A fixture value of exactly `length` characters, held by no file in any clone here."""
    return ("Fixture-only-length-" + "x" * length)[:length]


@pytest.mark.parametrize("name", secret_check.SECRET_VARIABLES)
def Given_a_secret_one_character_short_of_the_minimum_When_checked_Then_it_is_refused_by_name_alone(
    name: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    short = _of_length(secret_check.MIN_SECRET_LENGTH - 1)

    status, output = _run(root, _environment(**{name: short}), capsys)

    assert status == secret_check.EXIT_REFUSED
    refusals = [line for line in output.splitlines() if "REFUSED" in line]
    assert refusals == [
        f"secret-check REFUSED: {name} is shorter than the "
        f"{secret_check.MIN_SECRET_LENGTH} characters every secret needs"
    ]
    assert secret_check.ROTATION_GUIDE in output
    assert short not in output
    assert str(secret_check.MIN_SECRET_LENGTH - 1) not in output


@pytest.mark.parametrize("name", secret_check.SECRET_VARIABLES)
def Given_a_secret_of_exactly_the_minimum_length_When_checked_Then_it_passes(
    name: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})

    status, _ = _run(
        root, _environment(**{name: _of_length(secret_check.MIN_SECRET_LENGTH)}), capsys
    )

    assert status == secret_check.EXIT_PASSED


# Two bytes each in UTF-8, so a value of these is twice as many bytes as characters.
MULTI_BYTE_CHARACTER = "é"


def Given_a_secret_of_the_minimum_length_in_multi_byte_characters_When_checked_Then_it_passes(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    secret = MULTI_BYTE_CHARACTER * secret_check.MIN_SECRET_LENGTH

    status, _ = _run(root, _environment(KEYCLOAK_ADMIN_PASSWORD=secret), capsys)

    assert status == secret_check.EXIT_PASSED


def Given_a_secret_one_multi_byte_character_short_When_checked_Then_its_bytes_do_not_count(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    secret = MULTI_BYTE_CHARACTER * (secret_check.MIN_SECRET_LENGTH - 1)
    assert len(secret.encode()) >= secret_check.MIN_SECRET_LENGTH

    status, output = _run(root, _environment(KEYCLOAK_ADMIN_PASSWORD=secret), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: KEYCLOAK_ADMIN_PASSWORD is shorter" in output


def Given_the_minimum_length_When_read_Then_it_is_what_openssl_rand_hex_16_writes() -> None:
    # 16 random bytes, two hex digits each: the generator docs/operations/operations.md documents.
    assert secret_check.MIN_SECRET_LENGTH == 32


def Given_a_short_secret_a_committed_file_also_holds_When_checked_Then_both_refusals_are_named(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    short = _of_length(secret_check.MIN_SECRET_LENGTH - 1)
    root = _clone(tmp_path, {".env.example": f"POSTGRES_PASSWORD={short}\n".encode()})

    status, output = _run(root, _environment(POSTGRES_PASSWORD=short), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: POSTGRES_PASSWORD appears" in output
    assert "REFUSED: POSTGRES_PASSWORD is shorter" in output
    assert short not in output


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

    status, output = _run(root, _environment(POSTGRES_PASSWORD=PUBLISHED), capsys)

    assert status == secret_check.EXIT_REFUSED
    assert "REFUSED: POSTGRES_PASSWORD appears" in output


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
    except OSError as error:
        # Only Windows without Developer Mode may refuse this; anywhere else it is a real failure.
        if getattr(error, "winerror", None) != WINDOWS_PRIVILEGE_NOT_HELD:
            raise
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


@pytest.mark.skipif(
    not POSIX_PERMISSIONS_BIND, reason="needs POSIX permissions, as a non-root user"
)
def Given_an_unreadable_file_git_does_not_ignore_When_checked_Then_it_fails_misconfigured(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"locked.txt": f"PASSWORD={PUBLISHED}\n".encode()})
    (root / "locked.txt").chmod(0)
    try:
        status, output = _run(root, _environment(), capsys)
    finally:
        (root / "locked.txt").chmod(0o644)

    assert status == secret_check.EXIT_MISCONFIGURED
    assert "cannot read" in output
    assert "PermissionError" in output


def Given_no_root_argument_When_checked_Then_it_fails_misconfigured(
    capsys: pytest.CaptureFixture[str],
) -> None:
    status = secret_check.main([], _environment())

    assert status == secret_check.EXIT_MISCONFIGURED
    assert "the repository's root as the only argument" in capsys.readouterr().out


def Given_two_root_arguments_When_checked_Then_it_fails_misconfigured(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})

    status = secret_check.main([str(root), str(root)], _environment())

    assert status == secret_check.EXIT_MISCONFIGURED
    assert "the repository's root as the only argument" in capsys.readouterr().out


def Given_an_unexpected_failure_When_checked_Then_only_its_type_is_reported(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    root = _clone(tmp_path, {"README.md": b"text\n"})
    read = secret_check._read

    def failing_read(path: Path) -> bytes:
        # Fails on the content read alone: the ignore files before it are still read as usual.
        if path.name != "README.md":
            return read(path)
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


def Given_the_compose_file_When_the_check_service_is_read_Then_it_is_handed_each_secret() -> None:
    compose = (REPOSITORY_ROOT / "docker-compose.yml").read_bytes().decode()

    # The `secret-check` service takes each secret as `NAME: ${NAME:-}`, with an empty default.
    block = _service_block(compose, "secret-check")
    handed = set(re.findall(r"^\s+([A-Z0-9_]+): \$\{\1:-\}$", block, re.MULTILINE))

    assert handed == set(secret_check.SECRET_VARIABLES)


def _service_block(compose: str, service: str) -> str:
    """The lines under `service`'s key in `services:`, up to the next key at its indent or less."""
    lines = compose.splitlines()
    start = lines.index(f"  {service}:")
    ends = (
        index for index in range(start + 1, len(lines)) if re.match(r"^ {0,2}[^\s#]", lines[index])
    )
    return "\n".join(lines[start + 1 : next(ends, len(lines))])


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
            "drop.secret": b"x",
            "keep.secret": b"x",
        },
        gitignore=(
            b"# a comment\n\n*.log\ntarget/\n/anchored/\n*.jar\n"
            b"!**/.mvn/wrapper/maven-wrapper.jar\ncache/\n.env\n.env.*\n!.env.example\n"
            b"cfg[0-9].txt\n!keep.secret\n"
        ),
    )
    _git(root, "init", "-q")
    # Below every .gitignore in precedence, so the root one re-includes `keep.secret`.
    (root / ".git" / "info" / "exclude").write_bytes(b"*.secret\n")
    _git(root, "add", "keep.txt", "nested/.gitignore")
    seen_by_git = set(
        _git(root, "ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    )

    walked = set(secret_check.scanned_files(root))

    assert walked == seen_by_git


def Given_ignore_files_git_reads_byte_by_byte_When_walked_Then_it_sees_what_git_does_not_ignore(
    tmp_path: Path,
) -> None:
    # Each line is a way the walker once read an ignore file differently from git, and hid a file
    # that git would publish: a trailing tab kept, a lone carriage return as a line break, a BOM
    # cancelling a first-line negation, and an unclosed `[` matched as a literal. The lines after
    # `*.txt` pin the rest of gitignore(5)'s glob forms: a trailing `/**`, one negated, `**` between
    # two components, `?`, `[!...]`, and a backslash escaping `#`, `!` or a trailing space.
    root = _clone(
        tmp_path / "repo",
        {
            "foo": b"x",
            "a": b"x",
            "b": b"x",
            "bar[": b"x",
            "baz": b"x",
            "sub/.gitignore": b"\xef\xbb\xbf!keep.txt\n",
            "sub/keep.txt": b"x",
            "sub/drop.txt": b"x",
            "logs/run.dat": b"x",
            "logs/deep/run.dat": b"x",
            "drop.out": b"x",
            "keep/a.out": b"x",
            "keep/deep/b.out": b"x",
            "deep/z.bin": b"x",
            "deep/x/y/z.bin": b"x",
            "other/deep/z.bin": b"x",
            "data.bin": b"x",
            "dat.bin": b"x",
            "datum.bin": b"x",
            "va.bin": b"x",
            "v1.bin": b"x",
            "#hash": b"x",
            "!bang": b"x",
            "space": b"x",
        },
        gitignore=(
            b"foo\t\na\rb\nbar[\nbaz   \n*.txt\n"
            b"logs/**\n*.out\n!keep/**\ndeep/**/z.bin\ndat?.bin\nv[!0-9].bin\n"
            b"\\#hash\n\\!bang\nspace\\ \n"
        ),
    )
    _git(root, "init", "-q")
    seen_by_git = set(
        _git(root, "ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    )

    walked = set(secret_check.scanned_files(root))

    assert walked == seen_by_git
    assert {"foo", "a", "b", "bar[", "sub/keep.txt"} <= walked
    assert {"keep/a.out", "keep/deep/b.out", "other/deep/z.bin", "dat.bin", "datum.bin"} <= walked
    assert {"v1.bin", "space"} <= walked
    assert walked.isdisjoint(
        {"logs/run.dat", "logs/deep/run.dat", "drop.out", "deep/z.bin", "deep/x/y/z.bin"}
    )
    assert walked.isdisjoint({"data.bin", "va.bin", "#hash", "!bang"})


@pytest.mark.skipif(
    sys.platform == "win32", reason="Windows cannot name a file with a trailing space"
)
def Given_an_escaped_trailing_space_When_walked_Then_it_sees_what_git_does_not_ignore(
    tmp_path: Path,
) -> None:
    root = _clone(tmp_path / "repo", {"space ": b"x", "space": b"x"}, gitignore=b"space\\ \n")
    _git(root, "init", "-q")
    seen_by_git = set(
        _git(root, "ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    )

    walked = set(secret_check.scanned_files(root))

    assert walked == seen_by_git
    assert "space" in walked
    assert "space " not in walked


def Given_this_checkout_When_walked_Then_every_tracked_file_is_scanned() -> None:
    tracked = set(
        subprocess.run(
            ["git", "-C", str(REPOSITORY_ROOT), "ls-files"], capture_output=True, check=True
        )
        .stdout.decode()
        .splitlines()
    )

    walked = set(secret_check.scanned_files(REPOSITORY_ROOT))

    assert tracked, "git ls-files listed nothing, so there was nothing to compare"
    assert tracked - walked == set()

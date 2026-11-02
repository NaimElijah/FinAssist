# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Refuses a secret that a file in this repository already holds, before any database starts (#302).

Compose runs this as the one-shot service `secret-check`, which `db`, `keycloak-db` and `grafana`
wait on (ADR-044). PostgreSQL applies a password only when it first initialises an empty volume, so
a check made any later, such as in the ledger's start-up, would come after a published value was
already in the store.

It also refuses a secret shorter than `MIN_SECRET_LENGTH` characters, and a `LEDGER_DB_PASSWORD`
that differs from `POSTGRES_PASSWORD` while the ledger signs in as the role `db` creates, which
would otherwise pass here and leave the ledger refused at every connection, restarting.

It reads each secret from its own environment and walks the clone it is given, mounted read-only.
It searches every file that git would not ignore for each value: its UTF-8 bytes as a whole token,
and its UTF-16 bytes, in either byte order, anywhere. "Ignored" means what git decides from the
`.gitignore` files and `.git/info/exclude`, so `.env`, the backups and every build output are never
read. A file not yet committed is read, because the next `git add .` would publish it.

The report names a variable, never its value or its length, and never the file that holds it: in
a public repository, naming the file is as good as naming the value. Standard library only, so it
runs unmodified in the pinned `python:3.13.18-slim-trixie` image, as `ops/smoke/auth_smoke.py` does.
"""

from __future__ import annotations

import os
import re
import sys
from collections.abc import Callable, Iterator, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Final

# Every secret docker-compose.yml requires through `${VAR:?}`, and hands the `secret-check` service
# as `${VAR:-}`; tests/test_secret_check.py keeps all three lists equal.
SECRET_VARIABLES: Final = (
    "POSTGRES_PASSWORD",
    "LEDGER_DB_PASSWORD",
    "KEYCLOAK_DB_PASSWORD",
    "KEYCLOAK_ADMIN_PASSWORD",
    "FINASSIST_OWNER_INITIAL_PASSWORD",
    "GATEWAY_CLIENT_SECRET",
    "ASSISTANT_CLIENT_SECRET",
    "GRAFANA_ADMIN_PASSWORD",
)
# The ledger signs in as the role `db` creates until #34 gives it one of its own (ADR-024): while
# these two usernames name the same role, its two passwords must be one value. The usernames are
# not secrets, so Compose hands them with `db`'s and the ledger's own defaults, and they are never
# searched for.
DATABASE_ROLE_USER: Final = "POSTGRES_USER"
DATABASE_ROLE_PASSWORD: Final = "POSTGRES_PASSWORD"
LEDGER_ROLE_USER: Final = "LEDGER_DB_USERNAME"
LEDGER_ROLE_PASSWORD: Final = "LEDGER_DB_PASSWORD"
# What `openssl rand -hex 16`, the generator docs/operations/operations.md documents, writes. Length
# is the only bound on guessing a secret that no account lockout protects.
MIN_SECRET_LENGTH: Final = 32
# The scan root must hold this, or it is not the repository and nothing was searched.
ANCHOR_FILE: Final = "docker-compose.yml"
ROTATION_GUIDE: Final = "docs/operations/operations.md#rotating-a-secret"
# A mismatch alone needs no new value: both variables take the one the role already holds.
LEDGER_PASSWORD_GUIDE: Final = "docs/operations/operations.md#the-ledgers-database-password"
EXIT_PASSED: Final = 0
EXIT_REFUSED: Final = 1
EXIT_MISCONFIGURED: Final = 2
# A value matches only where it stands whole: not where these characters continue it on either
# side, so `ci-only` does not match inside `ci-only-12-chars`.
TOKEN_CHARACTER: Final = rb"[A-Za-z0-9_-]"
# Windows PowerShell 5.1, the owner's shell (ADR-038), writes UTF-16LE through `>`, so a copy of
# `.env` made with it holds no value's UTF-8 bytes. Either byte order is searched, mark or none.
UTF16_ENCODINGS: Final = ("utf-16-le", "utf-16-be")
# A directory in a clone, a file in a worktree; git itself never reads either as content.
GIT_METADATA: Final = ".git"
GITIGNORE: Final = ".gitignore"
INFO_EXCLUDE: Final = Path(".git", "info", "exclude")
# git's wildmatch never matches a pattern with an unclosed `[`, so neither does this.
NEVER: Final = re.compile(r"(?!)")
# Reading the clone over Docker Desktop's bind mount is latency, not work: 691 files took 5.7 s one
# at a time and 0.7 s on eight threads, measured on the owner's machine on 2026-10-18.
READ_WORKERS: Final = 8


class SecretCheckError(Exception):
    """The check cannot vouch for the tree: it was not given the repository, or cannot read it."""


@dataclass(frozen=True)
class Verdict:
    """What one check found.

    :param published: the variables whose value a searched file holds, in `SECRET_VARIABLES` order.
    :param short: the variables whose value is shorter than `MIN_SECRET_LENGTH`, in the same order.
    :param ledger_password_compared: whether `LEDGER_ROLE_USER` and `DATABASE_ROLE_USER` name one
        role, so that the two passwords were compared.
    :param ledger_password_differs: whether `LEDGER_ROLE_PASSWORD` differs from
        `DATABASE_ROLE_PASSWORD` while they do; never true unless `ledger_password_compared` is.
    :param checked: how many variables had a value to look for; an unset or empty one has none.
    :param files: how many files were searched.
    """

    published: tuple[str, ...]
    short: tuple[str, ...]
    ledger_password_compared: bool
    ledger_password_differs: bool
    checked: int
    files: int


@dataclass(frozen=True)
class _IgnoreRule:
    """One pattern line from the ignore file in `base`, a POSIX path relative to the root."""

    base: str
    regex: re.Pattern[str]
    negated: bool
    directory_only: bool
    # A pattern with a slash before its end is matched against the path below `base`; one
    # without is matched against the last component alone, at any depth.
    anchored: bool

    def matches(self, path: str, is_directory: bool) -> bool:
        if self.directory_only and not is_directory:
            return False
        if self.base:
            prefix = f"{self.base}/"
            if not path.startswith(prefix):
                return False
            path = path.removeprefix(prefix)
        subject = path if self.anchored else path.rsplit("/", 1)[-1]
        return self.regex.fullmatch(subject) is not None


def _glob(pattern: str) -> re.Pattern[str]:
    """Translates one gitignore glob into a regular expression over a `/`-separated path."""
    parts: list[str] = []
    index = 0
    while index < len(pattern):
        if pattern.startswith("**/", index) and (index == 0 or pattern[index - 1] == "/"):
            parts.append("(?:.*/)?")
            index += 3
        elif pattern.startswith("/**", index) and index + 3 == len(pattern):
            parts.append("/.*")
            index += 3
        elif pattern[index] == "*":
            while index < len(pattern) and pattern[index] == "*":
                index += 1
            parts.append("[^/]*")
        elif pattern[index] == "?":
            parts.append("[^/]")
            index += 1
        elif pattern[index] == "[":
            end = pattern.find("]", index + 2)
            if end == -1:
                return NEVER
            members = pattern[index + 1 : end].replace("\\", "\\\\")
            if members.startswith("!"):
                members = f"^{members[1:]}"
            parts.append(f"(?!/)[{members}]")
            index = end + 1
        elif pattern[index] == "\\" and index + 1 < len(pattern):
            parts.append(re.escape(pattern[index + 1]))
            index += 2
        else:
            parts.append(re.escape(pattern[index]))
            index += 1
    return re.compile("".join(parts))


def _trailing_spaces_removed(line: str) -> str:
    """Drops trailing spaces as git does: spaces only, and never one escaped with a backslash."""
    end = len(line)
    while end > 0 and line[end - 1] == " " and not line[: end - 1].endswith("\\"):
        end -= 1
    return line[:end]


def _rules(text: str, base: str) -> tuple[_IgnoreRule, ...]:
    """Parses one ignore file, whose patterns apply below `base`.

    Lines are split as git splits them, on a line feed alone, with one carriage return before it
    dropped. `str.splitlines` would also break at a lone carriage return, a form feed or a
    vertical tab, and so read patterns that git does not.
    """
    rules: list[_IgnoreRule] = []
    for line in text.split("\n"):
        pattern = _trailing_spaces_removed(line.removesuffix("\r"))
        if not pattern or pattern.startswith("#"):
            continue
        negated = pattern.startswith("!")
        pattern = pattern.removeprefix("!")
        directory_only = pattern.endswith("/")
        pattern = pattern.rstrip("/")
        anchored = "/" in pattern
        rules.append(
            _IgnoreRule(base, _glob(pattern.removeprefix("/")), negated, directory_only, anchored)
        )
    return tuple(rules)


def _ignored(path: str, is_directory: bool, rules: tuple[_IgnoreRule, ...]) -> bool:
    """Whether git ignores `path`: the last rule that matches decides, as in git."""
    ignored = False
    for rule in rules:
        if rule.matches(path, is_directory):
            ignored = not rule.negated
    return ignored


# The one place bytes are read from the clone, so a test can make a read fail.
def _read(path: Path) -> bytes:
    return path.read_bytes()


def _ignore_file(root: Path, relative: str) -> str:
    # git skips a UTF-8 byte-order mark, which PowerShell writes; kept, it would turn a first-line
    # `!pattern` into a pattern that matches nothing.
    return _content(root, relative).decode("utf-8-sig", "replace")


def _walk(root: Path, directory: str, inherited: tuple[_IgnoreRule, ...]) -> Iterator[str]:
    here = root / directory if directory else root
    try:
        with os.scandir(here) as listing:
            entries = sorted(listing, key=lambda entry: entry.name)
    except OSError as error:
        raise SecretCheckError(f"cannot list {directory or '.'}: {type(error).__name__}") from error
    rules = inherited
    if any(entry.name == GITIGNORE and entry.is_file(follow_symlinks=False) for entry in entries):
        rules += _rules(
            _ignore_file(root, f"{directory}/{GITIGNORE}" if directory else GITIGNORE), directory
        )
    for entry in entries:
        # A symlink's target may lie outside the clone, and git stores only the link itself.
        if entry.name == GIT_METADATA or entry.is_symlink():
            continue
        path = f"{directory}/{entry.name}" if directory else entry.name
        is_directory = entry.is_dir(follow_symlinks=False)
        if _ignored(path, is_directory, rules):
            continue
        if is_directory:
            yield from _walk(root, path, rules)
        elif entry.is_file(follow_symlinks=False):
            yield path


def scanned_files(root: Path) -> Iterator[str]:
    """Yields every file under `root` that git would not ignore, as a POSIX path relative to it.

    Ignore rules come from each directory's `.gitignore` and from `.git/info/exclude` when `root`
    holds one. An ignored directory is never entered, which is also why git cannot re-include a
    file below one. A file git tracks although a pattern matches it, as `git add -f` makes, is not
    yielded: only the index knows it, and this reads the patterns alone. The checkout test in
    `tests/test_secret_check.py` fails the day one appears (ADR-044).

    :raises SecretCheckError: if a directory git does not ignore cannot be listed, or an ignore
        file cannot be read; either is named, with the error's type and never its message.
    """
    exclude = root / INFO_EXCLUDE
    rules = _rules(_ignore_file(root, INFO_EXCLUDE.as_posix()), "") if exclude.is_file() else ()
    yield from _walk(root, "", rules)


def _token(value: str) -> re.Pattern[bytes]:
    # os.fsencode undoes the surrogate escapes os.environ decodes undecodable bytes into, so the
    # search is for the very bytes Compose handed this process.
    return re.compile(
        b"(?<!%s)%s(?!%s)" % (TOKEN_CHARACTER, re.escape(os.fsencode(value)), TOKEN_CHARACTER)
    )


def _finder(value: str) -> Callable[[bytes], bool]:
    token = _token(value)
    # Matched anywhere, not as a whole token: a refused longer token fails closed, and a value's
    # boundary in UTF-16 is two bytes the token pattern does not describe. `surrogatepass` encodes
    # the escapes of undecodable bytes rather than raising; such a value is no text to search for.
    wide = tuple(value.encode(encoding, "surrogatepass") for encoding in UTF16_ENCODINGS)
    return lambda content: (
        token.search(content) is not None or any(form in content for form in wide)
    )


def _ledger_shares_role(environment: Mapping[str, str]) -> bool:
    """Whether the ledger signs in as the role `db` creates.

    An unset or empty username names no role, so nothing is compared: Compose always hands both.
    """
    database_user = environment.get(DATABASE_ROLE_USER, "")
    return bool(database_user) and environment.get(LEDGER_ROLE_USER, "") == database_user


def _ledger_password_differs(environment: Mapping[str, str]) -> bool:
    """Whether the ledger signs in as the role `db` creates, with a password that is not the role's.

    One password empty and the other not counts as differing, so the check fails closed.
    """
    return _ledger_shares_role(environment) and (
        environment.get(LEDGER_ROLE_PASSWORD, "") != environment.get(DATABASE_ROLE_PASSWORD, "")
    )


def check(root: Path, environment: Mapping[str, str]) -> Verdict:
    """Searches every file under `root` that git would not ignore for each secret in `environment`,
    measures each one against `MIN_SECRET_LENGTH`, and compares the ledger's database password with
    the role's when the ledger signs in as the role `db` creates.

    :param root: the repository's root, which must hold `ANCHOR_FILE`.
    :param environment: where each of `SECRET_VARIABLES` is read from, and the two usernames; an
        unset or empty secret is skipped, since each service that needs it refuses to start without
        it anyway.
    :return: which variables are published or too short, whether the ledger's password differs from
        its role's, and how many variables were looked for and files searched.
    :raises SecretCheckError: if `root` is not the repository, or a directory or a file in it that
        git does not ignore, or an ignore file, cannot be read.
    """
    if not (root / ANCHOR_FILE).is_file():
        raise SecretCheckError(
            f"{root} holds no {ANCHOR_FILE}, so it is not the repository; nothing was searched"
        )
    values = {name: value for name in SECRET_VARIABLES if (value := environment.get(name, ""))}
    finders = {name: _finder(value) for name, value in values.items()}
    files = list(scanned_files(root))
    found: set[str] = set()
    with ThreadPoolExecutor(max_workers=READ_WORKERS) as pool:
        for content in pool.map(lambda relative: _content(root, relative), files):
            found.update(name for name, holds in finders.items() if holds(content))
    return Verdict(
        published=tuple(name for name in SECRET_VARIABLES if name in found),
        short=tuple(name for name, value in values.items() if len(value) < MIN_SECRET_LENGTH),
        ledger_password_compared=_ledger_shares_role(environment),
        ledger_password_differs=_ledger_password_differs(environment),
        checked=len(finders),
        files=len(files),
    )


def _content(root: Path, relative: str) -> bytes:
    # Wrapped so that an unreadable file stops the check as a SecretCheckError naming it, as
    # `_walk` names a directory it cannot list. Only the error's type is kept, never its message:
    # that is text this check did not write, an OSError's repeating the path it failed on and the
    # platform's wording, and the report quotes nothing it did not compose itself.
    try:
        return _read(root / relative)
    except OSError as error:
        raise SecretCheckError(f"cannot read {relative}: {type(error).__name__}") from error


def _report(line: str) -> None:
    # The verdict on stdout is this check's whole product: Compose prints it beside the services'
    # output, and `docker compose logs secret-check` keeps it.
    print(line, flush=True)  # noqa: T201


def main(argv: Sequence[str], environment: Mapping[str, str]) -> int:
    """Checks the clone named by the only argument against the secrets in `environment`.

    Writes the verdict to stdout, naming each refused variable and never its value or the file
    that holds it.

    :param argv: the command line without the program name: exactly one path, the repository root.
    :param environment: where each secret is read from, as for `check`.
    :return: `EXIT_PASSED` when no secret appears in a file git does not ignore, none is shorter
        than `MIN_SECRET_LENGTH` and the ledger's password is its role's; `EXIT_REFUSED` when any
        of the three does not hold; `EXIT_MISCONFIGURED` when the clone could not be searched.
        Compose starts nothing that waits on this unless it is `EXIT_PASSED`.
    """
    if len(argv) != 1:
        _report("secret-check FAILED: give the repository's root as the only argument")
        return EXIT_MISCONFIGURED
    try:
        verdict = check(Path(argv[0]), environment)
    except SecretCheckError as failure:
        _report(f"secret-check FAILED: {failure}")
        return EXIT_MISCONFIGURED
    except Exception as error:
        # Deliberately broad, and last: an unforeseen error's message could quote what it was
        # reading, which may be a secret. So only its type is reported, and the stack still
        # refuses to start.
        _report(f"secret-check FAILED: {type(error).__name__}")
        return EXIT_MISCONFIGURED
    if verdict.published or verdict.short or verdict.ledger_password_differs:
        for name in verdict.published:
            _report(
                f"secret-check REFUSED: {name} appears in a file git does not ignore, so the "
                "repository publishes it, or will once that file is committed"
            )
        for name in verdict.short:
            _report(
                f"secret-check REFUSED: {name} is shorter than the {MIN_SECRET_LENGTH} characters "
                "every secret needs"
            )
        if verdict.ledger_password_differs:
            _report(
                f"secret-check REFUSED: {LEDGER_ROLE_PASSWORD} differs from "
                f"{DATABASE_ROLE_PASSWORD}, while {LEDGER_ROLE_USER} and {DATABASE_ROLE_USER} name "
                "the same role, so the database would refuse the ledger's every sign-in. Give both "
                "one value"
            )
        if verdict.published or verdict.short:
            _report(
                "secret-check: nothing that waits on this check starts until each one is changed. "
                f"Write a new value into .env, then rotate it in place: {ROTATION_GUIDE}"
            )
        if verdict.ledger_password_differs:
            # Which of the two the role holds is not visible here, and a new value would need
            # rotating first; the one the ledger last signed in with is the role's.
            _report(
                "secret-check: nothing that waits on this check starts until the two match. Set "
                f"both {LEDGER_ROLE_PASSWORD} and {DATABASE_ROLE_PASSWORD} in .env to the password "
                "the role holds now, which is the one the ledger signs in with, or rotate it in "
                f"place first: {LEDGER_PASSWORD_GUIDE}"
            )
        return EXIT_REFUSED
    role_password = (
        f"; {LEDGER_ROLE_PASSWORD} is the password of the role {LEDGER_ROLE_USER} and "
        f"{DATABASE_ROLE_USER} both name"
        if verdict.ledger_password_compared
        else ""
    )
    _report(
        f"secret-check passed: none of the {verdict.checked} secrets set appears in the "
        f"{verdict.files} files git does not ignore, and each holds at least {MIN_SECRET_LENGTH} "
        f"characters{role_password}"
    )
    return EXIT_PASSED


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))

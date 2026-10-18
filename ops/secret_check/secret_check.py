# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Refuses a secret that a file in this repository already holds, before any database starts (#302).

Compose runs this as the one-shot service `secret-check`, which `db`, `keycloak-db` and `grafana`
wait on (ADR-044). PostgreSQL applies a password only when it first initialises an empty volume, so
a check made any later, such as in the ledger's start-up, would come after a published value was
already in the store.

It reads each secret from its own environment and walks the clone it is given, mounted read-only.
It searches every file that git would not ignore for each value, as a whole token. "Ignored" means
what git decides from the `.gitignore` files and `.git/info/exclude`, so `.env`, the backups and
every build output are never read. A file not yet committed is read, because the next `git add .`
would publish it.

The report names a variable, never its value, and never the file that holds it: in a public
repository, naming the file is as good as naming the value. Standard library only, so it runs
unmodified in the pinned `python:3.13.8-slim-trixie` image, as `ops/smoke/auth_smoke.py` does.
"""

from __future__ import annotations

import os
import re
import sys
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Final

# Every secret docker-compose.yml requires through `${VAR:?}`; tests/test_secret_check.py keeps
# the two lists equal.
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
# The scan root must hold this, or it is not the repository and nothing was searched.
ANCHOR_FILE: Final = "docker-compose.yml"
ROTATION_GUIDE: Final = "docs/operations/operations.md#rotating-a-secret"
EXIT_PASSED: Final = 0
EXIT_REFUSED: Final = 1
EXIT_MISCONFIGURED: Final = 2
# A value matches only where it stands whole: not where these characters continue it on either
# side, so `ci-only` does not match inside `ci-only-12-chars`.
TOKEN_CHARACTER: Final = rb"[A-Za-z0-9_-]"
# A directory in a clone, a file in a worktree; git itself never reads either as content.
GIT_METADATA: Final = ".git"
GITIGNORE: Final = ".gitignore"
INFO_EXCLUDE: Final = Path(".git", "info", "exclude")


class SecretCheckError(Exception):
    """The check cannot vouch for the tree: it was not given the repository, or cannot read it."""


@dataclass(frozen=True)
class Verdict:
    """What one check found.

    :param published: the variables whose value a searched file holds, in `SECRET_VARIABLES` order.
    :param checked: how many variables had a value to look for; an unset or empty one has none.
    :param files: how many files were searched.
    """

    published: tuple[str, ...]
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
        elif pattern[index] == "[" and (end := pattern.find("]", index + 2)) != -1:
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


def _rules(text: str, base: str) -> tuple[_IgnoreRule, ...]:
    """Parses one ignore file, whose patterns apply below `base`."""
    rules: list[_IgnoreRule] = []
    for line in text.splitlines():
        pattern = line.rstrip()
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


def _read(path: Path) -> bytes:
    return path.read_bytes()


def _walk(root: Path, directory: str, inherited: tuple[_IgnoreRule, ...]) -> Iterator[str]:
    here = root / directory if directory else root
    try:
        with os.scandir(here) as listing:
            entries = sorted(listing, key=lambda entry: entry.name)
    except OSError as error:
        raise SecretCheckError(f"cannot list {directory or '.'}: {type(error).__name__}") from error
    rules = inherited
    if any(entry.name == GITIGNORE and entry.is_file(follow_symlinks=False) for entry in entries):
        rules += _rules(_read(here / GITIGNORE).decode("utf-8", "replace"), directory)
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
    file below one.

    :raises SecretCheckError: if a directory git does not ignore cannot be listed.
    """
    exclude = root / INFO_EXCLUDE
    rules = _rules(_read(exclude).decode("utf-8", "replace"), "") if exclude.is_file() else ()
    yield from _walk(root, "", rules)


def _token(value: str) -> re.Pattern[bytes]:
    # os.fsencode undoes the surrogate escapes os.environ decodes undecodable bytes into, so the
    # search is for the very bytes Compose handed this process.
    return re.compile(
        b"(?<!%s)%s(?!%s)" % (TOKEN_CHARACTER, re.escape(os.fsencode(value)), TOKEN_CHARACTER)
    )


def check(root: Path, environment: Mapping[str, str]) -> Verdict:
    """Searches every file under `root` that git would not ignore for each secret in `environment`.

    :param root: the repository's root, which must hold `ANCHOR_FILE`.
    :param environment: where each of `SECRET_VARIABLES` is read from; an unset or empty one is
        skipped, since each service that needs it refuses to start without it anyway.
    :raises SecretCheckError: if `root` is not the repository, or a directory or file in it that
        git does not ignore cannot be read.
    """
    if not (root / ANCHOR_FILE).is_file():
        raise SecretCheckError(
            f"{root} holds no {ANCHOR_FILE}, so it is not the repository; nothing was searched"
        )
    tokens = {
        name: _token(value) for name in SECRET_VARIABLES if (value := environment.get(name, ""))
    }
    found: set[str] = set()
    files = 0
    for relative in scanned_files(root):
        pending = [name for name in tokens if name not in found]
        if not pending:
            break
        try:
            content = _read(root / relative)
        except OSError as error:
            raise SecretCheckError(f"cannot read {relative}: {type(error).__name__}") from error
        files += 1
        found.update(name for name in pending if tokens[name].search(content))
    return Verdict(tuple(name for name in SECRET_VARIABLES if name in found), len(tokens), files)


def _report(line: str) -> None:
    # The verdict on stdout is this check's whole product: Compose prints it beside the services'
    # output, and `docker compose logs secret-check` keeps it.
    print(line, flush=True)  # noqa: T201


def main(argv: Sequence[str], environment: Mapping[str, str]) -> int:
    """Checks the clone named by the only argument against the secrets in `environment`.

    :return: `EXIT_PASSED` when no secret appears in a file git does not ignore, `EXIT_REFUSED`
        when one does, and `EXIT_MISCONFIGURED` when the clone could not be searched. Compose
        starts nothing that waits on this unless it is `EXIT_PASSED`.
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
    if verdict.published:
        for name in verdict.published:
            _report(
                f"secret-check REFUSED: {name} appears in a file git does not ignore, so the "
                "repository publishes it, or will once that file is committed"
            )
        _report(
            "secret-check: nothing that waits on this check starts until each one is changed. "
            f"Write a new value into .env, then rotate it in place: {ROTATION_GUIDE}"
        )
        return EXIT_REFUSED
    _report(
        f"secret-check passed: none of the {verdict.checked} secrets set appears in the "
        f"{verdict.files} files git does not ignore"
    )
    return EXIT_PASSED


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))

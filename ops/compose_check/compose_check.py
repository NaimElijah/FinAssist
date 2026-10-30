# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Checks the resolved Compose file against the stack's restart, log and readiness conventions.

`make check-compose` pipes `docker compose config --format json`, under every profile, into this
script's standard input (ADR-049). Every long-running service must take the restart policy and the
log bound exactly as the `x-restart-policy` and `x-logging` blocks set them, the one-shot services
must take neither, and four healthchecks must probe readiness (#142, ADR-039). Neither the ledger
nor the gateway may set, in its environment, a key that reopens the Actuator exposure their code
pins, nor a `command` or `entrypoint`, whose arguments would reach Spring as properties; nor may the
gateway set a log level, a log group, a logging configuration or Spring Boot's `debug` or `trace`
switch, since below INFO it logs credentials.

It reads the resolved file, not its text, so an alias that stops resolving fails as surely as a
service that never took one, and a block copied in with other values fails too.

The resolved file carries every secret Compose interpolated, so the report names a service, an
`x-` block, a probe path or an environment key, never a value. Input that is not JSON is reported
by the error's type alone, input nested too deeply to parse as such, and JSON of the wrong shape by
the part that is not a mapping. Standard library only; run it with `uv run`, as
`ops/backup/backup.py` is.
"""

from __future__ import annotations

import json
import re
import sys
from collections.abc import Mapping
from typing import BinaryIO, Final

EXIT_PASSED: Final = 0
EXIT_FAILED: Final = 1
EXIT_MISCONFIGURED: Final = 2
RESTART_BLOCK: Final = "x-restart-policy"
LOGGING_BLOCK: Final = "x-logging"
# Their failure must stay a failure rather than loop (docker-compose.yml).
ONE_SHOT_SERVICES: Final = frozenset({"auth-smoke", "secret-check"})
# Readiness, not liveness, so `service_healthy` means "can serve" (docker-compose.yml).
READINESS_PROBES: Final = {
    "ledger": "/actuator/health/readiness",
    "gateway": "/actuator/health/readiness",
    "assistant": "/ready",
    "keycloak": "/health/ready",
}
# The Spring services pin their Actuator exposure in code, and their tests read the test JVM's
# environment, never Compose's: so a Compose key that reopens it would pass every other test.
SPRING_SERVICES: Final = frozenset({"ledger", "gateway"})
ACTUATOR_WEB_PREFIX: Final = "MANAGEMENT_ENDPOINTS_WEB_"
SPRING_APPLICATION_JSON: Final = "SPRING_APPLICATION_JSON"
# SPRING_CONFIG_IMPORT, _ADDITIONAL_LOCATION and the rest point Spring at another document, which
# can widen the exposure list as surely as the keys above.
SPRING_CONFIG_PREFIX: Final = "SPRING_CONFIG_"
# Both images run `java -jar` and pass their arguments to SpringApplication.run, so a -D the JVM
# reads from one of these, or an argument a Compose command appends, outranks application.yml too.
JVM_OPTION_VARIABLES: Final = frozenset({"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"})
COMMAND_LINE_MEMBERS: Final = ("command", "entrypoint")
# DEBUG on the gateway's Spring Web or Security loggers writes the authorization code, its PKCE
# verifier and a refresh grant's form body: so its levels stay as application.yml sets them.
LEVEL_PINNED_SERVICES: Final = frozenset({"gateway"})
LOGGING_LEVEL_PREFIXES: Final = ("LOGGING_LEVEL_", "LOGGING_GROUP_")
LOGGING_CONFIG: Final = "LOGGING_CONFIG"
# Spring Boot's LoggingApplicationListener lowers the `web` group to DEBUG for `debug`, and all of
# `org.springframework` to TRACE for `trace`, set to anything but `false`, even the empty string.
SPRING_LOG_SWITCHES: Final = frozenset({"DEBUG", "TRACE"})
PASSED: Final = "Every service follows the Compose conventions"


class ComposeCheckError(Exception):
    """The input is not a resolved Compose file, so nothing was checked."""


def problems(resolved: Mapping[str, object]) -> list[str]:
    """Every way `resolved`, the output of `docker compose config --format json`, breaks a
    convention: one line per problem, services in name order, and none when it follows them all.

    :raises ComposeCheckError: when `services`, a service, an environment or a healthcheck is not a
        mapping.
    """
    services = _mapping(resolved.get("services"), "services")
    policy = resolved.get(RESTART_BLOCK)
    bounded_logs = resolved.get(LOGGING_BLOCK)
    found: list[str] = []
    if policy is None or bounded_logs is None:
        found.append(f"the resolved file holds no {RESTART_BLOCK} or {LOGGING_BLOCK} block")
    for name, definition in sorted(services.items()):
        service = _mapping(definition, "a service")
        if name in ONE_SHOT_SERVICES:
            if service.get("restart") or service.get("logging"):
                found.append(f"{name}: a one-shot service takes no restart or logging")
        elif service.get("restart") != policy or service.get("logging") != bounded_logs:
            found.append(f"{name}: needs restart and logging as the x- blocks set them")
        if name in SPRING_SERVICES:
            environment = _mapping(service.get("environment") or {}, "an environment")
            found.extend(
                f"{name}: its environment must not set {key}"
                for key in sorted(environment)
                if _reopens_actuator(key)
                or (name in LEVEL_PINNED_SERVICES and _lowers_log_level(key))
            )
            found.extend(
                f"{name}: must not set {member}, which reaches Spring as an argument"
                for member in COMMAND_LINE_MEMBERS
                if service.get(member)
            )
    for name, path in READINESS_PROBES.items():
        service = _mapping(services.get(name) or {}, "a service")
        healthcheck = _mapping(service.get("healthcheck") or {}, "a healthcheck")
        if path not in _command(healthcheck.get("test")):
            found.append(f"{name}: its healthcheck must probe {path}")
    return found


def _mapping(value: object, what: str) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise ComposeCheckError(f"{what} is not a mapping")
    return value


def _canonical(key: str) -> str:
    # Spring Boot's relaxed binding reads one property under many names: `_`, `__`, `.` or brackets
    # between its parts, a `-` inside a part, any case. Spring Boot 4.1.2's Binder, over a
    # system-environment source, bound `logging.level[root]`, `logging[level][root]`,
    # `logging.lev-el.root`, `LOGGING__LEVEL_ROOT` and `de-bug` alike. So a key is compared on its
    # letters and digits alone, which can refuse a name that binds nothing but never misses one.
    return re.sub("[^A-Z0-9]", "", key.upper())


def _reopens_actuator(key: str) -> bool:
    canonical = _canonical(key)
    return canonical.startswith(
        (_canonical(ACTUATOR_WEB_PREFIX), _canonical(SPRING_CONFIG_PREFIX))
    ) or (canonical == _canonical(SPRING_APPLICATION_JSON) or key in JVM_OPTION_VARIABLES)


def _lowers_log_level(key: str) -> bool:
    canonical = _canonical(key)
    return (
        canonical.startswith(tuple(_canonical(prefix) for prefix in LOGGING_LEVEL_PREFIXES))
        or canonical == _canonical(LOGGING_CONFIG)
        or canonical in {_canonical(switch) for switch in SPRING_LOG_SWITCHES}
    )


def _command(test: object) -> str:
    """A healthcheck's command as one string, or empty when it has none Compose could run."""
    if isinstance(test, list) and all(isinstance(part, str) for part in test):
        return " ".join(test)
    return ""


def _report(line: str) -> None:
    # The verdict on stdout is this check's whole product: make and CI print it as the step's log.
    print(line, flush=True)  # noqa: T201


def main(source: BinaryIO) -> int:
    """Checks the resolved Compose file read, as JSON, from `source`.

    :return: `EXIT_PASSED` when every service follows the conventions, `EXIT_FAILED` when one does
        not, and `EXIT_MISCONFIGURED` when `source` holds no resolved Compose file: not JSON, nested
        too deeply to parse, or JSON of the wrong shape.
    """
    try:
        document = json.loads(source.read())
    except ValueError as error:
        # JSONDecodeError and UnicodeDecodeError are both ValueErrors, and the latter's message
        # quotes the bytes it could not decode: so the type alone.
        _report(f"compose-check FAILED: the input is not JSON ({type(error).__name__})")
        return EXIT_MISCONFIGURED
    except RecursionError:
        _report("compose-check FAILED: the input is nested too deeply to read")
        return EXIT_MISCONFIGURED
    # Only the shape checks raise ComposeCheckError, so a fault in a rule escapes as itself.
    try:
        found = problems(_mapping(document, "the input"))
    except ComposeCheckError as failure:
        _report(f"compose-check FAILED: {failure}")
        return EXIT_MISCONFIGURED
    for line in found or [PASSED]:
        _report(line)
    return EXIT_FAILED if found else EXIT_PASSED


if __name__ == "__main__":
    sys.exit(main(sys.stdin.buffer))

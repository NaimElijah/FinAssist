# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Checks the resolved Compose file against the stack's restart, log, probe and isolation rules.

`make check-compose` pipes `docker compose config --format json`, under every profile, into this
script's standard input (ADR-049). Every long-running service must take the restart policy and the
log bound exactly as the `x-restart-policy` and `x-logging` blocks set them, the one-shot services
must take neither, and four healthchecks must probe readiness (#142, ADR-039). Every published port
must bind `127.0.0.1` and `keycloak-db` must publish none (ADR-030, ADR-031); `auth-smoke` must sit
behind exactly the `smoke` profile and `secret-check` behind none (ADR-034, ADR-044). Every
service but the two databases must take the `x-hardened` block's `cap_drop` and `security_opt`,
and every one but those and Keycloak must set `read_only`; no service, a database included, may
set `cap_add`, `privileged`, `pid`, `ipc`, `userns_mode`, `uts` or `devices`, run as root by
`user`, or bind-mount the Docker socket; and each database must attach to one network alone,
shared with its one client and nothing else, judged by the Docker network each key resolves to,
so that no other key may resolve to it, neither database network may be `external`, and
`identity` must be `internal` (ADR-063). No service may set `network_mode` but `secret-check`,
whose must be exactly `none`, and Prometheus's `command` must carry exactly `--web.cors.origin=`,
the empty regex, which grants no origin, with no `entrypoint` to run it otherwise (ADR-057).
Neither the ledger nor the gateway may set, in its environment, a key that reopens the Actuator
exposure their code pins, nor a `command` or `entrypoint`, whose arguments would reach Spring as
properties; nor may the gateway set a log level, a log group, a logging configuration or Spring
Boot's `debug` or `trace` switch, since below INFO it logs credentials.

It reads the resolved file, not its text, so an alias that stops resolving fails as surely as a
service that never took one, and a block copied in with other values fails too.

The resolved file carries every secret Compose interpolated, so the report names a service, an
`x-` block, a probe path, an environment key or a member, never a value. Input that is not JSON is
reported by the error's type alone, input nested too deeply to parse as such, and JSON of the wrong
shape by the part that is not a mapping or a list. Standard library only; run it with `uv run`, as
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
# Every published port binds the host's IPv4 loopback alone, so none is reachable from another
# machine (ADR-030). Exactly this address: a wildcard, an IPv6 address or no address is not it.
LOOPBACK_HOST_IP: Final = "127.0.0.1"
# Keycloak's store is reached by Keycloak alone, over the Compose network (ADR-031).
UNPUBLISHED_SERVICES: Final = frozenset({"keycloak-db"})
# `auth-smoke` runs only by hand (ADR-034); `secret-check` runs on every `up`, since the databases
# and Grafana wait on it (ADR-044). A service missing from the file has no profiles.
PINNED_PROFILES: Final = {"auth-smoke": ("smoke",), "secret-check": ()}
# Least privilege for every container (ADR-063): the rules name the exemptions, never the services
# held to them, so a service added later is hardened unless it is exempted here on purpose.
HARDENED_BLOCK: Final = "x-hardened"
NO_CAPABILITIES: Final = ("ALL",)
NO_NEW_PRIVILEGES: Final = ("no-new-privileges:true",)
# The block as `config` resolves it, lists and all, so a service's members compare equal to it.
_HARDENED_MEMBERS: Final = {
    "cap_drop": list(NO_CAPABILITIES),
    "security_opt": list(NO_NEW_PRIVILEGES),
}
# The PostgreSQL image's entrypoint starts as root, takes ownership of its directories and drops
# to `postgres` itself, which a dropped capability refuses.
UNHARDENED_SERVICES: Final = frozenset({"db", "keycloak-db"})
# Every other service writes nothing outside a tmpfs or a volume, as a run of the whole stack
# showed; Keycloak writes its transaction log under /opt/keycloak/data.
WRITABLE_ROOT_SERVICES: Final = UNHARDENED_SERVICES | {"keycloak"}
# Either one widens a container's privileges, a database's included, so no service may set one.
PRIVILEGE_MEMBERS: Final = ("cap_add", "privileged")
# Each of these hands a container the host's privilege, whatever it drops, so none may be set on
# any service. Every namespace member set at all shares one, the host's or another container's.
NAMESPACE_MEMBERS: Final = ("pid", "ipc", "userns_mode", "uts")
DEVICES_MEMBER: Final = "devices"
# `user` is `<user>[:<group>]`; uid 0 by name or number is root, whatever the group.
ROOT_USERS: Final = frozenset({"root"})
ROOT_UID: Final = 0
# The daemon's socket by its file name, so `/var/run/…`, `/run/…` and `//var/run/…` all match,
# with or without a trailing separator, and in a Windows path's backslashes too: `config` keeps
# both as written, and the daemon binds the socket through a trailing slash.
DOCKER_SOCKET: Final = "docker.sock"
BIND_MOUNT: Final = "bind"
# Each database attaches to one network alone, shared with its one client, so no other container
# can resolve or reach it over a Compose network (ADR-063).
DATABASE_NETWORKS: Final = {"db": "books", "keycloak-db": "identity"}
DATABASE_NETWORK_MEMBERS: Final = {
    "books": frozenset({"db", "ledger"}),
    "identity": frozenset({"keycloak", "keycloak-db"}),
}
# An internal network has no route off it, so `keycloak-db`, which publishes no port, has no
# egress. Not `books`: Docker publishes no port from a container on an internal network alone.
INTERNAL_NETWORKS: Final = frozenset({"identity"})
# Prometheus defaults this regex to `.*` and echoes any page's Origin back as allowed; the anchored
# empty regex grants none (ADR-057). Exactly one such argument, so a later one cannot override it.
CORS_PINNED_SERVICE: Final = "prometheus"
CORS_ORIGIN_FLAG: Final = "--web.cors.origin"
NO_CORS_ORIGIN: Final = f"{CORS_ORIGIN_FLAG}="
# `host` would bypass the loopback port rule, and `service:` or `container:` would share another
# container's namespace, a database's included. Only the one-shot that talks to nothing sets one.
NETWORK_MODES: Final = {"secret-check": "none"}
PASSED: Final = "Every service follows the Compose conventions"


class ComposeCheckError(Exception):
    """The input is not a resolved Compose file, so nothing was checked."""


def problems(resolved: Mapping[str, object]) -> list[str]:
    """Every way `resolved`, the output of `docker compose config --format json`, breaks a
    convention: one line per problem, and none when it follows them all.

    The lines come in a fixed order: the file's own `x-` blocks and networks first, then each
    service's problems with the services in name order, then the pinned profiles, Prometheus's
    CORS flag and entrypoint, and the readiness probes last.

    :raises ComposeCheckError: when `services`, a service, an environment, a healthcheck, a port,
        a volume, a service's networks, the top-level `networks` or a network in it is not a
        mapping, a service's `ports` or `volumes` is not a list, its `user` is not a string, a
        network has no name, or a service names a network the file does not declare.
    """
    services = _mapping(resolved.get("services"), "services")
    policy = resolved.get(RESTART_BLOCK)
    bounded_logs = resolved.get(LOGGING_BLOCK)
    found: list[str] = []
    if policy is None or bounded_logs is None:
        found.append(f"the resolved file holds no {RESTART_BLOCK} or {LOGGING_BLOCK} block")
    if resolved.get(HARDENED_BLOCK) != _HARDENED_MEMBERS:
        found.append(
            f"the resolved file's {HARDENED_BLOCK} block must drop {', '.join(NO_CAPABILITIES)} "
            f"and set {', '.join(NO_NEW_PRIVILEGES)}"
        )
    declared_networks = _mapping(resolved.get("networks") or {}, "networks")
    networks = _network_names(declared_networks)
    found.extend(_database_network_problems(declared_networks, networks))
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
        ports = _ports(service.get("ports"))
        if any(port.get("host_ip") != LOOPBACK_HOST_IP for port in ports):
            found.append(f"{name}: each of its ports must bind host_ip {LOOPBACK_HOST_IP}")
        if name in UNPUBLISHED_SERVICES and ports:
            found.append(f"{name}: must publish no ports")
        found.extend(_hardening_problems(name, service))
        found.extend(_host_privilege_problems(name, service))
        found.extend(_network_problems(name, service, networks))
        found.extend(_network_mode_problems(name, service))
    for name, profiles in sorted(PINNED_PROFILES.items()):
        service = _mapping(services.get(name) or {}, "a service")
        if (service.get("profiles") or []) != list(profiles):
            found.append(
                f"{name}: its profiles must be exactly [{', '.join(profiles)}]"
                if profiles
                else f"{name}: must take no profiles"
            )
    prometheus = _mapping(services.get(CORS_PINNED_SERVICE) or {}, "a service")
    cors = [
        argument
        for argument in _arguments(prometheus.get("command"))
        if argument.startswith(CORS_ORIGIN_FLAG)
    ]
    if cors != [NO_CORS_ORIGIN]:
        found.append(
            f"{CORS_PINNED_SERVICE}: its command must set {CORS_ORIGIN_FLAG} to the empty regex"
        )
    # An entrypoint runs in the command's place, or ahead of it, where this rule cannot read it.
    if prometheus.get("entrypoint"):
        found.append(
            f"{CORS_PINNED_SERVICE}: must not set entrypoint, which could run it without its "
            "CORS flag"
        )
    for name, path in READINESS_PROBES.items():
        service = _mapping(services.get(name) or {}, "a service")
        healthcheck = _mapping(service.get("healthcheck") or {}, "a healthcheck")
        if path not in _command(healthcheck.get("test")):
            found.append(f"{name}: its healthcheck must probe {path}")
    return found


def _hardening_problems(name: str, service: Mapping[str, object]) -> list[str]:
    found: list[str] = []
    if name not in UNHARDENED_SERVICES and any(
        service.get(member) != value for member, value in _HARDENED_MEMBERS.items()
    ):
        found.append(
            f"{name}: needs cap_drop and security_opt as the {HARDENED_BLOCK} block sets them"
        )
    if any(service.get(member) for member in PRIVILEGE_MEMBERS):
        found.append(f"{name}: must not set {' or '.join(PRIVILEGE_MEMBERS)}")
    if name not in WRITABLE_ROOT_SERVICES and service.get("read_only") is not True:
        found.append(f"{name}: needs read_only: true")
    return found


def _host_privilege_problems(name: str, service: Mapping[str, object]) -> list[str]:
    found = [
        f"{name}: must not set {member}, which shares a namespace"
        for member in NAMESPACE_MEMBERS
        if service.get(member) is not None
    ]
    if service.get(DEVICES_MEMBER):
        found.append(f"{name}: must not set {DEVICES_MEMBER}")
    if _is_root(service.get("user")):
        found.append(f"{name}: must not run as root")
    if any(
        mount.get("type") == BIND_MOUNT and _file_name(mount.get("source")) == DOCKER_SOCKET
        for mount in _volumes(service.get("volumes"))
    ):
        found.append(f"{name}: must not mount the Docker socket")
    return found


def _is_root(user: object) -> bool:
    if user is None:
        return False
    if not isinstance(user, str):
        raise ComposeCheckError("a service's user is not a string")
    uid = user.split(":", 1)[0]
    return uid in ROOT_USERS or (uid.isdigit() and int(uid) == ROOT_UID)


def _file_name(path: object) -> str:
    return path.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1] if isinstance(path, str) else ""


def _network_names(declared: Mapping[str, object]) -> dict[str, str]:
    """Each top-level network key mapped to the Docker network it resolves to.

    Two keys naming one Docker network are one network, so the database rules compare these names,
    never the keys.
    """
    names: dict[str, str] = {}
    for key, definition in declared.items():
        name = _mapping(definition, "a network").get("name")
        if not isinstance(name, str):
            raise ComposeCheckError("a network has no name")
        names[key] = name
    return names


def _database_network_problems(
    declared: Mapping[str, object], names: Mapping[str, str]
) -> list[str]:
    found: list[str] = []
    for own in sorted(DATABASE_NETWORK_MEMBERS):
        if own not in names:
            continue
        # An external network is one another project, or anything on the host, can also join.
        network = _mapping(declared[own], "a network")
        if network.get("external"):
            found.append(f"the {own} network must not be external")
        if own in INTERNAL_NETWORKS and network.get("internal") is not True:
            found.append(f"the {own} network must be internal")
        found.extend(
            f"the {key} network must not resolve to the same network as {own}"
            for key in sorted(names)
            if key != own and names[key] == names[own]
        )
    return found


def _network_problems(
    name: str, service: Mapping[str, object], names: Mapping[str, str]
) -> list[str]:
    # `config` leaves `networks` out under `network_mode`, so a database moved onto the host's
    # network, or another container's, attaches to none of its own and fails here.
    keys = _mapping(service.get("networks") or {}, "a service's networks")
    if any(key not in names for key in keys):
        raise ComposeCheckError("a service's network is not declared")
    attached = {names[key] for key in keys}
    found: list[str] = []
    own = DATABASE_NETWORKS.get(name)
    if own is not None and attached != {names.get(own)}:
        found.append(f"{name}: must attach to the {own} network alone")
    found.extend(
        f"{name}: must not attach to the {network} network"
        for network, members in sorted(DATABASE_NETWORK_MEMBERS.items())
        if network in names and names[network] in attached and name not in members
    )
    return found


def _network_mode_problems(name: str, service: Mapping[str, object]) -> list[str]:
    mode = service.get("network_mode")
    required = NETWORK_MODES.get(name)
    if required is not None:
        return [] if mode == required else [f"{name}: its network_mode must be exactly {required}"]
    return [f"{name}: must not set network_mode"] if mode is not None else []


def _mapping(value: object, what: str) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise ComposeCheckError(f"{what} is not a mapping")
    return value


def _ports(value: object) -> list[Mapping[str, object]]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ComposeCheckError("a service's ports is not a list")
    return [_mapping(port, "a port") for port in value]


def _volumes(value: object) -> list[Mapping[str, object]]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ComposeCheckError("a service's volumes is not a list")
    return [_mapping(volume, "a volume") for volume in value]


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


def _arguments(command: object) -> list[str]:
    """A service's `command` as its arguments, or none when it is not a list of strings."""
    if isinstance(command, list) and all(isinstance(part, str) for part in command):
        return command
    return []


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

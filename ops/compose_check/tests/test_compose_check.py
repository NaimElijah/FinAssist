"""The Compose conventions check, driven through its standard input with crafted resolved files.

Each case hands the check what `docker compose config --format json` would print, as bytes on its
standard input (#142, ADR-039, ADR-049). Most call `main` in-process; the command-line cases run
the script as its own process, as `make check-compose` does, so its exit codes and its streams are
the real ones. No case needs Docker: the resolved file is built here, starting from one that
follows every convention.
"""

from __future__ import annotations

import copy
import io
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

import compose_check
import pytest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
PLACEHOLDERS = REPOSITORY_ROOT / "ops" / "compose_check" / "placeholders.env"
SCRIPT = REPOSITORY_ROOT / "ops" / "compose_check" / "compose_check.py"
# Far past any recursion limit, as a hostile or corrupted input could be.
DEEP_NESTING = 200_000
# Fixture value only: it stands for the real secrets a developer's resolved file carries.
SECRET = "Fixture-only-5Wd8Jq2Ns6"
PASSED = "Every service follows the Compose conventions"
POLICY = "unless-stopped"
BOUNDED_LOGS = {"driver": "json-file", "options": {"max-file": "3", "max-size": "10m"}}
LOOPBACK = "127.0.0.1"
NO_CAPABILITIES = ["ALL"]
NO_NEW_PRIVILEGES = ["no-new-privileges:true"]
NOBODY = "65534:65534"
NO_CORS_GRANT = "--web.cors.origin="
# Each list below is written out rather than read from the check, so a name dropped from the
# check's own, or added to an exemption, fails here instead of taking its cases with it.
ONE_SHOT_SERVICES = ["auth-smoke", "secret-check"]
READINESS_PROBES = [
    ("assistant", "/ready"),
    ("gateway", "/actuator/health/readiness"),
    ("keycloak", "/health/ready"),
    ("ledger", "/actuator/health/readiness"),
]
HARDENED_SERVICES = [
    "assistant",
    "auth-smoke",
    "gateway",
    "grafana",
    "keycloak",
    "ledger",
    "otel-collector",
    "prometheus",
    "secret-check",
]
# Each of those but Keycloak, which writes its transaction log under /opt/keycloak/data.
READ_ONLY_SERVICES = [
    "assistant",
    "auth-smoke",
    "gateway",
    "grafana",
    "ledger",
    "otel-collector",
    "prometheus",
    "secret-check",
]
# Each database and the one network it may attach to.
DATABASE_NETWORKS = [("db", "books"), ("keycloak-db", "identity")]
PROMETHEUS_COMMAND = [
    "--config.file=/etc/prometheus/prometheus.yml",
    "--storage.tsdb.retention.time=7d",
    NO_CORS_GRANT,
]


def _probe(path: str) -> dict[str, Any]:
    return {"test": ["CMD", "curl", "-f", f"http://localhost:8080{path}"]}


def _port(target: int, host_ip: str | None = LOOPBACK) -> dict[str, Any]:
    """A published port as `config` resolves `"127.0.0.1:<port>:<target>"`, or with no `host_ip`
    when `host_ip` is None, as it resolves a port given without an address."""
    port: dict[str, Any] = {
        "mode": "ingress",
        "target": target,
        "published": str(target),
        "protocol": "tcp",
    }
    if host_ip is not None:
        port["host_ip"] = host_ip
    return port


def _bind(source: str, target: str) -> dict[str, Any]:
    """A bind mount as `config` resolves `<source>:<target>:ro`."""
    return {"type": "bind", "source": source, "target": target, "read_only": True, "bind": {}}


def _long_running(**members: Any) -> dict[str, Any]:
    return {"restart": POLICY, "logging": copy.deepcopy(BOUNDED_LOGS), **members}


def _hardened(service: dict[str, Any]) -> dict[str, Any]:
    """`service` with the `x-hardened` block's members, as `<<: *hardened` resolves."""
    return {**service, "cap_drop": list(NO_CAPABILITIES), "security_opt": list(NO_NEW_PRIVILEGES)}


def _conforming() -> dict[str, Any]:
    """A resolved file shaped like today's, following every convention, with a secret in it."""
    return {
        "name": "finassist",
        "x-restart-policy": POLICY,
        "x-logging": copy.deepcopy(BOUNDED_LOGS),
        "x-hardened": {"cap_drop": list(NO_CAPABILITIES), "security_opt": list(NO_NEW_PRIVILEGES)},
        "services": {
            "db": _long_running(
                environment={"POSTGRES_PASSWORD": SECRET},
                ports=[_port(5432)],
                networks={"books": None},
            ),
            "ledger": _hardened(
                _long_running(
                    healthcheck=_probe("/actuator/health/readiness"),
                    environment={"LEDGER_DB_PASSWORD": SECRET, "OTEL_EXPORT_ENABLED": "false"},
                    ports=[_port(8080)],
                    read_only=True,
                    tmpfs=["/tmp"],
                    networks={"books": None, "default": None},
                )
            ),
            "gateway": _hardened(
                _long_running(
                    healthcheck=_probe("/actuator/health/readiness"),
                    environment={"GATEWAY_CLIENT_SECRET": SECRET, "OTEL_EXPORT_ENABLED": "false"},
                    ports=[_port(8090)],
                    read_only=True,
                    tmpfs=["/tmp"],
                    networks={"default": None},
                )
            ),
            "assistant": _hardened(
                _long_running(
                    healthcheck=_probe("/ready"),
                    ports=[_port(8000)],
                    read_only=True,
                    networks={"default": None},
                )
            ),
            "keycloak-db": _long_running(
                environment={"POSTGRES_PASSWORD": SECRET}, networks={"identity": None}
            ),
            "keycloak": _hardened(
                _long_running(
                    healthcheck=_probe("/health/ready"),
                    ports=[_port(8080)],
                    networks={"default": None, "identity": None},
                )
            ),
            "otel-collector": _hardened(
                _long_running(
                    profiles=["monitoring"],
                    ports=[_port(4318), _port(4317)],
                    read_only=True,
                    networks={"default": None},
                )
            ),
            "prometheus": _hardened(
                _long_running(
                    profiles=["monitoring"],
                    command=list(PROMETHEUS_COMMAND),
                    ports=[_port(9090)],
                    read_only=True,
                    networks={"default": None},
                )
            ),
            "grafana": _hardened(
                _long_running(
                    environment={"GF_SECURITY_ADMIN_PASSWORD": SECRET},
                    profiles=["monitoring"],
                    ports=[_port(3000)],
                    read_only=True,
                    networks={"default": None},
                    volumes=[
                        _bind("/repo/ops/grafana/provisioning", "/etc/grafana/provisioning"),
                        {"type": "volume", "source": "grafana-data", "target": "/var/lib/grafana"},
                    ],
                )
            ),
            "secret-check": _hardened(
                {
                    "environment": {"POSTGRES_PASSWORD": SECRET},
                    "network_mode": "none",
                    "read_only": True,
                    "user": NOBODY,
                    "volumes": [_bind("/repo", "/repo")],
                }
            ),
            "auth-smoke": _hardened(
                {
                    "environment": {"ASSISTANT_CLIENT_SECRET": SECRET},
                    "user": NOBODY,
                    "profiles": ["smoke"],
                    "read_only": True,
                    "networks": {"default": None},
                }
            ),
        },
        "networks": {
            "books": {"name": "finassist_books", "ipam": {}},
            "default": {"name": "finassist_default", "ipam": {}},
            "identity": {"name": "finassist_identity", "ipam": {}, "internal": True},
        },
    }


def _run(source: bytes, capsys: pytest.CaptureFixture[str]) -> tuple[int, list[str]]:
    status = compose_check.main(io.BytesIO(source))
    captured = capsys.readouterr()
    output = captured.out + captured.err
    assert SECRET not in output
    return status, output.splitlines()


def _check(resolved: dict[str, Any], capsys: pytest.CaptureFixture[str]) -> tuple[int, list[str]]:
    return _run(json.dumps(resolved).encode(), capsys)


# --- The verdict ---------------------------------------------------------------------------------


def Given_a_file_following_every_convention_When_checked_Then_it_passes_in_one_line(
    capsys: pytest.CaptureFixture[str],
) -> None:
    status, lines = _check(_conforming(), capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


def Given_a_long_running_service_without_a_restart_policy_When_checked_Then_it_fails_by_name(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["db"]["restart"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["db: needs restart and logging as the x- blocks set them"]


def Given_a_long_running_service_with_a_log_bound_of_its_own_When_checked_Then_it_fails_by_name(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["grafana"]["logging"]["options"]["max-size"] = "50m"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["grafana: needs restart and logging as the x- blocks set them"]


def Given_a_long_running_service_with_another_restart_policy_When_checked_Then_it_fails(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["ledger"]["restart"] = "always"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["ledger: needs restart and logging as the x- blocks set them"]


@pytest.mark.parametrize("member", ["restart", "logging"])
@pytest.mark.parametrize("one_shot", ONE_SHOT_SERVICES)
def Given_a_one_shot_service_with_a_restart_policy_or_log_bound_When_checked_Then_it_fails(
    one_shot: str, member: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][one_shot][member] = {"restart": POLICY, "logging": BOUNDED_LOGS}[member]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{one_shot}: a one-shot service takes no restart or logging"]


@pytest.mark.parametrize(("service", "path"), READINESS_PROBES)
def Given_a_healthcheck_probing_liveness_not_readiness_When_checked_Then_it_fails_naming_the_path(
    service: str, path: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["healthcheck"] = _probe("/health/live")

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: its healthcheck must probe {path}"]


def Given_a_probed_service_without_a_healthcheck_When_checked_Then_it_fails_naming_the_path(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["assistant"]["healthcheck"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["assistant: its healthcheck must probe /ready"]


def Given_a_probed_service_missing_from_the_file_When_checked_Then_its_probe_is_reported_missing(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["keycloak"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["keycloak: its healthcheck must probe /health/ready"]


@pytest.mark.parametrize("block", ["x-restart-policy", "x-logging"])
def Given_an_x_block_missing_from_the_resolved_file_When_checked_Then_it_fails_saying_so(
    block: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    del resolved[block]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines[0] == "the resolved file holds no x-restart-policy or x-logging block"
    # With nothing to compare against, every long-running service fails as well.
    assert "db: needs restart and logging as the x- blocks set them" in lines


def Given_several_problems_When_checked_Then_each_is_reported_with_services_in_name_order(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["ledger"]["logging"]
    del resolved["services"]["db"]["restart"]
    resolved["services"]["secret-check"]["restart"] = POLICY

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "db: needs restart and logging as the x- blocks set them",
        "ledger: needs restart and logging as the x- blocks set them",
        "secret-check: a one-shot service takes no restart or logging",
    ]


# --- The Actuator exposure, pinned in code, never reopened by Compose -----------------------------


@pytest.mark.parametrize(
    "key",
    [
        "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE",
        "MANAGEMENT_ENDPOINTS_WEB_BASE_PATH",
        "MANAGEMENT_ENDPOINTS_WEB_PATH_MAPPING_HEALTH",
        "SPRING_APPLICATION_JSON",
        "SPRING_CONFIG_ADDITIONAL_LOCATION",
        "SPRING_CONFIG_IMPORT",
        "management.endpoints.web.exposure.include",
        "spring_application_json",
        "spring.config.additional-location",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
    ],
)
@pytest.mark.parametrize("service", ["ledger", "gateway"])
def Given_a_spring_service_whose_environment_reopens_actuator_When_checked_Then_the_key_is_named(
    service: str, key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["environment"][key] = SECRET

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: its environment must not set {key}"]


def Given_both_spring_services_reopening_actuator_When_checked_Then_each_key_is_named_in_order(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    ledger = resolved["services"]["ledger"]["environment"]
    ledger["SPRING_APPLICATION_JSON"] = SECRET
    ledger["MANAGEMENT_ENDPOINTS_WEB_BASE_PATH"] = SECRET
    gateway = resolved["services"]["gateway"]["environment"]
    gateway["MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"] = SECRET

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "gateway: its environment must not set MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE",
        "ledger: its environment must not set MANAGEMENT_ENDPOINTS_WEB_BASE_PATH",
        "ledger: its environment must not set SPRING_APPLICATION_JSON",
    ]


# Each binds as its dotted form under Spring Boot 4.1.2's Binder, from a system-environment source.
@pytest.mark.parametrize(
    "key",
    [
        "management.endpoints.web.exposure[include]",
        "management[endpoints][web][exposure][include]",
        "spring.config[import]",
    ],
)
@pytest.mark.parametrize("service", ["ledger", "gateway"])
def Given_a_spring_service_reopening_actuator_in_bracketed_form_When_checked_Then_it_is_refused(
    service: str, key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["environment"][key] = SECRET

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: its environment must not set {key}"]


def Given_a_management_key_on_a_service_without_spring_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["db"]["environment"]["MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"] = "*"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


@pytest.mark.parametrize("member", ["command", "entrypoint"])
@pytest.mark.parametrize("service", ["ledger", "gateway"])
def Given_a_spring_service_overriding_its_command_line_When_checked_Then_the_member_is_named(
    service: str, member: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    argument = f"--management.endpoints.web.exposure.include={SECRET}"
    resolved["services"][service][member] = [argument]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not set {member}, which reaches Spring as an argument"]


def Given_a_command_on_a_service_without_spring_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["secret-check"]["command"] = ["python", "/repo/secret_check.py", SECRET]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


def Given_a_spring_service_with_no_environment_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["gateway"]["environment"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


# --- The gateway's log levels, set in application.yml, never lowered by Compose ------------------


@pytest.mark.parametrize(
    "key",
    [
        "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB",
        "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY",
        "LOGGING_LEVEL_ROOT",
        "logging.level.org.springframework.web",
        "logging_level_root",
    ],
)
def Given_the_gateway_service_sets_a_logging_level_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = "DEBUG"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


@pytest.mark.parametrize("key", ["LOGGING_GROUP_WEB", "logging.group.web"])
def Given_the_gateway_service_sets_a_logging_group_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = "org.springframework.security"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


@pytest.mark.parametrize("key", ["LOGGING_CONFIG", "logging.config"])
def Given_the_gateway_service_sets_a_logging_config_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = f"/tmp/{SECRET}.xml"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


@pytest.mark.parametrize("value", ["true", ""], ids=["true", "empty"])
@pytest.mark.parametrize("key", ["DEBUG", "debug", "TRACE", "trace"])
def Given_the_gateway_service_sets_springs_debug_or_trace_switch_When_checked_Then_it_is_refused(
    key: str, value: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = value

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


# Each binds as `logging.level.*` under Spring Boot 4.1.2's Binder, from a system environment.
@pytest.mark.parametrize(
    "key",
    [
        "logging.level[org.springframework.web]",
        "logging[level][root]",
        "logging_level[root]",
        "logging.lev-el.root",
        "LOGGING__LEVEL_ROOT",
    ],
)
def Given_a_gateway_logging_level_in_another_spelling_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = "DEBUG"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


@pytest.mark.parametrize("key", ["logging.group[web]", "logging[group][web]"])
def Given_a_gateway_logging_group_in_bracketed_form_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = "org.springframework.security"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


@pytest.mark.parametrize("key", ["logging[config]", "de-bug"])
def Given_a_gateway_logging_config_or_switch_respelled_When_checked_Then_compose_check_refuses_it(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"][key] = "true"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"gateway: its environment must not set {key}"]


def Given_the_gateway_service_sets_a_logging_key_that_lowers_no_level_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"]["LOGGING_STRUCTURED_FORMAT_CONSOLE"] = "ecs"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


def Given_a_logging_level_on_a_service_without_spring_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["db"]["environment"]["LOGGING_LEVEL_ROOT"] = "DEBUG"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


# --- Published ports and profiles (ADR-030, ADR-034, ADR-044) ------------------------------------


@pytest.mark.parametrize(
    "host_ip",
    ["0.0.0.0", "::", "::1", "127.0.0.2", ""],
    ids=["wildcard", "ipv6-wildcard", "ipv6-loopback", "other-loopback", "empty"],
)
def Given_a_port_bound_to_an_address_other_than_127_0_0_1_When_checked_Then_the_service_is_named(
    host_ip: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["grafana"]["ports"] = [_port(3000, host_ip)]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["grafana: each of its ports must bind host_ip 127.0.0.1"]


def Given_a_port_published_with_no_host_ip_When_checked_Then_the_service_is_named(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["ledger"]["ports"] = [_port(8080, host_ip=None)]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["ledger: each of its ports must bind host_ip 127.0.0.1"]


def Given_several_ports_of_which_one_is_unbound_When_checked_Then_the_service_is_named_once(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["otel-collector"]["ports"] = [_port(4318), _port(4317, "0.0.0.0")]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["otel-collector: each of its ports must bind host_ip 127.0.0.1"]


def Given_unbound_ports_on_two_services_When_checked_Then_each_is_named_in_name_order(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["keycloak"]["ports"] = [_port(8080, "0.0.0.0")]
    resolved["services"]["db"]["ports"] = [_port(5432, host_ip=None)]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "db: each of its ports must bind host_ip 127.0.0.1",
        "keycloak: each of its ports must bind host_ip 127.0.0.1",
    ]


def Given_keycloak_db_publishing_a_port_even_on_loopback_When_checked_Then_it_fails(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["keycloak-db"]["ports"] = [_port(5432)]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["keycloak-db: must publish no ports"]


def Given_keycloak_db_publishing_a_port_on_every_interface_When_checked_Then_both_rules_fail(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["keycloak-db"]["ports"] = [_port(5432, "0.0.0.0")]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "keycloak-db: each of its ports must bind host_ip 127.0.0.1",
        "keycloak-db: must publish no ports",
    ]


@pytest.mark.parametrize(
    "profiles",
    [None, [], ["monitoring"], ["smoke", "monitoring"]],
    ids=["none", "empty", "another", "a-second"],
)
def Given_auth_smoke_not_behind_exactly_the_smoke_profile_When_checked_Then_it_fails(
    profiles: list[str] | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if profiles is None:
        del resolved["services"]["auth-smoke"]["profiles"]
    else:
        resolved["services"]["auth-smoke"]["profiles"] = profiles

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["auth-smoke: its profiles must be exactly [smoke]"]


def Given_auth_smoke_missing_from_the_file_When_checked_Then_its_profile_is_reported_missing(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["auth-smoke"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["auth-smoke: its profiles must be exactly [smoke]"]


@pytest.mark.parametrize("profiles", [["smoke"], ["monitoring"]], ids=["smoke", "monitoring"])
def Given_secret_check_behind_a_profile_When_checked_Then_it_fails(
    profiles: list[str], capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["secret-check"]["profiles"] = profiles

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["secret-check: must take no profiles"]


# --- Container hardening and the databases' networks (ADR-063) ------------------------------------


@pytest.mark.parametrize("member", ["cap_drop", "security_opt"])
@pytest.mark.parametrize("service", HARDENED_SERVICES)
def Given_a_hardened_service_without_a_member_of_the_block_When_checked_Then_it_is_named(
    service: str, member: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    del resolved["services"][service][member]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        f"{service}: needs cap_drop and security_opt as the x-hardened block sets them"
    ]


@pytest.mark.parametrize(
    ("member", "value"),
    [
        ("cap_drop", ["NET_RAW"]),
        ("cap_drop", []),
        ("security_opt", ["no-new-privileges:false"]),
        ("security_opt", ["no-new-privileges:true", "seccomp=unconfined"]),
    ],
    ids=["one-capability", "no-capability", "privileges-allowed", "seccomp-unconfined"],
)
def Given_a_hardened_service_with_a_weaker_member_When_checked_Then_it_is_named(
    member: str, value: list[str], capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["keycloak"][member] = value

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["keycloak: needs cap_drop and security_opt as the x-hardened block sets them"]


@pytest.mark.parametrize(
    ("member", "value"),
    [("cap_add", ["NET_ADMIN"]), ("privileged", True)],
    ids=["cap_add", "privileged"],
)
def Given_a_hardened_service_that_takes_privilege_back_When_checked_Then_it_is_named(
    member: str, value: object, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["grafana"][member] = value

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["grafana: must not set cap_add or privileged"]


@pytest.mark.parametrize(
    "block",
    [
        None,
        {"cap_drop": ["NET_RAW"], "security_opt": list(NO_NEW_PRIVILEGES)},
        {"cap_drop": list(NO_CAPABILITIES), "security_opt": []},
    ],
    ids=["missing", "one-capability", "privileges-allowed"],
)
def Given_an_x_hardened_block_missing_or_weakened_When_checked_Then_it_fails_saying_so(
    block: dict[str, Any] | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if block is None:
        del resolved["x-hardened"]
    else:
        resolved["x-hardened"] = block

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "the resolved file's x-hardened block must drop ALL and set no-new-privileges:true"
    ]


@pytest.mark.parametrize(
    ("member", "value"),
    [("cap_add", ["CHOWN"]), ("privileged", True)],
    ids=["cap_add", "privileged"],
)
@pytest.mark.parametrize("service", ["db", "keycloak-db"])
def Given_a_database_that_takes_privilege_When_checked_Then_it_is_named(
    service: str, member: str, value: object, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service][member] = value

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not set cap_add or privileged"]


def Given_a_service_the_check_does_not_name_When_checked_Then_it_must_take_the_hardening(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["cache"] = _long_running(networks={"default": None})

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "cache: needs cap_drop and security_opt as the x-hardened block sets them",
        "cache: needs read_only: true",
    ]


def Given_a_service_the_check_does_not_name_taking_the_hardening_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["cache"] = _hardened(
        _long_running(read_only=True, networks={"default": None})
    )

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


def Given_a_copy_of_the_ledger_that_is_privileged_When_checked_Then_it_is_named(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    copied = copy.deepcopy(resolved["services"]["ledger"])
    # Off `books`, so the one line is the privilege rule's, not the database network's.
    copied["networks"] = {"default": None}
    resolved["services"]["ledger-worker"] = {**copied, "privileged": True}

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["ledger-worker: must not set cap_add or privileged"]


@pytest.mark.parametrize("read_only", [None, False], ids=["unset", "false"])
@pytest.mark.parametrize("service", READ_ONLY_SERVICES)
def Given_a_read_only_service_with_a_writable_root_When_checked_Then_it_is_named(
    service: str, read_only: bool | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if read_only is None:
        del resolved["services"][service]["read_only"]
    else:
        resolved["services"][service]["read_only"] = read_only

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: needs read_only: true"]


@pytest.mark.parametrize(("service", "network"), DATABASE_NETWORKS)
@pytest.mark.parametrize(
    "networks",
    [{"default": None}, "both", {}],
    ids=["default-instead", "default-as-well", "none"],
)
def Given_a_database_not_on_its_own_network_alone_When_checked_Then_it_is_named(
    service: str, network: str, networks: object, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["networks"] = (
        {network: None, "default": None} if networks == "both" else networks
    )

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must attach to the {network} network alone"]


def Given_a_database_on_the_host_network_When_checked_Then_it_is_named(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["db"]["networks"]
    resolved["services"]["db"]["network_mode"] = "host"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "db: must attach to the books network alone",
        "db: must not set network_mode",
    ]


# Every service that is neither a network's database nor its one client, written out rather than
# derived from the script's own lists, so a client added there fails here.
@pytest.mark.parametrize(
    ("service", "network"),
    [
        ("assistant", "books"),
        ("auth-smoke", "books"),
        ("gateway", "books"),
        ("grafana", "books"),
        ("keycloak", "books"),
        ("otel-collector", "books"),
        ("prometheus", "books"),
        ("assistant", "identity"),
        ("auth-smoke", "identity"),
        ("gateway", "identity"),
        ("grafana", "identity"),
        ("ledger", "identity"),
        ("otel-collector", "identity"),
        ("prometheus", "identity"),
    ],
)
def Given_another_service_on_a_databases_network_When_checked_Then_it_is_named(
    service: str, network: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["networks"][network] = None

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not attach to the {network} network"]


@pytest.mark.parametrize("external", [False, True], ids=["project", "external"])
@pytest.mark.parametrize(("network", "service"), [("books", "assistant"), ("identity", "gateway")])
def Given_another_network_key_resolving_to_a_databases_network_When_checked_Then_both_are_named(
    network: str, service: str, external: bool, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    alias: dict[str, Any] = {"name": resolved["networks"][network]["name"], "ipam": {}}
    if external:
        alias["external"] = True
    resolved["networks"]["shared"] = alias
    resolved["services"][service]["networks"]["shared"] = None

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        f"the shared network must not resolve to the same network as {network}",
        f"{service}: must not attach to the {network} network",
    ]


def Given_books_resolving_to_the_default_networks_name_When_checked_Then_each_joiner_is_named(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["networks"]["books"]["name"] = resolved["networks"]["default"]["name"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "the default network must not resolve to the same network as books",
        "assistant: must not attach to the books network",
        "auth-smoke: must not attach to the books network",
        "gateway: must not attach to the books network",
        "grafana: must not attach to the books network",
        "keycloak: must not attach to the books network",
        "otel-collector: must not attach to the books network",
        "prometheus: must not attach to the books network",
    ]


@pytest.mark.parametrize("internal", [None, False], ids=["unset", "false"])
def Given_the_identity_network_not_internal_When_checked_Then_it_is_named(
    internal: bool | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if internal is None:
        del resolved["networks"]["identity"]["internal"]
    else:
        resolved["networks"]["identity"]["internal"] = internal

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["the identity network must be internal"]


@pytest.mark.parametrize("network", ["books", "identity"])
def Given_a_databases_network_declared_external_When_checked_Then_it_is_named(
    network: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["networks"][network]["external"] = True

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"the {network} network must not be external"]


# --- Prometheus grants no origin (ADR-057), and no namespace is shared --------------------------


@pytest.mark.parametrize(
    "command",
    [
        PROMETHEUS_COMMAND[:2],
        [*PROMETHEUS_COMMAND[:2], "--web.cors.origin=.*"],
        [*PROMETHEUS_COMMAND[:2], f"--web.cors.origin={SECRET}"],
        [*PROMETHEUS_COMMAND[:2], "--web.cors.origin", ".*"],
        [*PROMETHEUS_COMMAND, "--web.cors.origin=.*"],
        None,
    ],
    ids=["missing", "default", "an-origin", "separate-value", "overridden-later", "no-command"],
)
def Given_prometheus_without_exactly_the_empty_cors_origin_When_checked_Then_the_flag_is_named(
    command: list[str] | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if command is None:
        del resolved["services"]["prometheus"]["command"]
    else:
        resolved["services"]["prometheus"]["command"] = command

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["prometheus: its command must set --web.cors.origin to the empty regex"]


def Given_prometheus_missing_from_the_file_When_checked_Then_its_cors_flag_is_reported_missing(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["prometheus"]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["prometheus: its command must set --web.cors.origin to the empty regex"]


@pytest.mark.parametrize("mode", ["host", "service:db", "container:finassist-db-1", "bridge"])
def Given_a_service_setting_network_mode_When_checked_Then_it_is_named(
    mode: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    del resolved["services"]["assistant"]["networks"]
    resolved["services"]["assistant"]["network_mode"] = mode

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["assistant: must not set network_mode"]


@pytest.mark.parametrize("mode", [None, "host", "bridge", "service:db"])
def Given_secret_check_not_on_network_mode_none_When_checked_Then_it_is_named(
    mode: str | None, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    if mode is None:
        del resolved["services"]["secret-check"]["network_mode"]
    else:
        resolved["services"]["secret-check"]["network_mode"] = mode

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == ["secret-check: its network_mode must be exactly none"]


def Given_keycloak_db_setting_network_mode_When_checked_Then_it_is_named(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    del resolved["services"]["keycloak-db"]["networks"]
    resolved["services"]["keycloak-db"]["network_mode"] = "service:keycloak"

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "keycloak-db: must attach to the identity network alone",
        "keycloak-db: must not set network_mode",
    ]


# --- No service takes the host's privilege, the databases included ------------------------------


# Each form `config` resolved a socket mount to: the short form's source as written, `//` and a
# trailing slash included, which the daemon still binds to the socket, and a Windows path, a
# relative one among them, with backslashes and a trailing one kept.
@pytest.mark.parametrize(
    "source",
    [
        "/var/run/docker.sock",
        "//var/run/docker.sock",
        "/run/docker.sock",
        "/var/run/docker.sock/",
        "//var/run/docker.sock//",
        "C:\\finassist\\docker.sock",
        "C:\\finassist\\docker.sock\\",
    ],
)
@pytest.mark.parametrize("service", ["db", "grafana"])
def Given_a_service_mounting_the_docker_socket_When_checked_Then_it_is_named(
    service: str, source: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    mount = {"type": "bind", "source": source, "target": "/var/run/docker.sock", "bind": {}}
    resolved["services"][service].setdefault("volumes", []).append(mount)

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not mount the Docker socket"]


@pytest.mark.parametrize(
    ("member", "value"),
    [
        ("pid", "host"),
        ("pid", "service:db"),
        ("ipc", "host"),
        ("ipc", "shareable"),
        ("userns_mode", "host"),
        ("uts", "host"),
    ],
)
@pytest.mark.parametrize("service", ["keycloak-db", "assistant"])
def Given_a_service_setting_a_namespace_member_When_checked_Then_it_is_named(
    service: str, member: str, value: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service][member] = value

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not set {member}, which shares a namespace"]


@pytest.mark.parametrize("service", ["db", "otel-collector"])
def Given_a_service_given_a_host_device_When_checked_Then_it_is_named(
    service: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["devices"] = [
        {"source": "/dev/fuse", "target": "/dev/fuse", "permissions": "rwm"}
    ]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not set devices"]


# Docker reads a numeric uid with a sign as well, so `+0` and `-0` are root too.
@pytest.mark.parametrize(
    "user", ["0", "root", "0:0", "root:root", "0:65534", "000", "+0", "-0", "+0:0", "-0:65534"]
)
@pytest.mark.parametrize("service", ["keycloak-db", "secret-check"])
def Given_a_service_running_as_root_When_checked_Then_it_is_named(
    service: str, user: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][service]["user"] = user

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{service}: must not run as root"]


@pytest.mark.parametrize("user", ["65534:65534", "1000", "postgres", "100:0", "10"])
def Given_a_service_running_as_a_user_other_than_root_When_checked_Then_it_passes(
    user: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["auth-smoke"]["user"] = user

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_PASSED
    assert lines == [PASSED]


# An empty entrypoint is one too: it clears the image's own, so the command runs in its place.
@pytest.mark.parametrize(
    "entrypoint", [["/bin/prometheus", "--web.cors.origin=.*"], []], ids=["binary", "empty"]
)
def Given_prometheus_with_an_entrypoint_When_checked_Then_it_is_named(
    entrypoint: list[str], capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"]["prometheus"]["entrypoint"] = entrypoint

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [
        "prometheus: must not set entrypoint, which could run it without its CORS flag"
    ]


# --- Input that is not a resolved Compose file ----------------------------------------------------


@pytest.mark.parametrize(
    "source",
    [
        b"",
        f'{{"services": {{"db": "{SECRET}"'.encode(),
        b"\xff\xfe\x00not json",
    ],
    ids=["empty", "truncated", "undecodable"],
)
def Given_input_that_is_not_json_When_checked_Then_it_is_refused_without_quoting_it(
    source: bytes, capsys: pytest.CaptureFixture[str]
) -> None:
    status, lines = _run(source, capsys)

    assert status == compose_check.EXIT_MISCONFIGURED
    assert len(lines) == 1
    assert lines[0].startswith("compose-check FAILED: ")


@pytest.mark.parametrize(
    "resolved",
    [
        [SECRET],
        {"x-restart-policy": POLICY, "x-logging": BOUNDED_LOGS},
        {"x-restart-policy": POLICY, "x-logging": BOUNDED_LOGS, "services": [SECRET]},
        {"x-restart-policy": POLICY, "x-logging": BOUNDED_LOGS, "services": {"db": SECRET}},
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"ledger": _long_running(healthcheck=SECRET)},
        },
        # `config` always resolves the list form to a mapping, so a list means the input is not
        # its output, and would otherwise hide a key from the Actuator rule.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {
                "gateway": _long_running(environment=[f"SPRING_APPLICATION_JSON={SECRET}"]),
            },
        },
        # `config` resolves every port to a mapping in a list, so anything else would hide a port
        # from the loopback rule.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"db": _long_running(ports={"5432": SECRET})},
        },
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"db": _long_running(ports=[f"0.0.0.0:5432:5432/{SECRET}"])},
        },
        # `config` resolves a service's networks to a mapping, so anything else is not its output.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"assistant": _long_running(networks=SECRET)},
        },
        # `config` resolves the top-level networks to mappings, each with the name Docker uses.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {},
            "networks": [SECRET],
        },
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {},
            "networks": {"books": SECRET},
        },
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {},
            "networks": {"books": {"external": True}},
        },
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"assistant": _long_running(networks={"undeclared": None})},
            "networks": {},
        },
        # `config` resolves every volume to a mapping in a list, so anything else would hide a
        # mount from the Docker-socket rule.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"grafana": _long_running(volumes={"/var/run/docker.sock": SECRET})},
        },
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"grafana": _long_running(volumes=[f"/var/run/docker.sock:/{SECRET}"])},
        },
        # Compose refuses a `user` that is not a string, so a number is not `config`'s output.
        {
            "x-restart-policy": POLICY,
            "x-logging": BOUNDED_LOGS,
            "services": {"grafana": _long_running(user=0)},
        },
    ],
    ids=[
        "not-a-mapping",
        "no-services",
        "services-a-list",
        "service-a-string",
        "healthcheck",
        "environment-a-list",
        "ports-a-mapping",
        "port-a-string",
        "networks-a-string",
        "top-level-networks-a-list",
        "network-a-string",
        "network-without-a-name",
        "network-undeclared",
        "volumes-a-mapping",
        "volume-a-string",
        "user-a-number",
    ],
)
def Given_json_not_shaped_like_a_resolved_file_When_checked_Then_it_is_refused_without_a_value(
    resolved: object, capsys: pytest.CaptureFixture[str]
) -> None:
    status, lines = _run(json.dumps(resolved).encode(), capsys)

    assert status == compose_check.EXIT_MISCONFIGURED
    assert len(lines) == 1
    assert lines[0].startswith("compose-check FAILED: ")


@pytest.mark.parametrize(
    "source",
    [b"[" * DEEP_NESTING, b'{"services": ' * DEEP_NESTING],
    ids=["arrays", "objects"],
)
def Given_input_nested_past_the_recursion_limit_When_checked_Then_it_is_refused_in_one_line(
    source: bytes, capsys: pytest.CaptureFixture[str]
) -> None:
    status, lines = _run(source, capsys)

    assert status == compose_check.EXIT_MISCONFIGURED
    assert lines == ["compose-check FAILED: the input is nested too deeply to read"]


def Given_a_fault_in_the_checks_themselves_When_checked_Then_it_escapes_not_posing_as_bad_input(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    def faulty(resolved: object) -> list[str]:
        raise ValueError("a bug in a check")

    monkeypatch.setattr(compose_check, "problems", faulty)

    with pytest.raises(ValueError, match="a bug in a check"):
        compose_check.main(io.BytesIO(json.dumps(_conforming()).encode()))
    assert capsys.readouterr().out == ""


# --- The command line, as make check-compose runs it ----------------------------------------------


def _run_script(source: bytes) -> tuple[int, list[str]]:
    # -I, so neither the caller's PYTHON* variables nor the script's own directory shape the run.
    process = subprocess.run(
        [sys.executable, "-I", str(SCRIPT)], input=source, capture_output=True, check=False
    )
    output = (process.stdout + process.stderr).decode()
    assert SECRET not in output
    return process.returncode, output.splitlines()


def Given_a_conforming_file_on_stdin_When_the_script_runs_Then_it_exits_0_and_passes() -> None:
    status, lines = _run_script(json.dumps(_conforming()).encode())

    assert status == compose_check.EXIT_PASSED == 0
    assert lines == [PASSED]


def Given_a_breaking_file_on_stdin_When_the_script_runs_Then_it_exits_1_naming_the_key() -> None:
    resolved = _conforming()
    resolved["services"]["gateway"]["environment"]["SPRING_APPLICATION_JSON"] = SECRET

    status, lines = _run_script(json.dumps(resolved).encode())

    assert status == compose_check.EXIT_FAILED == 1
    assert lines == ["gateway: its environment must not set SPRING_APPLICATION_JSON"]


@pytest.mark.parametrize(
    "source",
    [f'{{"services": {{"db": "{SECRET}"'.encode(), b"[" * DEEP_NESTING],
    ids=["truncated", "deeply-nested"],
)
def Given_input_that_is_no_resolved_file_When_the_script_runs_Then_it_exits_2_in_one_line(
    source: bytes,
) -> None:
    status, lines = _run_script(source)

    assert status == compose_check.EXIT_MISCONFIGURED == 2
    assert len(lines) == 1
    assert lines[0].startswith("compose-check FAILED: ")


# --- The placeholders check-compose resolves the file with -------------------------------------


def Given_the_variables_compose_requires_When_read_Then_the_placeholders_set_each() -> None:
    compose = (REPOSITORY_ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    required = set(re.findall(r"\$\{([A-Z0-9_]+):\?", compose))
    placeholders = PLACEHOLDERS.read_text(encoding="utf-8").splitlines()
    assignments = [line for line in placeholders if line and not line.startswith("#")]
    names = [line.split("=", 1)[0] for line in assignments]

    assert required
    assert sorted(names) == sorted(required)
    assert all(line.split("=", 1)[1] for line in assignments)


def Given_the_placeholders_When_read_Then_each_value_is_a_word_sh_sources_unchanged() -> None:
    # check-compose sources the file with /bin/sh, so a quote, space or `$` would be reinterpreted.
    placeholders = PLACEHOLDERS.read_text(encoding="utf-8").splitlines()
    values = [line.split("=", 1)[1] for line in placeholders if line and not line.startswith("#")]

    assert values
    assert all(re.fullmatch(r"[A-Za-z0-9_.-]+", value) for value in values)

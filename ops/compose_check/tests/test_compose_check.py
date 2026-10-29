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


def _probe(path: str) -> dict[str, Any]:
    return {"test": ["CMD", "curl", "-f", f"http://localhost:8080{path}"]}


def _long_running(**members: Any) -> dict[str, Any]:
    return {"restart": POLICY, "logging": copy.deepcopy(BOUNDED_LOGS), **members}


def _conforming() -> dict[str, Any]:
    """A resolved file shaped like today's, following every convention, with a secret in it."""
    return {
        "name": "finassist",
        "x-restart-policy": POLICY,
        "x-logging": copy.deepcopy(BOUNDED_LOGS),
        "services": {
            "db": _long_running(environment={"POSTGRES_PASSWORD": SECRET}),
            "ledger": _long_running(
                healthcheck=_probe("/actuator/health/readiness"),
                environment={"LEDGER_DB_PASSWORD": SECRET, "OTEL_EXPORT_ENABLED": "false"},
            ),
            "gateway": _long_running(
                healthcheck=_probe("/actuator/health/readiness"),
                environment={"GATEWAY_CLIENT_SECRET": SECRET, "OTEL_EXPORT_ENABLED": "false"},
            ),
            "assistant": _long_running(healthcheck=_probe("/ready")),
            "keycloak": _long_running(healthcheck=_probe("/health/ready")),
            "grafana": _long_running(environment={"GF_SECURITY_ADMIN_PASSWORD": SECRET}),
            "secret-check": {"environment": {"POSTGRES_PASSWORD": SECRET}},
            "auth-smoke": {"environment": {"ASSISTANT_CLIENT_SECRET": SECRET}},
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
@pytest.mark.parametrize("one_shot", sorted(compose_check.ONE_SHOT_SERVICES))
def Given_a_one_shot_service_with_a_restart_policy_or_log_bound_When_checked_Then_it_fails(
    one_shot: str, member: str, capsys: pytest.CaptureFixture[str]
) -> None:
    resolved = _conforming()
    resolved["services"][one_shot][member] = {"restart": POLICY, "logging": BOUNDED_LOGS}[member]

    status, lines = _check(resolved, capsys)

    assert status == compose_check.EXIT_FAILED
    assert lines == [f"{one_shot}: a one-shot service takes no restart or logging"]


@pytest.mark.parametrize(("service", "path"), sorted(compose_check.READINESS_PROBES.items()))
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
        "management.endpoints.web.exposure.include",
        "spring_application_json",
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


def Given_a_management_key_on_a_service_without_spring_When_checked_Then_it_passes(
    capsys: pytest.CaptureFixture[str],
) -> None:
    resolved = _conforming()
    resolved["services"]["db"]["environment"]["MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"] = "*"

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
    ],
    ids=[
        "not-a-mapping",
        "no-services",
        "services-a-list",
        "service-a-string",
        "healthcheck",
        "environment-a-list",
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

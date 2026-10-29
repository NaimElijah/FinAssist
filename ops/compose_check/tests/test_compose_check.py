"""The Compose conventions check, driven through its command line with crafted resolved files.

Every case hands `main` what `docker compose config --format json` would print, as bytes on its
standard input, the way `make check-compose` runs the script (#142, ADR-039, ADR-049). No case needs
Docker: the resolved file is built here, starting from one that follows every convention.
"""

from __future__ import annotations

import copy
import io
import json
import re
from pathlib import Path
from typing import Any

import compose_check
import pytest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
PLACEHOLDERS = REPOSITORY_ROOT / "ops" / "compose_check" / "placeholders.env"
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
            "ledger": _long_running(healthcheck=_probe("/actuator/health/readiness")),
            "gateway": _long_running(healthcheck=_probe("/actuator/health/readiness")),
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
    ],
    ids=["not-a-mapping", "no-services", "services-a-list", "service-a-string", "healthcheck"],
)
def Given_json_not_shaped_like_a_resolved_file_When_checked_Then_it_is_refused_without_a_value(
    resolved: object, capsys: pytest.CaptureFixture[str]
) -> None:
    status, lines = _run(json.dumps(resolved).encode(), capsys)

    assert status == compose_check.EXIT_MISCONFIGURED
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

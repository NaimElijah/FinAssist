"""Enforces the dependency direction declared in docs/architecture/architecture.md.

Dependencies point inward: adapters -> application -> domain -> nothing. The domain carries
the analysis rules and must stay free of frameworks and I/O so that it is testable by calling
it, and so that a later extraction is a move rather than a rewrite.

The second rule is the one that matters most in a polyglot system: neither the domain nor the
application may reach an adapter, because the ledger's anti-corruption layer lives there. A
ledger DTO that leaks inward makes the ledger's wire shape this service's model and couples
the two services across a language boundary. See ADR-004 and ADR-007.
"""

import json
import subprocess
import sys

import pytest

# Top-level distributions no inward layer may load, directly or transitively.
FORBIDDEN_FRAMEWORKS = frozenset(
    {"fastapi", "starlette", "uvicorn", "httpx", "anthropic", "sqlalchemy"}
)

ADAPTERS_PACKAGE = "finassist_assistant.adapters"

INWARD_PACKAGES = [
    "finassist_assistant.domain",
    "finassist_assistant.domain.analysis",
    "finassist_assistant.domain.model",
    "finassist_assistant.application",
    "finassist_assistant.application.port",
    "finassist_assistant.application.usecase",
]


def _modules_loaded_by(package: str) -> set[str]:
    """Import ``package`` in a fresh interpreter and return every module it pulled in.

    A subprocess is used so the result cannot depend on what another test imported first;
    an in-process check would pass or fail according to test ordering.
    """
    probe = (
        "import importlib, json, sys; "
        f"importlib.import_module({package!r}); "
        "print(json.dumps(sorted(sys.modules)))"
    )
    result = subprocess.run(
        [sys.executable, "-c", probe],
        capture_output=True,
        text=True,
        check=True,
    )
    return set(json.loads(result.stdout))


@pytest.mark.parametrize("package", INWARD_PACKAGES)
def Given_an_inward_layer_When_imported_Then_it_loads_no_framework(package: str) -> None:
    leaked = sorted(_modules_loaded_by(package) & FORBIDDEN_FRAMEWORKS)
    assert not leaked, f"{package} imported framework packages: {leaked}"


@pytest.mark.parametrize("package", INWARD_PACKAGES)
def Given_an_inward_layer_When_imported_Then_it_loads_no_adapter(package: str) -> None:
    loaded = _modules_loaded_by(package)
    leaked = sorted(m for m in loaded if m.startswith(ADAPTERS_PACKAGE))
    assert not leaked, (
        f"{package} imported the adapter layer: {leaked}. "
        "The anti-corruption layer lives there; nothing inward may depend on it."
    )

"""Enforces the structural rules declared in docs/architecture/architecture.md.

Three rules, each checked by importing a package in a fresh interpreter and looking at what
came with it:

1. Dependencies point inward - no inward layer loads a framework.
2. No inward layer reaches its own adapters, where the anti-corruption layers live.
3. No context imports another context. Only ``shared`` is common ground.

Rule 3 is what makes context-first packaging more than folders: it is the difference between
a context you can extract by moving a directory and one that merely looks that way. See
ADR-007 and ADR-010.
"""

import json
import subprocess
import sys

import pytest

ROOT = "finassist_assistant"

# Bounded contexts. `shared` is deliberately absent - it is the one package all may use.
CONTEXTS = ("portfolio", "assistance", "marketdata")

# Top-level distributions no inward layer may load, directly or transitively.
FORBIDDEN_FRAMEWORKS = frozenset(
    {"fastapi", "starlette", "uvicorn", "httpx", "anthropic", "sqlalchemy", "pandas"}
)

# Every inward (non-adapter) package, across every context.
INWARD_PACKAGES = (
    *(
        f"{ROOT}.{context}.{suffix}"
        for context in CONTEXTS
        for suffix in ("domain", "application", "application.port", "application.usecase")
    ),
    f"{ROOT}.shared",
)

CROSS_CONTEXT_PAIRS = tuple(
    (importer, other) for importer in CONTEXTS for other in CONTEXTS if importer != other
)


def _modules_loaded_by(package: str) -> set[str]:
    """Import ``package`` in a fresh interpreter and return every module it pulled in.

    A subprocess is used so the result cannot depend on what another test imported first; an
    in-process check would pass or fail according to test ordering.
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
    leaked = sorted(m for m in loaded if ".adapters" in m and m.startswith(ROOT))
    assert not leaked, (
        f"{package} imported an adapter layer: {leaked}. "
        "The anti-corruption layers live there; nothing inward may depend on one."
    )


@pytest.mark.parametrize(("importer", "other"), CROSS_CONTEXT_PAIRS)
def Given_a_context_When_imported_Then_it_loads_no_other_context(importer: str, other: str) -> None:
    loaded = _modules_loaded_by(f"{ROOT}.{importer}")
    prefix = f"{ROOT}.{other}"
    leaked = sorted(m for m in loaded if m == prefix or m.startswith(prefix + "."))
    assert not leaked, (
        f"context '{importer}' imported context '{other}': {leaked}. "
        "Contexts collaborate through a published port and an explicit translation, "
        "never through an import."
    )

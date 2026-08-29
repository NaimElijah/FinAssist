"""Enforces the structural rules declared in docs/architecture/architecture.md.

Three rules:

1. Dependencies point inward - no inward layer imports a framework.
2. No inward layer imports the adapter layer, where the anti-corruption layers live.
3. No context imports another context. Only ``shared`` is common ground.

**These are checked statically, by parsing every source file, not by importing packages and
inspecting sys.modules.** An earlier version did the latter and was blind by construction:
``importlib.import_module('finassist_assistant.portfolio')`` executes only that package's
``__init__.py``, and Python does not import subpackages on its own, so a forbidden import
sitting in ``portfolio/domain/analysis/returns.py`` was never loaded and the rule passed
green. Reading the source tree is the only way these rules see the code that will actually
be written. It also matches how the ledger (ArchUnit bytecode scan) and the web client (file
walk) enforce the same boundary.

See ADR-007 and ADR-010.
"""

import ast
from pathlib import Path

import pytest

ROOT_PACKAGE = "finassist_assistant"
SRC = Path(__file__).resolve().parent.parent / "src" / ROOT_PACKAGE

# Bounded contexts. `shared` is deliberately absent - it is the one package all may use.
CONTEXTS = frozenset({"portfolio", "assistance", "marketdata"})

# Top-level distributions no inward layer may import, directly.
FORBIDDEN_FRAMEWORKS = frozenset(
    {"fastapi", "starlette", "uvicorn", "httpx", "anthropic", "sqlalchemy", "pandas"}
)

ADAPTER_SEGMENT = "adapters"

SOURCE_FILES = sorted(SRC.rglob("*.py"))


def _module_package(path: Path) -> str:
    """The dotted package a file lives in - the base for resolving its relative imports."""
    relative = path.relative_to(SRC.parent)
    return ".".join(relative.parent.parts)


def _imported_modules(path: Path) -> set[str]:
    """Every absolute module name the file imports, with relative imports resolved."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package = _module_package(path)
    imported: set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level == 0:
                base = node.module or ""
            else:
                # `from . import x` resolves against the file's own package, `from .. import x`
                # against its parent, and so on.
                parts = package.split(".")
                trimmed = parts[: len(parts) - (node.level - 1)]
                base = ".".join([*trimmed, node.module] if node.module else trimmed)
            imported.add(base)
            imported.update(f"{base}.{alias.name}" for alias in node.names if base)

    return imported


def _context_of(path: Path) -> str | None:
    """The bounded context a file belongs to, or None for `shared` and the root package."""
    parts = path.relative_to(SRC).parts
    return parts[0] if parts and parts[0] in CONTEXTS else None


def _is_inward(path: Path) -> bool:
    """True for anything outside the adapter layer: domain, application, ports, use cases."""
    return ADAPTER_SEGMENT not in path.relative_to(SRC).parts


def _context_imported_by(module: str) -> str | None:
    parts = module.split(".")
    if len(parts) >= 2 and parts[0] == ROOT_PACKAGE and parts[1] in CONTEXTS:
        return parts[1]
    return None


def Given_the_source_tree_When_collected_Then_it_is_not_empty() -> None:
    """Guards every rule below from passing because it found nothing to check."""
    assert SOURCE_FILES, f"no Python sources found under {SRC}"


@pytest.mark.parametrize("path", SOURCE_FILES, ids=lambda p: str(p.relative_to(SRC)))
def Given_an_inward_module_When_parsed_Then_it_imports_no_framework(path: Path) -> None:
    if not _is_inward(path):
        pytest.skip("adapters are the layer allowed to import frameworks")

    leaked = sorted(
        module for module in _imported_modules(path) if module.split(".")[0] in FORBIDDEN_FRAMEWORKS
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} imports {leaked}. Frameworks and I/O live in adapters; "
        "the inward layers stay testable by calling them."
    )


@pytest.mark.parametrize("path", SOURCE_FILES, ids=lambda p: str(p.relative_to(SRC)))
def Given_an_inward_module_When_parsed_Then_it_imports_no_adapter(path: Path) -> None:
    if not _is_inward(path):
        pytest.skip("adapters may import each other within their own context")

    prefix = f"{ROOT_PACKAGE}."
    leaked = sorted(
        module
        for module in _imported_modules(path)
        if module.startswith(prefix) and ADAPTER_SEGMENT in module.split(".")
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} imports the adapter layer: {leaked}. The anti-corruption "
        "layers live there; nothing inward may depend on one."
    )


@pytest.mark.parametrize("path", SOURCE_FILES, ids=lambda p: str(p.relative_to(SRC)))
def Given_a_module_When_parsed_Then_it_imports_no_other_context(path: Path) -> None:
    own = _context_of(path)
    if own is None:
        pytest.skip("shared and the root package belong to no context")

    leaked = sorted(
        module
        for module in _imported_modules(path)
        if (other := _context_imported_by(module)) is not None and other != own
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} (context '{own}') imports {leaked}. Contexts collaborate "
        "through a published port and an explicit translation, never through an import."
    )

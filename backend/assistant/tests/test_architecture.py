"""Enforces the structural rules declared in docs/architecture/architecture.md.

Five rules:

1. Dependencies point inward - no inward layer imports a framework.
2. No inward layer imports the adapter layer, where the anti-corruption layers live.
3. No context imports another context. Only ``shared`` is common ground.
4. No domain module names ``float``. Money is exact decimal, in both languages.
5. No domain module reads the wall clock. The caller passes the time in.

Rules 4 and 5 mirror commitments the ledger already enforces or documents:
``LedgerArchitectureTest.money_is_never_floating_point`` for the first, and the "pass the
clock in" convention for the second, which every domain function here already follows and
nothing checked.

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

# Top-level distributions no inward layer may import, directly. Every declared runtime
# dependency of this service belongs here: the list is what stops one leaking inward, so a
# dependency missing from it is a hole rather than an omission. `sqlalchemy` was listed and is
# not a dependency at all, which made the rule look wider than it was.
FORBIDDEN_FRAMEWORKS = frozenset(
    {
        "fastapi",
        "starlette",
        "uvicorn",
        "httpx",
        "anthropic",
        "pandas",
        "pydantic",
        "pydantic_settings",
        "structlog",
    }
)

DOMAIN_SEGMENT = "domain"

# Attribute calls that read the wall clock. Matched as calls, never by name: `is_stale` and
# `age` both take a parameter called `now`, and a name-based rule would fail the build on the
# very convention it exists to enforce.
CLOCK_READS = frozenset({"now", "today", "utcnow", "time", "monotonic", "time_ns"})

ADAPTER_SEGMENT = "adapters"

SOURCE_FILES = sorted(SRC.rglob("*.py"))


def _module_package(path: Path) -> str:
    """The dotted package a file lives in - the base for resolving its relative imports."""
    relative = path.relative_to(SRC.parent)
    return ".".join(relative.parent.parts)


def _imported_modules(path: Path) -> set[str]:
    """Every absolute module name the file imports, with relative imports resolved."""
    return _imports_in(path.read_text(encoding="utf-8"), _module_package(path), str(path))


def _imports_in(source: str, package: str, filename: str = "<test>") -> set[str]:
    """The same, over source text rather than a path.

    Split out so the resolution logic can be tested directly. It could not be reached from a
    temporary file, because ``_module_package`` resolves paths relative to ``SRC`` and a file
    outside the tree raises instead.
    """
    tree = ast.parse(source, filename=filename)
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


def _is_domain(path: Path) -> bool:
    return DOMAIN_SEGMENT in path.relative_to(SRC).parts


def _float_uses_in(source: str, filename: str = "<test>") -> list[str]:
    """Every mention of `float` as a type, and every float literal.

    A docstring saying the word "float" is an ``ast.Constant`` holding a string and is not
    matched; only the name and real literals are.
    """
    tree = ast.parse(source, filename=filename)
    found: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id == "float":
            found.append(f"float at line {node.lineno}")
        elif isinstance(node, ast.Constant) and isinstance(node.value, float):
            found.append(f"literal {node.value!r} at line {node.lineno}")
    return found


def _clock_reads_in(source: str, filename: str = "<test>") -> list[str]:
    """Every call that reads the wall clock, matched by call shape rather than by name."""
    tree = ast.parse(source, filename=filename)
    found: list[str] = []
    for node in ast.walk(tree):
        if (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr in CLOCK_READS
        ):
            found.append(f"{node.func.attr}() at line {node.lineno}")
    return found


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


@pytest.mark.parametrize("path", SOURCE_FILES, ids=lambda p: str(p.relative_to(SRC)))
def Given_a_domain_module_When_parsed_Then_it_names_no_float(path: Path) -> None:
    if not _is_domain(path):
        pytest.skip("only the domain is held to exact decimal arithmetic")

    found = _float_uses_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} uses floating point: {found}. Money is exact decimal - "
        "Decimal here, BigDecimal in the ledger, NUMERIC in Postgres. The ledger fails the "
        "same way through LedgerArchitectureTest.money_is_never_floating_point."
    )


@pytest.mark.parametrize("path", SOURCE_FILES, ids=lambda p: str(p.relative_to(SRC)))
def Given_a_domain_module_When_parsed_Then_it_reads_no_clock(path: Path) -> None:
    if not _is_domain(path):
        pytest.skip("only the domain is required to take its time as an argument")

    found = _clock_reads_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} reads the wall clock: {found}. A domain function takes the "
        "current time as an argument - is_stale and age already do - so its behaviour is a "
        "function of its inputs and a test never has to freeze anything."
    )


# ---------------------------------------------------------------------------------------------
# The rules above are only as good as the helpers underneath them. Nothing tested those, so a
# refactor of the import scanner could have neutered all five while every case stayed green -
# the same class of failure as the sys.modules version this file's docstring describes, which
# also passed green on code it could not see. These assert the scanner itself.
# ---------------------------------------------------------------------------------------------

PACKAGE = f"{ROOT_PACKAGE}.portfolio.domain.analysis"


def Given_a_plain_import_When_scanned_Then_the_module_is_found() -> None:
    assert _imports_in("import pandas", PACKAGE) == {"pandas"}
    assert _imports_in("import os, sys", PACKAGE) == {"os", "sys"}
    assert _imports_in("import pandas as pd", PACKAGE) == {"pandas"}


def Given_a_from_import_When_scanned_Then_both_module_and_member_are_found() -> None:
    assert _imports_in("from decimal import Decimal", PACKAGE) == {"decimal", "decimal.Decimal"}


def Given_an_import_inside_a_function_When_scanned_Then_it_is_still_found() -> None:
    # A deferred import is the obvious way to smuggle a framework past a rule that only looked
    # at the top of a file. ast.walk descends into the whole tree, so it does not work.
    source = "def load():\n    import httpx\n    return httpx"
    assert "httpx" in _imports_in(source, PACKAGE)


def Given_a_relative_import_When_scanned_Then_it_resolves_against_its_own_package() -> None:
    # No source in this tree uses relative imports, so this arithmetic is otherwise unexercised.
    assert _imports_in("from . import model", PACKAGE) == {
        PACKAGE,
        f"{PACKAGE}.model",
    }


def Given_a_parent_relative_import_When_scanned_Then_it_resolves_one_level_up() -> None:
    parent = f"{ROOT_PACKAGE}.portfolio.domain"
    assert _imports_in("from .. import model", PACKAGE) == {parent, f"{parent}.model"}


def Given_a_named_relative_import_When_scanned_Then_the_submodule_is_included() -> None:
    assert _imports_in("from .model import Lot", PACKAGE) == {
        f"{PACKAGE}.model",
        f"{PACKAGE}.model.Lot",
    }


def Given_a_module_path_When_asked_Then_its_context_is_the_first_segment() -> None:
    assert _context_of(SRC / "portfolio" / "domain" / "__init__.py") == "portfolio"
    assert _context_of(SRC / "shared" / "money.py") is None
    assert _context_of(SRC / "__init__.py") is None


def Given_a_module_path_When_asked_Then_only_the_adapter_layer_is_outward() -> None:
    assert _is_inward(SRC / "portfolio" / "domain" / "__init__.py")
    assert not _is_inward(SRC / "portfolio" / "adapters" / "outbound" / "ledger" / "__init__.py")


def Given_a_module_name_When_asked_Then_the_context_it_belongs_to_is_returned() -> None:
    assert _context_imported_by(f"{ROOT_PACKAGE}.assistance.domain.model") == "assistance"
    assert _context_imported_by(f"{ROOT_PACKAGE}.shared.money") is None
    assert _context_imported_by("decimal") is None


def Given_a_domain_path_When_asked_Then_only_domain_modules_are_matched() -> None:
    assert _is_domain(SRC / "portfolio" / "domain" / "model" / "__init__.py")
    assert not _is_domain(SRC / "shared" / "money.py")


def Given_source_using_float_When_scanned_Then_the_annotation_and_the_literal_are_found() -> None:
    assert _float_uses_in("x: float = 1.0")
    assert _float_uses_in("def f(v: list[float]) -> None: ...")
    assert _float_uses_in("rate = 0.5")


def Given_a_docstring_mentioning_float_When_scanned_Then_it_is_not_a_violation() -> None:
    # The word appears in several domain docstrings explaining why floats are refused. A rule
    # that flagged those would be uninstallable, so it must look at the tree, not the text.
    assert _float_uses_in('"""Never a float; Decimal only."""') == []
    assert _float_uses_in('name = "float"') == []


def Given_source_reading_the_clock_When_scanned_Then_the_call_is_found() -> None:
    assert _clock_reads_in("import datetime\nx = datetime.datetime.now()")
    assert _clock_reads_in("from datetime import date\nx = date.today()")
    assert _clock_reads_in("import time\nx = time.time()")


def Given_a_parameter_named_now_When_scanned_Then_it_is_not_a_violation() -> None:
    # is_stale and age both take a parameter called `now` and subtract with it. A name-based
    # rule would fail the build on the very convention it exists to enforce, so the rule
    # matches call shape instead. This is the case that pins that choice.
    assert _clock_reads_in("def is_stale(now, other):\n    return now - other") == []
    assert _clock_reads_in("age = now - quote.as_of") == []

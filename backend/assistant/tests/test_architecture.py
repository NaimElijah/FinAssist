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
import re
import tomllib
from pathlib import Path

import pytest

ROOT_PACKAGE = "finassist_assistant"
SRC = Path(__file__).resolve().parent.parent / "src" / ROOT_PACKAGE

# Bounded contexts. `shared` is deliberately absent - it is the one package all may use.
CONTEXTS = frozenset({"portfolio", "assistance", "marketdata"})

PYPROJECT = SRC.parent.parent / "pyproject.toml"


def _declared_runtime_distributions() -> frozenset[str]:
    """Every runtime dependency, as the name it is imported by.

    Read from pyproject.toml rather than copied here. A hand-kept list is a second place to
    remember, and it fails silently in the wrong direction: a dependency missing from it leaves
    an inward module free to import a framework, which is exactly what this rule exists to
    stop. Adding a dependency now widens the rule on its own.
    """
    with PYPROJECT.open("rb") as handle:
        project = tomllib.load(handle)["project"]

    names = set()
    for requirement in project["dependencies"]:
        # "uvicorn[standard]==0.52.4" -> "uvicorn". Distribution names are hyphenated where
        # import names are underscored.
        name = re.split(r"[\[<>=!~;\s]", requirement, maxsplit=1)[0]
        names.add(name.strip().replace("-", "_"))
    return frozenset(names)


# Top-level distributions no inward layer may import, directly. Derived from the declared
# runtime dependencies, plus two transitives worth naming: starlette arrives under fastapi and
# is just as much a framework, and sqlalchemy is refused on the day it is added rather than the
# day someone notices.
FORBIDDEN_FRAMEWORKS = _declared_runtime_distributions() | frozenset({"starlette", "sqlalchemy"})

DOMAIN_SEGMENT = "domain"

# Attribute calls that read the wall clock. Matched as calls, never by name: `is_stale` and
# `age` both take a parameter called `now`, and a name-based rule would fail the build on the
# very convention it exists to enforce.
CLOCK_READS = frozenset({"now", "today", "utcnow", "time", "monotonic", "time_ns"})

ADAPTER_SEGMENT = "adapters"

SHARED_PACKAGE = "shared"

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
    """A context's domain, or the shared kernel.

    ``shared`` is included deliberately. It sits inside no context's ``domain`` directory, but
    it is where ``Money`` and ``Currency`` live - so a rule about exact arithmetic that skipped
    it would skip the one module it exists for, while the requirements table claimed otherwise.
    The ledger's ArchUnit mirror covers its own ``shared`` package for the same reason.
    """
    parts = path.relative_to(SRC).parts
    return DOMAIN_SEGMENT in parts or (bool(parts) and parts[0] == SHARED_PACKAGE)


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
    """Every reference to a wall-clock reader.

    An attribute is matched by name whether or not it is called, because
    ``field(default_factory=datetime.now)`` produces no ``Call`` node at that point and is
    exactly how a clock read enters a value object. Bare calls are matched too, so
    ``from time import time`` followed by ``time()`` does not slip past.

    A parameter *named* ``now`` is still not a violation - ``is_stale`` and ``age`` both take
    one - so a bare name is flagged only where it is being called.
    """
    tree = ast.parse(source, filename=filename)
    found: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr in CLOCK_READS:
            found.append(f"{node.attr} at line {node.lineno}")
        elif (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id in CLOCK_READS
        ):
            found.append(f"{node.func.id}() at line {node.lineno}")
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


def Given_the_source_tree_When_collected_Then_some_of_it_is_domain() -> None:
    """The same guard for the two domain-scoped rules, which skip everything else.

    Without it, renaming ``portfolio/domain/`` to ``portfolio/core/`` would make every case of
    both rules skip and the suite stay green - the behaviour the ledger deliberately removed
    when it deleted ``archRule.failOnEmptyShould=false``.
    """
    assert any(_is_domain(path) for path in SOURCE_FILES), (
        f"no domain or shared module found under {SRC}; the float and clock rules would skip "
        "every file and pass without checking anything"
    )


def Given_the_forbidden_framework_list_When_derived_Then_it_covers_the_dependencies() -> None:
    """The list is derived now, so this asserts the derivation still finds something."""
    declared = _declared_runtime_distributions()

    assert declared, f"no runtime dependencies parsed from {PYPROJECT}"
    assert declared <= FORBIDDEN_FRAMEWORKS


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


def Given_a_domain_path_When_asked_Then_the_domain_and_the_kernel_are_matched() -> None:
    assert _is_domain(SRC / "portfolio" / "domain" / "model" / "__init__.py")
    # shared holds Money and Currency, so the exact-arithmetic rule must reach it.
    assert _is_domain(SRC / "shared" / "money.py")
    assert not _is_domain(SRC / "portfolio" / "adapters" / "outbound" / "ledger" / "__init__.py")


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


def Given_an_uncalled_clock_reference_When_scanned_Then_it_is_still_a_violation() -> None:
    # How a clock read actually enters a frozen value object: no Call node at that point, so a
    # call-shape-only rule saw nothing at all.
    assert _clock_reads_in("taken_at: datetime = field(default_factory=datetime.now)")
    assert _clock_reads_in("from time import time" + chr(10) + "x = time()")


def Given_a_field_named_after_the_clock_When_scanned_Then_it_is_flagged_deliberately() -> None:
    # A false positive, and an intended one. Matching an attribute by name is what catches
    # `field(default_factory=datetime.now)`, which produces no Call node and is how a clock
    # read enters a value object; the price is that an ordinary attribute sharing the name is
    # flagged too. In a domain whose entire convention is that time arrives as an argument, a
    # field called `now` or `time` deserves the second look, and the message names the file and
    # line. Pinned so that narrowing the rule back is a deliberate act rather than a tidy-up -
    # the narrower version had exactly the hole described above.
    assert _clock_reads_in("x = quote.now")
    assert _clock_reads_in("x = instrument.time")


def Given_a_parameter_named_now_When_scanned_Then_it_is_not_a_violation() -> None:
    # is_stale and age both take a parameter called `now` and subtract with it. A rule matching
    # every bare name would fail the build on the very convention it exists to enforce.
    assert _clock_reads_in("def is_stale(now, other):\n    return now - other") == []
    assert _clock_reads_in("age = now - quote.as_of") == []

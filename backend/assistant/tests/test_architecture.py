"""Enforces the structural rules declared in docs/architecture/architecture.md.

Eight rules:

1. Dependencies point inward - no inward layer imports a framework.
2. No inward layer imports the adapter layer, where the anti-corruption layers live.
3. No context imports another context. Only ``shared`` is common ground.
4. No context imports the composition root, where the frameworks are wired.
5. No domain module names ``float``. Money is exact decimal, in both languages.
6. No inward module reads the wall clock - not the domain, not a use case, not ``shared``.
   The caller passes the time in; the composition root reads the clock once.
7. No inbound adapter imports an outbound one. A route reaches a client only through a use case.
8. No domain module imports the application layer. The use cases call the domain, never the
   reverse - the inward half of ``LedgerArchitectureTest.layers_point_inward``.

Rules 5 and 6 mirror commitments the ledger already enforces or documents:
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
# runtime dependencies, plus three worth naming explicitly: starlette arrives under fastapi and
# is just as much a framework, sqlalchemy is refused on the day it is added rather than the day
# someone notices, and `opentelemetry` is the import root that the derivation cannot reach.
#
# That last one is the interesting case. The distributions are named opentelemetry-sdk and
# opentelemetry-instrumentation-fastapi, which the derivation turns into `opentelemetry_sdk`
# and `opentelemetry_instrumentation_fastapi` - and code imports neither. It imports
# `opentelemetry.trace`, whose root is `opentelemetry`, matching nothing. Adding the OTel
# dependencies would otherwise have left this rule silently blind to them.
#
# `pydantic_core` is blind for a different reason: it is not declared at all, arriving under
# pydantic. The composition root maps its ValidationError to a problem, which makes it a name
# worth reaching for, and an inward module importing it passed this rule until it was listed.
#
# `jwt` is blind the same way `opentelemetry` is: the distribution is declared as
# `PyJWT[crypto]==2.14.0` (ADR-032), and the derivation strips the extra and the version pin
# but never lowercases, producing the literal `PyJWT` - while code imports `jwt`, matching
# neither that nor `pyjwt`.
#
# `cryptography` is blind for the `pydantic_core` reason, only worse: it is never declared at
# all. It arrives transitively, through the `crypto` extra on `PyJWT[crypto]`, so no declared
# runtime dependency's name is ever `cryptography` and the derivation can never produce it.
FORBIDDEN_FRAMEWORKS = _declared_runtime_distributions() | frozenset(
    {"opentelemetry", "pydantic_core", "starlette", "sqlalchemy", "jwt", "cryptography"}
)

# Standard-library modules no inward layer may import: the network, processes, a database and
# an event loop are I/O however they arrive, and `http` is a transport type besides. The
# derivation above reads only pyproject.toml, so the standard library is invisible to it - an
# inward module could open a socket and the framework rule passed. Nothing inward imports any of
# these today; the composition root and the inbound adapters use `http.HTTPStatus`, where it
# belongs.
FORBIDDEN_STANDARD_IO = frozenset(
    {"socket", "ssl", "http", "urllib", "sqlite3", "subprocess", "asyncio"}
)


def _is_forbidden_inward(module: str) -> bool:
    """Whether an inward module importing *module* reaches a framework or the I/O beneath one."""
    root = module.split(".")[0]
    return root in FORBIDDEN_FRAMEWORKS or root in FORBIDDEN_STANDARD_IO


DOMAIN_SEGMENT = "domain"

# Attribute calls that read the wall clock. Matched as calls, never by name: `is_stale` and
# `age` both take a parameter called `now`, and a name-based rule would fail the build on the
# very convention it exists to enforce. The process clocks are here too: a use case that times
# itself has read a clock just as surely as one that stamps a date.
CLOCK_READS = frozenset(
    {
        "now",
        "today",
        "utcnow",
        "time",
        "time_ns",
        "monotonic",
        "monotonic_ns",
        "perf_counter",
        "perf_counter_ns",
        "process_time",
        "process_time_ns",
        "localtime",
        "gmtime",
        "ctime",
    }
)

# The modules whose members CLOCK_READS names. Importing a reader out of one of them binds it
# to a name of the importer's choosing - `from time import time as clock` - which no name-based
# match on the call could follow, so the import itself is the violation.
CLOCK_MODULES = frozenset({"time", "datetime"})

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

    Two indirections are matched as well: importing a reader (or everything) out of ``time`` or
    ``datetime``, which lets it be called under any alias, and ``getattr`` naming a reader as a
    string, which no attribute node shows.
    """
    tree = ast.parse(source, filename=filename)
    found: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr in CLOCK_READS:
            found.append(f"{node.attr} at line {node.lineno}")
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module in CLOCK_MODULES:
            found.extend(
                f"import of {node.module}.{alias.name} at line {node.lineno}"
                for alias in node.names
                if alias.name in CLOCK_READS or alias.name == "*"
            )
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            if node.func.id in CLOCK_READS:
                found.append(f"{node.func.id}() at line {node.lineno}")
            elif (
                node.func.id == "getattr"
                and len(node.args) >= 2
                and isinstance(node.args[1], ast.Constant)
                and node.args[1].value in CLOCK_READS
            ):
                found.append(f"getattr(..., {node.args[1].value!r}) at line {node.lineno}")
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


INBOUND_SEGMENT = "inbound"
OUTBOUND_SEGMENT = "outbound"


def _is_inbound_adapter(path: Path) -> bool:
    """A module under a context's ``adapters/inbound``: what calls the context."""
    parts = path.relative_to(SRC).parts
    return parts[0] in CONTEXTS and parts[1:3] == (ADAPTER_SEGMENT, INBOUND_SEGMENT)


def _names_outbound_adapter(module: str) -> bool:
    """A module name under any context's ``adapters/outbound``: what a context calls."""
    parts = module.split(".")
    return (
        parts[0] == ROOT_PACKAGE
        and len(parts) > 1
        and parts[1] in CONTEXTS
        and parts[2:4] == [ADAPTER_SEGMENT, OUTBOUND_SEGMENT]
    )


APPLICATION_SEGMENT = "application"


def _names_application_layer(module: str) -> bool:
    """A module name under any context's ``application``: its ports and use cases."""
    parts = module.split(".")
    return (
        len(parts) > 2
        and parts[0] == ROOT_PACKAGE
        and parts[1] in CONTEXTS
        and parts[2] == APPLICATION_SEGMENT
    )


# Each rule runs over the files it holds, filtered here once, rather than over every file with
# the rest skipped. A skip is not a check, and a filter rendered as hundreds of skips hid the one
# failure that matters: a layer renamed out of its filter's reach, so that every case of its rule
# skipped and the suite stayed green. An empty list now fails its guard below instead.
INWARD_FILES = [path for path in SOURCE_FILES if _is_inward(path)]
CONTEXT_FILES = [path for path in SOURCE_FILES if _context_of(path) is not None]
COMPOSITION_ROOT_HELD_FILES = [
    path for path in SOURCE_FILES if _context_of(path) is not None or _is_domain(path)
]
INBOUND_ADAPTER_FILES = [path for path in SOURCE_FILES if _is_inbound_adapter(path)]
DOMAIN_FILES = [path for path in SOURCE_FILES if _is_domain(path)]

RULE_SCOPES = {
    "inward": INWARD_FILES,
    "context": CONTEXT_FILES,
    "composition-root-held": COMPOSITION_ROOT_HELD_FILES,
    "inbound-adapter": INBOUND_ADAPTER_FILES,
    "domain": DOMAIN_FILES,
}


def _relative_id(path: Path) -> str:
    return str(path.relative_to(SRC))


def Given_the_source_tree_When_collected_Then_it_is_not_empty() -> None:
    """Guards every rule below from passing because it found nothing to check."""
    assert SOURCE_FILES, f"no Python sources found under {SRC}"


def Given_the_source_tree_When_collected_Then_some_of_it_is_domain() -> None:
    """The same guard for the domain-scoped rules, which hold only :data:`DOMAIN_FILES`.

    Without it, renaming ``portfolio/domain/`` to ``portfolio/core/`` would leave those rules
    nothing to check and the suite green - the behaviour the ledger deliberately removed when
    it deleted ``archRule.failOnEmptyShould=false``.
    """
    assert DOMAIN_FILES, (
        f"no domain or shared module found under {SRC}; the domain-scoped rules would pass "
        "without checking anything"
    )


@pytest.mark.parametrize("scope", RULE_SCOPES)
def Given_the_files_a_rule_holds_When_filtered_Then_there_is_at_least_one(scope: str) -> None:
    """Guards each rule below from passing because its filter matched nothing.

    A rule parametrised over an empty list collects no case at all, so renaming
    ``adapters/inbound`` or ``domain`` would otherwise leave that rule checking nothing while the
    suite stayed green.
    """
    assert RULE_SCOPES[scope], (
        f"no {scope} module found under {SRC}; the rules held to that scope would check nothing"
    )


def Given_the_forbidden_framework_list_When_derived_Then_it_covers_the_dependencies() -> None:
    """The list is derived now, so this asserts the derivation still finds something."""
    declared = _declared_runtime_distributions()

    assert declared, f"no runtime dependencies parsed from {PYPROJECT}"
    assert declared <= FORBIDDEN_FRAMEWORKS


def Given_the_opentelemetry_packages_When_derived_Then_the_derivation_alone_would_miss_them() -> (
    None
):
    """Pins why `opentelemetry` is listed explicitly rather than left to the derivation.

    The distributions are `opentelemetry-sdk` and friends, which the derivation turns into
    `opentelemetry_sdk`; the code imports `opentelemetry.trace`, whose root is `opentelemetry`.
    Those never match, so before this entry existed an inward module could import the OTel API
    and the framework rule passed - verified by removing the entry and watching a planted
    violation go green.

    This test fails if someone deletes the explicit entry believing the derivation covers it.
    """
    derived = _declared_runtime_distributions()

    assert any(name.startswith("opentelemetry_") for name in derived), (
        "no opentelemetry distribution is declared; this test no longer guards anything"
    )
    assert "opentelemetry" not in derived, (
        "the derivation now produces the import root on its own, so the explicit entry below "
        "may be redundant - check before removing it"
    )
    assert "opentelemetry" in FORBIDDEN_FRAMEWORKS


def Given_the_pyjwt_distribution_When_derived_Then_the_derivation_alone_would_miss_it() -> None:
    """Pins why `jwt` is listed explicitly rather than left to the derivation.

    The distribution is declared as `PyJWT[crypto]==2.14.0` (ADR-032). The derivation splits
    off the `[crypto]` extra and the version pin but never lowercases, so it produces the
    literal `PyJWT` - and the code imports `jwt`, matching neither that nor `pyjwt`. Before this
    entry existed an inward module could import PyJWT under its real import root and the
    framework rule passed - verified by removing the entry and watching a planted `import jwt`
    in `portfolio/domain` go green.

    This test fails if someone deletes the explicit entry believing the derivation covers it.
    """
    derived = _declared_runtime_distributions()

    assert "PyJWT" in derived, "PyJWT is no longer declared; this test no longer guards anything"
    assert "jwt" not in derived, (
        "the derivation now produces the import root on its own, so the explicit entry below "
        "may be redundant - check before removing it"
    )
    assert "jwt" in FORBIDDEN_FRAMEWORKS


def Given_the_cryptography_package_When_derived_Then_it_is_unreachable() -> None:
    """Pins why `cryptography` is listed explicitly: it is never declared at all.

    `PyJWT[crypto]`'s `crypto` extra brings `cryptography` in transitively (ADR-032); no
    runtime dependency is ever named `cryptography` itself, so the derivation - which reads
    only the distributions declared in `pyproject.toml` - can never produce it, no matter how
    it mangles a name. That makes this entry worse than `opentelemetry`'s or `jwt`'s: those are
    at least declared under a different name, and this one is not declared under any name.

    This test fails if someone deletes the explicit entry believing the derivation covers it.
    """
    derived = _declared_runtime_distributions()

    assert "cryptography" not in derived, (
        "cryptography is now declared as a runtime dependency in its own right; check whether "
        "the derivation covers it before relying on the explicit entry below"
    )
    assert "cryptography" in FORBIDDEN_FRAMEWORKS


@pytest.mark.parametrize("path", INWARD_FILES, ids=_relative_id)
def Given_an_inward_module_When_parsed_Then_it_imports_no_framework(path: Path) -> None:
    leaked = sorted(module for module in _imported_modules(path) if _is_forbidden_inward(module))
    assert not leaked, (
        f"{path.relative_to(SRC)} imports {leaked}. Frameworks and I/O live in adapters; "
        "the inward layers stay testable by calling them."
    )


@pytest.mark.parametrize("path", INWARD_FILES, ids=_relative_id)
def Given_an_inward_module_When_parsed_Then_it_imports_no_adapter(path: Path) -> None:
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


@pytest.mark.parametrize("path", CONTEXT_FILES, ids=_relative_id)
def Given_a_module_When_parsed_Then_it_imports_no_other_context(path: Path) -> None:
    own = _context_of(path)

    leaked = sorted(
        module
        for module in _imported_modules(path)
        if (other := _context_imported_by(module)) is not None and other != own
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} (context '{own}') imports {leaked}. Contexts collaborate "
        "through a published port and an explicit translation, never through an import."
    )


@pytest.mark.parametrize("path", COMPOSITION_ROOT_HELD_FILES, ids=_relative_id)
def Given_a_context_module_When_parsed_Then_it_imports_no_composition_root(path: Path) -> None:
    """The composition root wires frameworks; a context must not reach back into it.

    ``src/finassist_assistant/adapters/`` is the one package allowed to import FastAPI,
    structlog and OpenTelemetry. It is not a bounded context, so the cross-context rule skips
    it, and it lives under an ``adapters`` path, so the framework rule skips it too - which
    would leave nothing at all watching the direction of this dependency.
    """
    composition_root = f"{ROOT_PACKAGE}.{ADAPTER_SEGMENT}"
    leaked = sorted(
        module
        for module in _imported_modules(path)
        if module == composition_root or module.startswith(f"{composition_root}.")
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} imports the composition root: {leaked}. Frameworks travel "
        "inward only through the ports a context declares."
    )


@pytest.mark.parametrize("path", INBOUND_ADAPTER_FILES, ids=_relative_id)
def Given_an_inbound_adapter_When_parsed_Then_it_imports_no_outbound_adapter(path: Path) -> None:
    """What calls a context reaches what the context calls only through a use case.

    A route importing an outbound adapter could read the in-memory ledger - or the ledger client
    that replaces it - directly, skipping the use case and every rule behind the port. None of the
    rules above covers it: both adapters sit under ``adapters``, which the inward rules skip, and
    in one context, which the cross-context rule allows.
    """
    leaked = sorted(module for module in _imported_modules(path) if _names_outbound_adapter(module))
    assert not leaked, (
        f"{path.relative_to(SRC)} imports an outbound adapter: {leaked}. An inbound adapter calls "
        "a use case, and the use case reaches outbound adapters through the ports it declares."
    )


@pytest.mark.parametrize("path", DOMAIN_FILES, ids=_relative_id)
def Given_a_domain_module_When_parsed_Then_it_imports_no_application_layer(path: Path) -> None:
    """Dependencies point inward: a use case calls the domain, and the domain never calls back.

    The inward rules above stop a domain module reaching an adapter or a framework, but not
    the application layer beside it - and that is the one import a domain function reaches
    for first, to name a port. The ledger forbids it in ``layers_point_inward``.
    """
    leaked = sorted(
        module for module in _imported_modules(path) if _names_application_layer(module)
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} imports the application layer: {leaked}. The domain is the "
        "innermost layer; the ports and use cases depend on it, never the reverse."
    )


@pytest.mark.parametrize("path", DOMAIN_FILES, ids=_relative_id)
def Given_a_domain_module_When_parsed_Then_it_names_no_float(path: Path) -> None:
    found = _float_uses_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} uses floating point: {found}. Money is exact decimal - "
        "Decimal here, BigDecimal in the ledger, NUMERIC in Postgres. The ledger fails the "
        "same way through LedgerArchitectureTest.money_is_never_floating_point."
    )


@pytest.mark.parametrize("path", INWARD_FILES, ids=_relative_id)
def Given_an_inward_module_When_parsed_Then_it_reads_no_clock(path: Path) -> None:
    """Pass the clock in: the domain, the use cases and ``shared`` all take time as an argument.

    The composition root reads the clock once (``utc_now``) and passes it down, so the only
    clock reads in the tree belong under ``adapters``. A use case reading one would make its
    behaviour depend on when the test ran, exactly as a domain function would.
    """
    found = _clock_reads_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} reads the wall clock: {found}. An inward function takes the "
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


@pytest.mark.parametrize(
    "source",
    [
        "import time\nx = time.perf_counter()",
        "import time\nx = time.perf_counter_ns()",
        "import time\nx = time.monotonic_ns()",
        "import time\nx = time.process_time()",
        "import time\nx = time.process_time_ns()",
        "import time\nx = time.localtime()",
        "import time\nx = time.gmtime()",
        "import time\nx = time.ctime()",
    ],
)
def Given_source_reading_a_process_or_calendar_clock_When_scanned_Then_it_is_found(
    source: str,
) -> None:
    assert _clock_reads_in(source), source


@pytest.mark.parametrize(
    "source",
    [
        "from time import time as clock\nx = clock()",
        "from time import perf_counter as tick\nx = tick()",
        "from time import localtime",
        "from datetime import datetime as dt, now",
        "from time import *\nx = monotonic()",
    ],
)
def Given_a_clock_reader_imported_under_any_name_When_scanned_Then_the_import_is_found(
    source: str,
) -> None:
    # An alias moves the call out of reach of any name match - `clock()` names nothing in
    # CLOCK_READS - so the import that binds it is what the rule must see.
    assert any("import of" in found for found in _clock_reads_in(source)), source


def Given_a_clock_reader_named_through_getattr_When_scanned_Then_it_is_found() -> None:
    # No Attribute node: the reader's name is a string constant.
    found = _clock_reads_in('import datetime\nx = getattr(datetime.datetime, "now")()')

    assert any("getattr" in item for item in found), found


def Given_a_non_clock_import_from_datetime_When_scanned_Then_it_is_not_a_violation() -> None:
    # Every domain module imports `date` or `datetime` as a type; the import rule must not flag
    # that, or it would be uninstallable.
    assert _clock_reads_in("from datetime import UTC, date, datetime, timedelta") == []


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


def Given_a_module_path_When_asked_Then_only_a_contexts_inbound_adapters_are_inbound() -> None:
    assert _is_inbound_adapter(SRC / "portfolio" / "adapters" / "inbound" / "api" / "router.py")
    assert not _is_inbound_adapter(
        SRC / "portfolio" / "adapters" / "outbound" / "ledger" / "__init__.py"
    )
    # The composition root sits under an `adapters` directory too, but belongs to no context.
    assert not _is_inbound_adapter(SRC / "adapters" / "app.py")


def Given_a_module_name_When_asked_Then_only_a_contexts_outbound_adapters_are_outbound() -> None:
    assert _names_outbound_adapter(
        f"{ROOT_PACKAGE}.portfolio.adapters.outbound.ledger.InMemoryLedger"
    )
    assert _names_outbound_adapter(f"{ROOT_PACKAGE}.marketdata.adapters.outbound")
    assert not _names_outbound_adapter(f"{ROOT_PACKAGE}.portfolio.adapters.inbound.api")
    assert not _names_outbound_adapter(f"{ROOT_PACKAGE}.adapters.wiring")
    assert not _names_outbound_adapter("outbound.adapters")


def Given_a_module_name_When_asked_Then_only_a_contexts_application_layer_is_application() -> None:
    assert _names_application_layer(f"{ROOT_PACKAGE}.portfolio.application.port.LedgerPort")
    assert _names_application_layer(f"{ROOT_PACKAGE}.marketdata.application")
    assert not _names_application_layer(f"{ROOT_PACKAGE}.portfolio.domain.model")
    assert not _names_application_layer(f"{ROOT_PACKAGE}.shared.money")
    assert not _names_application_layer(f"{ROOT_PACKAGE}.application")
    assert not _names_application_layer("application")


def Given_a_relative_import_of_the_application_layer_When_scanned_Then_it_is_named() -> None:
    # A domain module two levels deep reaches its own context's ports with `from ...application`;
    # the rule sees that only because the scanner resolves it to an absolute name first.
    package = f"{ROOT_PACKAGE}.portfolio.domain.model"
    imported = _imports_in("from ...application.port import LedgerPort", package)

    assert any(_names_application_layer(module) for module in imported), imported


@pytest.mark.parametrize("module", sorted(FORBIDDEN_STANDARD_IO))
def Given_a_standard_library_io_import_When_checked_Then_it_is_forbidden_inward(
    module: str,
) -> None:
    imported = _imports_in(f"import {module}", PACKAGE) | _imports_in(
        f"from {module} import member", PACKAGE
    )

    assert all(_is_forbidden_inward(name) for name in imported), imported


def Given_an_ordinary_standard_library_import_When_checked_Then_it_is_allowed_inward() -> None:
    # The domain imports these every day; refusing them would make the rule uninstallable.
    for module in ("decimal", "datetime", "dataclasses", "enum", "collections.abc"):
        assert not _is_forbidden_inward(module), module

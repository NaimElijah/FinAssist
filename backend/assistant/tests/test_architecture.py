"""Enforces the structural rules declared in docs/architecture/architecture.md.

Thirteen rules:

1. Dependencies point inward - no inward layer imports a framework or the standard library's
   I/O.
2. No inward layer opens a file through the builtin ``open``.
3. No inward layer imports the adapter layer, where the anti-corruption layers live.
4. No context imports another context. Only ``shared`` is common ground.
5. No context imports the composition root, where the frameworks are wired.
6. No domain module names ``float``. Money is exact decimal, in both languages.
7. No module outside the composition root reads the wall clock - not the domain, not a use
   case, not ``shared``, not a context's adapters. The composition root reads it once and passes
   the time in.
8. No inbound adapter imports an outbound one. A route reaches a client only through a use case.
9. No domain module imports the application layer. The use cases call the domain, never the
   reverse - the inward half of ``LedgerArchitectureTest.layers_point_inward``.
10. ``shared`` imports no context. It is the common ground every context may use, so a
   dependency from it onto one context would reach every other through it.
11. No outbound adapter imports an inbound one. The wire shape stops at the route.
12. A translation - a class in the composition root that implements a context's port
   ``Protocol`` - calls the upstream context's use case, never its out-ports or adapters.
13. No module imports by a call - ``importlib.import_module`` or ``__import__`` - so every import
   is a statement the rules above can read.

Rules 6, 7 and 11 mirror rules the ledger enforces too:
``LedgerArchitectureTest.money_is_never_floating_point`` for the first,
``LedgerArchitectureTest.clock_is_read_only_in_the_composition_root`` for the second, and
``LedgerArchitectureTest.outbound_adapters_do_not_reach_inbound`` for the third.

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
from collections.abc import Mapping
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

# Standard-library modules no inward layer may import, because each is I/O however it arrives:
#
# - the network: `socket`, `ssl`, `select`, `selectors`, and `http` - a transport type besides;
# - network protocols: `urllib`, `smtplib`, `ftplib`, `imaplib`, `poplib`, `xmlrpc`;
# - the filesystem: `os`, `pathlib`, `io`, `shutil`, `tempfile`;
# - storage: `sqlite3`, `dbm`, `shelve`;
# - processes and event loops: `subprocess`, `multiprocessing`, `asyncio`.
#
# The derivation above reads only pyproject.toml, so the standard library is invisible to it - an
# inward module could open a socket and the framework rule passed. Nothing inward imports any of
# these today; the composition root and the inbound adapters use `http.HTTPStatus`, where it
# belongs. The builtin `open` needs no import at all, so the same rule matches its call instead.
FORBIDDEN_STANDARD_IO = frozenset(
    {
        "socket",
        "ssl",
        "select",
        "selectors",
        "http",
        "urllib",
        "smtplib",
        "ftplib",
        "imaplib",
        "poplib",
        "xmlrpc",
        "os",
        "pathlib",
        "io",
        "shutil",
        "tempfile",
        "sqlite3",
        "dbm",
        "shelve",
        "subprocess",
        "multiprocessing",
        "asyncio",
    }
)

OPEN_BUILTIN = "open"


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
#
# `datetime` is deliberately absent. Its readers are methods - `datetime.now`, `date.today` -
# reached through a class the attribute match already sees, and the one member it exports whose
# name CLOCK_READS holds is `time`, the time-of-day class: a type, not a reader.
CLOCK_MODULES = frozenset({"time"})

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


def _resolved_from(node: ast.ImportFrom, package: str) -> str:
    """The absolute module a ``from ... import`` reads, its relative dots resolved."""
    if node.level == 0:
        return node.module or ""
    # `from . import x` resolves against the file's own package, `from .. import x` against its
    # parent, and so on.
    parts = package.split(".")
    trimmed = parts[: len(parts) - (node.level - 1)]
    return ".".join([*trimmed, node.module] if node.module else trimmed)


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
            base = _resolved_from(node, package)
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

    Two indirections are matched as well: importing a reader (or everything) out of ``time``,
    which lets it be called under any alias, and ``getattr`` naming a reader as a string, which
    no attribute node shows.
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


# The calls that import a module named at run time. The import rules read statements, and a
# call's argument can be any string, so the call itself is refused rather than its argument read.
DYNAMIC_IMPORTERS = frozenset({"import_module", "__import__"})
IMPORTLIB = "importlib"


def _dynamic_imports_in(source: str, filename: str = "<test>") -> list[str]:
    """Every call to an importer, by name or as an attribute, and every import binding one.

    The import is matched as well as the call, for the reason the clock rule matches one:
    ``from importlib import import_module as load`` leaves a call named ``load`` that no name
    match could follow.
    """
    found: list[str] = []
    for node in ast.walk(ast.parse(source, filename=filename)):
        if isinstance(node, ast.Call) and _dotted(node.func).split(".")[-1] in DYNAMIC_IMPORTERS:
            found.append(f"{_dotted(node.func)}() at line {node.lineno}")
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module == IMPORTLIB:
            found.extend(
                f"import of {IMPORTLIB}.{alias.name} at line {node.lineno}"
                for alias in node.names
                if alias.name in DYNAMIC_IMPORTERS or alias.name == "*"
            )
    return found


def _file_opens_in(source: str, filename: str = "<test>") -> list[str]:
    """Every call to the builtin ``open``.

    Matched as a call on the bare name only, the way a bare clock read is: a method that happens
    to be called ``open`` - a position opened, a gate opened - is an attribute, and no I/O.
    """
    tree = ast.parse(source, filename=filename)
    return [
        f"{OPEN_BUILTIN}() at line {node.lineno}"
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == OPEN_BUILTIN
    ]


def _context_of(path: Path) -> str | None:
    """The bounded context a file belongs to, or None for `shared` and the root package."""
    parts = path.relative_to(SRC).parts
    return parts[0] if parts and parts[0] in CONTEXTS else None


def _is_inward(path: Path) -> bool:
    """True for anything outside the adapter layer: domain, application, ports, use cases.

    ``adapters`` is the adapter layer only where the layout puts it: directly under a context, or
    as the composition root. Matched at any depth, a directory of that name inside a domain or a
    use case would have lifted every inward rule off the modules beneath it.
    """
    parts = path.relative_to(SRC).parts
    context_adapter = parts[0] in CONTEXTS and parts[1:2] == (ADAPTER_SEGMENT,)
    return not (_is_composition_root(path) or context_adapter)


def _is_composition_root(path: Path) -> bool:
    """A module under ``src/finassist_assistant/adapters/``, the one package that wires the rest."""
    parts = path.relative_to(SRC).parts
    return bool(parts) and parts[0] == ADAPTER_SEGMENT


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


def _names_inbound_adapter(module: str) -> bool:
    """A module name under any context's ``adapters/inbound``: what calls a context."""
    parts = module.split(".")
    return (
        parts[0] == ROOT_PACKAGE
        and len(parts) > 1
        and parts[1] in CONTEXTS
        and parts[2:4] == [ADAPTER_SEGMENT, INBOUND_SEGMENT]
    )


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


def _module_name(path: Path) -> str:
    """The dotted name a file is imported by: its package, plus its stem unless it is one."""
    package = _module_package(path)
    return package if path.name == "__init__.py" else f"{package}.{path.stem}"


PORT_SEGMENT = "port"
PROTOCOL = "Protocol"

# A port Protocol, as the package a translation imports it from - a context's `application.port` -
# and its simple name, mapped to the names of the methods it declares.
type PortName = tuple[str, str]
type PortProtocols = Mapping[PortName, frozenset[str]]


def _dotted(node: ast.AST) -> str:
    """``a.b.c`` for a chain of attributes on a name; empty for anything else."""
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute) and (base := _dotted(node.value)):
        return f"{base}.{node.attr}"
    return ""


def _resolve(node: ast.AST, bound: Mapping[str, str]) -> str:
    """The qualified name a reference resolves to through the module's bindings; empty if none."""
    head, _, rest = _dotted(node).partition(".")
    return ".".join(filter(None, [bound[head], rest])) if head in bound else ""


def _bindings(tree: ast.Module, package: str) -> dict[str, str]:
    """Each name an import binds anywhere in the module, mapped to what it is bound to.

    A top-level alias of a bound name - ``_Feed = InMemoryQuoteFeed`` - is followed too, in source
    order, so that renaming an adapter once at the top of a module does not hide it from every rule
    reading these bindings.
    """
    bound: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            base = _resolved_from(node, package)
            bound.update(
                {alias.asname or alias.name: f"{base}.{alias.name}" for alias in node.names}
            )
        elif isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".")[0]
                bound[alias.asname or root] = alias.name if alias.asname else root
    for statement in tree.body:
        for target, value in _aliases(statement):
            if resolved := _resolve(value, bound):
                bound[target] = resolved
    return bound


def _aliases(statement: ast.stmt) -> list[tuple[str, ast.expr]]:
    """Each plain name a top-level statement binds to an expression: ``x = y``, ``x: T = y``."""
    if isinstance(statement, ast.Assign):
        return [(t.id, statement.value) for t in statement.targets if isinstance(t, ast.Name)]
    if isinstance(statement, ast.AnnAssign) and isinstance(statement.target, ast.Name):
        return [(statement.target.id, statement.value)] if statement.value else []
    if isinstance(statement, ast.TypeAlias):
        return [(statement.name.id, statement.value)]
    return []


def _port_package(module: str) -> str | None:
    """The context ``application.port`` package a qualified name sits in or under, if any."""
    parts = module.split(".")
    in_port = (
        len(parts) >= 4
        and parts[0] == ROOT_PACKAGE
        and parts[1] in CONTEXTS
        and parts[2:4] == [APPLICATION_SEGMENT, PORT_SEGMENT]
    )
    return ".".join(parts[:4]) if in_port else None


def _unsubscripted(node: ast.expr) -> ast.expr:
    """The generic a subscript parameterises - ``Protocol`` for ``Protocol[T]`` - or the node."""
    return node.value if isinstance(node, ast.Subscript) else node


def _is_protocol_base(base: ast.expr) -> bool:
    """``Protocol``, ``typing.Protocol``, or either subscripted - ``Protocol[T]``."""
    return _dotted(_unsubscripted(base)).split(".")[-1] == PROTOCOL


def _methods_of(node: ast.ClassDef) -> frozenset[str]:
    return frozenset(
        member.name
        for member in node.body
        if isinstance(member, ast.FunctionDef | ast.AsyncFunctionDef)
    )


def _protocols_in(source: str, module: str) -> dict[PortName, frozenset[str]]:
    """Each ``Protocol`` a module under a context's ``application/port`` declares, with its methods.

    Keyed by the port package rather than the declaring module. A translation names a port by the
    module it imports it from, which is the package that re-exports it; keyed by the submodule
    that declares it, splitting ``port/__init__.py`` into ``port/feed.py`` would leave every name
    the translation uses matching nothing, and the rule green.
    """
    package = _port_package(module)
    if package is None:
        return {}
    return {
        (package, node.name): _methods_of(node)
        for node in ast.walk(ast.parse(source))
        if isinstance(node, ast.ClassDef) and any(_is_protocol_base(base) for base in node.bases)
    }


def _out_port_protocols(paths: list[Path]) -> PortProtocols:
    """Every ``Protocol`` declared under a context's ``application/port``, with its methods."""
    found: dict[PortName, frozenset[str]] = {}
    for path in paths:
        found.update(_protocols_in(path.read_text(encoding="utf-8"), _module_name(path)))
    return found


def _protocol_named(name: str, protocols: PortProtocols) -> PortName | None:
    """The port Protocol a qualified name is, or is a member of, whichever port module it passes."""
    package = _port_package(name)
    if package is None:
        return None
    return next(
        ((package, part) for part in name.split(".")[4:] if (package, part) in protocols), None
    )


def _translations_in(
    tree: ast.Module, bound: Mapping[str, str], protocols: PortProtocols
) -> list[tuple[ast.ClassDef, frozenset[PortName]]]:
    """The module's translations, each with the port Protocols it implements.

    A translation is picked out by what it is, as the ledger's rule picks one out: a class that
    implements a context's port - by shape, defining every method the Protocol declares, as
    ``MarketDataQuotes`` defines ``QuotePort.unit_price``, or by naming the Protocol as a base.
    ``Assembly`` and ``Portfolio`` implement none, so a record of what was wired may hold adapters
    and out-ports, and grow a method, without becoming one. A Protocol declaring no method is
    implemented by every class and so names none.
    """
    found = []
    for node in tree.body:
        if not isinstance(node, ast.ClassDef):
            continue
        methods = _methods_of(node)
        by_shape = {port for port, needed in protocols.items() if needed and needed <= methods}
        by_base = {
            port
            for base in node.bases
            if (port := _protocol_named(_resolve(_unsubscripted(base), bound), protocols))
        }
        if implemented := frozenset(by_shape | by_base):
            found.append((node, implemented))
    return found


def _reaches_past_use_case(
    name: str, protocols: PortProtocols, implemented: frozenset[PortName]
) -> bool:
    """Whether a qualified name is a context's adapter, or an out-port the class does not implement.

    The implemented port is exempt for the reason the ledger exempts it (``implemented.contains``):
    it is the translation's own downstream port, and naming it is how the class says what it is.
    """
    parts = name.split(".")
    in_adapters = len(parts) > 2 and parts[1] in CONTEXTS and parts[2] == ADAPTER_SEGMENT
    port = _protocol_named(name, protocols)
    return in_adapters or (port is not None and port not in implemented)


def _translation_overreach_in(source: str, package: str, protocols: PortProtocols) -> list[str]:
    """Every name a translation's bases or body resolves to a context's out-port or adapter.

    An out-port's exceptions stay allowed, because converting them at the boundary is the job.
    """
    tree = ast.parse(source)
    bound = _bindings(tree, package)
    found: set[str] = set()
    for translation, implemented in _translations_in(tree, bound, protocols):
        parts = [*translation.bases, *translation.body]
        for node in (node for part in parts for node in ast.walk(part)):
            name = _resolve(node, bound)
            if name and _reaches_past_use_case(name, protocols, implemented):
                found.add(f"{translation.name} names {name} at line {getattr(node, 'lineno', 0)}")
    return sorted(found)


def _translation_names_in(path: Path, protocols: PortProtocols) -> list[str]:
    """The names of the translations a composition-root module declares."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    bound = _bindings(tree, _module_package(path))
    return [node.name for node, _ in _translations_in(tree, bound, protocols)]


# Each rule runs over the files it holds, filtered here once, rather than over every file with
# the rest skipped. A skip is not a check, and a filter rendered as hundreds of skips hid the one
# failure that matters: a layer renamed out of its filter's reach, so that every case of its rule
# skipped and the suite stayed green. An empty list now fails its guard below instead.
#
# Three rules also refuse an import by the name it carries: the inbound-adapter rule one under
# ``adapters/outbound``, the outbound-adapter rule one under ``adapters/inbound``, the domain rule
# one under ``application``. Renaming any of those directories would leave its name match
# recognising nothing and its rule green with nothing left to refuse, so the files each match
# recognises are a scope of their own, and the guard fails on the rename instead. The inbound
# match's scope is the inbound-adapter one, built from the same two segments.
INWARD_FILES = [path for path in SOURCE_FILES if _is_inward(path)]
OUTSIDE_COMPOSITION_ROOT_FILES = [path for path in SOURCE_FILES if not _is_composition_root(path)]
CONTEXT_FILES = [path for path in SOURCE_FILES if _context_of(path) is not None]
COMPOSITION_ROOT_HELD_FILES = [
    path for path in SOURCE_FILES if _context_of(path) is not None or _is_domain(path)
]
INBOUND_ADAPTER_FILES = [path for path in SOURCE_FILES if _is_inbound_adapter(path)]
OUTBOUND_ADAPTER_FILES = [
    path for path in SOURCE_FILES if _names_outbound_adapter(_module_name(path))
]
APPLICATION_FILES = [path for path in SOURCE_FILES if _names_application_layer(_module_name(path))]
DOMAIN_FILES = [path for path in SOURCE_FILES if _is_domain(path)]
SHARED_FILES = [path for path in SOURCE_FILES if path.relative_to(SRC).parts[0] == SHARED_PACKAGE]
OUT_PORT_PROTOCOLS = _out_port_protocols(APPLICATION_FILES)
COMPOSITION_ROOT_FILES = [path for path in SOURCE_FILES if _is_composition_root(path)]

RULE_SCOPES = {
    "inward": INWARD_FILES,
    "outside-composition-root": OUTSIDE_COMPOSITION_ROOT_FILES,
    "context": CONTEXT_FILES,
    "composition-root-held": COMPOSITION_ROOT_HELD_FILES,
    "inbound-adapter": INBOUND_ADAPTER_FILES,
    "outbound-adapter": OUTBOUND_ADAPTER_FILES,
    "application": APPLICATION_FILES,
    "domain": DOMAIN_FILES,
    "shared": SHARED_FILES,
    "composition-root": COMPOSITION_ROOT_FILES,
}


def _relative_id(path: Path) -> str:
    return str(path.relative_to(SRC))


def Given_the_source_tree_When_collected_Then_it_is_not_empty() -> None:
    """Guards every rule below from passing because it found nothing to check."""
    assert SOURCE_FILES, f"no Python sources found under {SRC}"


@pytest.mark.parametrize("scope", RULE_SCOPES)
def Given_the_files_a_rule_holds_When_filtered_Then_there_is_at_least_one(scope: str) -> None:
    """Guards each rule below from passing because its filter matched nothing.

    A rule parametrised over an empty list collects a single case that pytest skips ("got
    empty parameter set"), and a skip never fails the run - so renaming ``adapters/inbound`` or
    ``domain`` would otherwise leave that rule checking nothing while the suite stayed green, the
    behaviour the ledger deliberately removed when it deleted
    ``archRule.failOnEmptyShould=false``.
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
def Given_an_inward_module_When_parsed_Then_it_opens_no_file(path: Path) -> None:
    """The builtin ``open`` is file I/O that needs no import, so the rule above cannot see it."""
    found = _file_opens_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} opens a file: {found}. Reading or writing a file is I/O, and "
        "I/O lives in adapters behind a port the inward layer declares."
    )


@pytest.mark.parametrize("path", SOURCE_FILES, ids=_relative_id)
def Given_a_module_When_parsed_Then_it_imports_nothing_by_a_call(path: Path) -> None:
    """Every rule here reads import statements, so an import made by a call escapes all of them.

    ``importlib.import_module("fastapi")`` in a domain module, or a context loading another by
    name, would pass every rule above. The web client refuses a computed import specifier for the
    same reason.
    """
    found = _dynamic_imports_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} imports by a call: {found}. Write the import as a statement, "
        "so the architecture rules can see what it depends on."
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


@pytest.mark.parametrize("path", SHARED_FILES, ids=_relative_id)
def Given_a_shared_module_When_parsed_Then_it_imports_no_context(path: Path) -> None:
    """``shared`` belongs to no context, so the cross-context rule above does not hold it.

    Without this, ``shared`` importing ``portfolio`` passed every rule, and every other
    context importing ``shared`` would then depend on ``portfolio`` through it.
    """
    leaked = sorted(
        module for module in _imported_modules(path) if _context_imported_by(module) is not None
    )
    assert not leaked, (
        f"{path.relative_to(SRC)} imports a bounded context: {leaked}. Every context depends "
        "on shared, so shared may depend on none of them."
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


@pytest.mark.parametrize("path", OUTBOUND_ADAPTER_FILES, ids=_relative_id)
def Given_an_outbound_adapter_When_parsed_Then_it_imports_no_inbound_adapter(path: Path) -> None:
    """What a context calls knows nothing of how the context was called.

    The wire shape must not reach the anti-corruption layer: a ledger client or a feed adapter
    that named a route's request or response model would tie its translation to the published
    contract, so a change to ``contracts/assistant-v1.openapi.json`` would reach the foreign API's
    mapping too. The rule above holds the opposite direction only, and both adapters share a
    context and an ``adapters`` path, which every other rule here allows. The ledger forbids the
    same in ``outbound_adapters_do_not_reach_inbound``.
    """
    leaked = sorted(module for module in _imported_modules(path) if _names_inbound_adapter(module))
    assert not leaked, (
        f"{path.relative_to(SRC)} imports an inbound adapter: {leaked}. An outbound adapter "
        "translates a foreign shape into the context's model, never into the wire shape a route "
        "publishes."
    )


def Given_the_composition_root_When_parsed_Then_it_holds_a_translation_and_ports_to_refuse() -> (
    None
):
    """Guards the translation rule below from passing because it recognised nothing.

    It holds only the classes that implement a port Protocol found under ``application/port``, in
    every composition-root module; renaming that layer, or a translation drifting out of the shape
    of its port, would otherwise leave it green with nothing to check.
    """
    translations = [
        name
        for path in COMPOSITION_ROOT_FILES
        for name in _translation_names_in(path, OUT_PORT_PROTOCOLS)
    ]

    assert OUT_PORT_PROTOCOLS, f"no Protocol found under any context's application/{PORT_SEGMENT}"
    assert translations, (
        f"no class implementing a port Protocol found under {SRC / ADAPTER_SEGMENT}"
    )


@pytest.mark.parametrize("path", COMPOSITION_ROOT_FILES, ids=_relative_id)
def Given_a_translation_When_parsed_Then_it_names_no_out_port_or_adapter(path: Path) -> None:
    """A translation calls the upstream context's use case, never its repositories.

    ``MarketDataQuotes`` answers portfolio's ``QuotePort`` through ``LatestQuote``, which is where
    market data refuses a stale quote. Reaching ``QuoteFeedPort`` or the feed adapter instead would
    value a holding at a price of any age, and no rule inside either context could see it: the
    translation lives in the composition root, which may import both. A module's assembly function
    and the records it returns still name the adapters and ports they wire; only the classes that
    implement a port are held, and each may name the port it implements.

    Static, so a name it cannot resolve is missed: a string annotation; a path through an allowed
    object such as ``self.quotes.feed``; an alias made inside a function or by unpacking, rather
    than by a top-level ``Name = name``; a translation written as a function, or as a class that
    shapes itself to no port. Those are held in review.
    """
    found = _translation_overreach_in(
        path.read_text(encoding="utf-8"), _module_package(path), OUT_PORT_PROTOCOLS
    )
    assert not found, (
        f"{path.relative_to(SRC)} reaches past an upstream use case: {found}. A translation "
        "calls the upstream context's in-port and converts its exceptions; its out-ports and "
        "adapters are wired by the assembly, never called by the translation."
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


@pytest.mark.parametrize("path", OUTSIDE_COMPOSITION_ROOT_FILES, ids=_relative_id)
def Given_a_module_outside_the_composition_root_When_parsed_Then_it_reads_no_clock(
    path: Path,
) -> None:
    """Pass the clock in: everything but the composition root takes time as an argument.

    The composition root reads the clock once (``utc_now``) and passes it down, so the only
    clock reads in the tree belong under ``src/finassist_assistant/adapters/``. A use case reading
    one would make its behaviour depend on when the test ran, exactly as a domain function would.

    A context's own adapters are held too. They sit under an ``adapters`` directory, which once
    put them outside this rule, yet a ledger client stamping its own fetch time is the same hidden
    clock one layer out. The ledger holds every package but ``composition`` to the same rule in
    ``clock_is_read_only_in_the_composition_root``.
    """
    found = _clock_reads_in(path.read_text(encoding="utf-8"), str(path))
    assert not found, (
        f"{path.relative_to(SRC)} reads the wall clock: {found}. Only the composition root reads "
        "it; everything else takes the current time as an argument - is_stale and age already do "
        "- so its behaviour is a function of its inputs and a test never has to freeze anything."
    )


# ---------------------------------------------------------------------------------------------
# The rules above are only as good as the helpers underneath them. Nothing tested those, so a
# refactor of the import scanner could have neutered every one while every case stayed green -
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
    assert not _is_inward(SRC / "adapters" / "wiring.py")


def Given_an_adapters_directory_below_a_layer_When_asked_Then_it_is_still_inward() -> None:
    # Only a context's own `adapters` and the composition root are outward; the name deeper down
    # is just a directory, and the rules on the layer above it still hold.
    assert _is_inward(SRC / "portfolio" / "domain" / "adapters" / "model.py")
    assert _is_inward(SRC / "marketdata" / "application" / "usecase" / "adapters" / "feed.py")
    assert _is_inward(SRC / "shared" / "adapters" / "money.py")


def Given_a_module_path_When_asked_Then_only_the_root_adapters_are_the_composition_root() -> None:
    assert _is_composition_root(SRC / "adapters" / "wiring.py")
    assert not _is_composition_root(
        SRC / "portfolio" / "adapters" / "outbound" / "ledger" / "__init__.py"
    )
    assert not _is_composition_root(SRC / "shared" / "money.py")
    assert not _is_composition_root(SRC / "__init__.py")


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
        "from time import monotonic as tick",
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
    # `datetime.time` is the time-of-day class, whose name only happens to be a reader's.
    assert _clock_reads_in("from datetime import time") == []


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


def Given_a_module_name_When_asked_Then_only_a_contexts_inbound_adapters_are_inbound() -> None:
    assert _names_inbound_adapter(f"{ROOT_PACKAGE}.portfolio.adapters.inbound.api.models")
    assert _names_inbound_adapter(f"{ROOT_PACKAGE}.assistance.adapters.inbound")
    assert not _names_inbound_adapter(f"{ROOT_PACKAGE}.portfolio.adapters.outbound.ledger")
    assert not _names_inbound_adapter(f"{ROOT_PACKAGE}.adapters.app")
    assert not _names_inbound_adapter("inbound.adapters")


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


# Spelled out rather than read from FORBIDDEN_STANDARD_IO, so that deleting an entry from the set
# fails a case here instead of quietly removing it. Two submodules stand in for every dotted form.
@pytest.mark.parametrize(
    "module",
    [
        "socket",
        "ssl",
        "select",
        "selectors",
        "http",
        "urllib",
        "smtplib",
        "ftplib",
        "imaplib",
        "poplib",
        "xmlrpc",
        "xmlrpc.client",
        "os",
        "os.path",
        "pathlib",
        "io",
        "shutil",
        "tempfile",
        "sqlite3",
        "dbm",
        "shelve",
        "subprocess",
        "multiprocessing",
        "asyncio",
    ],
)
def Given_a_standard_library_io_import_When_checked_Then_it_is_forbidden_inward(
    module: str,
) -> None:
    imported = _imports_in(f"import {module}", PACKAGE) | _imports_in(
        f"from {module} import member", PACKAGE
    )

    assert imported and all(_is_forbidden_inward(name) for name in imported), imported


@pytest.mark.parametrize(
    "source",
    [
        'open("holdings.csv")',
        'with open(path, "w") as handle:\n    handle.write(text)',
        "def load(path):\n    return open(path).read()",
    ],
)
def Given_source_calling_the_builtin_open_When_scanned_Then_it_is_found(source: str) -> None:
    assert _file_opens_in(source), source


def Given_a_method_named_open_When_scanned_Then_it_is_not_a_violation() -> None:
    # Only the builtin reaches the filesystem; a domain method of the same name does not.
    assert _file_opens_in("position.open(on=trade_date)") == []
    assert _file_opens_in("def open(self, on):\n    return on") == []


def Given_an_ordinary_standard_library_import_When_checked_Then_it_is_allowed_inward() -> None:
    # The domain imports these every day; refusing them would make the rule uninstallable.
    for module in ("decimal", "datetime", "dataclasses", "enum", "collections.abc"):
        assert not _is_forbidden_inward(module), module


MARKETDATA_PORT = f"{ROOT_PACKAGE}.marketdata.application.port"
PORTFOLIO_PORT = f"{ROOT_PACKAGE}.portfolio.application.port"
TRANSLATION_PACKAGE = f"{ROOT_PACKAGE}.adapters"

# The ports a translation in these cases may implement or reach past, as discovery reports them.
QUOTE_PORT: PortProtocols = {(PORTFOLIO_PORT, "QuotePort"): frozenset({"unit_price"})}
PORTS: PortProtocols = {
    **QUOTE_PORT,
    (MARKETDATA_PORT, "QuoteFeedPort"): frozenset({"latest"}),
    (PORTFOLIO_PORT, "LedgerPort"): frozenset({"lots_for"}),
}

# A translation by shape: it defines QuotePort's one method.
TRANSLATION_HEAD = "class T:\n    def unit_price(self, symbol):\n"
FEED_ADAPTER_IMPORT = (
    "from finassist_assistant.marketdata.adapters.outbound.feed import InMemoryQuoteFeed\n"
)


@pytest.mark.parametrize(
    "source",
    [
        "from finassist_assistant.marketdata.application.port import QuoteFeedPort\n"
        "class T:\n    feed: QuoteFeedPort\n    def unit_price(self, symbol): ...",
        "import finassist_assistant.marketdata.application.port as ports\n"
        "class T:\n    def unit_price(self, symbol, feed: ports.QuoteFeedPort): ...",
        TRANSLATION_HEAD
        + "        from ..marketdata.adapters.outbound.feed import InMemoryQuoteFeed\n"
        "        return InMemoryQuoteFeed()",
        # A top-level alias hides the adapter's name from the method that uses it.
        FEED_ADAPTER_IMPORT
        + "_Feed = InMemoryQuoteFeed\n"
        + TRANSLATION_HEAD
        + "        return _Feed()",
        FEED_ADAPTER_IMPORT
        + "_Feed: type = InMemoryQuoteFeed\n_Again = _Feed\n"
        + TRANSLATION_HEAD
        + "        return _Again()",
        FEED_ADAPTER_IMPORT
        + "type _Feed = InMemoryQuoteFeed\n"
        + TRANSLATION_HEAD
        + "        return _Feed()",
        # A base is a dependency too: extending the feed adapter reaches it.
        FEED_ADAPTER_IMPORT + "class T(InMemoryQuoteFeed):\n    def unit_price(self, symbol): ...",
        # Implementing QuotePort exempts QuotePort only, not the consumer's other out-ports.
        "from finassist_assistant.portfolio.application.port import LedgerPort\n"
        "class T:\n    ledger: LedgerPort\n    def unit_price(self, symbol): ...",
        # Implemented by naming the Protocol as a base, rather than by shape.
        "from finassist_assistant.portfolio.application.port import QuotePort\n"
        "from finassist_assistant.marketdata.application.port import QuoteFeedPort\n"
        "class T(QuotePort):\n    feed: QuoteFeedPort",
    ],
)
def Given_a_translation_naming_an_out_port_or_adapter_When_scanned_Then_it_is_found(
    source: str,
) -> None:
    found = _translation_overreach_in(source, TRANSLATION_PACKAGE, PORTS)

    assert found, source


def Given_a_translation_naming_a_use_case_and_a_port_exception_When_scanned_Then_it_passes() -> (
    None
):
    # Converting the upstream's exceptions is the translation's job, and they live beside the port.
    source = (
        "from finassist_assistant.marketdata.application.port import QuoteUnavailableError\n"
        "from finassist_assistant.marketdata.application.usecase import LatestQuote\n"
        "class T:\n    quotes: LatestQuote\n    def unit_price(self, symbol):\n"
        "        try:\n            return self.quotes\n"
        "        except QuoteUnavailableError:\n            raise"
    )

    assert _translation_overreach_in(source, TRANSLATION_PACKAGE, PORTS) == []


def Given_a_translation_naming_the_port_it_implements_When_scanned_Then_it_passes() -> None:
    # Its own downstream port, as the ledger exempts it with `implemented.contains(target)`.
    source = (
        "from finassist_assistant.portfolio.application.port import QuotePort\n"
        "class ByShape:\n    def unit_price(self, symbol):\n"
        "        port: QuotePort = self\n        return port\n"
        "class ByBase(QuotePort):\n    def unit_price(self, symbol): ..."
    )

    assert _translation_overreach_in(source, TRANSLATION_PACKAGE, PORTS) == []


def Given_a_record_with_a_method_naming_out_ports_and_adapters_When_scanned_Then_it_passes() -> (
    None
):
    # `Assembly` is a record of what was wired. A method on it implements no port, so it is not a
    # translation however many adapters and out-ports it holds.
    source = (
        "from finassist_assistant.marketdata.application.port import QuoteFeedPort\n"
        "from finassist_assistant.portfolio.adapters.outbound.ledger import InMemoryLedger\n"
        "class Assembly:\n    ledger: InMemoryLedger\n    feed: QuoteFeedPort\n"
        "    def use_cases(self):\n        return (self.ledger, self.feed)"
    )

    assert _translation_overreach_in(source, TRANSLATION_PACKAGE, PORTS) == []


def Given_composition_root_classes_When_asked_Then_only_port_implementers_are_translations() -> (
    None
):
    tree = ast.parse(
        "from finassist_assistant.marketdata.application.port import QuoteFeedPort\n"
        "class ByShape:\n    def unit_price(self, symbol): ...\n"
        "class ByBase(QuoteFeedPort): ...\n"
        "class Record:\n    def use_cases(self): ...\n"
        "class Partial:\n    def lots(self): ...\n"
    )
    bound = _bindings(tree, TRANSLATION_PACKAGE)
    # A Protocol with no method would be implemented by every class; it must pick out none.
    ports: PortProtocols = {**PORTS, (MARKETDATA_PORT, "Marker"): frozenset()}

    held = {node.name: implemented for node, implemented in _translations_in(tree, bound, ports)}

    assert held == {
        "ByShape": frozenset({(PORTFOLIO_PORT, "QuotePort")}),
        "ByBase": frozenset({(MARKETDATA_PORT, "QuoteFeedPort")}),
    }, held


def Given_a_protocol_declared_in_a_port_submodule_When_discovered_Then_the_package_keys_it() -> (
    None
):
    # Split out of `port/__init__.py` and re-exported from it; a translation imports it from there.
    declared = (
        "from typing import Protocol\n"
        "class QuoteFeedPort(Protocol):\n    def latest(self, ticker): ...\n"
        "class QuoteUnavailableError(LookupError): ...\n"
    )

    assert _protocols_in(declared, f"{MARKETDATA_PORT}.feed") == {
        (MARKETDATA_PORT, "QuoteFeedPort"): frozenset({"latest"})
    }


def Given_a_reexported_protocol_When_a_translation_names_it_Then_it_is_found() -> None:
    declared = "import typing\nclass QuoteFeedPort(typing.Protocol):\n    def latest(self, t): ..."
    ports = {**QUOTE_PORT, **_protocols_in(declared, f"{MARKETDATA_PORT}.feed")}
    source = (
        "from finassist_assistant.marketdata.application.port import QuoteFeedPort\n"
        "class T:\n    feed: QuoteFeedPort\n    def unit_price(self, symbol): ..."
    )

    assert _translation_overreach_in(source, TRANSLATION_PACKAGE, ports), ports


def Given_a_generic_protocol_When_discovered_Then_it_is_recognised() -> None:
    declared = (
        "from typing import Protocol\n"
        "class Feed[T](Protocol):\n    def pull(self, key: T): ...\n"
        "class OldStyle(Protocol[T]):\n    def pull(self, key: T): ...\n"
    )

    assert set(_protocols_in(declared, MARKETDATA_PORT)) == {
        (MARKETDATA_PORT, "Feed"),
        (MARKETDATA_PORT, "OldStyle"),
    }


def Given_a_protocol_outside_a_port_package_When_discovered_Then_it_is_not_a_port() -> None:
    declared = "from typing import Protocol\nclass Clock(Protocol):\n    def now(self): ..."

    assert _protocols_in(declared, f"{ROOT_PACKAGE}.marketdata.application.usecase") == {}
    assert _protocols_in(declared, f"{ROOT_PACKAGE}.adapters.wiring") == {}


@pytest.mark.parametrize(
    "source",
    [
        'import importlib\nm = importlib.import_module("fastapi")',
        "import importlib as il\nm = il.import_module(name)",
        "from importlib import import_module as load\nm = load(name)",
        "from importlib import *",
        'm = __import__("fastapi")',
        'import builtins\nm = builtins.__import__("fastapi")',
    ],
)
def Given_source_importing_by_a_call_When_scanned_Then_it_is_found(source: str) -> None:
    assert _dynamic_imports_in(source), source


def Given_an_ordinary_importlib_use_When_scanned_Then_it_is_not_a_violation() -> None:
    # Reading a distribution's version imports nothing; refusing it would refuse too much.
    assert _dynamic_imports_in("from importlib.metadata import version") == []
    assert _dynamic_imports_in("from importlib import resources") == []

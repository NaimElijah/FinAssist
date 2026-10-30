# ADR-046: Architecture rules read the code, never load it

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-003](ADR-003-python-fastapi-for-assistant-and-analysis.md) and
[ADR-010](ADR-010-bounded-context-map.md), and replaces the mechanism each describes. What those
records decided, that dependency direction and context isolation are held by tests rather than by
convention, stands. How the assistant's tests hold it does not.

## Context

**Two Accepted records describe a mechanism that was removed the day they were written**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

- ADR-003 says that `test_architecture.py` "imports the domain in a subprocess and asserts that no
  framework module was loaded".
- ADR-010's table names `test_architecture.py::Given_a_context_When_imported_Then_it_loads_no_other_context`.
  That function no longer exists. It is
  `Given_a_module_When_parsed_Then_it_imports_no_other_context`.

**The removal was a fix for a rule that could not fail** (`ed813b1`, 2026-08-29).

- **What it did.** It imported a package in a subprocess and read `sys.modules`.
- **Why it was blind.** Python does not import subpackages on its own. Importing
  `finassist_assistant.portfolio` ran only that package's `__init__.py`. A file at
  `portfolio/domain/analysis/returns.py` holding both an `import pandas` and a cross-context
  import passed green.
- **Why nobody saw it.** The proof that the rules fail had only ever planted a violation in an
  `__init__.py`, the one file the probe loaded.
- **What replaced it.** Every source file is parsed with `ast`, and relative imports are resolved
  to absolute ones. The same deep-module violation then failed two named cases.

**The rule that replaced it is written down in four places, and none of them is a decision
record.** The docstring of `backend/assistant/tests/test_architecture.py`, the root
`.claude/CLAUDE.md` ("Architecture rules are static"), `docs/architecture/architecture.md` and
`docs/testing/testing.md` all say it. A reader who follows ADR-003 or ADR-010 for the reason is
told the opposite.

**Each codebase already has its own mechanism.**

| Codebase | Mechanism |
|---|---|
| Ledger | ArchUnit over compiled bytecode: `LedgerArchitectureTest`, eighteen rules, `ImportOption.DoNotIncludeTests` |
| Assistant | `ast.parse` over every `.py` under `src/finassist_assistant`: `tests/test_architecture.py`, thirteen rules |
| Web client | The TypeScript compiler's parser over `src`: `tests/architecture.test.ts` |

## Decision

**An architecture rule reads the code it governs. It never imports that code and inspects what
loaded.**

- **Java.** ArchUnit reads class files. It runs no application code.
- **Python.** The rule parses source text with `ast.walk`, so an import inside a function body is
  found as surely as one at the top of a file. `_imports_in` resolves relative imports against
  the file's own package.
- **TypeScript.** `architecture.test.ts` walks every `.ts`, `.tsx`, `.mts` and `.cts` file under
  `src/` and reads every module specifier with
  TypeScript's own parser: static and type-only imports, re-exports, `import()`, `require`, and
  `vi.mock`-style calls that name a module.
- **Never `sys.modules`.** Python's import system is the wrong instrument. It reports what some
  code happened to load, not what the files say.
- **An import made by a call is refused, so every import is a statement.** The assistant's
  `Given_a_module_When_parsed_Then_it_imports_nothing_by_a_call` fails on `importlib.import_module`,
  `__import__`, a loader's `load_module`, `runpy` and the builtins `exec` and `eval`, whether
  called, referenced, aliased or named to `getattr`. An aliased `importlib` is resolved first, so
  `import importlib as il` followed by `il.util.find_spec` is refused, and so is
  `from builtins import exec`. A method that merely shares a name, such as `model.eval()` or
  `session.exec(statement)`, passes. The web client refuses a computed specifier it cannot resolve
  for the same reason.

**A rule that matches nothing fails.**

- The ledger deleted `archRule.failOnEmptyShould=false` with its first domain class.
- The assistant's `Given_the_files_a_rule_holds_When_filtered_Then_there_is_at_least_one` fails
  when a rename leaves a rule's scope empty.
- The web client's `Given_the_features_directory_When_listed_Then_it_is_not_empty` does the same
  for its tree.

**A new rule is shown to fail before it is trusted.** Plant a violation in a deep module, never in
a package `__init__.py`. Confirm the build breaks, then revert. A rule that has never failed is
not evidence. ADR-010 already records this for its three rules, and the root `CLAUDE.md` states it
as a standing rule.

**The assistant's scanner is itself under test.** The same file holds cases for the import
resolver, so a refactor that neuters every rule fails a helper case.

**Static reading has a boundary, and it is stated.** It sees what the files say. What only a
running server builds, such as the filter chain, the request mappings or the generated OpenAPI
document, is held by integration tests: `LedgerAuthorizationIT`, `LedgerContractIT`,
`GatewayContractIT`.

## Consequences

**Makes easy**

- **A rule sees code that has not been written yet.** A forbidden import in a deep module fails
  the build the moment it appears.
- **A rule has no side effects.** It imports no module, so a module-level effect or a missing
  optional dependency cannot make it pass or fail.
- **The scanner can be tested directly.** Its helper cases pin what it finds and what it ignores.

**Makes hard**

- **Three scanners to maintain.** Each language needs its own, and nothing is shared between them.
- **A dynamic import is refused, not followed.** The Python scanner reads `import` and
  `from … import` statements, and the rule above fails the build on an import made by a call. What
  no syntax node names escapes it: an importer's name assembled at run time and handed to
  `getattr`, or read out of a namespace by subscript. Those are held in review, as the rule's
  docstring says.
- **The forbidden-framework list in Python is derived, and the derivation has holes.** It reads
  `pyproject.toml`, so a distribution whose import root differs from its name is missed until it
  is named in `tests/test_architecture.py`. `opentelemetry`, `jwt`, `cryptography` and
  `pydantic_core` are named there for that reason.
- **Parsers follow language syntax.** A new Python or TypeScript construct that names a module
  needs a new case.
- **The Python scanner's resolver is more than an accident guard, and that is a cost.** It
  follows bindings and top-level aliases, resolves an aliased `importlib` or `builtins`, and
  reads `getattr` strings and `__builtins__`, so a contributor who set out to evade the rule
  would have to work at it. Nothing in the tree imports dynamically, and a simpler option would
  catch every slip made by accident: refuse `importlib` (except `metadata` and `resources`),
  `runpy`, `pkgutil`, `zipimport`, `builtins` and the bare `__import__`, `exec` and `eval`, by
  name, in about twenty lines with no resolver. The resolver earns its upkeep only against
  deliberate evasion, which matters once someone other than the owner writes code here. It was
  not weighed against the simpler option when it was built, and this record does not claim it
  pays for itself today.

## Alternatives considered

- **Import the package and read `sys.modules`.** This was ADR-003's mechanism. Rejected: it was
  blind by construction, and it passed on a file holding both violations (`ed813b1`).
- **Import every module the tree contains, then read `sys.modules`.** A fix that keeps the
  mechanism. Rejected: it runs application code at test time, and it still misses an import
  inside a function body, which nothing loads until the function runs.
- **Hold the rules by review alone.** ADR-010 records why not: "nothing structural resists a
  cross-context import — the boundary exists only as long as everyone remembers it."

## Revisit when

- **A fourth language or codebase gains a bounded context.** It needs a scanner of its own, and a
  row in the table above.
- **A context needs a dynamic import** (the ML work, M8, is the likeliest). The rule refuses it,
  so confine it to one module, name that module as the rule's single exception, and give the
  reason. The scanner need not follow it.
- **Contributors beyond the owner arrive, or most code is written unattended.** Keep the
  resolver: it is what makes the rule hold against intent. If neither happens and the resolver
  costs more to maintain than it catches, replace it with the by-name list above.
- **A deliberate violation in a deep module passes.** The rule is blind, as the first one was.
  Fix the scanner, and amend the claim above if it no longer holds.

## What lands with this record

Documentation only. ADR-003 and ADR-010 name this record in their Status lines, and the README
carries a row and a note for it. The scan that found the gap also widened the rules in all three
codebases ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).
`docs/architecture/architecture.md` lists them, and each was shown to fail on a planted violation
before it was kept.

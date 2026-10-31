# ADR-065: javac's own lint, every category, is the Java services' lint gate

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-017](ADR-017-test-strategy.md), which chose the Java services' test, coverage and
mutation tools and named no lint tool.

## Context

**The owner's baseline asks CI for build, test, lint, format check and a dependency audit.** The
assistant has ruff and mypy, and the web client has ESLint and `tsc`. The Java services had a
format check alone. `make lint-ledger` and `make lint-gateway` run `spotless:check`, and neither
`pom.xml` enabled a compiler warning, so an unchecked cast or a deprecated call compiled silently
([#342](https://github.com/NaimElijah/FinAssist/issues/342), scan 5 of 12). CodeQL
([#67](https://github.com/NaimElijah/FinAssist/issues/67)) is security analysis. It overlaps with
lint only in part.

**With `-Xlint:all`, the two modules held 35 warnings,** measured on 2026-10-30 across main and
test code:

| Category | Ledger | Gateway | Where |
|---|---|---|---|
| `serial` | 10 | 0 | the ledger's ten exception classes |
| `deprecation` | 19 | 0 | `JsonNode.asText()` in `MoneyVectorsTest` |
| `unchecked` | 3 | 1 | one raw `Schema` in a test; generic varargs in `KeycloakRegistration` |
| `try` | 1 | 1 | `RequestRejectedHandlerTest`, whose observation scope is opened for its effect alone |

None of them needed a design change, and no category was noise.

## Decision

**Both `pom.xml` files compile with `-Xlint:all` and `-Werror`, with `showWarnings` on, for main
and test code alike. No category is excluded.** Every warning above is fixed, not suppressed:

- **Each exception declares `private static final long serialVersionUID = 1L`.** It does not take
  `@Serial`, because `@Serial` is `java.io.Serial`, and the ledger's domain, application layer and
  `shared` import nothing from `java.io`. The annotation has source retention, so ArchUnit would
  not see it. The ledger's `CLAUDE.md` says so.
- **`asText()` becomes `asString()`.** In jackson-databind 3.1.7, `asText()` is a final method that
  returns `asString()`.
- **The raw `Schema` becomes `Schema<?>`.** The gateway calls the `List` overload of
  `JwtValidators.createDefaultWithValidators`, to which the varargs one delegates.
- **The one exception is `@SuppressWarnings("try")`**, on each of the two test methods that open a
  scope for its side effect, each with its reason. The warning is right in general and wrong for
  these two methods.

**`make lint` is still Spotless alone.** javac's lint runs in every compile, so `make test`, CI's
build-and-test step and both image builds fail on a warning. The README's Development table says
so.

## Consequences

**Makes easy**

- **A warning cannot land.** It fails the build where it appears, in either module, in main or in
  test code.
- **No new tool.** javac ships with the JDK the build already pins: nothing to vet, update or
  configure.

**Makes hard**

- **A dependency bump that deprecates an API the code calls fails the build** until the call site
  moves. A Dependabot PR can then be red for a reason that is not a regression. The weekly triage
  (ADR-045) fixes the call site in the same PR.
- **A new JDK may add lint categories,** and `-Xlint:all` turns them on. Moving the Java line is
  already an ADR (ADR-045), which then also clears the new warnings.
- **javac's lint is narrow.** It finds no null-safety bug, no resource leak beyond `try`, and none
  of the patterns a static analyser such as Error Prone or SpotBugs looks for.

## Alternatives considered

- **Checkstyle.** Rejected: the format is Spotless's (palantir-java-format), and Checkstyle's
  remaining rules are mostly naming and layout. It would be a second configuration to keep beside
  Spotless.
- **Error Prone or SpotBugs.** Deferred, not rejected. Either finds bug patterns javac does not,
  and either is a new dependency that needs vetting (ADR-062) and a hook into the compiler or the
  build. **Revisit when** a bug that one of them reports by default reaches `dev`, or with CodeQL
  (#67), whose findings would show what is left.
- **Excluding `serial`.** Rejected: one line per exception clears it, and a category excluded
  today hides tomorrow's real warning.
- **Making `make lint` compile too.** Rejected: it would run a full compile twice in CI, and
  `make test` already fails on the warning.

## Revisit when

- **The Java line moves** (ADR-045). Clear the new JDK's warnings in the same change.
- **A bug that Error Prone or SpotBugs would have caught by default reaches `dev`.**
- **A category produces warnings that cannot be fixed without harm.** Exclude it in the `pom.xml`,
  with its reason, and record the exclusion here.

## What lands with this record

- **The compiler configuration.** Both `pom.xml` files gain the `maven-compiler-plugin`
  configuration.
- **The fixes.** One commit per module clears every warning, before the commit that turns on
  `-Werror`.
- **Proven by running it.** With the gate on and the code unfixed, both modules failed:
  `warnings found and -Werror specified`. After the fixes, both modules' full `./mvnw -B verify`
  passes with no warning.
- **Documentation.** The "Lint / format" lines in `backend/ledger/CLAUDE.md` and
  `backend/gateway/CLAUDE.md`, and the README's Development table. ADR-017 reads
  `Accepted, extended by … ADR-065`, as does its row in `docs/ADRs/README.md`, with a row and a
  note for this record.

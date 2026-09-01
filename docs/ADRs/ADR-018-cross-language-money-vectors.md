# ADR-018: The two Money kernels are pinned to a shared vector file

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

[ADR-010](ADR-010-bounded-context-map.md) forbids a shared kernel across languages. `Money` is
implemented once in Java and once in Python, and the record says the two are "kept honest by
equivalent tests on both sides rather than by a shared artefact". The architecture document
repeats it; `Currency`'s docstring repeats it again.

Nothing enforced it. `MoneyTest.java` and `test_money.py` were hand-maintained mirrors: no
generator, no comparison, and two CI jobs that never meet. Deleting or weakening either side
was invisible to the build.

It had already drifted, in two ways found while auditing the suite rather than by any test.

`Money.times_rounded` in Python hard-coded its decimal context precision at 60, so
`Money(Decimal("1E+30"), USD).times_rounded(Decimal("1E+30"))` raised
`decimal.InvalidOperation` — an `ArithmeticError`, not the `ValueError` the module's contract
promises — where Java's unbounded `BigDecimal` returned a value. The constructor had already
been given exactly this fix, with a comment explaining it. `times_rounded` was missed, and the
mirrored pair had never covered multiplication of large amounts.

The mirror was also asymmetrically strong where it mattered most. Java's normalisation case
used scale-sensitive `BigDecimal.equals`; Python's used `Decimal.__eq__`, which is numeric, so
`Decimal("12.5") == Decimal("12.50")` is true and the assertion could not fail. Scale is
precisely what normalisation does, and precisely what the two languages disagree about by
default.

## Decision

Both suites read one file, `contracts/money-vectors.v1.json`, holding the behaviour both
kernels must agree on: construction and normalisation, refusals, binary arithmetic, and
scaling with and without rounding.

**It is data, never code.** Values and language-neutral error categories. No expressions, no
mini-language, no types, and imported by nothing at runtime.

**Expectations are full string forms**, including trailing zeros, compared against
`toString()` and `str()`. That is what pins scale, which is the thing the two languages
silently differ on.

**Errors are neutral categories** — `PRECISION_EXCEEDED`, `CURRENCY_MISMATCH` — because Java
raises `IllegalArgumentException` where Python raises `ValueError`. Each side maps a category
to its own exception type in a three-line table; the file asserts neither.

**Both sides refuse to run without it.** Each asserts the file parses, that every group is
non-empty, and that vector ids are unique — the anti-vacuity guard the architecture rules
already use, for the same reason: a truncated file would otherwise let every case pass by
checking nothing.

**Paths are injected, not assumed.** Java reads a surefire system property built from
`${project.basedir}`, so it is right however the build was invoked. Python anchors on
`__file__`, because pytest's rootdir and the invocation directory both vary.

It lives in `contracts/` because that directory already exists to hold language-neutral,
cross-codebase, reviewed artefacts. Its README now says the directory holds two kinds: the wire
contract, and value-semantics conformance vectors.

## Consequences

**Easier.** Divergence fails a build instead of waiting to be noticed. Changing one expectation
was confirmed to fail both language suites, naming the same vector id in each. Adding a
behaviour to one kernel now has an obvious place to state it for both.

**Harder.** This is the repository's first cross-codebase file dependency, and it cuts against
ADR-010's goal that a context be extractable by moving its directory. Extracting
`backend/ledger` now means taking `contracts/money-vectors.v1.json` with it, or dropping
`MoneyVectorsTest`. That cost is small — one small file — but it is real and it is new.

**Also harder.** A vector is a shared assertion, so a deliberate divergence between the two
kernels can no longer be expressed silently. It has to be argued for by editing this file,
which is the point.

**Accepted cost.** The file duplicates a handful of cases the hand-written suites already
cover. That overlap is deliberate: the hand-written tests explain *why* a rule exists in prose
a vector cannot carry, and the vectors check that both languages *agree*, which prose cannot.

## Alternatives considered

**Leave it as mirrored prose.** Rejected by the evidence. `times_rounded` had already diverged,
and the normalisation assertion on the Python side could not fail. The mechanism ADR-010 named
was not working.

**A shared implementation — one kernel, called from both.** Rejected, and it is what ADR-010
forbids. It would mean a JVM-to-Python bridge or a native library, and it fuses two services
that are separate on purpose. ADR-007's whole argument is that the ledger and the assistant
have different shapes; a shared `Money` binary would be the thickest possible coupling between
them.

**Generate one suite from the other.** Rejected. It makes one language's test file a build
artefact of the other's, which is worse coupling than a data file for no extra safety, and the
generated side would stop being readable as a specification.

**Duplicate the vector file into both trees and have CI compare them.** Rejected. It keeps each
codebase self-contained, at the cost of a third mechanism that can itself rot, and a
byte-comparison job is a strictly weaker guarantee than both suites reading the same bytes.

**Put it under a new top-level `testdata/`.** Rejected in favour of `contracts/`, which already
carries exactly this meaning and already has a README explaining that the committed artefact is
the contract. A second directory with the same purpose and a different name would need its own
justification.

**Revisit this decision** if `backend/ledger` is actually extracted into its own repository, or
if the vector file grows past what a person will read in review — at which point the question
becomes whether it is still a specification or has quietly become a test framework.

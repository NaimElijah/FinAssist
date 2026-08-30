# ADR-017: Coverage is measured and never gated; test strength is measured by mutation

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

All six bounded contexts had a domain layer with tests, and every structural rule had a test
that had been proved to fail. What was missing was any way to tell whether those tests were
*good*. Three specific gaps made that concrete.

**Nothing measured coverage anywhere.** No JaCoCo in the ledger; `pytest-cov` was a declared
dev dependency of the assistant that no command ever invoked; no provider at all in the web
client. Untested branches were therefore not merely uncovered, they were invisible — `Lot`'s
refusal of a negative unit cost, `Balance`'s documented refusal of a foreign entry, and the
whole of `Entry.isDebit` had no test and nothing said so.

**Coverage would not have been enough.** Several assertions in the suite could not fail. The
Python test named "…Then_it_is_normalised" compared `Decimal("12.5") == Decimal("12.50")`,
which is numerically true whether or not normalisation happened; deleting the line it existed
to protect left all thirteen tests green. Those lines were *covered*. Coverage counts lines
that ran, and says nothing about whether a test would notice them being wrong.

**Property-based testing had no obvious tool.** `Money` and `Recurrence` are algebraic enough
that example-based tests only ever assert the cases their author thought of.

A fourth force shaped every answer: the project already refuses percentage targets. The global
engineering rules say coverage is a diagnostic rather than a target, and the README said so in
as many words before this record existed.

## Decision

**Coverage is measured in all three codebases and gated in none.** JaCoCo on `verify`,
`pytest-cov` inside the assistant's whole-suite target, `@vitest/coverage-v8` in the web
client. Each CI job publishes its report as an artefact. There is no `check` goal, no
`fail_under`, and no `thresholds` key anywhere, and adding one is a decision that supersedes
this record rather than a tuning exercise.

**Test strength is measured by mutation, on demand, and also not gated.** PIT runs from a
`mutation` profile in the ledger, bound to no lifecycle phase:

```
cd backend/ledger && ./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
```

It is scoped to the domain and shared kernel, excluding the Spring and ArchUnit tests, because
PIT re-runs the suite once per mutant and booting Tomcat each time buys nothing against wiring
that holds no logic. It is not in CI: nothing gates on the number, and making every push pay
for it would buy a statistic rather than a signal.

**Property-based tests are deterministic in both languages.** The ledger generates from a
`java.util.Random` with a hard-coded seed, driven by `@ParameterizedTest` and `@MethodSource`.
The assistant uses Hypothesis with `derandomize=True`, registered in `tests/conftest.py`. Both
choices exist for the same reason ADR-016 gives for the domain itself: stated inputs in, one
reproducible result out. A property suite that picks fresh examples every run can turn a commit
that changed nothing red, which teaches people to re-run the build instead of reading it.

**jqwik is rejected.** It is the idiomatic Java tool and it does not work here.

## Consequences

**Easier.** A gap is now visible rather than merely present: the first JaCoCo run named
`LedgerApplication` at 0%, the three id records at 60%, and `Divergence` at 63%, all of which
had been true and unsaid for weeks. The first mutation run found that deleting
`requireSameCurrency` from `Money.minus` broke no test — the cross-currency refusal was
asserted for `plus` and `compareTo` and never for `minus`, on either side of the language
split. Working through the survivors took test strength from 93% to 97% and closed six real
gaps that coverage alone rated as fully covered.

**Harder.** Two numbers now exist that nobody is accountable for, and an unenforced number
tends to drift. The mitigation is that mutation testing is a tool someone runs when changing
the domain, not a dashboard; if it stops being run, this record should be superseded rather
than quietly ignored.

**Accepted cost.** Mutation testing cannot reach 100%, and chasing it would be actively
harmful. Six survivors are left deliberately, each checked by hand and recorded in the commit
that closed the rest: two mutate an `ArrayList` capacity hint, one widens a loop bound the
half-open occurrence window makes unreachable, one sits behind an invariant `Projection`'s
constructor already guarantees, and one duplicates a check the compact constructor performs
immediately afterwards. Equivalent mutants are the normal condition of a well-guarded domain,
not a backlog.

**Also accepted.** The Java property tests are hand-rolled generation rather than a framework.
They shrink nothing: a failure reports the seeded input that broke, not a minimal one.

## Alternatives considered

**A coverage threshold, scoped to the domain packages.** Rejected. It would contradict the
project's own stated position, and the evidence for that position is in this repository: the
normalisation assertion above sat at 100% line coverage while asserting nothing. A percentage
gate rewards tests written to reach lines. The trigger to revisit is a defect reaching `main`
that a line-coverage gate would have caught — which is a different failure mode from the ones
found here, and none of these would have been.

**A mutation-score threshold.** Rejected for now, and the more tempting of the two. It measures
the right thing, but the score includes equivalent mutants, so the last few percent are not
merely hard but impossible, and a threshold near the current 97% would fail on an honest
refactor. Revisit when the application layer exists and the domain has stopped moving.

**jqwik for Java property tests.** Rejected on compatibility, not on merit. Its newest release,
1.10.1, requires JUnit Platform 1.14.4, while `spring-boot-starter-test` 4.1.1 puts this module
on JUnit Jupiter 6.0.3 and Platform 6. No Platform 6 release exists, and upstream's own note
says such a build is for "upcoming releases, if ever realised". A seeded `Random` behind
`@MethodSource` needs no dependency and is deterministic by construction, which is what this
project wanted anyway. **Revisit when a jqwik release declares JUnit Platform 6 support.**

**PIT rejected outright.** Considered, because `pitest-junit5-plugin` 1.2.2 documents support
only up to JUnit Platform 1.x and never mentions 6. Trying it settled the question: it works,
provided the plugin is on PIT's own classpath — without it the run fails with "check you have
correctly installed the pitest plugin for your project's test library" after auto-resolving
`junit-platform-launcher` 6.0.3. Adopted rather than rejected, because the first run paid for
itself immediately.

**Committing no mutation profile and only recording the command.** Considered seriously: a
Maven profile CI never exercises is configuration that rots. Rejected because the profile is
the only thing that makes the command reproducible, and its scoping — which classes, which
excluded tests — is the part worth reviewing and the part nobody would reconstruct correctly
from prose.

**Testcontainers and integration tests.** Not rejected, just not yet applicable: there is no
persistence layer, no HTTP adapter and no `*IT.java`, and `verify` binds no failsafe execution.
This becomes a decision the moment ADR-006 is ratified.

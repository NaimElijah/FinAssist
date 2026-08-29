# ADR-014: An answer stating a figure must cite the ledger record it came from

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

The `assistance` context answers questions in natural language using a language model. Models
produce fluent, plausible numbers that were never in the data. FinAssist's own
[non-goals](../requirements/requirements.md#non-goals) say it "reports on and explains the
owner's own data" and is "not financial advice" — but until now nothing in the code held it to
that. A prompt instruction is not an enforcement mechanism; it is a request.

Without a rule here, this context has no domain at all. It becomes a pass-through to an API,
which does not justify being a bounded context.

## Decision

An answer is a sequence of `Claim` values, each with the `EvidenceRef` values it rests on.

**A claim whose text states a figure must cite at least one piece of evidence.** A claim that
states no figure needs none. `ground(claims)` returns a `GroundedAnswer` when every claim passes
and an `Ungrounded` naming the offenders when any does not.

`Ungrounded` is **returned, not raised**. An uncited figure is an expected outcome of asking a
model for numbers, not an exceptional one, and the caller has to decide what to show. Making it
a value keeps that decision explicit at every call site.

The figure detector is deliberately blunt, and asymmetric on purpose: asking for a citation that
was not strictly needed costs a citation, while missing one lets an invented number through.

It matches digits **and a list of number words**. Digits alone were not enough — review of this
decision found that "you spent one thousand two hundred shekels" was accepted as grounded with no
citation at all, which is the exact failure this record exists to prevent. A model asked for
figures writes them in prose as readily as in digits.

`EvidenceRef` is a pair of strings — kind and identifier — rather than a typed accounting
identifier. The accounting context owns those types, and importing one would couple two contexts
([ADR-010](ADR-010-bounded-context-map.md)). Turning a ledger response into refs is the
anti-corruption layer's job.

## Consequences

**Makes easy**

- A hallucinated figure is caught by code at a defined boundary, not by whoever reads the output.
- The UI can show what every number rests on, because the citation is attached to the claim
  rather than to the answer as a whole.
- The rule is a pure function, so it is tested by calling it — no model, no key, no network.

**Makes hard**

- The detector over-reports. "There are 3 accounts" needs a citation it arguably does not, and a
  caller will find that irritating before it saves them.
- **It still under-reports.** A word list is not a number parser: "hundred" is caught, "a fair
  bit more" is not, and no list closes that. The rule reduces the gap rather than eliminating it,
  and this is the honest limit of a text-based check.
- It only checks that a citation *exists*, not that the cited evidence actually supports the
  figure. A model citing a real balance while stating a different number passes.
- Every use case that produces an answer now has to handle two outcomes.

## Revisit when

Tighten the check when a claim carries its figure as a typed value rather than embedded in text.
That closes **both** gaps at once: the cited evidence can be compared against the number, and the
word list and regex can be deleted rather than extended. Extending the word list is not the fix;
it is the thing to stop doing once claims carry values.

Loosen it only with evidence that the false-positive rate is a real problem in use, not in
anticipation of one.

## Alternatives considered

- **The answer carries its evidence set, without per-claim citation** — much less to model, and
  the UI could still show sources. Rejected because nothing connects a specific figure to a
  specific source, so an invented number sits beside real evidence and looks equally supported.
  That is the failure this decision exists to prevent.
- **No grounding concept** — the answer is whatever the model returns. Rejected: it would leave
  the context with no rule to enforce and nothing in the code holding the project to its own
  non-goal.
- **Asking the model to cite, and trusting it** — the cheapest option, and it is what the prompt
  will say anyway. Rejected as the *only* mechanism: an instruction the model may ignore is not
  a guarantee, and this rule costs one pure function.

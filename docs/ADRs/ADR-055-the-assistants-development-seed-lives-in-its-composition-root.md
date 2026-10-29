# ADR-055: The assistant's development seed lives in its composition root, and is off by default

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-021](ADR-021-application-layer-shape.md), which records the in-memory adapters as "a
development seam, not the store" but says nothing of a mode that fills them with data.

## Context

**The assistant can start with invented holdings and quotes, and no record says so**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). `ASSISTANT_DEV_SEED` arrived in
`9e606a0` on 2026-09-14, the day the assistant's first endpoints did.

**Why it exists.** The assistant has no store, no ledger client and no price feed
(`backend/assistant/CLAUDE.md`). Without the seed, every lookup finds nothing. The web client's
portfolio screens could then be tested but not seen. The ledger client is
[#62](https://github.com/NaimElijah/FinAssist/issues/62), which waits on
[#135](https://github.com/NaimElijah/FinAssist/issues/135), the decision on where a purchase is
recorded as a lot. The feed is an open question in the requirements.

**What it is.**

- **The switch.** `Settings.dev_seed` reads `ASSISTANT_DEV_SEED`, `False` by default
  (`adapters/settings.py`). `.env.example` sets it to `false`, and
  `docker-compose.yml` passes `${ASSISTANT_DEV_SEED:-false}`.
- **The data.** `adapters/seed.py` holds three lots, two of one USD instrument at different dates
  and one in ILS, and one price per symbol.
- **The wiring.** `create_app` (`adapters/app.py`) builds the seeded assembly
  when the switch is on and the plain one otherwise. It logs `development seed loaded` with the
  symbols and no amount.
- **The feed.** `SeededQuoteFeed` stamps each quote's `as_of` with the clock at read time.
  Publishing once at start-up would freeze `as_of`, and `LatestQuote` refuses a quote older than an
  hour, so valuation would start answering 503 an hour after start.

**A fabricated figure is dangerous in a finance tool.** A seed that is on by accident puts invented
holdings in front of the owner as if they were theirs. The requirements say the seed "is a
development aid, not an answer to this question, and it is off by default".

**Where it lives matters to the architecture.** No context may import the composition root
(`Given_a_context_module_When_parsed_Then_it_imports_no_composition_root`), and `shared` imports no
context. A seed inside a context would put data policy in a layer that must stay free of it.

## Decision

**`ASSISTANT_DEV_SEED` is `false` by default, and when `true` it assembles fictional holdings and
a quote feed.**

- **It lives only in `adapters/seed.py`.** That is the composition root. No context holds seed data
  or knows a seed exists. `seeded_assembly` returns the same `Assembly` that `assemble` does.
- **It is never a data source.** It is not an answer to the ledger-client question or the
  price-feed question. Nothing reads the seed as truth, and nothing in the code path depends on it.
- **It logs once, and names symbols only.** The start-up line carries no amount.
- **It is tested.** `tests/test_dev_seed.py` pins the default
  (`Given_the_environment_names_no_seed_setting_When_read_Then_it_defaults_to_off`), the off state
  (`Given_the_seed_is_off_When_a_seeded_symbol_is_read_Then_it_is_holding_not_found`), the on state,
  freshness after two hours, and that the log line holds no amount.

## Consequences

**Makes easy**

- **The portfolio screens can be seen by hand** before a ledger client or a feed exists.
- **Removing it is local.** `seed.py`, its branch in `app.py`, the setting, the Compose variable,
  the `.env.example` line and `tests/test_dev_seed.py`.
- **The contexts stay clean.** The architecture rules need no exception for it.

**Makes hard**

- **Nothing in a response marks a figure as seeded.** The start-up log line is the only trace. A
  running assistant started with the flag serves invented figures from the same routes as real ones.
- **The seed's lots and prices are invented but plausible.** One symbol is a real fund's ticker. A
  screenshot of a seeded run could be mistaken for a real one.
- **A seam that outlives its need.** The seed stays until someone removes it, and nothing says
  when that is.

**Accepted cost**

- **The assistant's composition root carries code that production never wants.** It is off, but it
  is shipped in the image.

## Alternatives considered

- **Seed inside a context**, for example in the in-memory ledger adapter's constructor. Rejected:
  it puts fabricated data in a layer the architecture keeps free of policy, and the tests that use
  the adapter would carry it.
- **On by default for local runs.** Rejected: a production-shaped run must never serve invented
  figures by accident (`seed.py`'s own docstring), and Compose is how the owner runs the stack.
- **No seed.** Rejected: the portfolio screens would have nothing to show until #62 and a feed
  land, and the screens could not be exercised by hand.
- **Load the data by a request.** Not possible: the assistant has no endpoint that records a lot.
  Its routes read a position, value a holding and price a disposal.

## Revisit when

- **A ledger client and a quote feed both exist** ([#62](https://github.com/NaimElijah/FinAssist/issues/62),
  [#135](https://github.com/NaimElijah/FinAssist/issues/135),
  [#156](https://github.com/NaimElijah/FinAssist/issues/156)). Remove the seed in the change that
  adds the second of them.
- **The assistant runs anywhere beyond the owner's machine** ([#61](https://github.com/NaimElijah/FinAssist/issues/61)).
  A flag that serves invented figures should not be one environment variable away from a deployed
  service.
- **A response needs to say it is seeded.** If the seed outlives its removal date, mark the figures
  rather than rely on the log line.

## What lands with this record

No code. Documentation only: ADR-021 reads `Accepted, extended by ADR-041 and ADR-055`, with a row
and a note in `docs/ADRs/README.md`.

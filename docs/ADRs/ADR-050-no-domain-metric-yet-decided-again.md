# ADR-050: No domain metric yet, decided again

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-019](ADR-019-opentelemetry-and-structured-logging.md). ADR-019 left "no domain metric"
as a position to be revisited when the first business endpoint shipped. This is that revisit. It
keeps the position, for a reason ADR-019 could not give, and replaces the trigger.

## Context

**ADR-019's revisit trigger fired, and the position was inherited rather than revisited**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). The trigger reads: "The first
business endpoint ships, which is when a domain metric becomes meaningful and this record's 'no
domain metric' position should be revisited rather than inherited."

- It fired on 2026-09-14: `a9fd7aa` opened and listed accounts and read balances over HTTP, and
  `d7c592f` read a position, valued a holding and priced a disposal.
- Nothing revisited it. `README.md` still said "no domain metric yet".

**No meter exists.** A search of the ledger's and the gateway's `src/main`, the assistant's `src`
and the web client's `src` finds no `MeterRegistry`, `Counter`, `Timer`, `Gauge`, OpenTelemetry
meter or histogram of FinAssist's own.

**What does exist is request metrics, for the ledger only.**

- Micrometer's HTTP server metrics, with histogram buckets
  (`management.metrics.distribution.percentiles-histogram` in the ledger's `application.yml`).
- A Prometheus job, `finassist-ledger`, scrapes them.
- `ops/grafana/dashboards/finassist-ledger.json` charts "Request rate by outcome" and the p95 and
  p99 latency.
- The assistant exports traces only, and the gateway exports metrics by OTLP push only when
  `OTEL_EXPORT_ENABLED` is true.

**Nothing pages anyone.** `ops/prometheus.yml` and Grafana's provisioning hold no alerting rule. A
single owner on their own machine reads a dashboard when they choose to.

**The owner's standing rule pulls the other way.** It asks to instrument "what would page someone:
latency, error rate, saturation, queue depth, and the domain's own success metric". With no one
to page, the first four exist where they are cheap, and the last has nothing to be a signal for.

## Decision

**FinAssist registers no domain metric.** The HTTP server metrics already carry the ledger's
request error rate, and no human or rule acts on a domain counter.

**The position is conditional, with new triggers.** ADR-019's trigger is spent. It is replaced by
the three below.

## Consequences

**Makes easy**

- **There is no meter-name contract to keep.** A dashboard that reads a counter makes its name an
  interface.
- **The README stays true.** "No domain metric yet" describes the code.

**Makes hard**

- **A domain failure has no signal of its own.** A refused write is a 422 `RULE_VIOLATED` among
  the ledger's `CLIENT_ERROR` outcomes. A priced disposal is a request. Only logs and traces tell
  them apart.
- **The assistant and the gateway have no request-rate or error-rate panel.** The dashboard
  covers the ledger alone.

**Accepted cost**

- **The owner learns of a domain failure from the books, not from telemetry.** That is the third
  trigger below, stated as a cost until it happens.

## Alternatives considered

- **Add counters now**, for example transactions recorded and refused, and disposals priced.
  Rejected. ADR-019's argument still holds in a weaker form: "a counter incremented by nothing
  would make the README's 'no domain metric yet' false while teaching a reader that the number
  means something". The endpoints now exist, so the counter would move. But no dashboard panel,
  rule or person would read it, and each name added is one more thing to keep stable.

## Revisit when

- **The first alerting rule is written.** A rule needs a signal, and a domain counter may be the
  right one.
- **FinAssist runs anywhere beyond the owner's machine**, starting with the first deployment
  target ([#61](https://github.com/NaimElijah/FinAssist/issues/61)). Someone other than the owner
  reading the system is who a success metric is for.
- **The owner learns of a failure from the books rather than from telemetry.** A refused
  transaction that went unnoticed, or a disposal priced wrongly, is the evidence this record bets
  against.

## What lands with this record

No code. Documentation only:

- **Status line and README.** ADR-019's Status line and README row name this record as an
  extension, and a note in `docs/ADRs/README.md` records the change.
- **`README.md`.** Its "no domain metric yet" cites this record.

# ADR-019: OpenTelemetry, structured logs, and one redaction boundary per service

- **Status:** Accepted, extended by [ADR-050](ADR-050-no-domain-metric-yet-decided-again.md),
  corrected by [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-08-31

## Context

Observability was already a written requirement in three places and implemented in none.

`docs/requirements/requirements.md` carried two rows: *"Structured logs, health and readiness
endpoints, OpenTelemetry rather than a vendor SDK"*, status *"Actuator is on the ledger's
classpath; nothing is wired"*; and *"redacted at the logging boundary, not at each call site"*,
status *"no logging boundary exists"*. The README's operations table said Traces *"Not wired"* and
Logs *"Not wired — Spring's default console format is what you get today"*.

The gap had already produced artefacts that pointed at it. `.env.example` declared
`OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SERVICE_NAME`, with the promise *"traces are dropped when
unset; the services still start"*, and the README listed both under "The remaining variables, none
of which is used yet". `structlog==26.1.0` was pinned as a runtime dependency of the assistant and
imported nowhere — the same shape of defect ADR-017 found in `pytest-cov`, a declared dependency no
command invoked.

Three forces constrained the answer:

**The services are at very different stages.** The ledger boots and answers Actuator probes. The
assistant had no entry point at all: 35 of its 38 Python files were empty package markers, and
health, readiness and a logging boundary all need a process to live in. The web client renders a
placeholder over one tested HTTP wrapper.

**There is nothing above the domain layer.** No application services, no persistence, no business
endpoints. Whatever gets instrumented can only observe the traffic that exists, which today is
Actuator's.

**Secrets are already promised redaction.** `ANTHROPIC_API_KEY` is documented in `.env.example` as
*"Never logged; redacted at the logging boundary"*, and the assistant's LLM adapter docstring
repeats it. Nothing enforced either statement.

## Decision

**OpenTelemetry, never a vendor SDK.** This was already the recorded requirement; this record
ratifies it and names the consequence: instrumentation is vendor-neutral, and a backend is chosen
by pointing `OTEL_EXPORTER_OTLP_ENDPOINT` somewhere, not by changing code.

**Telemetry degrades to a no-op when unconfigured.** With export off, both services start
normally and emit nothing outward — no spans, and no metrics push. This is not a convenience: it
is the contract `.env.example` already published, and the ledger has a test that reads the
shipped configuration and fails if a future change flips either default on.

**The OTLP endpoint is never set in application configuration.** Spring Boot 4.1 registers
`OpenTelemetryEnvironmentVariableEnvironmentPostProcessor`, which maps the standard
`OTEL_EXPORTER_OTLP_ENDPOINT` — and the signal-specific variants — onto its own properties with
`addFirst`. A hand-written value would be discarded whenever the standard variable is set, and
would silently ignore the signal-specific one where it is not.

**Logs are JSON on stdout, and the process writes no log files.** The ledger uses Spring Boot 4.1's
built-in structured logging (`logging.structured.format.console: ecs`), so no JSON encoder
dependency is added; SLF4J 2.0.18 and Logback 1.5.38 already arrive with `spring-boot-starter-web`.
The assistant uses `structlog`, which was already pinned. Rotation, shipping and retention belong
to whatever runs the container, not to the application.

**One redaction boundary per service that handles secrets, never a call site.** The assistant's
pipeline carries a redaction processor, and the web client's logger does the same; both remove
secret-named keys and scrub registered values. Redaction at each call site is what the requirement
already rejected, and it fails the first time somebody forgets.

The processor runs *after* the traceback is rendered, not before. An exception is one of the
likeliest carriers of a secret — a client raising with the key in its message or URL — and until
`format_exc_info` has turned it into a string there is nothing to scrub. Reversing those two
leaks every secret an exception carries, which a test now prevents.

The ledger has no such boundary and needs none: it holds no secret, reaches no third-party API,
and does not expose `/actuator/env`. Claiming otherwise in the README was an overstatement
corrected in the same change.

**The correlation id is a W3C trace context, not a bespoke header.** The ledger gets `traceId` and
`spanId` in the MDC from Micrometer Tracing and into every log line through the ECS format; the
assistant carries the same ids in a `contextvar`; the web client generates a `traceparent` and sends
it on every request. One standard the whole way, so a browser request and the span the ledger
records are the same trace rather than two ids someone has to join by hand.

**Metrics are exposed for scraping, and their OTLP push is gated by the same flag as traces.**
`micrometer-registry-prometheus` puts `/actuator/prometheus` on the ledger, which is how
Prometheus reads them. `spring-boot-starter-opentelemetry` also drags in
`micrometer-registry-otlp`, which would otherwise POST to `localhost:4318/v1/metrics` every
minute forever on a machine with no collector — so one variable, `OTEL_EXPORT_ENABLED`, gates
every signal the ledger can export: traces, metrics, and the OTLP log export added later, which
nothing feeds until a log appender exists. Actuator exposure stays an explicit allow-list —
`health,info,metrics,prometheus`, never `"*"` — and the existing test asserting `/actuator/env`
returns 404 is what stops that widening by accident.

**Sampling is 1.0.** This is a single-owner system with no traffic; a sampling budget would trade a
resource nobody is short of for gaps in the only traces that exist.

**The web client gets no OpenTelemetry SDK.** It generates a `traceparent` and logs through one
typed boundary, adding zero runtime dependencies. ADR-009 already records that the client ships as
a static bundle with no Node process to monitor.

**The assistant gains a composition root at `src/finassist_assistant/adapters/`.** Frameworks are
forbidden in inward layers by a test, and a composition root is genuinely the layer that wires
frameworks, so it belongs under a path the existing rule already exempts. It serves `/health` and
`/ready` and nothing else — no business endpoints, so the unratified ADR-004 is not pre-empted.

## Consequences

**Easier.** Two of the three "Not wired" rows in the README become statements about behaviour that
a test holds. The assistant has a process, which is what every future adapter needs first. The
redaction boundary exists, so the `ANTHROPIC_API_KEY` promise is enforced rather than asserted. A
developer can see a trace locally without owning a collector, because Compose carries one.

**Harder.** Three more services in Compose and four configuration files under `ops/` are
infrastructure that can rot without anyone noticing, since CI validates only that the Compose file
parses. Adding OpenTelemetry to the assistant also widens what `pip-audit` scans, so an advisory in
a transitive OTel package can now turn the build red.

**Accepted cost.** The ledger has no business endpoints, so its traces and metrics presently
describe Actuator traffic and nothing else. The instrumentation is real and correct; what it
observes becomes interesting with the first use case. Wiring it now rather than later is deliberate:
retrofitting a correlation id across three languages after the call paths exist is materially harder
than establishing it while there is one.

**Also accepted.** No domain metric is registered. There is no domain operation to meter yet, and a
counter incremented by nothing would make the README's "no domain metric yet" false while teaching a
reader that the number means something.

## Revisit when

- **A second consumer of the telemetry appears** — anything beyond a developer's laptop — at which
  point sampling, retention and a real backend stop being deferrable.
- **The first business endpoint ships**, which is when a domain metric becomes meaningful and this
  record's "no domain metric" position should be revisited rather than inherited.
- **The web client gains real traffic**, which is the trigger to reconsider a browser SDK for
  timing the user actually experiences.

## Alternatives considered

**A vendor SDK — Sentry, Datadog, New Relic.** Rejected. The requirement already said
"OpenTelemetry rather than a vendor SDK", and the reason holds: a vendor SDK in the code makes the
backend a rewrite rather than an environment variable. Revisit if error grouping and alerting become
needed and running an OTLP backend proves to cost more than it returns — a vendor can sit behind
OTLP anyway, which is the point.

**`logstash-logback-encoder` for the ledger's JSON.** Rejected as an unnecessary dependency. Spring
Boot 4.1 emits ECS or Logstash JSON from a configuration property; adding a library to obtain what
the framework already does would fail the project's own bar for a new dependency.

**The OpenTelemetry Java agent.** Rejected. It instruments more, with no code change, by attaching
at the JVM level — but it makes the run command carry a `-javaagent` flag that `./mvnw
spring-boot:run` and any future container both have to reproduce, and it moves version management
out of the POM where the rest of this project's pins live. The starter is BOM-managed and visible.

**A browser OpenTelemetry SDK for the web client.** Rejected for now. It is a substantial runtime
dependency for a client that renders a placeholder, and the value of browser tracing is in
measuring real user timing, which needs real users. The revisit trigger is above.

**A bespoke `X-Trace-Id` header.** Rejected in favour of `traceparent`. A custom header would have
been slightly simpler to generate and would not join the trace the backends record, leaving two
correlation schemes to reconcile. The existing client tests use `X-Trace-Id` only as an arbitrary
custom header, so nothing depended on it.

**Logging to files, with rotation.** Rejected. `.gitignore` already ignores `*.log`, and a service
that writes its own log files has to be told when to stop; stdout is the interface a container
runtime, Compose and a collector all already understand.

**Gating CI on an observability check.** Rejected, consistently with ADR-017. The tests assert the
wiring holds; nothing measures a percentage, so there is no number to gate on.

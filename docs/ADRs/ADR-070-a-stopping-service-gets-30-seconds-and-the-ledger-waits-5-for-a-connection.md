# ADR-070: A stopping service gets 30 seconds, and the ledger waits 5 seconds for a connection

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md), whose `x-` blocks
state what every long-running service shares and whose restart policy makes a stop routine. It
also extends [ADR-067](ADR-067-a-relay-that-times-out-answers-504-outcome-unknown.md), whose 504
tells the owner that a change may already be applied, and so needs the ledger not to apply one
later.

## Context

**Scan 12 of 12 read the stack's behaviour at the edges of a request's life**
([#349](https://github.com/NaimElijah/FinAssist/issues/349)). Two waits were unbounded or
unrelated to the bounds around them: how long a service has to stop, and how long the ledger waits
for a database connection.

**Compose's default grace is shorter than a ledger needs to finish a request.** No service set
`stop_grace_period`, and on this machine Docker Compose v5.6.1 sends `SIGKILL` about 3 seconds
after `SIGTERM` when none is set.

- **A process that ignores `SIGTERM`** was killed at 3.9 s, with exit code 137.
- **The ledger.** Spring Boot's graceful shutdown lets a request in flight finish, but with one
  request in flight the ledger needed 27.7 s to drain, and Compose killed it at 3 s. A request
  that had started was cut off by an ordinary stop (`make stop`, Ctrl+C on `make run`, a recreate).
- **`db`.** PostgreSQL writes a checkpoint when it shuts down. It had the same 3 s.

**The ledger waited for a connection longer than the gateway waited for the ledger.** Hikari's
`connection-timeout` was its default, 30 s. The gateway gives up on a relayed request after its
10 s read timeout ([ADR-053](ADR-053-the-gateways-outbound-calls-speak-http-1-1-under-one-time-budget.md))
and answers 504, outcome unknown (ADR-067). A request the pool had not yet served was still
waiting for a connection when that answer went out. A connection that freed up at 12 s would let
the ledger run the statement and commit it, after its caller had been told the outcome is unknown
and to check before sending it again.

**A running statement is a third, separate wait.** `statement_timeout` is 0, so once a statement
has a connection nothing ends it. Bounding it needs a figure from the performance requirement that
[#400](https://github.com/NaimElijah/FinAssist/issues/400) sizes, and Flyway shares the ledger's
`DataSource`, so the bound would apply to a migration too.

## Decision

**Every long-running Compose service stops with a 30 s `stop_grace_period`, stated once in the
`x-stop-grace-period` block.** Each takes the block by YAML alias, as it takes ADR-039's restart
and logging blocks. The one-shots take none. `make check-compose` fails a long-running service
whose grace differs, and a one-shot that has one.

**The ledger and the gateway end their own shutdown at 20 s**, with
`spring.lifecycle.timeout-per-shutdown-phase: 20s`. The JVM stops waiting on a request in flight
and exits well inside the 30 s that Compose allows, so Compose's `SIGKILL` is the last resort and
not the usual end.

**The ledger waits 5 s for a database connection**, with
`spring.datasource.hikari.connection-timeout: 5000`, in milliseconds: Hikari's setter takes a
`long`, so a duration such as `5s` fails to bind. 5 s is below the gateway's 10 s read timeout,
so the ledger gives up on a request that cannot get a connection before the gateway answers 504,
and a request that does get one has the rest of the budget to run.

**A running statement stays unbounded here.** `statement_timeout` is not set. That is deferred to
[#430](https://github.com/NaimElijah/FinAssist/issues/430), which sizes it from #400.

## Consequences

**Makes easy**

- **A stop lets a request in flight finish.** A write that started before `make stop` commits or
  rolls back whole, rather than being cut off at 3 s.
- **`db` has time for its shutdown checkpoint**, which 3 s did not always allow.
- **A 504 holds more often.** A write the ledger could not give a connection to inside the gateway's
  budget is refused by the ledger, not committed after the fact.
- **One block states the grace**, and the check holds a new service to it.

**Makes hard**

- **A stop can take up to 30 s.** `make stop`, and a Compose recreate of a service, wait for the
  slowest container to finish or be killed. A hung service no longer stops in 3 s.
- **A request can fail where it used to wait.** When no connection frees up inside 5 s, the ledger
  now gives up on the request where it used to wait up to 30 s.
- **The figures belong together.** 5 s to a connection, 10 s to the gateway's answer, 20 s to end
  shutdown and 30 s to be killed are only safe in that order. Raising one without the others breaks
  the property it was set for, and `make check-compose` pins the 30 s alone.

**Accepted cost**

- **A write can still commit after its caller was told the outcome is unknown.** A statement that
  holds a connection for more than 10 s runs to its end, because no statement bound exists.
  ADR-067's 504 is still a 504 "outcome unknown", and its duplicate risk stands until #430.
- **A request still running at 20 s of a shutdown is abandoned**, and its caller sees a failed
  call.

## Alternatives considered

- **Keep the defaults.** Rejected: the ledger was measured killed at 3 s with a request in flight,
  and its 30 s wait for a connection outlasts the gateway's 10 s, which leaves a late commit after
  a 504 possible.
- **10 s, Docker's usual default.** Rejected: the ledger needed 27.7 s to drain in the measured
  case, so 10 s would still kill it while it finished a request.
- **A grace on the Spring services only.** Rejected: `db` is the service whose shutdown writes the
  books' checkpoint and also had the 3 s, and a value set per service is one more thing a new
  service can forget. One block is held by a check; scattered values are not.
- **A Hikari timeout equal to the gateway's 10 s.** Rejected: a connection granted at 9.9 s leaves
  the statement 0.1 s before the gateway has answered 504, which is the failure this record
  addresses. A margin below the budget is the point.
- **A `statement_timeout` now.** Not adopted here. The `DataSource` is Flyway's too, so the bound
  would cap a migration, and the right figure comes from #400's performance requirement. #430
  owns it.

## Revisit when

- **A deployment target other than Compose runs the stack**
  ([#179](https://github.com/NaimElijah/FinAssist/issues/179)). An orchestrator sets its own
  termination grace, and the 30 s here is the Compose stack's.
- **[#430](https://github.com/NaimElijah/FinAssist/issues/430) or
  [#400](https://github.com/NaimElijah/FinAssist/issues/400) sets a statement bound.** The 5 s,
  10 s and 20 s figures are then revisited against it, and the accepted cost above is struck.
- **A request that must run longer than 20 s exists.** The shutdown phase and the grace move
  together with the relay's read timeout.

## What lands with this record

- **The configuration.**
  - `docker-compose.yml`: the 30 s grace, in one `x-` block, on every long-running service.
  - The ledger's and the gateway's `application.yml`: `timeout-per-shutdown-phase: 20s`.
  - The ledger's `application.yml`: `hikari.connection-timeout: 5000`.
- **The check.** `ops/compose_check/compose_check.py` pins the grace on every long-running service
  and its absence from the one-shots, and `make check-compose` runs it.
- **Documentation.**
  - ADR-039 and ADR-067 read `extended by … ADR-070`, as do their rows in `docs/ADRs/README.md`,
    with a row and a note for this record.

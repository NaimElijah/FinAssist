# ADR-039: Compose restarts a crashed service, and keeps at most 30 MB of each container's logs

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

**Compose is the only thing that runs FinAssist**
([ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md)), and `docker-compose.yml` left three
runtime concerns to Compose's defaults
([#142](https://github.com/NaimElijah/FinAssist/issues/142)).

- **No restart policy.** No service declared `restart:`, so Compose's default of `no` applied. A
  ledger, gateway or Keycloak that exited stayed down until someone ran `docker compose up` again.
  So did every service after Docker itself restarted.
- **No log bound.** Every service writes JSON to stdout and leaves "rotation, shipping and
  retention" to "whatever runs the container"
  ([ADR-019](ADR-019-opentelemetry-and-structured-logging.md)). Under Compose that is this file,
  and it set none of the three. Docker's `json-file` driver keeps every line for the life of the
  container unless told otherwise.
- **Two healthchecks probed liveness.** The gateway's probed `/actuator/health` and the
  assistant's `/health`, so `service_healthy` meant only "alive". The assistant's `/ready` exists
  so that the ledger client ([#62](https://github.com/NaimElijah/FinAssist/issues/62)) can make it
  a real check by editing one function. With Compose probing `/health`, that change would have
  been invisible to everything that waits on `service_healthy`.

## Decision

**Every long-running service runs under `restart: unless-stopped`, with Docker's `json-file` log
driver capped at three 10 MB files.** Two `x-` blocks at the top of `docker-compose.yml`,
`x-restart-policy` and `x-logging`, state each choice once. All nine long-running services take
them by YAML alias: `db`, `keycloak-db`, `keycloak`, `ledger`, `gateway`, `assistant`,
`otel-collector`, `prometheus` and `grafana`. The one-shot `auth-smoke` takes neither, so a failed
check stays failed rather than looping.

**Every Compose healthcheck probes readiness:**

- `/actuator/health/readiness` on the ledger and the gateway;
- `/ready` on the assistant;
- `/health/ready` on Keycloak's management port.

**CI's `compose` job enforces all of it from the resolved file**
(`docker compose config --format json`), so an alias that stops resolving fails as surely as a
service that never took one. It fails:

- a long-running service whose `restart` or `logging` differs from the `x-` blocks;
- `auth-smoke` with either;
- any of those four healthchecks that does not probe readiness.

## Consequences

**Makes easy**

- **A crash heals itself, and so does a Docker restart.** One stopped on purpose stays stopped:
  Ctrl+C on `docker compose up`, `docker compose stop`, or `make stop`.
- **Logs cannot fill the disk.** At most 30 MB per container, nine containers at most.
- **`service_healthy` means ready to serve**, so `auth-smoke`, and anything later that waits on a
  service, waits for the right thing.

**Makes hard**

- **A crash loop retries forever.** Docker waits longer before each attempt, and `docker compose
  ps` shows the service as `Restarting`. Nothing alerts on it
  ([#160](https://github.com/NaimElijah/FinAssist/issues/160)).
- **After a Docker restart, Docker restarts the containers itself, without Compose's `depends_on`
  ordering.** The ledger, which migrates `db` at start-up, and Keycloak, which needs
  `keycloak-db`, may each loop a few times until their database is ready.
- **A container started with `-d` and never stopped comes back after Docker Desktop restarts,**
  and holds its port.

**Accepted cost**

- **A gateway restart signs everyone out**, because sessions live in its memory
  ([ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)). An automatic restart does
  too, so a gateway caught in a crash loop signs everyone out on each pass.
- **`docker compose logs` reaches back only 30 MB per container.** Older lines are gone, and
  nothing ships them elsewhere: the ledger's OTLP log export has no appender feeding it
  (ADR-019).

## Alternatives considered

- **`on-failure[:N]`.** Rejected. It never restarts a process that exits 0. Docker also documents
  that it "doesn't restart the container if the daemon restarts", which was half of #142. A retry
  cap would make a crash loop stop, but nothing would notice that it had stopped, either.
- **`always`.** Rejected. After a daemon restart it also restarts a container that was stopped on
  purpose, so `docker compose stop` would not survive a reboot.
- **Leaving the policy at `no`.** Rejected: that is the problem #142 describes.
- **Bounding logs in the Docker daemon's own `log-opts`.** Rejected. That is machine
  configuration outside the repository, so a fresh machine or CI would not have it, and the
  stack's behaviour would depend on where it runs.
- **Docker's `local` log driver.** Rejected for now. `json-file` with two options is Docker's
  default driver, made bounded, and is enough at this size.
- **Writing a separate ADR for the probes.** Rejected. They are the same decision about what
  Compose may assume about a service, so they belong here.

## Revisit when

- **A deployment target replaces Compose as the runtime.** On AWS, ECS restarts tasks and
  CloudWatch Logs keeps them ([#179](https://github.com/NaimElijah/FinAssist/issues/179),
  [#190](https://github.com/NaimElijah/FinAssist/issues/190)), and this ADR stays the record for
  the local stack only.
- **Diagnosing a defect needs older lines than 30 MB holds.** Raise the bound, or ship logs to the
  collector.
- **A crash loop goes unnoticed long enough to matter.** That is the trigger for alerting
  ([#160](https://github.com/NaimElijah/FinAssist/issues/160)), not for a retry cap.

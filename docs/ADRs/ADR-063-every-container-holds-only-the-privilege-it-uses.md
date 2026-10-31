# ADR-063: Under Compose, every container holds only the privilege it uses, and `make check-compose` holds it there

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md), which makes the
Compose conventions check `make check-compose` and names what it refuses. That list did not include
the loopback publication of [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md) or the
`smoke` profile of [ADR-034](ADR-034-keep-the-compose-smoke-check-manual.md), although the
root `CLAUDE.md` listed both among the rules the build enforces. It also extends
[ADR-054](ADR-054-each-service-ships-as-a-two-stage-image-run-as-a-non-root-user.md), which runs
every image as a non-root user and left open whether the Java images should hand their jar to that
user.

## Context

**Scan 5 of 12 read the containers, Compose and CI on 2026-10-30**
([#342](https://github.com/NaimElijah/FinAssist/issues/342)). It found five gaps between the
owner's rule — least privilege for every container — and the stack as built.

- **Nothing checked the loopback rule.** `make check-compose` checked restart and logging, the
  readiness probes and the Spring keys. When the scan edited a copy of the file to publish `db` on
  every interface, removed `auth-smoke`'s profile, or published a port on `keycloak-db`, the check
  passed each one. ADR-030 makes loopback the defence for every published port. The root
  `CLAUDE.md` says that breaking a Compose rule fails CI. For these rules, that was not true.
- **Every container ran with Docker's default privileges.** No service dropped a capability, none
  set `no-new-privileges`, and none had a read-only root filesystem.
- **Every container shared one network.** Code execution in the assistant, Grafana, the collector
  or Keycloak gave a direct TCP path to `db`, which holds the books, with only a password in the
  way.
- **The Java images handed their jar to the service user.** `RUN chown` let code running in the JVM
  rewrite `/app/app.jar`. Under `restart: unless-stopped` the container's filesystem survives a
  restart, so a rewritten jar would run again on every start. The assistant's image deliberately
  leaves its packages owned by root (ADR-054).
- **Prometheus answered every origin's CORS request.** Its `--web.cors.origin` defaults to `.*`.
  `prom/prometheus:v3.7.3 --help` shows that default, and a request carrying
  `Origin: http://evil.example` got `Access-Control-Allow-Origin: http://evil.example` back. While
  the monitoring profile ran, any page the owner opened could read the metrics API. ADR-057 treats
  a page on another localhost port as hostile, and a page on any other origin is no less so.

The images' users, read with `docker image inspect` and from the running stack:

| Service | Runs as |
|---|---|
| ledger, gateway, assistant | a system user of their own (uid 999), from their Dockerfiles (ADR-054) |
| keycloak | `1000`, with primary group 0, inherited from `quay.io/keycloak/keycloak`; `1000:1000` since #342 |
| otel-collector | `10001:10001` |
| prometheus | `nobody` |
| grafana | `472` |
| secret-check, auth-smoke | `65534:65534`, set by Compose's `user:` |
| db, keycloak-db | root at start; the PostgreSQL entrypoint takes ownership of its directories, then drops to `postgres` itself |

## Decision

**Every service but the two databases takes `<<: *hardened`.** One `x-hardened` block at the top of
`docker-compose.yml` holds `cap_drop: [ALL]` and `security_opt: ["no-new-privileges:true"]`. Nine
services take it today: `ledger`, `gateway`, `assistant`, `secret-check`, `auth-smoke`, `keycloak`,
`otel-collector`, `prometheus` and `grafana`. The rule names its exemptions rather than its
members, so a service added later is held to the block until a change exempts it and says why.

**The two PostgreSQL services are the exemptions.** With every capability dropped, the
entrypoint's `chmod` of `/var/run/postgresql` and its `chown` of the data directory are refused,
and the container exits. They run with the image's default capabilities. Partial hardening is
[#386](https://github.com/NaimElijah/FinAssist/issues/386): drop every capability but the ones the
entrypoint uses, and set `no-new-privileges`.

**No service takes host privilege, the databases included.** None sets:

- `cap_add` or `privileged`;
- `pid`, `ipc`, `userns_mode` or `uts`, which share a namespace with the host or another container;
- `devices`;
- a `user` that is root, by name or by a number Docker reads as zero;
- a bind of a file named `docker.sock`.

**Every service but the databases and Keycloak runs on a read-only root filesystem.** Today that is
`ledger`, `gateway`, `assistant`, `secret-check`, `auth-smoke`, `otel-collector`, `prometheus` and
`grafana`, the same exemption form as the block.

- **A tmpfs on `/tmp` where a process writes there:**
  - the two JVMs, for their perf data and Tomcat's work and docbase directories;
  - Grafana, for the plugin archives it downloads at start-up.
- **Everything else these services write goes to a named volume:** Prometheus's TSDB and Grafana's
  database.
- **Keycloak and the two databases stay writable.** Keycloak writes its transaction store under
  `/opt/keycloak/data/transaction-logs`. With XA off, its default, the store holds nothing to
  recover, so a tmpfs there is probably safe, but a read-only Keycloak is untested against the
  `kc.sh` recipes in `docs/operations/operations.md`
  ([#394](https://github.com/NaimElijah/FinAssist/issues/394)).

**Each database shares a network with its one client alone.**

- `db` attaches only to `books`, which the ledger also joins.
- `keycloak-db` attaches only to `identity`, which Keycloak also joins.
- The ledger and Keycloak also stay on `default`, so every other service still reaches them.
- **`identity` is `internal`.** `keycloak-db` publishes no port, so it needs no route off its
  network, and has none. Keycloak keeps its egress through `default`.
- **`books` is not `internal`.** `db` publishes `127.0.0.1:${POSTGRES_PORT}` for the owner's
  tools and a natively run ledger, and Docker does not publish a port from a container attached to
  an internal network alone.
- **The host is a second path to `db`.** On Docker Desktop, the owner's engine, any container with
  a route off its networks reaches a loopback-published port through `host.docker.internal`.
  `db`'s port is one of them, with only its password in the way. Whether `db` should publish its
  port by default is the owner's decision
  ([#388](https://github.com/NaimElijah/FinAssist/issues/388)).

**The Java images leave the jar owned by root.** The `chown` goes. `COPY` leaves the jar root-owned
and world-readable, which aligns the Java images with the assistant's. This settles the question
ADR-054 left open.

**The Keycloak image leaves its code and configuration owned by root.**

- Upstream leaves `/opt/keycloak` owned by uid 1000, with group-write for gid 0, so the server could
  rewrite its own `kc.sh`, jars and `conf/keycloak.conf`.
- The image now copies the tree as root, clears its group-write bits in the builder stage, and
  gives the runtime user only `data/transaction-logs`. That is the one path under `/opt/keycloak`
  the server was seen to write, in a run that imported the realm, issued tokens, served the login
  page and admin console, and stopped.
- It runs as `USER 1000:1000`. The user is upstream's own, and the group is stated so that the
  runtime user is not in group 0.

**Prometheus grants no CORS origin.** Its command carries `--web.cors.origin=`, and it may set no
`entrypoint` that could run it without that flag. The regex is fully anchored, so the empty one
matches only an empty `Origin`, which Prometheus never answers with a grant. Grafana needs no grant
either, because it queries server-side (`access: proxy`), and Prometheus's own UI is same-origin.
Prometheus has no Host allow-list, so this stops a cross-origin read, not a DNS-rebound one.

**`make check-compose` refuses a break of any of these, and of the rules it did not check.**
`ops/compose_check/compose_check.py` reads the resolved file, as ADR-049 set out. It refuses:

- a published port whose `host_ip` is not exactly `127.0.0.1`: a wildcard, an IPv6 address, an
  empty address or none (ADR-030);
- any port on `keycloak-db`;
- `auth-smoke` behind anything but exactly `profiles: [smoke]`, or missing (ADR-034);
- `secret-check` behind any profile (ADR-044);
- an `x-hardened` block that is not exactly the two members above, and any service it does not
  exempt that lacks either member;
- on any service: `cap_add`, `privileged`, a namespace member, `devices`, a root `user` or a
  bind of a file named `docker.sock`;
- a writable root on any service it does not exempt;
- a database that is not alone on its own network, and any other service that joins `books` or
  `identity`. Networks are compared by the Docker network each key resolves to, so another key
  that resolves to either one is refused, and so is `external` on either;
- an `identity` network that is not `internal`;
- `network_mode` on any service but `secret-check`, whose `none` it requires. `host` would bypass
  the loopback rule, and `service:<db>` would share a database's namespace;
- a Prometheus `command` that does not carry exactly `--web.cors.origin=`, or any Prometheus
  `entrypoint`.

As every one of its reports does, each refusal names the service and the member, never a value.

## Consequences

**Makes easy**

- **The loopback rule is enforced, not just stated.** A published port off loopback now fails CI's
  `compose` job, which is what the root `CLAUDE.md` already claimed.
- **No other container can resolve or reach a database over a Compose network.** The assistant,
  the gateway, Grafana, Prometheus, the collector and Keycloak cannot resolve `db`, and only the
  ledger can. Only Keycloak can resolve `keycloak-db`, and `keycloak-db` can reach nothing beyond
  Keycloak.
- **A compromised service cannot rewrite its own code.** A read-only root, the Java images'
  root-owned jar, and Keycloak's root-owned tree are all read-only to the process that runs them.
  Under `restart: unless-stopped`, a rewritten file would otherwise run on every start.
- **A new service is hardened by default.** Taking a capability back means `cap_add`, which the
  check refuses on every service. Leaving the block means naming the service as an exemption in the
  same change.

**Makes hard**

- **A service that writes somewhere new fails at start.** A process that starts writing outside
  `/tmp` or its volume gets `Read-only file system`. Its fix is a tmpfs or a volume for that path,
  with a comment.
- **A new client of either database** must join that database's network, and the check refuses it
  until its list names the client.
- **Compose's YAML merge (`<<:`) is now load-bearing.** A service that writes its own `cap_drop`
  instead of merging the block still passes, provided its values are exactly the block's.
- **`db` stays reachable through the host on Docker Desktop** (above, #388).
- **The check refuses named members, so some routes to the host still pass it.** Each of these
  passes:
  - a bind of `/var/run` or `/`, which exposes the socket without naming it;
  - another socket the engine serves, such as Docker Desktop's `docker.raw.sock`;
  - a secret or config read from the socket's file, or a volume bound by `driver_opts`;
  - an unconfined seccomp or AppArmor profile, `cgroup: host`, or a device rule on a database;
  - a database network named after another project's, or given a `driver`.

  Each takes a deliberate edit that review sees. Refusing every bind from outside the clone, and
  the rest, is [#389](https://github.com/NaimElijah/FinAssist/issues/389).
- **The `/tmp` tmpfs mounts have no size bound, and nothing limits memory or PIDs.** A runaway
  writer in a JVM or Grafana can use up to half the host's memory before failing. Setting the
  limits needs observed usage first
  ([#387](https://github.com/NaimElijah/FinAssist/issues/387)).

## Alternatives considered

- **Make `books` `internal: true` too.** Rejected while `db` publishes a loopback port, since an
  internal-only network cannot publish one. **Revisit with** #388.
- **Read-only Keycloak with a tmpfs over its transaction store.** Deferred, not rejected: the store
  holds nothing to recover with XA off, and `docker compose down` discards it anyway, but nothing
  has yet run Keycloak's recipes against a read-only root (#394).
- **Harden the databases with five capabilities added back.** The scan's run showed that
  PostgreSQL 18 starts with `CHOWN`, `SETUID`, `SETGID`, `DAC_OVERRIDE` and `FOWNER` alone. Not
  done here; tracked by #386, which first finds the smallest set.
- **Name the hardened services instead of the exemptions.** Rejected after review: a service added
  later would pass the check with Docker's defaults, which is the gap this record closes for the
  loopback rule.
- **Docker secrets or `*_FILE` variables instead of environment variables.** Not now. On a
  single-owner machine, the Docker socket is root-equivalent anyway. **Revisit with** the
  secret-manager work for the cloud deployment
  ([#177](https://github.com/NaimElijah/FinAssist/issues/177)).
- **A Prometheus CORS grant for its own origin.** Rejected: its UI is same-origin and needs none,
  and the host port moves with `PROMETHEUS_PORT`.

## Revisit when

- **A service needs a capability, or a writable root.** Exempt it in the check in the same change,
  and say why there and in `docker-compose.yml`.
- **A third service needs a database.** It joins that database's network, and the check's list of
  clients names it.
- **#386 finds the PostgreSQL capability set.** The databases then take a variant of the block.
- **#388 decides `db`'s published port.** If the port goes, `books` can become `internal`.
- **Prometheus starts holding financial data,** such as the domain metrics of
  [#64](https://github.com/NaimElijah/FinAssist/issues/64). With no Host allow-list, a
  DNS-rebound page can still read it as its own origin. Give it an allow-list then, or stop
  publishing its port.
- **A deployment target replaces Compose (#61).** These controls move to its task or pod
  definitions, and this record is superseded there.

## What lands with this record

Every rule above, in `docker-compose.yml`, the two Java Dockerfiles, `ops/keycloak/Dockerfile` and
`ops/compose_check/compose_check.py`. Each rule the check enforces has a test in
`ops/compose_check/tests/test_compose_check.py` that fails when it is lost. The tests name the
hardened, read-only and database-network services as literals rather than reading the check's own
lists. Each new rule was also shown to fail on a mutated copy of the real file.

- **Ports and profiles:**
  - `Given_a_port_bound_to_an_address_other_than_127_0_0_1_When_checked_Then_the_service_is_named`,
    with its siblings for a port with no `host_ip`, one of several ports, and two services;
  - `Given_keycloak_db_publishing_a_port_even_on_loopback_When_checked_Then_it_fails`;
  - `Given_auth_smoke_not_behind_exactly_the_smoke_profile_When_checked_Then_it_fails`;
  - `Given_secret_check_behind_a_profile_When_checked_Then_it_fails`.
- **Hardening:**
  - `Given_a_hardened_service_without_a_member_of_the_block_When_checked_Then_it_is_named`, with
    its siblings for a weaker member and the block missing or weakened;
  - `Given_a_service_the_check_does_not_name_When_checked_Then_it_must_take_the_hardening`.
- **Host privilege:**
  - `Given_a_copy_of_the_ledger_that_is_privileged_When_checked_Then_it_is_named`;
  - `Given_a_database_that_takes_privilege_When_checked_Then_it_is_named`;
  - `Given_a_service_mounting_the_docker_socket_When_checked_Then_it_is_named`;
  - `Given_a_service_setting_a_namespace_member_When_checked_Then_it_is_named`;
  - `Given_a_service_given_a_host_device_When_checked_Then_it_is_named`;
  - `Given_a_service_running_as_root_When_checked_Then_it_is_named`.
- **Read-only roots:**
  `Given_a_read_only_service_with_a_writable_root_When_checked_Then_it_is_named`.
- **Database networks:**
  - `Given_a_database_not_on_its_own_network_alone_When_checked_Then_it_is_named`;
  - `Given_another_service_on_a_databases_network_When_checked_Then_it_is_named`;
  - `Given_another_network_key_resolving_to_a_databases_network_When_checked_Then_both_are_named`;
  - `Given_books_resolving_to_the_default_networks_name_When_checked_Then_each_joiner_is_named`;
  - `Given_a_databases_network_declared_external_When_checked_Then_it_is_named`;
  - `Given_the_identity_network_not_internal_When_checked_Then_it_is_named`.
- **`network_mode`:**
  - `Given_a_service_setting_network_mode_When_checked_Then_it_is_named`;
  - `Given_keycloak_db_setting_network_mode_When_checked_Then_it_is_named`;
  - `Given_secret_check_not_on_network_mode_none_When_checked_Then_it_is_named`.
- **Prometheus:**
  - `Given_prometheus_without_exactly_the_empty_cors_origin_When_checked_Then_the_flag_is_named`;
  - `Given_prometheus_with_an_entrypoint_When_checked_Then_it_is_named`.

**Proven by running it.** The stack started under isolated project names (`-p
finassist-scan-infra`, then `finassist-scan-infra2`), with moved ports and fresh volumes, and every
service came up healthy, read-only and hardened. The checks run against it:

- the assistant could not resolve `db`, while a connection from it to `host.docker.internal` on
  `db`'s published port succeeded;
- `keycloak-db` could resolve and reach nothing outside `identity`;
- `db`'s published port still answered from the host;
- Grafana's Prometheus datasource still worked;
- the cross-origin request got no grant;
- `auth-smoke` passed 8 of 8.

The Keycloak image's ownership is pinned by `KeycloakRealmIT`, which builds the image and probes
its tree as the runtime user: nothing under `/opt/keycloak` but `data/transaction-logs` is
writable. By hand, the same held as `1000:0`, and the image still imported the realm and reached
readiness. The Java images' root-owned jar was checked by hand; no CI job builds those images yet
([#65](https://github.com/NaimElijah/FinAssist/issues/65)).

Documentation:

- **This record's row and note** in `docs/ADRs/README.md`.
- **ADR-049 and ADR-054** read `Accepted, extended by ADR-063`, as do their rows.
- **`docs/architecture/architecture.md`.**
- **`ops/CLAUDE.md`.**
- **The root `CLAUDE.md`'s Compose section.**

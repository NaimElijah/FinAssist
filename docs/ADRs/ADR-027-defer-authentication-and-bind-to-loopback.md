# ADR-027: Authentication deferred, and every service bound to loopback

- **Status:** Superseded by [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)
- **Date:** 2026-09-14

## Context

The requirements' Authentication row has been a `TODO`: *"single-owner system, but the assistant
reaches the ledger over the network, so that hop needs a decided answer"*. Their open questions add
*"What authenticates the assistant to the ledger?"*. Neither mattered while nothing answered business
traffic. [ADR-026](ADR-026-web-client-calls-both-services-through-one-origin.md) puts a browser in
front of both services, so both matter now, and the owner answered on 2026-09-14: no authentication,
loopback only, with the deferral and its trigger written down.

FinAssist has one owner and runs only on that owner's machine. The requirements name no deployment
target, and they list a multi-tenant product as a non-goal.

**Two Accepted records are waiting on this answer.**
[ADR-020](ADR-020-audit-trail-follows-persistence.md) holds that "who" is *"blocked on
authentication, not on persistence"* and names "authentication is decided" as its revisit trigger;
[ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) repeats that trigger for the journal's
actor column.

**What bound where was uneven when this was decided.** The assistant's `ASSISTANT_HOST` already
defaulted to `127.0.0.1`. The ledger set only `server.port`, so it listened on every interface, and
`docker-compose.yml` published the database and the `monitoring` profile's ports on every interface
too.

**Loopback is not the whole threat.** The owner's own browser runs pages from anywhere, and a hostile
one can reach a loopback service without any network access of its own:

- **DNS rebinding.** A page served from `attacker.example:8080` has its DNS record switched to
  `127.0.0.1` after it loads. The browser still treats the page as same-origin with
  `attacker.example:8080`, which now resolves to the ledger, so the page can read what the ledger
  returns. What gives it away is the `Host` header, which still names `attacker.example`.
- **Simple cross-site requests.** A form `POST` with a form-encoded or `text/plain` body is sent
  without a CORS preflight. The browser hides the response from the page, but the request has
  already reached the server and done whatever it does.

Binding to `127.0.0.1` stops neither.

## Decision

**No authentication while FinAssist runs only on the owner's machine.** No login, no token, no
session. No request carries a principal, and neither service checks for one.

**Every process binds `127.0.0.1` by default.**

- ledger: `server.address: ${LEDGER_ADDRESS:127.0.0.1}`;
- assistant: `ASSISTANT_HOST`, which already defaults to `127.0.0.1`;
- Vite: `server.host` and `preview.host`, for the dev server and `vite preview` alike.

**Containers are the one exception.** Inside a container a process binds `0.0.0.0`, because the
container's own loopback is unreachable from the host. Compose then publishes each port only on the
host's loopback, as `127.0.0.1:<port>`, so what the machine exposes is still loopback.

**Loopback alone does not protect against a hostile page in the owner's own browser, so two controls
are required.**

**A Host allow-list.** A request whose `Host` header is not on the list is refused before it reaches
a handler. This is what defeats DNS rebinding: the rebound page is same-origin with the attacker's
name, and every request it makes carries that name.

- ledger: a servlet filter in `composition`, configured by `LEDGER_ALLOWED_HOSTS`;
- assistant: Starlette's `TrustedHostMiddleware`, configured by `ASSISTANT_ALLOWED_HOSTS`;
- Vite: `server.allowedHosts`, with the proxy set to `changeOrigin`, so each backend sees its own host
  rather than the dev server's and needs only its own names on its list.

**Writes accept only `application/json`.** Every ledger `POST` declares `consumes`, so a form-encoded
or `text/plain` post is answered 415 before a controller runs. A cross-site page cannot send
`application/json` without a preflight, and no CORS configuration exists to approve one. The
assistant's one POST, the disposal calculation, also accepts only `application/json` and answers
415 otherwise.

**"Who" stays absent from the journal**, exactly as ADR-020 and ADR-024 anticipated. This record
decides authentication by deferring it, and a deferral gives the journal nothing to record: with one
unauthenticated actor, an actor column would hold the same value on every row, which ADR-020 already
refused to build. Their trigger is answered "not yet" rather than fired.

**Assistant-to-ledger authentication is deferred by this record too.** The assistant has no ledger
client yet — its `LedgerPort` is satisfied by an in-memory stand-in — so there is no hop to secure.
When that client arrives, it calls one loopback service from another, and this record's triggers
govern it.

## Consequences

**Makes easy**

- Nothing to log in to, no secret to provision, rotate or keep out of the browser, and no identity
  code in either service.
- The threat model is written down, including the part loopback does not cover, so the two controls
  that cover it have a stated reason rather than looking like ceremony.
- Each control is small and sits at one boundary per service — a filter, a middleware, a `consumes`
  attribute — so replacing them when authentication arrives costs no more than adding them did.

**Makes hard**

- Every service must be told its allowed hosts. A name that is not on the list — a Compose service
  name, the machine's hostname — is refused, and the refusal looks like an outage rather than a
  configuration miss.
- The `monitoring` profile's Prometheus scrapes the Compose `ledger` by its service name, so
  `ledger` must stay on that container's `LEDGER_ALLOWED_HOSTS`, or every scrape is refused. A
  ledger run natively on loopback is not reachable from the Prometheus container, and is not
  scraped.
- Anything written by hand against a write endpoint has to send `Content-Type: application/json`.
  `curl -d` sends a form-encoded body by default, and gets 415.
- Loopback is a property of configuration, not of code. One variable set to `0.0.0.0` publishes an
  unauthenticated ledger to the local network, and nothing refuses it.

**Accepted cost**

- Any process on the owner's machine, running as any user, can read and write the books. One owner on
  one machine is the threat model, and it is stated here rather than implied.
- The journal records what changed, when and why, and not who.

## Alternatives considered

**A static bearer token in `.env`.** Cheap, and it would shut out other local processes. Rejected: it
still gives the journal no actor, since one token is one identity for every request, and the web
client would have to hold it, which puts a secret in the browser where any script on the page can
read it.

**OIDC, or a login.** The right answer once a second person or a reachable origin exists. Rejected
for now: it is identity machinery — a provider, token validation, sessions and a login screen — for
one person on one machine, and the actor it gave the journal would be the same on every row.

**Loopback only, with no Host check.** The smallest thing that sounds sufficient. Rejected because of
DNS rebinding: a hostile page in the owner's own browser reaches a loopback service without any
network access of its own, and the bind address does nothing to stop it.

## Revisit when

- **Anything other than the owner's machine can reach a port** — a port published on `0.0.0.0`, a
  tunnel, a LAN bind. Loopback is this record's premise, and without it the absence of authentication
  is an open door.
- **A deployment target is chosen.**
- **The journal needs an actor.** ADR-020 and ADR-024 both reserve that column, and this record is
  what leaves it empty.
- **A second person uses FinAssist**, which also contradicts the requirements' single-owner scope and
  reopens more than this record.

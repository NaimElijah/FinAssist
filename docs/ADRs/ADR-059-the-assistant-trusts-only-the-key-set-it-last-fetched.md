# ADR-059: The assistant trusts only the key set it last fetched

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-037](ADR-037-an-unusable-key-set-is-an-outage.md), which made a key source that
cannot be used an outage on both services and asks them to stay symmetric. It says what an
unusable key set is. It does not say how long a key a service has already seen stays trusted, and
the two services answered that differently.

## Context

**The two services kept different things from the key set they fetched**
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

- **The ledger keeps the set and nothing else.** Spring Security builds a Nimbus key source around
  the JWKS URI, and Nimbus caches the fetched set for its default of five minutes
  (`DEFAULT_CACHE_TIME_TO_LIVE` in nimbus-jose-jwt 10.9.1). Once the set expires it is fetched again,
  and if that fails no token verifies.
- **The assistant kept the set and every key it had resolved.** `PyJWKClient` caches the set for
  the lifespan the assistant gave it, 300 seconds, and with `cache_keys=True` it also caches each
  signing key by its `kid`. That second cache never expires.

**Two things followed from the second cache, and each broke the symmetry ADR-037 asks for.**

- **A key removed from the realm's set now stops verifying once the set expires, as on the
  ledger.** The per-`kid` cache never expires, so it went on answering for a key that a refetch
  had dropped from the set, until the process restarted.
- **An outage was invisible for every key already seen.** Once the set had expired and the key
  source could not be reached, the ledger answered 503 `AUTHENTICATION_UNAVAILABLE` to every
  token. The assistant went on answering 200 to any token whose `kid` it had cached, which is the
  reverse of what ADR-037 decided an outage means: no request can be authenticated, and somebody
  is paged.

**A failing fetch stalled every request behind it.** PyJWT fetches under a lock, and a fetch waits
up to the assistant's five-second timeout. While the key source stalled, each request that needed
a fetch waited out its own timeout behind every other request's.

## Decision

**The assistant trusts a key only while the key set it was fetched in is fresh.**

- **No per-`kid` key cache.** `_KeySetClient` builds `PyJWKClient` with `cache_keys=False`, so
  every lookup reads the cached set and a key leaves with it. The set is cached for
  `JWKS_CACHE_LIFESPAN_SECONDS`, 300, as the ledger's is. A key removed from the realm stops
  verifying once that cache expires and the set is fetched again.
- **A failed fetch is not tried again for 15 seconds.** After one, `fetch_data` raises
  `KeySourceUnavailable` for `JWKS_FETCH_BACKOFF_SECONDS`, 15, without fetching. That is three
  times a fetch's own timeout (`_JWKS_TIMEOUT_SECONDS`, 5), and deliberately longer than it: every
  lookup takes PyJWT's lock and a stalled fetch holds it for its whole timeout, so a backoff no
  longer than the timeout would leave the lock held by the next stalled fetch about half the time.
  With 15, one fetch per backoff fails and every lookup in between is answered at once. The time
  is read from an injected monotonic clock
  (`jwks_key_source`'s `monotonic` argument, `time.monotonic` by default), and a successful fetch
  clears the failure.
- **A token whose key is in the fresh set verifies throughout.** The backoff applies to a fetch,
  and a lookup answered from the set makes none.

## Consequences

**Makes easy**

- **Removing a signing key from the realm takes effect on both services,** on the next fetch
  after the set expires, with no restart.
- **A key-source outage is one outage on both services.** Once the set has expired and the source
  cannot be reached, a token is a 503 `AUTHENTICATION_UNAVAILABLE` whichever service receives it.
  ADR-037's rule that the two stay symmetric holds for how long a key is trusted as well as for
  what a bad set is.
- **A stalled key source costs one wait per 15 seconds,** not one per request.

**Makes hard**

- **An outage longer than the set's lifespan is now a 503 on the assistant.** Before, it kept
  verifying every key it had seen. The portfolio screens now fail when the ledger's do.
- **A removed key verifies for up to five minutes more.** It is gone only when the set expires,
  as on the ledger.
- **The key source recovers up to 15 seconds late.** A request that arrives inside the backoff
  is answered as an outage even if the source has just come back.
- **The backoff leans on PyJWT's lock.** The failure time is read and written without a lock of
  its own, because every fetch happens inside `get_signing_key`, under PyJWT's. A release that
  fetches elsewhere would make the field a race. `tests/test_auth.py` serves the failing source
  and counts fetches.
- **ADR-037's claim about the assistant's per-`kid` cache no longer holds.** Its "Makes easy"
  section says a failed refresh leaves the last good set in place "and its per-`kid` cache is
  untouched". There is no per-`kid` cache now. The first half still holds, until the set's
  lifespan ends and not beyond it. ADR-037 keeps its own text; this record is where the change is
  stated.

## Alternatives considered

- **Keep `cache_keys=True` and accept the asymmetry.** Rejected: a removed key keeps verifying
  until a restart, and an outage is hidden from every key already seen. **Revisit when** PyJWT
  gives the per-`kid` cache an expiry that follows the set's.
- **Keep serving the keys already seen during an outage, on both services.** Rejected: ADR-037
  decided that a key source that cannot be used is an outage that pages someone, and the ledger
  answers 503. **Revisit when** a Keycloak restart regularly outlasts five minutes and the owner
  is failed by it. Both services then change together.
- **No backoff.** Rejected: with the key source stalled, every request that needs a fetch queues
  behind the others' timeouts.

## Revisit when

- **A Keycloak restart or a slow key source regularly outlasts the set's five minutes.** The
  question is then whether both services should keep serving known keys for a while, decided for
  both at once.
- **PyJWT changes its caches, its lock or how a failed fetch is raised.** `_KeySetClient` is built
  on all three.

## What lands with this record

No code beyond #340's own fixes. Every rule above exists and has a test, in
`backend/assistant/tests/test_auth.py`:

- **A removed key.**
  `Given_a_key_removed_from_the_served_set_When_the_set_is_refetched_Then_its_token_is_refused`.
- **An outage past the lifespan.**
  `Given_the_key_source_unreachable_past_the_set_lifespan_When_a_known_kid_arrives_Then_it_is_503`.
- **The backoff.**
  `Given_a_failed_fetch_When_tokens_arrive_within_the_backoff_Then_none_fetches_again`,
  `Given_a_failed_fetch_When_the_backoff_has_passed_Then_the_key_source_is_fetched_again` and
  `Given_a_failing_source_and_a_fresh_set_When_unknown_kids_arrive_Then_one_fetch_is_made`.

Documentation:

- **Status line and README.** ADR-037 reads `Accepted, extended by ADR-059`, with its row in
  `docs/ADRs/README.md`, a row for 059 and a note.
- **`backend/assistant/CLAUDE.md`.** The assistant's key source.
- **`docs/architecture/architecture.md`.** The key source as built.

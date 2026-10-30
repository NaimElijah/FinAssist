# ADR-037: A key set that cannot be read, or holds no key, is an outage, on both services

- **Status:** Accepted, extended by
  [ADR-059](ADR-059-the-assistant-trusts-only-the-key-set-it-last-fetched.md)
- **Date:** 2026-10-10

## Context

**[ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md) decided that an unreachable key
source is answered 503 `AUTHENTICATION_UNAVAILABLE`, never 401**, because a service that cannot
fetch signing keys cannot tell a good token from a bad one, and the failure is not the caller's.
Both resource servers log it at ERROR, under a message of its own, and a refused token at WARN.

**"Unreachable" turned out to be the easy case.** A JWKS endpoint can answer `200` with a body no
key can be read from — a proxy's maintenance page with Keycloak down behind it, a body that is not
UTF-8, or JSON that is not a key set at all. Each service drew that line somewhere different, and
neither drew it on purpose:

| JWKS body | Ledger before | Assistant before |
|---|---|---|
| not JSON, not UTF-8, nested too deep | 503 | 503, since [#121](https://github.com/NaimElijah/FinAssist/issues/121) |
| `[]` | 503 | 401 `unknown_key` |
| `{}` | 503 | 401 `rejected` |
| a JSON integer too long for Python to convert | 503 | **500** `INTERNAL_ERROR` |
| `{"keys": []}`, or keys of a type neither library supports | 401 `unknown_key` | 401 `rejected` |

The two services also failed for different reasons. Spring Security surfaces a key set that will
not parse as a plain `JwtException`, but it parses `{"keys": []}` successfully and then reports a
token whose key it cannot find. Nimbus skips a key whose `kty` it does not support, so a set made
only of those parses as empty and ends in the same place. On the assistant, PyJWT raises
`PyJWKClientError` for `[]`, the very type a token naming a key the set lacks raises, and
`PyJWKSetError` for the other bodies, which its verifier fell through to `rejected`; neither type
told it the set itself was unusable ([#126](https://github.com/NaimElijah/FinAssist/issues/126)).

**A 401 here is the costly answer.** The browser is sent back to sign in, Keycloak signs it in, and
the next request is refused again. Only WARN lines are left, so nobody is paged. Meanwhile no
request at all can be authenticated until the key source serves a usable set again, which is what
ERROR means in this repository.

## Decision

**A key source that cannot be reached, answers something that cannot be read as a key set, or
serves a key set with no key in it is an outage, on both services: 503
`AUTHENTICATION_UNAVAILABLE`, logged at ERROR with the `key_source` reason.** That covers a body
that is not JSON, JSON that is not a key set, and a key set left with no key at all once the
service has set aside the keys it cannot parse — `{"keys": []}` included.

**A key set that holds keys, but not the one a token names, stays a refused token: 401
`unknown_key`, at WARN.** That is one caller's problem. The line between the two is whether the set
holds any key at all, not whether any token could be verified against it: a set whose only key is
published for encryption (`use: "enc"`) verifies nothing, yet it holds a key, and both services
refuse the token 401.

Each service separates "the set could not be used" from "this token's key is not in it" in the
layer that can see the difference, rather than by reading an exception's message:

- **Assistant.** `jwks_key_source` builds a private `PyJWKClient` subclass that overrides only
  `fetch_data`, a public method PyJWT's own `get_jwk_set` allows a subclass to override. Every
  failure to fetch, decode or parse the set is raised from there as `KeySourceUnavailable`. That
  method never sees a token, so a failure inside it cannot be the caller's.
- **Ledger.** `LedgerSecurityConfiguration` wraps the `JWSVerificationKeySelector` Spring builds in
  `EmptyKeySetIsAnOutage`. When no key matches a token whose algorithm the decoder allows, it asks
  the same `JWKSource` for every key. If the set holds none, it raises Nimbus's
  `KeySourceException`, which `NimbusJwtDecoder` surfaces as a plain `JwtException` and
  `ProblemAuthenticationEntryPoint` answers 503. A token with a disallowed algorithm is refused
  without any lookup, exactly as before.

Pinned by `LedgerJwksBodyTest` and by `tests/test_auth.py`, one body for each row of the table above.

## Consequences

**Makes easy**

- **One rule for an operator, on both services.** A JWKS that cannot verify anything pages
  someone, whatever its body looks like and whichever service noticed first.
- **An ordinary request costs nothing more.** PyJWT writes its key-set cache only after a
  successful fetch, so a failed refresh still leaves the last good set in place, and its per-`kid`
  cache is untouched. The ledger's extra lookup runs only once no key matched, and on a set that
  holds keys it is answered from the same cached `JWKSource`.

**Makes hard**

- **The ledger now departs from Spring Security's default on purpose.** An upgrade that changes the
  key selector Spring builds fails at start-up, because `EmptyKeySetIsAnOutage` refuses any other
  selector type rather than wrapping it blindly.
- **An empty set costs the ledger two JWKS fetches per request instead of one.** Nimbus fetches the
  set again whenever a selection comes back empty, and Spring builds the source without a rate
  limit, so the extra lookup fetches a second time. It happens only while every request is already
  an outage.
- **The assistant relies on an undocumented detail of PyJWT.** `fetch_data` also parses the set,
  into the key-set cache, which is where `PyJWKSetError` comes from. A PyJWT release that moved
  that parse elsewhere would put `{}` and `{"keys": []}` back at 401; `tests/test_auth.py` serves
  each of those bodies and would fail first.
- **An empty set now raises an ERROR, where it used to raise WARN.** A deployment whose identity
  provider legitimately published no keys for a while would page someone. Keycloak generates an
  active signing key for every realm and serves none only once an administrator has disabled them
  all, so that is a misconfiguration here rather than a state the system passes through.

## Alternatives considered

**401 on both services, matching the ledger as it stood.** Rejected. The assistant cannot tell `{}`
from `{"keys": []}` without reading PyJWT's error messages or overriding a private method, both of
which #126 called fragile. It would also have kept the failure this record exists to prevent: an
outage reported as the caller's fault, with nobody paged.

**503 on the assistant, and the ledger left at 401 for `{"keys": []}`.** Rejected. It knowingly
leaves the two services out of step on the one convention this repository asks them to keep
symmetric, and an operator would have to know which service noticed first to read the logs right.

**On the ledger, build the `JWKSource` by hand and pass it to `NimbusJwtDecoder.withJwkSource`.**
Rejected. It would replace the source Spring builds around the ledger's own `RestTemplate`, with
the connect and read timeouts that bound the JWKS fetch, and would have to rebuild that bounding
by hand. Wrapping the key selector leaves the fetch path exactly as it was.

## Revisit when

- **An identity provider this system trusts publishes an empty key set as a normal state**, for
  example while rotating keys, and the ERROR line pages someone for nothing.
- **Spring Security or Nimbus starts treating an empty key set as an error itself.** Then
  `EmptyKeySetIsAnOutage` has nothing left to do and is deleted.
- **PyJWT gains a distinct exception type for a key set it cannot use.** Then the assistant's
  `fetch_data` override can map that type instead of every failure inside the method.

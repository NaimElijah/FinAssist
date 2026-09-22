"""Test configuration for the assistant suite.

Exists for two reasons: to make Hypothesis deterministic, and to reset the logging boundary's
process-wide state around every test.

ADR-016 says a projection is "stated assumptions in, one reproducible line out", and the
ledger's own property tests generate from a fixed seed for the same reason. A property suite
that picks fresh examples every run can turn a commit that changed nothing red on CI, which
teaches everyone to re-run the build instead of reading it. ``derandomize`` makes the examples
a function of the test itself, so the same inputs run everywhere.

The profile is registered here rather than in ``pyproject.toml`` because ``--strict-config``
rejects ini keys pytest does not define, and ``hypothesis_profile`` is not one of them.
"""

from collections.abc import Iterator
from datetime import UTC, datetime
from typing import Final

import pytest
import structlog
from cryptography.hazmat.primitives.asymmetric import rsa
from hypothesis import HealthCheck, Verbosity, settings
from jwt import PyJWK
from jwt.algorithms import RSAAlgorithm

from finassist_assistant.adapters.auth import KeySource
from finassist_assistant.adapters.observability import forget_secrets, reset_logging
from finassist_assistant.adapters.wiring import Clock

settings.register_profile(
    "finassist",
    # Same inputs on every machine and every run: no flaky property test, ever.
    derandomize=True,
    # No .hypothesis/ directory to gitignore or to carry state between runs. With derandomize
    # the failure database buys nothing - a failing example reappears on the next run anyway.
    database=None,
    # These are pure functions over Decimals; a per-example time limit only ever fires because
    # a CI runner was cold, never because the domain was slow.
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
    verbosity=Verbosity.normal,
)
settings.load_profile("finassist")


@pytest.fixture(autouse=True)
def _reset_observability() -> Iterator[None]:
    """Registered secrets, structlog and the root logger are global; no test may leak any.

    Autouse for the whole suite rather than per module, so a module that starts configuring
    logging is isolated without anyone remembering to copy this in. A test that never touches
    the boundary pays for three calls that find nothing to undo.
    """
    forget_secrets()
    yield
    forget_secrets()
    structlog.reset_defaults()
    reset_logging()


# --- token verification (tests/test_auth.py) ------------------------------------------------
#
# Shared here rather than in the test module itself, so a real key pair is generated once per
# module instead of once per test: real keys, a static key source and a fixed clock, so a
# boundary test moves a token's own claims rather than the wall clock.

FIXED_NOW: Final[datetime] = datetime(2026, 1, 1, tzinfo=UTC)
"""The instant :func:`fixed_clock` reports. A leeway boundary is proven by moving a token's own
claims relative to this fixed point, never by moving the clock itself or sleeping."""


@pytest.fixture(scope="module")
def rsa_key_pair() -> tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey]:
    """One RSA key pair, generated once per test module rather than once per test.

    Nothing under test depends on a fresh key for each case, and generating a 2048-bit key -
    the floor :class:`jwt.algorithms.RSAAlgorithm` itself enforces - is the slowest thing in the
    whole suite.
    """
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return private_key, private_key.public_key()


@pytest.fixture
def fixed_clock() -> Clock:
    """A :data:`~finassist_assistant.adapters.wiring.Clock` that always reports
    :data:`FIXED_NOW`."""
    return lambda: FIXED_NOW


@pytest.fixture
def static_key_source(rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey]) -> KeySource:
    """A :data:`~finassist_assistant.adapters.auth.KeySource` that returns one fixed key
    regardless of the token it is asked about.

    Stands in for a real JWKS lookup in every test that is not itself testing that lookup; the
    real ``PyJWKClient`` path is exercised separately, against a real ``http.server``.
    """
    _, public_key = rsa_key_pair
    jwk = PyJWK.from_dict(RSAAlgorithm.to_jwk(public_key, as_dict=True))
    return lambda _token: jwk

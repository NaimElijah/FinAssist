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

import pytest
import structlog
from hypothesis import HealthCheck, Verbosity, settings

from finassist_assistant.adapters.observability import forget_secrets, reset_logging

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

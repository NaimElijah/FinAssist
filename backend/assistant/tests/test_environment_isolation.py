"""The suite's isolation from the shell that runs it.

``conftest.py`` clears every setting the developer's shell exports before each test. The clearing
itself runs before any test body, so it is pinned here through the predicate it applies.
"""

import pytest

from conftest import names_a_setting


# Lower and mixed case are the cases a case-sensitive match missed. Windows upper-cases every
# ``os.environ`` key, so they cannot be planted there; on Linux each one reached ``Settings``.
@pytest.mark.parametrize(
    "name",
    [
        "ASSISTANT_DEV_SEED",
        "assistant_dev_seed",
        "Assistant_Host",
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        "otel_export_enabled",
        "ANTHROPIC_API_KEY",
        "anthropic_api_key",
    ],
)
def Given_a_settings_variable_in_any_case_When_matched_Then_it_is_cleared(name: str) -> None:
    assert names_a_setting(name), f"{name!r} reaches Settings but would survive the clearing"


@pytest.mark.parametrize("name", ["PATH", "HOME", "assistant", "ANTHROPIC_API_KEY_FILE"])
def Given_a_variable_no_setting_reads_When_matched_Then_it_is_kept(name: str) -> None:
    assert not names_a_setting(name), f"{name!r} names no setting but would be cleared"

"""Puts `ops/secret_check` on the import path, so the tests import the check as `secret_check`.

The check is a single file run by Compose and by `uv run`, not a package, and these tests run from
`backend/assistant` with its pytest settings (`make test-secret-check`), so nothing else would find
it.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

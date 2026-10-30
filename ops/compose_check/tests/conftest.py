"""Puts `ops/compose_check` on the import path, so the tests import the check as `compose_check`.

The check is a single file run by `uv run`, not a package, and these tests run from
`backend/assistant` with its pytest settings (`make test-compose-check`), so nothing else would find
it.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

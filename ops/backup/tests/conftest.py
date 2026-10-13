"""Puts `ops/backup` on the import path, so the tests import the CLI as the module `backup`.

The CLI is a single file run by `uv run`, not a package, and these tests run from
`backend/assistant` with its pytest settings (`make test-backup`), so nothing else would find it.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

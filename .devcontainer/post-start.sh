#!/usr/bin/env bash
# Runs on every container start: apply any new migrations (non-fatal).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/backend" || exit 0
[[ -x .venv/bin/alembic && -f .env ]] || exit 0
set -a; source .env; set +a
.venv/bin/alembic upgrade head || echo "alembic upgrade failed (non-fatal)" >&2

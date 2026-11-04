#!/usr/bin/env bash
# Start a dev process with the right environment loaded.
#   bash .devcontainer/run.sh backend|worker|frontend|test
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

load_backend_env() {
  cd "$ROOT/backend"
  set -a; source .env; set +a
}

case "${1:-}" in
  backend)
    load_backend_env
    # 0.0.0.0 so the Codespaces port forwarder can reach it.
    exec .venv/bin/uvicorn app.main:app --reload --host 0.0.0.0 --port 8000 --proxy-headers
    ;;
  worker)
    load_backend_env
    exec .venv/bin/celery -A app.workers.celery_app worker -Q generation,editing,animation -l info -c 2
    ;;
  frontend)
    cd "$ROOT/frontend"
    exec npm run dev -- --hostname 0.0.0.0 --port 3000
    ;;
  test)
    (cd "$ROOT/backend" && .venv/bin/ruff check . && .venv/bin/pytest -q)
    (cd "$ROOT/frontend" && npx vitest run && npx tsc --noEmit)
    ;;
  *)
    echo "usage: $0 backend|worker|frontend|test" >&2
    exit 2
    ;;
esac

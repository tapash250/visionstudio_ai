#!/usr/bin/env bash
# One-time setup after the dev container is created.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_HOST="${POSTGRES_HOST:-localhost}"
REDIS_HOST="${REDIS_HOST:-localhost}"

# Public URLs: Codespaces forwards each port to its own https host.
if [[ -n "${CODESPACE_NAME:-}" && -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]]; then
  FRONTEND_URL="https://${CODESPACE_NAME}-3000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  API_URL="https://${CODESPACE_NAME}-8000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
else
  FRONTEND_URL="http://localhost:3000"
  API_URL="http://localhost:8000"
fi
API_HOST="${API_URL#*://}"
API_HOST="${API_HOST%%:*}"
WS_URL="${API_URL/http/ws}"

gen_secret() { python3 -c 'import secrets; print(secrets.token_hex(32))'; }

# set_env FILE KEY VALUE: replace KEY=... in FILE, or append it.
set_env() {
  local file="$1" key="$2" value="$3"
  if grep -qE "^${key}=" "$file"; then
    python3 - "$file" "$key" "$value" <<'PY'
import re, sys
path, key, value = sys.argv[1:]
text = open(path).read()
text = re.sub(rf"(?m)^{re.escape(key)}=.*$", lambda _: f"{key}={value}", text)
open(path, "w").write(text)
PY
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

# get_env FILE KEY: current value (empty if missing).
get_env() { grep -E "^$2=" "$1" 2>/dev/null | tail -1 | cut -d= -f2- || true; }

echo "==> psql client"
if ! command -v psql >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
  (sudo apt-get update -qq && sudo apt-get install -y -qq postgresql-client >/dev/null) \
    || echo "postgresql-client install failed (non-fatal)" >&2
fi

echo "==> backend/.env"
BACKEND_ENV="$ROOT/backend/.env"
if [[ ! -f "$BACKEND_ENV" ]]; then
  cp "$ROOT/backend/.env.example" "$BACKEND_ENV"
fi
# Keep secrets that already exist so rebuilding the container doesn't log everyone out.
[[ -n "$(get_env "$BACKEND_ENV" JWT_SECRET_KEY)" ]] || set_env "$BACKEND_ENV" JWT_SECRET_KEY "$(gen_secret)"
[[ -n "$(get_env "$BACKEND_ENV" GPU_WORKER_TOKEN)" ]] || set_env "$BACKEND_ENV" GPU_WORKER_TOKEN "$(gen_secret)"
set_env "$BACKEND_ENV" DATABASE_URL "postgresql+asyncpg://visionstudio:visionstudio@${DB_HOST}:5432/visionstudio"
set_env "$BACKEND_ENV" REDIS_HOST "$REDIS_HOST"
set_env "$BACKEND_ENV" REDIS_PORT "6379"
set_env "$BACKEND_ENV" REDIS_URL "redis://${REDIS_HOST}:6379/0"
set_env "$BACKEND_ENV" GPU_WORKER_URL "http://localhost:8001"
set_env "$BACKEND_ENV" ENV "development"
set_env "$BACKEND_ENV" CORS_ORIGINS "${FRONTEND_URL},http://localhost:3000"
set_env "$BACKEND_ENV" ALLOWED_HOSTS "localhost,127.0.0.1,${API_HOST}"
# Codespaces' port forwarder is one proxy hop in front of uvicorn.
set_env "$BACKEND_ENV" TRUSTED_PROXY_COUNT "$([[ -n "${CODESPACE_NAME:-}" ]] && echo 1 || echo 0)"

echo "==> frontend/.env.local"
FRONTEND_ENV="$ROOT/frontend/.env.local"
if [[ ! -f "$FRONTEND_ENV" ]]; then
  cp "$ROOT/frontend/.env.local.example" "$FRONTEND_ENV"
fi
[[ "$(get_env "$FRONTEND_ENV" NEXTAUTH_SECRET)" =~ ^change-this ]] && set_env "$FRONTEND_ENV" NEXTAUTH_SECRET "$(gen_secret)"
[[ -n "$(get_env "$FRONTEND_ENV" NEXTAUTH_SECRET)" ]] || set_env "$FRONTEND_ENV" NEXTAUTH_SECRET "$(gen_secret)"
set_env "$FRONTEND_ENV" NEXT_PUBLIC_API_URL "$API_URL"
set_env "$FRONTEND_ENV" NEXT_PUBLIC_WS_URL "$WS_URL"
set_env "$FRONTEND_ENV" API_URL "http://localhost:8000"
set_env "$FRONTEND_ENV" NEXTAUTH_URL "$FRONTEND_URL"
# Prisma uses the plain postgresql:// scheme.
set_env "$FRONTEND_ENV" DATABASE_URL "postgresql://visionstudio:visionstudio@${DB_HOST}:5432/visionstudio"

echo "==> Backend: Python venv + dependencies"
cd "$ROOT/backend"
python3 -m venv .venv
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet -r requirements-dev.txt

echo "==> Backend: database migrations"
set -a; source "$BACKEND_ENV"; set +a
for attempt in $(seq 1 30); do
  if .venv/bin/alembic upgrade head; then break; fi
  [[ "$attempt" == 30 ]] && { echo "Database not reachable; run 'make -C backend migrate' later." >&2; break; }
  sleep 2
done

echo "==> Frontend: npm ci + prisma client"
cd "$ROOT/frontend"
npm ci --no-audit --no-fund
npx --no-install prisma generate || echo "prisma generate failed (non-fatal)" >&2

cat <<MSG

VisionStudio dev container is ready.
  Backend:  bash .devcontainer/run.sh backend   (API on :8000, docs at /docs)
  Worker:   bash .devcontainer/run.sh worker    (Celery; GPU worker not included)
  Frontend: bash .devcontainer/run.sh frontend  (Next.js on :3000)
  Tests:    bash .devcontainer/run.sh test
MSG

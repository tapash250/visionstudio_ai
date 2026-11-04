# Codespaces / Dev Container

Open the repo in a Codespace (**Code → Codespaces → Create codespace**) or in VS Code with
the Dev Containers extension. Setup runs automatically:

- **Services:** Python 3.11 + Node 20 workspace, Postgres 15 (`db:5432`), Redis 7 (`redis:6379`).
- **post-create:** writes `backend/.env` (from `backend/.env.example`) with freshly generated
  `JWT_SECRET_KEY` / `GPU_WORKER_TOKEN` and Codespaces-aware `CORS_ORIGINS` / `ALLOWED_HOSTS`,
  writes `frontend/.env.local`, creates `backend/.venv`, installs dependencies, runs
  `alembic upgrade head`, `npm ci` and `prisma generate`.
- Both env files are git-ignored. Existing secrets are kept on rebuild.

Start things in separate terminals:

```bash
bash .devcontainer/run.sh backend    # FastAPI on :8000 (docs at /docs)
bash .devcontainer/run.sh worker     # Celery worker
bash .devcontainer/run.sh frontend   # Next.js on :3000
bash .devcontainer/run.sh test       # ruff + pytest + vitest + tsc
```

Ports 3000 and 8000 are forwarded. In Codespaces they are **private** by default (only you
can open them). The browser app calls the API at its own forwarded URL, and a private port
answers cross-origin `fetch` calls with a GitHub sign-in redirect, so if API calls from the
frontend fail, set port 8000 to **Public** in the Ports panel (or
`gh codespace ports visibility 8000:public -c $CODESPACE_NAME`) while you work. The API still
requires its own login token.

The GPU worker isn't part of this setup (it needs an NVIDIA GPU), so generation jobs will
queue but not complete. Point `GPU_WORKER_URL` / `GPU_WORKER_TOKEN` in `backend/.env` at a
real GPU worker to run them end to end.

To make yourself an admin after registering:

```bash
psql postgresql://visionstudio:visionstudio@db:5432/visionstudio \
  -c "UPDATE users SET is_admin = true WHERE email = 'you@example.com';"
```

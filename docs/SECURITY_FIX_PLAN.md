# Security fix plan

Ordered by severity. Every item below is fixed in this PR; "Fix" describes what changed.
Severity: **Critical** = remote takeover / unauthenticated access to internal systems,
**High** = account/authz bypass or data exposure, **Medium** = abuse or hardening gap,
**Low** = defence in depth.

## Critical

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| C1 | JWT signing key falls back to `"dev-secret"` (also hard-coded in the WebSocket handler), so anyone can forge tokens for any user if the env var is missing | `backend/app/routers/auth.py`, `backend/app/routers/websocket.py` | New `app/config.py::jwt_secret()` refuses missing, known-placeholder or <32-char keys; checked at startup (lifespan). All token code goes through `app/security.py`, including the WebSocket. |
| C2 | Open proxy / SSRF: `/api/proxy?path=` concatenates user input onto the API URL (`@evil.com`, `//host`, etc. escape it) | `frontend/src/app/api/proxy/route.ts` | Path must be relative, under `/api/`, no dot-segments/backslashes/control chars; resolved URL's origin is re-checked; redirects not followed. Logic in `src/lib/proxy-target.ts` with vitest coverage. |
| C3 | SSRF via GPU worker: it fetches any `source_url` (cloud metadata, internal services); the API passes user-supplied URLs straight through | `gpu-worker/main.py`, `backend/app/routers/edit.py`, `animate.py` | API: `sourceUrl`/`maskUrl`/`audioUrl` must be `https` on an allow-listed media host (`ALLOWED_MEDIA_HOSTS` + `R2_PUBLIC_URL` host). Worker: same allowlist enforced again, no redirects, streamed download with size cap, Pillow decompression-bomb limit. |
| C4 | GPU worker has no authentication and `CORS *` | `gpu-worker/main.py`, compose files | Shared secret `GPU_WORKER_TOKEN` (≥32 chars, required at startup) checked with `hmac.compare_digest` on every job endpoint; Celery workers send it as `X-Worker-Token`. CORS and OpenAPI docs removed; port no longer published in `docker/docker-compose.yml`. |

## High

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| H1 | Anyone with an `@visionstudio.app` email is admin | `backend/app/routers/admin.py` | New `users.is_admin` column (migration `002_user_is_admin.py`, default false); `require_admin` checks it. Admins can't ban themselves. |
| H2 | Seed script creates `admin@visionstudio.app` / `admin123` | `backend/seed.py` | Admin seeded only from `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` (12+ chars); no default credential. |
| H3 | CORS `*` with `allow_credentials=True`; `TrustedHostMiddleware(allowed_hosts=["*"])` | `backend/app/main.py` | Explicit `CORS_ORIGINS` (default `http://localhost:3000`, `*` refused), explicit methods/headers; `ALLOWED_HOSTS` env (default localhost; `*` refused in production). |
| H4 | WebSocket job subscription leaks other users' jobs (IDOR: any `jobId` is polled without an owner check) | `backend/app/routers/websocket.py` | Poll query filters on `user_id`; job type allow-listed; max 20 subscriptions per socket; polling stops on disconnect or after 15 min. |
| H5 | Upload: MIME check only compares the major type (SVG, any `video/*`, `image/svg+xml` pass); client extension used in the storage key; whole body read before the size check | `backend/app/routers/upload.py` | Type detected from magic bytes against an exact allowlist (JPEG/PNG/WebP/HEIC/HEIF/MP4/MP3); extension derived from detected type; streamed read aborts past `MAX_UPLOAD_SIZE` (413). |
| H6 | Vulnerable / unmaintained auth libraries: `python-jose` 3.3.0 (CVE-2024-33663 algorithm confusion, CVE-2024-33664 JWE DoS) and `passlib` 1.7.4 (unmaintained, pinned old bcrypt) | `backend/requirements.txt` | Replaced by `PyJWT` 2.15 and `bcrypt` 5 used directly (existing `$2b$` hashes stay valid). Tokens require `exp`/`sub`; `alg=none` rejected. |
| H7 | Push registration lets one account take over another account's device by fingerprint | `backend/app/routers/websocket.py` | 409 if the fingerprint belongs to another user. (The `Device` model doesn't exist yet, so both push endpoints now return 501 instead of crashing.) |
| H8 | Placeholder secrets committed as a real manifest (`JWT_SECRET_KEY: change-me-in-production`) | `k8s/secret.yaml` | Renamed to `k8s/secret.example.yaml` with empty values + instructions; `k8s/secret.yaml` git-ignored. Compose files require `JWT_SECRET_KEY`/`GPU_WORKER_TOKEN` (`${VAR:?}`). |

## Medium

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| M1 | Rate limit keyed on client-chosen `x-device-id` header (trivially bypassed) | `backend/app/main.py`, `services/rate_limiter.py` | Keyed on peer IP; `X-Forwarded-For` honoured only for `TRUSTED_PROXY_COUNT` proxies. Unique sorted-set members so same-tick requests all count. |
| M2 | No input validation: endpoints take raw `dict` (unbounded prompt, steps, batch size, arbitrary model/aspect) | `generate.py`, `edit.py`, `animate.py`, `projects.py`, `auth.py`, `admin.py`, `websocket.py` | Pydantic models in `app/schemas.py` with bounds/enums; GPU worker request models bounded too. Register requires a valid email and 8–128 char password. Edit prompts go through moderation. Setting `isMature` requires the user's mature consent. |
| M3 | Internal error details leaked: GPU worker returns `str(e)`; workers store `str(e)` in `job.error`, which users read | `gpu-worker/main.py`, `backend/app/workers/*` | Generic messages to clients; details only logged. |
| M4 | Banned users can still log in and use tokens; refresh token read from the query string (logged) | `backend/app/routers/auth.py` | Banned users get 403 at login/refresh/any authenticated call; refresh token taken from the JSON body (which is what the frontend already sends). Constant-time-ish login for unknown emails. |
| M5 | Celery jobs never ran (routers send `tasks.*`, workers registered under module paths; routers built their own broker-only Celery app) and failed jobs were marked FAILED *then* retried; `asyncio.run` reused a loop-bound connection pool | `backend/app/routers/*`, `backend/app/workers/*` | Tasks registered with explicit names shared via constants in `celery_app.py`; routers use the shared app via `enqueue()` with `task_routes`. Only transient errors (network/5xx) are retried; job marked FAILED only after the last attempt. Each task runs with its own `NullPool` engine. GPU `/animate` (which uploaded a file that was never written) now returns 501, a permanent failure. |

## Low

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| L1 | No security headers | `backend/app/main.py` | `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`; HSTS in production. |
| L2 | Containers run as root | `backend/Dockerfile`, `gpu-worker/Dockerfile` | Non-root user. |
| L3 | Dev servers bind `0.0.0.0` when run directly | `backend/app/main.py`, `gpu-worker/main.py` | Default `127.0.0.1` (override with `HOST`). Docker CMDs unchanged. |
| L4 | GPU worker deps unpinned | `gpu-worker/Dockerfile` | `gpu-worker/requirements.txt` with pinned versions. |

## Dependency updates (backend)

All pins in `backend/requirements.txt` moved to current releases (Nov 2026), `pyproject.toml` floors match:
fastapi 0.109.0→0.162.0, uvicorn 0.27.0→0.54.0, python-multipart 0.0.6→0.0.33 (fixes CVE-2024-24762 / CVE-2024-53981 ReDoS/DoS),
pydantic 2.5.3→2.14.2, pydantic-settings 2.1.0→2.16.2, email-validator 2.1.0→2.3.0,
sqlalchemy 2.0.25→2.1.8 (now `sqlalchemy[asyncio]`, since 2.1 no longer pulls in greenlet), asyncpg 0.29→0.32,
alembic 1.13.1→1.20.1, redis 5.0.1→8.1.0 (`close()`→`aclose()`), celery 5.3.6→5.6.3, pillow 10.2.0→13.0.0,
boto3 1.34.0→1.43.123, httpx 0.26→0.28.1, aiofiles 23.2.1→25.1.0, sentry-sdk 1.39→2.73, prometheus-client 0.19→0.26,
structlog 23.2→26.1; python-jose→PyJWT 2.15.1, passlib+bcrypt 4.0.1→bcrypt 5.0.0; unused python-magic dropped
(libmagic was never installed in the image; upload sniffing is now built in).
Dev: pytest 9.2, pytest-asyncio 1.4, pytest-cov 7.1, ruff 0.17, fakeredis 2.40.

## Deploy notes (breaking config)

- Set `JWT_SECRET_KEY` (≥32 random chars) and `GPU_WORKER_TOKEN` everywhere, or the services won't start.
- Set `CORS_ORIGINS`, `ALLOWED_HOSTS`, `ALLOWED_MEDIA_HOSTS` (or `R2_PUBLIC_URL`) and `TRUSTED_PROXY_COUNT` for your environment.
- Run `alembic upgrade head` (adds `users.is_admin`), then grant admins explicitly, e.g. `UPDATE users SET is_admin = true WHERE email = '…';`.
- Rotate any secret that was ever set to a committed placeholder value.

## Not covered here (follow-ups)

- `/edit` on the GPU worker still returns the source image unchanged and `/animate` returns 501: functional gaps, not security.
- Prisma (frontend) and SQLAlchemy (backend) both define the schema; consolidate.
- Frontend dependency upgrades are handled in separate PRs.
- `visionstudio-ai-complete.zip` (a committed archive of the repo) should be removed.

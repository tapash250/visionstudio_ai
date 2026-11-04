import os

# Configure the app before it is imported: the JWT secret is mandatory and media URLs are
# only accepted from allowlisted hosts.
os.environ.setdefault("JWT_SECRET_KEY", "test-secret-key-that-is-long-enough-0123456789")
os.environ.setdefault("ALLOWED_MEDIA_HOSTS", "media.example.com")
os.environ.setdefault("CORS_ORIGINS", "http://localhost:3000")

import fakeredis  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app.main as main_module  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture(autouse=True)
def fake_redis(monkeypatch):
    """Each test gets an empty in-memory Redis, so rate-limit buckets never leak between tests."""
    client = fakeredis.FakeAsyncRedis(decode_responses=True)
    monkeypatch.setattr(main_module, "redis_client", client)
    monkeypatch.setattr(main_module.rate_limiter, "redis", client)
    return client


@pytest.fixture(autouse=True)
def no_celery(monkeypatch):
    """Record queued jobs instead of talking to a broker."""
    sent: list[tuple[str, list]] = []

    def fake_send_task(name, args=None, **kwargs):
        sent.append((name, args))

    from app.workers.celery_app import celery_app

    monkeypatch.setattr(celery_app, "send_task", fake_send_task)
    return sent


@pytest.fixture
def api_client():
    """In-process client; used as a context manager so the app's lifespan runs."""
    with TestClient(app) as test_client:
        yield test_client

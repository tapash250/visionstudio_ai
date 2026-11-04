"""Regression tests for the fixes in docs/SECURITY_FIX_PLAN.md."""
from datetime import timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import jwt
import pytest
from fastapi.testclient import TestClient

from app import config
from app.database import get_db
from app.main import app
from app.models import GenerationJob
from app.routers.auth import get_current_user
from app.security import ALGORITHM, create_access_token, create_refresh_token, decode_token


@pytest.fixture
def user():
    u = MagicMock(id="user-1", email="u@example.com", is_admin=False, banned_at=None)
    u.mature_enabled = False
    app.dependency_overrides[get_current_user] = lambda: u
    yield u
    app.dependency_overrides.pop(get_current_user, None)


@pytest.fixture
def mock_db():
    db = AsyncMock()

    async def _get_db():
        yield db

    app.dependency_overrides[get_db] = _get_db
    yield db
    app.dependency_overrides.pop(get_db, None)


AUTH = {"Authorization": "Bearer x"}


class TestJwtSecret:
    @pytest.mark.parametrize("value", ["", "dev-secret", "short", "change-me-in-production"])
    def test_weak_or_missing_secret_is_rejected(self, monkeypatch, value):
        monkeypatch.setenv("JWT_SECRET_KEY", value)
        config.jwt_secret.cache_clear()
        try:
            with pytest.raises(config.ConfigError):
                config.jwt_secret()
        finally:
            monkeypatch.undo()
            config.jwt_secret.cache_clear()

    def test_token_signed_with_old_default_is_rejected(self):
        forged = jwt.encode({"sub": "admin", "type": "access", "exp": 9999999999}, "dev-secret",
                            algorithm=ALGORITHM)
        assert decode_token(forged, "access") is None

    def test_alg_none_is_rejected(self):
        forged = jwt.encode({"sub": "a", "type": "access", "exp": 9999999999}, None, algorithm="none")
        assert decode_token(forged, "access") is None

    def test_refresh_token_cannot_be_used_as_access_token(self):
        assert decode_token(create_refresh_token({"sub": "u"}), "access") is None
        assert decode_token(create_access_token({"sub": "u"}), "access")["sub"] == "u"

    def test_expired_token_is_rejected(self):
        token = create_access_token({"sub": "u"}, expires_delta=timedelta(seconds=-1))
        assert decode_token(token, "access") is None

    def test_refresh_endpoint_reads_json_body(self, api_client, mock_db):
        u = MagicMock(id="u", email="u@example.com", banned_at=None)
        mock_db.execute.return_value = MagicMock(scalar_one_or_none=MagicMock(return_value=u))
        r = api_client.post("/api/auth/refresh",
                            json={"refresh_token": create_refresh_token({"sub": "u"})})
        assert r.status_code == 200 and "access_token" in r.json()


class TestCorsAndHosts:
    def test_wildcard_cors_is_refused(self, monkeypatch):
        monkeypatch.setenv("CORS_ORIGINS", "*")
        with pytest.raises(config.ConfigError):
            config.cors_origins()

    def test_unknown_origin_gets_no_cors_headers(self, api_client):
        r = api_client.get("/health", headers={"Origin": "https://evil.example"})
        assert "access-control-allow-origin" not in r.headers

    def test_allowed_origin_gets_cors_headers(self, api_client):
        r = api_client.get("/health", headers={"Origin": "http://localhost:3000"})
        assert r.headers["access-control-allow-origin"] == "http://localhost:3000"

    def test_unknown_host_header_is_rejected(self):
        with TestClient(app, base_url="http://evil.example") as c:
            assert c.get("/health").status_code == 400

    def test_security_headers(self, api_client):
        r = api_client.get("/health")
        assert r.headers["x-content-type-options"] == "nosniff"
        assert r.headers["x-frame-options"] == "DENY"


class TestRateLimit:
    def test_device_id_header_does_not_reset_the_limit(self, api_client, user):
        codes = [
            api_client.post("/api/generate/enhance", json={"prompt": "a"},
                            headers={**AUTH, "x-device-id": f"d{i}"}).status_code
            for i in range(7)
        ]
        assert 429 in codes

    def test_forwarded_for_ignored_without_trusted_proxy(self, api_client, user):
        codes = [
            api_client.post("/api/generate/enhance", json={"prompt": "a"},
                            headers={**AUTH, "x-forwarded-for": f"10.0.0.{i}"}).status_code
            for i in range(7)
        ]
        assert 429 in codes


class TestAdmin:
    def test_company_email_is_not_admin(self, api_client, user, mock_db):
        user.email = "someone@visionstudio.app"
        user.is_admin = False
        assert api_client.get("/api/admin/users", headers=AUTH).status_code == 403

    def test_admin_flag_grants_access(self, api_client, user, mock_db):
        user.is_admin = True
        mock_db.execute.return_value = MagicMock(
            scalars=MagicMock(return_value=MagicMock(all=lambda: []))
        )
        assert api_client.get("/api/admin/users", headers=AUTH).status_code == 200


class TestInputValidation:
    def test_generate_rejects_bad_values(self, api_client, user, mock_db):
        for body in ({"prompt": ""}, {"prompt": "x" * 5000}, {"prompt": "a", "steps": 10000},
                     {"prompt": "a", "batchSize": 100}, {"prompt": "a", "aspectRatio": "7:3"}):
            assert api_client.post("/api/generate/", json=body, headers=AUTH).status_code == 422

    @patch("app.routers.generate.moderation.check_prompt")
    def test_generate_queues_named_task(self, mod, api_client, user, mock_db, no_celery):
        mod.return_value = MagicMock(blocked=False)
        mock_db.add = MagicMock()
        r = api_client.post("/api/generate/", json={"prompt": "a cat"}, headers=AUTH)
        assert r.status_code == 200
        assert no_celery and no_celery[0][0] == "tasks.generate_image"

    @pytest.mark.parametrize("url", [
        "http://169.254.169.254/latest/meta-data/",
        "https://169.254.169.254/",
        "http://media.example.com/a.png",
        "file:///etc/passwd",
        "https://evil.example/a.png",
        "https://user:pw@media.example.com/a.png",
    ])
    def test_edit_rejects_non_media_urls(self, api_client, user, mock_db, url):
        r = api_client.post("/api/edit/", json={"sourceUrl": url, "operation": "INPAINT"},
                            headers=AUTH)
        assert r.status_code == 422

    def test_edit_accepts_media_url_and_queues(self, api_client, user, mock_db, no_celery):
        mock_db.add = MagicMock()
        r = api_client.post("/api/edit/", json={
            "sourceUrl": "https://media.example.com/uploads/a.png", "operation": "INPAINT"},
            headers=AUTH)
        assert r.status_code == 200
        assert no_celery[0][0] == "tasks.edit_image"

    def test_animate_rejects_internal_url(self, api_client, user, mock_db):
        r = api_client.post("/api/animate/", json={
            "sourceUrl": "http://localhost:8001/health", "animationType": "smile"}, headers=AUTH)
        assert r.status_code == 422


class TestUpload:
    PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64

    @pytest.fixture(autouse=True)
    def storage(self):
        with patch("app.routers.upload.storage.upload", new=AsyncMock(return_value="https://m/x")) as m:
            yield m

    def test_png_is_accepted_and_extension_comes_from_content(self, api_client, user, storage):
        r = api_client.post("/api/upload/", headers=AUTH,
                            files={"file": ("../../evil.php", self.PNG, "application/x-php")})
        assert r.status_code == 200
        body = r.json()
        assert body["key"].endswith(".png") and ".." not in body["key"]
        assert body["contentType"] == "image/png"
        assert storage.await_args.args[2] == "image/png"

    def test_svg_is_rejected_even_when_labelled_as_image(self, api_client, user):
        svg = b'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'
        r = api_client.post("/api/upload/", headers=AUTH,
                            files={"file": ("a.svg", svg, "image/svg+xml")})
        assert r.status_code == 415

    def test_html_labelled_as_png_is_rejected(self, api_client, user):
        r = api_client.post("/api/upload/", headers=AUTH,
                            files={"file": ("a.png", b"<html><script></script>", "image/png")})
        assert r.status_code == 415

    def test_oversized_upload_is_rejected(self, api_client, user, monkeypatch):
        monkeypatch.setattr("app.routers.upload.MAX_SIZE_MB", 1)
        big = self.PNG + b"\x00" * (2 * 1024 * 1024)
        r = api_client.post("/api/upload/", headers=AUTH, files={"file": ("a.png", big, "image/png")})
        assert r.status_code == 413


class TestCeleryWiring:
    def test_worker_tasks_are_registered_under_the_names_the_api_sends(self):
        import app.workers.animation  # noqa: F401
        import app.workers.editing  # noqa: F401
        import app.workers.generation  # noqa: F401
        from app.workers.celery_app import ANIMATE_TASK, EDIT_TASK, GENERATE_TASK, celery_app

        for name, queue in ((GENERATE_TASK, "generation"), (EDIT_TASK, "editing"),
                            (ANIMATE_TASK, "animation")):
            assert name in celery_app.tasks
            assert celery_app.conf.task_routes[name]["queue"] == queue


class TestWorkerFailureHandling:
    def _task(self, retries, max_retries=3):
        return MagicMock(request=MagicMock(retries=retries), max_retries=max_retries)

    def _session_factory(self, job):
        db = AsyncMock()
        db.add = MagicMock()
        db.execute.return_value = MagicMock(scalar_one_or_none=MagicMock(return_value=job))
        factory = MagicMock()
        factory.return_value.__aenter__ = AsyncMock(return_value=db)
        factory.return_value.__aexit__ = AsyncMock(return_value=False)
        return factory

    async def test_transient_failure_retries_without_marking_failed(self):
        from app.workers.common import RetryableJobError, process_job

        job = MagicMock(status="QUEUED", started_at=None)
        work = AsyncMock(side_effect=RetryableJobError("down"))
        with pytest.raises(RetryableJobError):
            await process_job(self._task(0), self._session_factory(job), GenerationJob, "j", work)
        assert job.status == "PROCESSING"

    async def test_final_failure_marks_failed_with_generic_error(self):
        from app.workers.common import PUBLIC_FAILURE_MESSAGE, RetryableJobError, process_job

        job = MagicMock(status="PROCESSING", started_at=None)
        work = AsyncMock(side_effect=RetryableJobError("secret internal host 10.0.0.5"))
        await process_job(self._task(3), self._session_factory(job), GenerationJob, "j", work)
        assert job.status == "FAILED"
        assert job.error == PUBLIC_FAILURE_MESSAGE

    async def test_success_marks_completed(self):
        from app.workers.common import process_job

        job = MagicMock(status="QUEUED", started_at=None)
        await process_job(self._task(0), self._session_factory(job), GenerationJob, "j",
                          AsyncMock(return_value="https://m/r.png"))
        assert job.status == "COMPLETED"

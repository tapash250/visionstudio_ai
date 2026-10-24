import pytest
from unittest.mock import MagicMock, patch

from app.main import app
from app.routers.auth import get_current_user

class TestGeneration:
    @pytest.fixture
    def auth_headers(self):
        # The token is never decoded: signed_in_user replaces the auth dependency.
        return {"Authorization": "Bearer test-token"}

    @pytest.fixture
    def signed_in_user(self):
        # FastAPI resolves dependencies it captured at import time, so patching
        # app.routers.auth.get_current_user has no effect; an override does.
        user = MagicMock(id="user-1", email="test@example.com")
        app.dependency_overrides[get_current_user] = lambda: user
        yield user
        app.dependency_overrides.pop(get_current_user, None)

    def test_create_generation_unauthorized(self, api_client):
        response = api_client.post("/api/generate/", json={
            "prompt": "a beautiful sunset"
        })
        assert response.status_code == 401

    @patch("app.routers.generate.moderation.check_prompt")
    def test_create_generation_blocked_prompt(self, mock_moderation, api_client, signed_in_user, auth_headers):
        mock_moderation.return_value = MagicMock(
            blocked=True,
            reason="Inappropriate content"
        )

        response = api_client.post("/api/generate/", json={
            "prompt": "inappropriate content"
        }, headers=auth_headers)

        assert response.status_code == 400
        assert "blocked" in response.json()["detail"].lower()
        mock_moderation.assert_awaited_once_with("inappropriate content")

    def test_enhance_prompt(self, api_client, signed_in_user, auth_headers):
        response = api_client.post("/api/generate/enhance", json={
            "prompt": "a cat"
        }, headers=auth_headers)

        assert response.status_code == 200
        data = response.json()
        assert "enhanced" in data
        assert "masterpiece" in data["enhanced"]

class TestStyles:
    def test_get_styles(self, api_client):
        response = api_client.get("/api/styles/")
        assert response.status_code == 200
        data = response.json()
        assert "styles" in data

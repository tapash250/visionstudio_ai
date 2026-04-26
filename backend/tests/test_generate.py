import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch, MagicMock

from app.main import app
from app.database import get_db

client = TestClient(app)

class TestGeneration:
    @pytest.fixture
    def auth_headers(self):
        # In production, generate a real JWT token
        return {"Authorization": "Bearer test-token"}

    def test_create_generation_unauthorized(self):
        response = client.post("/api/generate/", json={
            "prompt": "a beautiful sunset"
        })
        assert response.status_code == 401

    @patch("app.routers.generate.moderation.check_prompt")
    def test_create_generation_blocked_prompt(self, mock_moderation, auth_headers):
        mock_moderation.return_value = MagicMock(
            blocked=True,
            reason="Inappropriate content"
        )

        # Mock auth
        with patch("app.routers.auth.get_current_user") as mock_auth:
            mock_auth.return_value = MagicMock(id="user-1", email="test@example.com")

            response = client.post("/api/generate/", json={
                "prompt": "inappropriate content"
            }, headers=auth_headers)

            assert response.status_code == 400
            assert "blocked" in response.json()["detail"].lower()

    def test_enhance_prompt(self, auth_headers):
        with patch("app.routers.auth.get_current_user") as mock_auth:
            mock_auth.return_value = MagicMock(id="user-1", email="test@example.com")

            response = client.post("/api/generate/enhance", json={
                "prompt": "a cat"
            }, headers=auth_headers)

            assert response.status_code == 200
            data = response.json()
            assert "enhanced" in data
            assert "masterpiece" in data["enhanced"]

class TestStyles:
    def test_get_styles(self):
        response = client.get("/api/styles/")
        assert response.status_code == 200
        data = response.json()
        assert "styles" in data

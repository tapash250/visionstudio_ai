import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import AsyncSession
from unittest.mock import AsyncMock, patch

from app.main import app
from app.database import get_db
from app.models import User

client = TestClient(app)

@pytest.fixture
def mock_db():
    db = AsyncMock(spec=AsyncSession)
    return db

@pytest.fixture
def override_get_db(mock_db):
    async def _get_db():
        yield mock_db
    app.dependency_overrides[get_db] = _get_db
    yield
    app.dependency_overrides.clear()

class TestAuth:
    def test_register_success(self, override_get_db, mock_db):
        mock_db.execute.return_value = AsyncMock(
            scalar_one_or_none=AsyncMock(return_value=None)
        )

        response = client.post("/api/auth/register", json={
            "email": "test@example.com",
            "password": "securepassword123",
            "name": "Test User"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["email"] == "test@example.com"
        assert "id" in data

    def test_register_duplicate_email(self, override_get_db, mock_db):
        existing_user = User(email="test@example.com", name="Existing")
        mock_db.execute.return_value = AsyncMock(
            scalar_one_or_none=AsyncMock(return_value=existing_user)
        )

        response = client.post("/api/auth/register", json={
            "email": "test@example.com",
            "password": "securepassword123",
            "name": "Test User"
        })

        assert response.status_code == 400
        assert "already registered" in response.json()["detail"]

    def test_login_success(self, override_get_db, mock_db):
        from passlib.context import CryptContext
        pwd_context = CryptContext(schemes=["bcrypt"])

        user = User(
            id="test-id",
            email="test@example.com",
            password_hash=pwd_context.hash("password123"),
            name="Test User"
        )
        mock_db.execute.return_value = AsyncMock(
            scalar_one_or_none=AsyncMock(return_value=user)
        )

        response = client.post("/api/auth/token", data={
            "username": "test@example.com",
            "password": "password123"
        })

        assert response.status_code == 200
        data = response.json()
        assert "access_token" in data
        assert "refresh_token" in data

    def test_login_invalid_credentials(self, override_get_db, mock_db):
        mock_db.execute.return_value = AsyncMock(
            scalar_one_or_none=AsyncMock(return_value=None)
        )

        response = client.post("/api/auth/token", data={
            "username": "wrong@example.com",
            "password": "wrongpassword"
        })

        assert response.status_code == 401

class TestRateLimit:
    def test_rate_limit_headers(self):
        response = client.get("/health")
        assert response.status_code == 200
        assert "X-Process-Time" in response.headers

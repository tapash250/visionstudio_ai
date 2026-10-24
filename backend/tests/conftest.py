import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker
from unittest.mock import AsyncMock

from app.main import app
from app.database import get_db, Base

# Test database
TEST_DATABASE_URL = "postgresql+asyncpg://test:test@localhost:5432/test"

test_engine = create_async_engine(TEST_DATABASE_URL, echo=False)
TestingSessionLocal = sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

@pytest.fixture(scope="session")
def event_loop():
    import asyncio
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()

@pytest.fixture(scope="session", autouse=True)
async def setup_database():
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)

@pytest.fixture
async def db_session():
    async with TestingSessionLocal() as session:
        yield session
        await session.rollback()

@pytest.fixture
def client(db_session):
    async def override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()

@pytest.fixture
def api_client():
    """In-process client for tests that mock their own dependencies.

    Used as a context manager so every request in a test shares one event loop (the app's
    Redis connection pool is bound to the loop it was first used on) and the app's lifespan
    runs. A fresh device id gives each test its own rate-limit bucket, so reruns within the
    limiter's 60-second window can't trip the limit.
    """
    with TestClient(app, headers={"x-device-id": f"pytest-{uuid.uuid4()}"}) as test_client:
        yield test_client

@pytest.fixture
def mock_user():
    return {
        "id": "test-user-id",
        "email": "test@example.com",
        "name": "Test User",
        "mature_enabled": False,
    }

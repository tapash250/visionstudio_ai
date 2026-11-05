from unittest.mock import AsyncMock, patch

import pytest
from fastapi import HTTPException

from app.routers.providers import _validated_base_url


def test_rejects_http_provider_url():
    with pytest.raises(HTTPException):
        _validated_base_url("http://example.com/v1")


def test_rejects_localhost_provider_url():
    with pytest.raises(HTTPException):
        _validated_base_url("https://localhost:8443/v1")


def test_rejects_url_credentials():
    with pytest.raises(HTTPException):
        _validated_base_url("https://user:pass@example.com/v1")

"""Per-user provider configuration and OpenAI-compatible connection verification.

This endpoint deliberately probes only the standard /models capability. A successful
probe proves authentication/model discovery, not image generation, editing, or video support.
"""
import ipaddress
import socket
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import ProviderConfig, User
from app.routers.auth import get_current_user
from app.services.provider_credentials import decrypt_api_key, encrypt_api_key

router = APIRouter()


class ProviderInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provider_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
    model_id: str = Field(min_length=1, max_length=256)
    base_url: str = Field(min_length=8, max_length=2048)
    api_key: str | None = Field(default=None, max_length=8192)


def _validated_base_url(value: str) -> str:
    parsed = urlparse(value.rstrip("/"))
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise HTTPException(status_code=422, detail="Base URL must be an HTTPS URL without credentials, query, or fragment")
    host = parsed.hostname.lower()
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)}
    except OSError as exc:
        raise HTTPException(status_code=422, detail="Provider host could not be resolved") from exc
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if not ip.is_global:
            raise HTTPException(status_code=422, detail="Private, loopback, and link-local provider hosts are not allowed")
    return value.rstrip("/")


async def _probe(base_url: str, api_key: str, model_id: str) -> dict:
    base = _validated_base_url(base_url)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=False) as client:
            response = await client.get(f"{base}/models", headers=headers)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail="Could not reach provider endpoint") from exc
    if response.status_code in (401, 403):
        raise HTTPException(status_code=401, detail="Provider rejected the API credentials")
    if response.status_code == 404:
        raise HTTPException(status_code=422, detail="This endpoint does not expose the OpenAI-compatible /models API")
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Provider returned HTTP {response.status_code}")
    try:
        payload = response.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="Provider returned invalid JSON") from exc
    models = payload.get("data", []) if isinstance(payload, dict) else []
    available = any(isinstance(item, dict) and item.get("id") == model_id for item in models)
    return {"reachable": True, "model_listed": available, "model_id": model_id,
            "message": "Connection verified" if available else "Authenticated; model was not listed by provider"}


@router.get("/")
async def get_provider_config(current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(ProviderConfig).where(ProviderConfig.user_id == current_user.id).order_by(ProviderConfig.created_at.desc()))
    return {"providers": [
        {"providerId": item.provider_id, "modelId": item.model_id, "baseUrl": item.base_url,
         "hasApiKey": bool(item.encrypted_api_key), "isActive": item.is_active}
        for item in result.scalars().all()
    ]}


@router.put("/")
async def save_provider_config(data: ProviderInput, current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    base_url = _validated_base_url(data.base_url)
    result = await db.execute(select(ProviderConfig).where(
        ProviderConfig.user_id == current_user.id, ProviderConfig.provider_id == data.provider_id
    ))
    item = result.scalar_one_or_none()
    if item is None and not data.api_key:
        raise HTTPException(status_code=422, detail="API key is required for a new provider")
    effective_key = data.api_key or decrypt_api_key(item.encrypted_api_key)
    verification = await _probe(base_url, effective_key, data.model_id.strip())
    if not verification["model_listed"]:
        raise HTTPException(status_code=422, detail="Provider connection succeeded, but the requested model was not listed; configuration was not saved")
    if item is None:
        item = ProviderConfig(user_id=current_user.id, provider_id=data.provider_id)
        db.add(item)
    item.model_id = data.model_id.strip()
    item.base_url = base_url
    await db.execute(ProviderConfig.__table__.update().where(ProviderConfig.user_id == current_user.id).values(is_active=False))
    item.is_active = True
    if data.api_key:
        item.encrypted_api_key = encrypt_api_key(data.api_key)
    await db.commit()
    return {"providerId": item.provider_id, "modelId": item.model_id, "baseUrl": item.base_url,
            "hasApiKey": bool(item.encrypted_api_key), "isActive": item.is_active}


@router.post("/test")
async def test_provider_config(data: ProviderInput, current_user: User = Depends(get_current_user)):
    if not data.api_key:
        raise HTTPException(status_code=422, detail="API key is required for connection testing")
    return await _probe(data.base_url, data.api_key, data.model_id.strip())

"""Encryption for user-supplied provider API credentials."""
import os

from cryptography.fernet import Fernet, InvalidToken
from fastapi import HTTPException


def _fernet() -> Fernet:
    key = os.getenv("PROVIDER_ENCRYPTION_KEY", "")
    if not key:
        raise RuntimeError("PROVIDER_ENCRYPTION_KEY must be configured before saving provider credentials")
    try:
        return Fernet(key.encode("ascii"))
    except (ValueError, UnicodeEncodeError) as exc:
        raise RuntimeError("PROVIDER_ENCRYPTION_KEY must be a valid Fernet key") from exc


def encrypt_api_key(value: str) -> str:
    return _fernet().encrypt(value.encode("utf-8")).decode("ascii")


def decrypt_api_key(value: str) -> str:
    try:
        return _fernet().decrypt(value.encode("ascii")).decode("utf-8")
    except (InvalidToken, ValueError, UnicodeDecodeError) as exc:
        raise HTTPException(status_code=500, detail="Provider credentials could not be decrypted") from exc

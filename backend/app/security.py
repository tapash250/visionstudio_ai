"""Password hashing and JWT helpers (bcrypt + PyJWT; replaces passlib and python-jose)."""
from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
import jwt

from app.config import jwt_secret

ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 15
REFRESH_TOKEN_EXPIRE_DAYS = 7
# bcrypt only uses the first 72 bytes; bcrypt>=5 raises on longer input, so cap explicitly.
_BCRYPT_MAX_BYTES = 72


def _pw_bytes(password: str) -> bytes:
    return password.encode("utf-8")[:_BCRYPT_MAX_BYTES]


def hash_password(password: str) -> str:
    return bcrypt.hashpw(_pw_bytes(password), bcrypt.gensalt()).decode("ascii")


def verify_password(plain: str, hashed: str | None) -> bool:
    if not hashed:
        return False
    try:
        return bcrypt.checkpw(_pw_bytes(plain), hashed.encode("ascii"))
    except ValueError:
        return False


def _encode(claims: dict[str, Any], lifetime: timedelta, token_type: str) -> str:
    now = datetime.now(UTC)
    payload = {**claims, "iat": now, "exp": now + lifetime, "type": token_type}
    return jwt.encode(payload, jwt_secret(), algorithm=ALGORITHM)


def create_access_token(data: dict[str, Any], expires_delta: timedelta | None = None) -> str:
    return _encode(data, expires_delta or timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES), "access")


def create_refresh_token(data: dict[str, Any]) -> str:
    return _encode(data, timedelta(days=REFRESH_TOKEN_EXPIRE_DAYS), "refresh")


def decode_token(token: str, expected_type: str) -> dict[str, Any] | None:
    """Return the payload if the token is valid and of the expected type, else None."""
    try:
        payload = jwt.decode(
            token, jwt_secret(), algorithms=[ALGORITHM], options={"require": ["exp", "sub"]}
        )
    except jwt.PyJWTError:
        return None
    if payload.get("type") != expected_type:
        return None
    return payload

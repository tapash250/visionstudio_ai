"""Central, validated runtime configuration.

Security-sensitive settings fail closed: the app refuses to start rather than fall back to an
insecure default.
"""
import os
from functools import lru_cache
from urllib.parse import urlparse

MIN_JWT_SECRET_LENGTH = 32
_WEAK_SECRETS = {
    "dev-secret",
    "change-me-in-production",
    "your-super-secret-jwt-key-change-in-production",
    "change-this-to-a-64-character-random-string-minimum",
}


class ConfigError(RuntimeError):
    pass


def _csv(name: str, default: str = "") -> list[str]:
    return [item.strip() for item in os.getenv(name, default).split(",") if item.strip()]


@lru_cache
def jwt_secret() -> str:
    secret = os.getenv("JWT_SECRET_KEY", "")
    if not secret or secret in _WEAK_SECRETS or len(secret) < MIN_JWT_SECRET_LENGTH:
        raise ConfigError(
            "JWT_SECRET_KEY must be set to a random value of at least "
            f"{MIN_JWT_SECRET_LENGTH} characters (e.g. `openssl rand -hex 32`)."
        )
    return secret


def is_production() -> bool:
    return os.getenv("ENV", "development").lower() == "production"


def cors_origins() -> list[str]:
    origins = _csv("CORS_ORIGINS", "http://localhost:3000")
    if "*" in origins:
        # Browsers reject "*" with credentials anyway; never reflect arbitrary origins.
        raise ConfigError("CORS_ORIGINS must list explicit origins; '*' is not allowed.")
    return origins


def allowed_hosts() -> list[str]:
    hosts = _csv("ALLOWED_HOSTS", "localhost,127.0.0.1,testserver")
    if "*" in hosts and is_production():
        raise ConfigError("ALLOWED_HOSTS must list explicit host names in production.")
    return hosts


def trusted_proxy_count() -> int:
    """How many reverse proxies in front of the API append to X-Forwarded-For."""
    return int(os.getenv("TRUSTED_PROXY_COUNT", "0"))


def allowed_media_hosts() -> set[str]:
    """Hosts that user-supplied media URLs (sourceUrl, maskUrl, audioUrl) may point at."""
    hosts = set(_csv("ALLOWED_MEDIA_HOSTS"))
    public_url = os.getenv("R2_PUBLIC_URL", "")
    if public_url:
        host = urlparse(public_url).hostname
        if host:
            hosts.add(host.lower())
    return {h.lower() for h in hosts}


def gpu_worker_token() -> str:
    return os.getenv("GPU_WORKER_TOKEN", "")

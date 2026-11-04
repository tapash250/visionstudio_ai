import time
import uuid

from starlette.requests import Request

from app.config import trusted_proxy_count


def client_ip(request: Request) -> str:
    """Best-effort client IP that the client cannot choose.

    Client-sent headers such as X-Device-Id are never used for rate limiting. X-Forwarded-For
    is only honoured for the hops added by TRUSTED_PROXY_COUNT reverse proxies we control.
    """
    hops = trusted_proxy_count()
    if hops > 0:
        forwarded = [p.strip() for p in request.headers.get("x-forwarded-for", "").split(",")]
        forwarded = [p for p in forwarded if p]
        if len(forwarded) >= hops:
            return forwarded[-hops]
    return request.client.host if request.client else "unknown"


class RateLimiter:
    def __init__(self, redis_client):
        self.redis = redis_client
        self.default_limit = 60  # requests per minute
        self.gen_limit = 5  # generation jobs per minute

    async def check(self, client_id: str, path: str) -> bool:
        limit = self.gen_limit if "/generate" in path else self.default_limit
        window = 60

        # Bucket by the segment after /api (e.g. "generate" for /api/generate/...). Short paths
        # such as /health have no such segment and share the first one (or "root").
        segments = [part for part in path.split("/") if part]
        if len(segments) > 1 and segments[0] == "api":
            bucket = segments[1]
        else:
            bucket = segments[0] if segments else "root"
        key = f"rate_limit:{client_id}:{bucket}"
        now = time.time()

        pipe = self.redis.pipeline()
        pipe.zremrangebyscore(key, 0, now - window)
        pipe.zcard(key)
        # Unique member so two requests in the same clock tick both count.
        pipe.zadd(key, {f"{now}:{uuid.uuid4().hex}": now})
        pipe.expire(key, window)
        _, current_count, _, _ = await pipe.execute()

        return current_count < limit

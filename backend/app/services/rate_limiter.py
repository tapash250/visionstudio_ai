import time
from typing import Optional

class RateLimiter:
    def __init__(self, redis_client):
        self.redis = redis_client
        self.default_limit = 60  # requests per minute
        self.anon_limit = 10
        self.gen_limit = 5  # generation jobs per minute

    async def check(self, client_id: str, path: str) -> bool:
        # Different limits for different endpoints
        if "/generate" in path:
            limit = self.gen_limit
            window = 60
        else:
            limit = self.default_limit
            window = 60

        # Bucket by the segment after /api (e.g. "generate" for /api/generate/...). Short paths
        # such as /health have no such segment and share the first one (or "root").
        segments = [part for part in path.split("/") if part]
        bucket = segments[1] if len(segments) > 1 and segments[0] == "api" else (segments[0] if segments else "root")
        key = f"rate_limit:{client_id}:{bucket}"
        now = time.time()

        # Clean old entries and count current
        pipe = self.redis.pipeline()
        pipe.zremrangebyscore(key, 0, now - window)
        pipe.zcard(key)
        pipe.zadd(key, {str(now): now})
        pipe.expire(key, window)
        _, current_count, _, _ = await pipe.execute()

        return current_count < limit

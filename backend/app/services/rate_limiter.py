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

        key = f"rate_limit:{client_id}:{path.split('/')[2]}"
        now = time.time()

        # Clean old entries and count current
        pipe = self.redis.pipeline()
        pipe.zremrangebyscore(key, 0, now - window)
        pipe.zcard(key)
        pipe.zadd(key, {str(now): now})
        pipe.expire(key, window)
        _, current_count, _, _ = await pipe.execute()

        return current_count < limit

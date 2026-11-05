import logging
import os
import time
from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import JSONResponse

from app import config
from app.routers import admin, animate, auth, edit, generate, projects, providers, styles, upload, websocket
from app.services.moderation import ModerationService
from app.services.rate_limiter import RateLimiter, client_ip

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Redis connection
redis_client = redis.Redis(
    host=os.getenv('REDIS_HOST', 'localhost'),
    port=int(os.getenv('REDIS_PORT', 6379)),
    decode_responses=True,
)

rate_limiter = RateLimiter(redis_client)
moderation = ModerationService()

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("VisionStudio API starting up...")
    config.jwt_secret()  # fail fast on a missing or weak signing key
    await redis_client.ping()
    logger.info("Redis connected")
    yield
    await redis_client.aclose()
    logger.info("VisionStudio API shutting down...")

app = FastAPI(
    title="VisionStudio AI API",
    description="Backend API for AI image generation, editing, and animation",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if os.getenv("ENV") != "production" else None,
    redoc_url="/redoc" if os.getenv("ENV") != "production" else None,
)

# Security middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=config.cors_origins(),
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)

app.add_middleware(TrustedHostMiddleware, allowed_hosts=config.allowed_hosts())


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    if config.is_production():
        response.headers.setdefault(
            "Strict-Transport-Security", "max-age=63072000; includeSubDomains"
        )
    return response

# Request timing & logging
@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    start = time.time()

    if not await rate_limiter.check(client_ip(request), request.url.path):
        return JSONResponse(
            status_code=429,
            content={"detail": "Rate limit exceeded. Please try again later."},
        )

    response = await call_next(request)
    duration = time.time() - start
    response.headers["X-Process-Time"] = str(duration)

    logger.info(
        "%s %s — %s — %.3fs", request.method, request.url.path, response.status_code, duration
    )
    return response

# Error handlers
@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail, "status": "error"},
    )

@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled exception on %s", request.url.path, exc_info=exc)
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error", "status": "error"},
    )

# Health check
@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "version": "1.0.0",
        "timestamp": time.time(),
    }

# Include routers
app.include_router(auth.router, prefix="/api/auth", tags=["Authentication"])
app.include_router(generate.router, prefix="/api/generate", tags=["Generation"])
app.include_router(providers.router, prefix="/api/providers", tags=["AI Providers"])
app.include_router(edit.router, prefix="/api/edit", tags=["Editing"])
app.include_router(animate.router, prefix="/api/animate", tags=["Animation"])
app.include_router(projects.router, prefix="/api/projects", tags=["Projects"])
app.include_router(upload.router, prefix="/api/upload", tags=["Upload"])
app.include_router(styles.router, prefix="/api/styles", tags=["Styles"])
app.include_router(admin.router, prefix="/api/admin", tags=["Admin"])
app.include_router(websocket.router, prefix="", tags=["WebSocket"])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=os.getenv("HOST", "127.0.0.1"), port=8000)

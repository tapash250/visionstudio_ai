from fastapi import FastAPI, Request, HTTPException, Depends, UploadFile, File, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import JSONResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from contextlib import asynccontextmanager
import redis.asyncio as redis
import os
import time
import logging

from app.routers import auth, generate, edit, animate, projects, upload, admin, styles, websocket
from app.services.rate_limiter import RateLimiter
from app.services.moderation import ModerationService

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
    await redis_client.ping()
    logger.info("Redis connected")
    yield
    await redis_client.close()
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
    allow_origins=os.getenv("CORS_ORIGINS", "*").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(TrustedHostMiddleware, allowed_hosts=["*"])

# Request timing & logging
@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    start = time.time()

    client_id = request.headers.get("x-device-id") or request.client.host
    if not await rate_limiter.check(client_id, request.url.path):
        return JSONResponse(
            status_code=429,
            content={"detail": "Rate limit exceeded. Please try again later."},
        )

    response = await call_next(request)
    duration = time.time() - start
    response.headers["X-Process-Time"] = str(duration)

    logger.info(f"{request.method} {request.url.path} — {response.status_code} — {duration:.3f}s")
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
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
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
app.include_router(edit.router, prefix="/api/edit", tags=["Editing"])
app.include_router(animate.router, prefix="/api/animate", tags=["Animation"])
app.include_router(projects.router, prefix="/api/projects", tags=["Projects"])
app.include_router(upload.router, prefix="/api/upload", tags=["Upload"])
app.include_router(styles.router, prefix="/api/styles", tags=["Styles"])
app.include_router(admin.router, prefix="/api/admin", tags=["Admin"])
app.include_router(websocket.router, prefix="", tags=["WebSocket"])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)

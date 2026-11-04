"""Shared plumbing for the Celery job workers."""
import asyncio
import logging
import os
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.config import gpu_worker_token
from app.models import Project

logger = logging.getLogger(__name__)

GPU_WORKER_URL = os.getenv("GPU_WORKER_URL", "http://localhost:8001")
# Shown to users; the real exception is only logged.
PUBLIC_FAILURE_MESSAGE = "Processing failed. Please try again."


class RetryableJobError(Exception):
    """The GPU worker could not be reached or returned a 5xx; the job may be retried."""


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def run_job(coro_factory: Callable[[async_sessionmaker[AsyncSession]], Awaitable[None]]) -> None:
    """Run one async job on a fresh event loop with its own engine.

    asyncio.run() creates a new loop per task, and pooled asyncpg connections are bound to the
    loop that created them, so each task gets a NullPool engine that is disposed afterwards.
    """
    from app.database import DATABASE_URL

    async def _main() -> None:
        engine = create_async_engine(DATABASE_URL, poolclass=NullPool)
        try:
            await coro_factory(async_sessionmaker(engine, expire_on_commit=False))
        finally:
            await engine.dispose()

    asyncio.run(_main())


async def call_gpu_worker(path: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    headers = {}
    token = gpu_worker_token()
    if token:
        headers["X-Worker-Token"] = token
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(f"{GPU_WORKER_URL}{path}", json=payload, headers=headers)
    except httpx.TransportError as exc:
        raise RetryableJobError(str(exc)) from exc
    if response.status_code >= 500:
        raise RetryableJobError(f"GPU worker returned {response.status_code}")
    response.raise_for_status()
    return response.json()


async def mark_project(db: AsyncSession, project_id: str, status: str, result_url: str | None):
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()
    if project is not None:
        project.status = status
        if result_url is not None:
            project.result_url = result_url


async def process_job(
    task: Any,
    session_factory: async_sessionmaker[AsyncSession],
    model: Any,
    job_id: str,
    work: Callable[[Any], Awaitable[str | None]],
) -> None:
    """Load the job, run ``work(job)``, persist the outcome.

    ``work`` returns the project's result URL. A job is only marked FAILED once it will not be
    retried any more; transient failures are left PROCESSING and re-raised for Celery to retry.
    """
    async with session_factory() as db:
        job = (await db.execute(select(model).where(model.id == job_id))).scalar_one_or_none()
        if job is None or job.status in ("COMPLETED", "CANCELLED"):
            return

        job.status = "PROCESSING"
        job.started_at = job.started_at or utcnow()
        await db.commit()

        try:
            result_url = await work(job)
        except RetryableJobError as exc:
            retries_left = task.request.retries < task.max_retries
            logger.warning("Job %s attempt failed (retry=%s): %s", job_id, retries_left, exc)
            if retries_left:
                raise
            await _fail(db, job)
            return
        except Exception:
            logger.exception("Job %s failed permanently", job_id)
            await _fail(db, job)
            return

        job.status = "COMPLETED"
        job.completed_at = utcnow()
        job.error = None
        await mark_project(db, job.project_id, "COMPLETED", result_url)
        await db.commit()


async def _fail(db: AsyncSession, job: Any) -> None:
    job.status = "FAILED"
    job.error = PUBLIC_FAILURE_MESSAGE
    await mark_project(db, job.project_id, "FAILED", None)
    await db.commit()

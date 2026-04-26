from fastapi import APIRouter, Depends, BackgroundTasks, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from celery import Celery
import os
import uuid

from app.database import get_db
from app.models import User, GenerationJob, Project
from app.routers.auth import get_current_user
from app.services.storage import StorageService
from app.services.moderation import ModerationService

router = APIRouter()
storage = StorageService()
moderation = ModerationService()

celery_app = Celery("visionstudio", broker=os.getenv("REDIS_URL", "redis://localhost:6379/0"))

@router.post("/")
async def create_generation(
    data: dict,
    background_tasks: BackgroundTasks,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    prompt = data.get("prompt", "").strip()
    if not prompt:
        raise HTTPException(status_code=400, detail="Prompt is required")

    # Moderation check
    mod_result = await moderation.check_prompt(prompt)
    if mod_result.blocked:
        raise HTTPException(status_code=400, detail=f"Content blocked: {mod_result.reason}")

    # Create project
    project = Project(
        user_id=current_user.id,
        title=prompt[:50] + "..." if len(prompt) > 50 else prompt,
        type="GENERATION",
        status="QUEUED",
    )
    db.add(project)
    await db.flush()

    # Create generation job
    job = GenerationJob(
        project_id=project.id,
        user_id=current_user.id,
        prompt=prompt,
        negative_prompt=data.get("negativePrompt", ""),
        model=data.get("model", "flux-dev"),
        style=data.get("style"),
        aspect_ratio=data.get("aspectRatio", "9:16"),
        seed=data.get("seed"),
        steps=data.get("steps", 30),
        cfg_scale=data.get("cfgScale", 7.5),
        batch_size=data.get("batchSize", 1),
        status="QUEUED",
        priority=5,
    )
    db.add(job)
    await db.commit()

    # Queue GPU worker
    celery_app.send_task("tasks.generate_image", args=[job.id], queue="generation")

    return {"jobId": job.id, "status": "QUEUED"}

@router.get("/{job_id}")
async def get_generation_status(
    job_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    from sqlalchemy import select
    result = await db.execute(
        select(GenerationJob).where(
            GenerationJob.id == job_id,
            GenerationJob.user_id == current_user.id
        )
    )
    job = result.scalar_one_or_none()
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")

    return {
        "status": job.status,
        "resultUrls": job.result_urls,
        "error": job.error,
        "progress": job.progress if hasattr(job, "progress") else None,
    }

@router.post("/enhance")
async def enhance_prompt(
    data: dict,
    current_user: User = Depends(get_current_user),
):
    prompt = data.get("prompt", "").strip()
    if not prompt:
        raise HTTPException(status_code=400, detail="Prompt required")

    # Call LLM for prompt enhancement
    # In production, this calls an LLM API (Claude, GPT-4, etc.)
    enhanced = f"masterpiece, best quality, highly detailed, {prompt}, professional photography, 8k uhd"

    return {"enhanced": enhanced}

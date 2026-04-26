from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
import os

from app.database import get_db
from app.models import User, AnimationJob, Project
from app.routers.auth import get_current_user
from app.services.storage import StorageService

router = APIRouter()
storage = StorageService()

@router.post("/")
async def create_animation(
    data: dict,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    source_url = data.get("sourceUrl")
    animation_type = data.get("animationType")

    if not source_url or not animation_type:
        raise HTTPException(status_code=400, detail="Source and animation type required")

    project = Project(
        user_id=current_user.id,
        title=f"Animation: {animation_type}",
        type="ANIMATION",
        status="QUEUED",
        source_url=source_url,
    )
    db.add(project)
    await db.flush()

    job = AnimationJob(
        project_id=project.id,
        user_id=current_user.id,
        source_url=source_url,
        source_type=data.get("sourceType", "image"),
        animation_type=animation_type,
        duration=data.get("duration", 3),
        fps=data.get("fps", 24),
        audio_url=data.get("audioUrl"),
        format=data.get("format", "mp4"),
        status="QUEUED",
    )
    db.add(job)
    await db.commit()

    from celery import Celery
    celery_app = Celery("visionstudio", broker=os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    celery_app.send_task("tasks.animate_image", args=[job.id], queue="animation")

    return {"jobId": job.id}

@router.get("/{job_id}")
async def get_animation_status(
    job_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(AnimationJob).where(AnimationJob.id == job_id, AnimationJob.user_id == current_user.id)
    )
    job = result.scalar_one_or_none()
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")

    return {
        "status": job.status,
        "resultUrl": job.result_url,
        "previewUrl": job.preview_url,
        "error": job.error,
    }

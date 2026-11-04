from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import AnimationJob, Project, User
from app.routers.auth import get_current_user
from app.schemas import AnimateRequest
from app.workers.celery_app import ANIMATE_TASK, enqueue

router = APIRouter()

@router.post("/")
async def create_animation(
    data: AnimateRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    source_url = data.source_url
    animation_type = data.animation_type

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
        source_type=data.source_type,
        animation_type=animation_type,
        duration=data.duration,
        fps=data.fps,
        audio_url=data.audio_url,
        format=data.format,
        status="QUEUED",
    )
    db.add(job)
    await db.commit()

    enqueue(ANIMATE_TASK, job.id)

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

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
import os

from app.database import get_db
from app.models import User, EditJob, Project
from app.routers.auth import get_current_user
from app.services.storage import StorageService
from app.services.moderation import ModerationService

router = APIRouter()
storage = StorageService()
moderation = ModerationService()

@router.post("/")
async def create_edit(
    data: dict,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    source_url = data.get("sourceUrl")
    operation = data.get("operation")

    if not source_url or not operation:
        raise HTTPException(status_code=400, detail="Source and operation required")

    # Create project
    project = Project(
        user_id=current_user.id,
        title=f"Edit: {operation}",
        type="EDIT",
        status="QUEUED",
        source_url=source_url,
    )
    db.add(project)
    await db.flush()

    job = EditJob(
        project_id=project.id,
        user_id=current_user.id,
        source_url=source_url,
        operation=operation,
        prompt=data.get("prompt"),
        mask_url=data.get("maskUrl"),
        strength=data.get("strength", 0.75),
        preserve_face=data.get("preserveFace", True),
        status="QUEUED",
    )
    db.add(job)
    await db.commit()

    # Queue worker
    from celery import Celery
    celery_app = Celery("visionstudio", broker=os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    celery_app.send_task("tasks.edit_image", args=[job.id], queue="editing")

    return {"jobId": job.id}

@router.get("/{job_id}")
async def get_edit_status(
    job_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(EditJob).where(EditJob.id == job_id, EditJob.user_id == current_user.id)
    )
    job = result.scalar_one_or_none()
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")

    return {
        "status": job.status,
        "resultUrl": job.result_url,
        "error": job.error,
    }

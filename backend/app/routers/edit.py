from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import EditJob, Project, User
from app.routers.auth import get_current_user
from app.schemas import EditRequest
from app.services.moderation import ModerationService
from app.workers.celery_app import EDIT_TASK, enqueue

router = APIRouter()
moderation = ModerationService()

@router.post("/")
async def create_edit(
    data: EditRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    source_url = data.source_url
    operation = data.operation

    if data.prompt:
        mod_result = await moderation.check_prompt(data.prompt)
        if mod_result.blocked:
            raise HTTPException(status_code=400, detail=f"Content blocked: {mod_result.reason}")

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
        prompt=data.prompt,
        mask_url=data.mask_url,
        strength=data.strength,
        preserve_face=data.preserve_face,
        status="QUEUED",
    )
    db.add(job)
    await db.commit()

    enqueue(EDIT_TASK, job.id)

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

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import GenerationJob, Project, User
from app.routers.auth import get_current_user
from app.schemas import EnhanceRequest, GenerateRequest
from app.services.moderation import ModerationService
from app.workers.celery_app import GENERATE_TASK, enqueue

router = APIRouter()
moderation = ModerationService()


@router.post("/")
async def create_generation(
    data: GenerateRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    prompt = data.prompt

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
        negative_prompt=data.negative_prompt,
        model=data.model,
        style=data.style,
        aspect_ratio=data.aspect_ratio,
        seed=data.seed,
        steps=data.steps,
        cfg_scale=data.cfg_scale,
        batch_size=data.batch_size,
        status="QUEUED",
        priority=5,
    )
    db.add(job)
    await db.commit()

    # Queue GPU worker
    enqueue(GENERATE_TASK, job.id)

    return {"jobId": job.id, "status": "QUEUED"}

@router.get("/{job_id}")
async def get_generation_status(
    job_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
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
    data: EnhanceRequest,
    current_user: User = Depends(get_current_user),
):
    prompt = data.prompt.strip()
    if not prompt:
        raise HTTPException(status_code=400, detail="Prompt required")

    # Call LLM for prompt enhancement
    # In production, this calls an LLM API (Claude, GPT-4, etc.)
    enhanced = f"masterpiece, best quality, highly detailed, {prompt}, professional photography, 8k uhd"

    return {"enhanced": enhanced}

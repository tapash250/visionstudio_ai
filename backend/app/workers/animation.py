from datetime import datetime
from celery import shared_task
from sqlalchemy import select
from app.database import async_session
from app.models import AnimationJob, Project
import httpx
import os

GPU_WORKER_URL = os.getenv("GPU_WORKER_URL", "http://localhost:8001")

@shared_task(bind=True, max_retries=2)
def animate_image(self, job_id: str):
    import asyncio
    asyncio.run(_animate_image_async(job_id))

async def _animate_image_async(job_id: str):
    async with async_session() as db:
        result = await db.execute(select(AnimationJob).where(AnimationJob.id == job_id))
        job = result.scalar_one_or_none()
        if not job:
            return

        job.status = "PROCESSING"
        job.started_at = datetime.utcnow()
        await db.commit()

        try:
            async with httpx.AsyncClient(timeout=600.0) as client:
                response = await client.post(
                    f"{GPU_WORKER_URL}/animate",
                    json={
                        "source_url": job.source_url,
                        "animation_type": job.animation_type,
                        "duration": job.duration,
                        "fps": job.fps,
                        "audio_url": job.audio_url,
                        "format": job.format,
                    },
                )
                response.raise_for_status()
                data = response.json()

            job.result_url = data.get("video")
            job.preview_url = data.get("preview")
            job.status = "COMPLETED"
            job.completed_at = datetime.utcnow()

            project_result = await db.execute(select(Project).where(Project.id == job.project_id))
            project = project_result.scalar_one()
            project.status = "COMPLETED"
            project.result_url = job.result_url

            await db.commit()
        except Exception as e:
            job.status = "FAILED"
            job.error = str(e)
            await db.commit()
            raise self.retry(exc=e, countdown=60)

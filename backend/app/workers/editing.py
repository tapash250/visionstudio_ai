from datetime import datetime
from celery import shared_task
from sqlalchemy import select
from app.database import async_session
from app.models import EditJob, Project
import httpx
import os

GPU_WORKER_URL = os.getenv("GPU_WORKER_URL", "http://localhost:8001")

@shared_task(bind=True, max_retries=2)
def edit_image(self, job_id: str):
    import asyncio
    asyncio.run(_edit_image_async(job_id))

async def _edit_image_async(job_id: str):
    async with async_session() as db:
        result = await db.execute(select(EditJob).where(EditJob.id == job_id))
        job = result.scalar_one_or_none()
        if not job:
            return

        job.status = "PROCESSING"
        job.started_at = datetime.utcnow()
        await db.commit()

        try:
            async with httpx.AsyncClient(timeout=300.0) as client:
                response = await client.post(
                    f"{GPU_WORKER_URL}/edit",
                    json={
                        "source_url": job.source_url,
                        "operation": job.operation,
                        "prompt": job.prompt,
                        "mask_url": job.mask_url,
                        "strength": job.strength,
                        "preserve_face": job.preserve_face,
                    },
                )
                response.raise_for_status()
                data = response.json()

            job.result_url = data.get("image")
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

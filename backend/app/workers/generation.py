from datetime import datetime
from celery import shared_task
from sqlalchemy import select
from app.database import async_session
from app.models import GenerationJob, Project
import httpx
import os

GPU_WORKER_URL = os.getenv("GPU_WORKER_URL", "http://localhost:8001")

@shared_task(bind=True, max_retries=3)
def generate_image(self, job_id: str):
    import asyncio
    asyncio.run(_generate_image_async(job_id))

async def _generate_image_async(job_id: str):
    async with async_session() as db:
        result = await db.execute(select(GenerationJob).where(GenerationJob.id == job_id))
        job = result.scalar_one_or_none()
        if not job:
            return

        job.status = "PROCESSING"
        job.started_at = datetime.utcnow()
        await db.commit()

        try:
            async with httpx.AsyncClient(timeout=300.0) as client:
                response = await client.post(
                    f"{GPU_WORKER_URL}/generate",
                    json={
                        "prompt": job.prompt,
                        "negative_prompt": job.negative_prompt,
                        "model": job.model,
                        "width": get_width(job.aspect_ratio),
                        "height": get_height(job.aspect_ratio),
                        "seed": job.seed,
                        "steps": job.steps,
                        "cfg_scale": job.cfg_scale,
                        "batch_size": job.batch_size,
                    },
                )
                response.raise_for_status()
                data = response.json()

            job.result_urls = data.get("images", [])
            job.status = "COMPLETED"
            job.completed_at = datetime.utcnow()

            project_result = await db.execute(select(Project).where(Project.id == job.project_id))
            project = project_result.scalar_one()
            project.status = "COMPLETED"
            project.result_url = job.result_urls[0] if job.result_urls else None

            await db.commit()

        except Exception as e:
            job.status = "FAILED"
            job.error = str(e)
            await db.commit()
            raise self.retry(exc=e, countdown=60)

def get_width(ratio: str) -> int:
    map = {"1:1": 1024, "16:9": 1024, "9:16": 576, "4:5": 832, "3:2": 1024}
    return map.get(ratio, 576)

def get_height(ratio: str) -> int:
    map = {"1:1": 1024, "16:9": 576, "9:16": 1024, "4:5": 1040, "3:2": 680}
    return map.get(ratio, 1024)
 1024, "16:9": 576, "9:16": 1024, "4:5": 1040, "3:2": 680}
    return map.get(ratio, 1024)

from datetime import datetime

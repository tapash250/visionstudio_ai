from app.models import EditJob
from app.workers.celery_app import EDIT_TASK, celery_app
from app.workers.common import RetryableJobError, call_gpu_worker, process_job, run_job


@celery_app.task(
    name=EDIT_TASK,
    bind=True,
    max_retries=2,
    autoretry_for=(RetryableJobError,),
    retry_backoff=30,
    retry_jitter=True,
)
def edit_image(self, job_id: str) -> None:
    async def work(job: EditJob) -> str | None:
        data = await call_gpu_worker(
            "/edit",
            {
                "source_url": job.source_url,
                "operation": job.operation,
                "prompt": job.prompt,
                "mask_url": job.mask_url,
                "strength": job.strength,
                "preserve_face": job.preserve_face,
            },
            timeout=300.0,
        )
        job.result_url = data.get("image")
        return job.result_url

    run_job(lambda sessions: process_job(self, sessions, EditJob, job_id, work))

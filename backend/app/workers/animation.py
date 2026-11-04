from app.models import AnimationJob
from app.workers.celery_app import ANIMATE_TASK, celery_app
from app.workers.common import RetryableJobError, call_gpu_worker, process_job, run_job


@celery_app.task(
    name=ANIMATE_TASK,
    bind=True,
    max_retries=2,
    autoretry_for=(RetryableJobError,),
    retry_backoff=30,
    retry_jitter=True,
)
def animate_image(self, job_id: str) -> None:
    async def work(job: AnimationJob) -> str | None:
        data = await call_gpu_worker(
            "/animate",
            {
                "source_url": job.source_url,
                "animation_type": job.animation_type,
                "duration": job.duration,
                "fps": job.fps,
                "audio_url": job.audio_url,
                "format": job.format,
            },
            timeout=600.0,
        )
        job.result_url = data.get("video")
        job.preview_url = data.get("preview")
        return job.result_url

    run_job(lambda sessions: process_job(self, sessions, AnimationJob, job_id, work))

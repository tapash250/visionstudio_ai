from app.models import GenerationJob
from app.workers.celery_app import GENERATE_TASK, celery_app
from app.workers.common import RetryableJobError, call_gpu_worker, process_job, run_job

_WIDTH = {"1:1": 1024, "16:9": 1024, "9:16": 576, "4:5": 832, "3:2": 1024}
_HEIGHT = {"1:1": 1024, "16:9": 576, "9:16": 1024, "4:5": 1040, "3:2": 680}


def get_width(ratio: str) -> int:
    return _WIDTH.get(ratio, 576)


def get_height(ratio: str) -> int:
    return _HEIGHT.get(ratio, 1024)


@celery_app.task(
    name=GENERATE_TASK,
    bind=True,
    max_retries=3,
    autoretry_for=(RetryableJobError,),
    retry_backoff=30,
    retry_jitter=True,
)
def generate_image(self, job_id: str) -> None:
    async def work(job: GenerationJob) -> str | None:
        data = await call_gpu_worker(
            "/generate",
            {
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
            timeout=300.0,
        )
        job.result_urls = data.get("images", [])
        return job.result_urls[0] if job.result_urls else None

    run_job(lambda sessions: process_job(self, sessions, GenerationJob, job_id, work))

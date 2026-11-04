import os

from celery import Celery

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")

# Task names are part of the wire contract between the API (which only calls send_task by
# name) and the workers. Keep them here so both sides import the same constants.
GENERATE_TASK = "tasks.generate_image"
EDIT_TASK = "tasks.edit_image"
ANIMATE_TASK = "tasks.animate_image"

celery_app = Celery(
    "visionstudio",
    broker=REDIS_URL,
    backend=REDIS_URL,
    include=["app.workers.generation", "app.workers.editing", "app.workers.animation"],
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=600,  # 10 minutes max
    worker_prefetch_multiplier=1,
    broker_connection_retry_on_startup=True,
    task_routes={
        GENERATE_TASK: {"queue": "generation"},
        EDIT_TASK: {"queue": "editing"},
        ANIMATE_TASK: {"queue": "animation"},
    },
)


def enqueue(task_name: str, job_id: str) -> None:
    """Queue a job by its registered task name (routing comes from task_routes)."""
    celery_app.send_task(task_name, args=[job_id])

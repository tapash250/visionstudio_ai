import asyncio
import contextlib
import json
import logging

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import AnimationJob, EditJob, GenerationJob, User
from app.routers.auth import get_current_user
from app.schemas import PushRegisterRequest
from app.security import decode_token

logger = logging.getLogger(__name__)
JOB_MODELS = {"generation": GenerationJob, "edit": EditJob, "animation": AnimationJob}
MAX_SUBSCRIPTIONS_PER_SOCKET = 20
MAX_POLL_SECONDS = 15 * 60


def _device_model():
    # There is no Device table in app.models yet; answer 501 instead of crashing with a 500.
    import app.models as models

    device = getattr(models, "Device", None)
    if device is None:
        raise HTTPException(status_code=501, detail="Push notifications are not available yet")
    return device

router = APIRouter()

class ConnectionManager:
    def __init__(self):
        self.active_connections: dict[str, list[WebSocket]] = {}

    async def connect(self, user_id: str, websocket: WebSocket):
        await websocket.accept()
        if user_id not in self.active_connections:
            self.active_connections[user_id] = []
        self.active_connections[user_id].append(websocket)

    def disconnect(self, user_id: str, websocket: WebSocket):
        if user_id in self.active_connections:
            self.active_connections[user_id].remove(websocket)
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]

    async def send_personal_message(self, user_id: str, message: dict):
        if user_id in self.active_connections:
            disconnected = []
            for connection in self.active_connections[user_id]:
                try:
                    await connection.send_json(message)
                except Exception:
                    disconnected.append(connection)
            for conn in disconnected:
                self.disconnect(user_id, conn)

    async def broadcast(self, message: dict):
        for connections in self.active_connections.values():
            for connection in connections:
                with contextlib.suppress(Exception):
                    await connection.send_json(message)

manager = ConnectionManager()

@router.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    payload = decode_token(token, "access")
    if payload is None:
        await websocket.close(code=4001, reason="Invalid token")
        return
    user_id = payload["sub"]

    await manager.connect(user_id, websocket)
    subscriptions: set[str] = set()

    try:
        while True:
            data = await websocket.receive_text()
            try:
                message = json.loads(data)
            except json.JSONDecodeError:
                continue
            if not isinstance(message, dict):
                continue

            if message.get("type") == "subscribe_job":
                job_id = message.get("jobId")
                job_type = message.get("jobType", "generation")
                if (
                    not isinstance(job_id, str)
                    or job_type not in JOB_MODELS
                    or job_id in subscriptions
                    or len(subscriptions) >= MAX_SUBSCRIPTIONS_PER_SOCKET
                ):
                    continue
                subscriptions.add(job_id)
                asyncio.create_task(poll_job_status(user_id, job_id, job_type))

            elif message.get("type") == "ping":
                await websocket.send_json({"type": "pong"})

    except WebSocketDisconnect:
        manager.disconnect(user_id, websocket)
    except Exception:
        manager.disconnect(user_id, websocket)

async def poll_job_status(user_id: str, job_id: str, job_type: str):
    from app.database import async_session

    model = JOB_MODELS.get(job_type)
    if model is None:
        return

    loop = asyncio.get_running_loop()
    deadline = loop.time() + MAX_POLL_SECONDS
    async with async_session() as db:
        while loop.time() < deadline and user_id in manager.active_connections:
            try:
                # Scoped to the caller: a user can only watch their own jobs.
                result = await db.execute(
                    select(model).where(model.id == job_id, model.user_id == user_id)
                )
                job = result.scalar_one_or_none()
                if not job:
                    break

                await manager.send_personal_message(user_id, {
                    "type": "job_update",
                    "jobId": job_id,
                    "jobType": job_type,
                    "status": job.status,
                    "resultUrls": getattr(job, "result_urls", None),
                    "resultUrl": getattr(job, "result_url", None),
                    "previewUrl": getattr(job, "preview_url", None),
                    "error": job.error,
                })

                if job.status in ["COMPLETED", "FAILED", "CANCELLED"]:
                    break

                db.expire_all()
                await asyncio.sleep(2)

            except Exception:
                logger.exception("Error polling job %s", job_id)
                break


@router.post("/push/register")
async def register_push(
    data: PushRegisterRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    Device = _device_model()  # noqa: N806
    result = await db.execute(select(Device).where(Device.fingerprint == data.fingerprint))
    device = result.scalar_one_or_none()

    if device and device.user_id != current_user.id:
        # Never let one account take over another account's device registration.
        raise HTTPException(status_code=409, detail="Device registered to another account")

    push_token = data.push_token
    if device:
        device.push_token = push_token
        device.push_enabled = True
    else:
        device = Device(
            user_id=current_user.id,
            fingerprint=data.fingerprint,
            push_token=push_token,
            push_enabled=True,
            device_name=data.device_name,
            device_type=data.device_type,
            os=data.os,
            browser=data.browser,
        )
        db.add(device)

    await db.commit()
    return {"message": "Push token registered"}

@router.post("/push/send")
async def send_push(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    Device = _device_model()  # noqa: N806
    result = await db.execute(
        select(Device).where(
            Device.user_id == current_user.id,
            Device.push_enabled.is_(True)
        )
    )
    devices = result.scalars().all()

    return {
        "sent": len(devices),
        "devices": [{"id": d.id, "name": d.device_name} for d in devices],
    }

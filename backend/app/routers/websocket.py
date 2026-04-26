from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
import json
import asyncio

from app.database import get_db
from app.models import User, GenerationJob, EditJob, AnimationJob
from app.routers.auth import get_current_user

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
                except:
                    disconnected.append(connection)
            for conn in disconnected:
                self.disconnect(user_id, conn)

    async def broadcast(self, message: dict):
        for user_id, connections in self.active_connections.items():
            for connection in connections:
                try:
                    await connection.send_json(message)
                except:
                    pass

manager = ConnectionManager()

@router.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    try:
        from jose import jwt
        import os
        payload = jwt.decode(token, os.getenv("JWT_SECRET_KEY", "dev-secret"), algorithms=["HS256"])
        user_id = payload.get("sub")
    except Exception:
        await websocket.close(code=4001, reason="Invalid token")
        return

    await manager.connect(user_id, websocket)

    try:
        while True:
            data = await websocket.receive_text()
            message = json.loads(data)

            if message.get("type") == "subscribe_job":
                job_id = message.get("jobId")
                job_type = message.get("jobType", "generation")
                asyncio.create_task(poll_job_status(user_id, job_id, job_type))

            elif message.get("type") == "ping":
                await websocket.send_json({"type": "pong"})

    except WebSocketDisconnect:
        manager.disconnect(user_id, websocket)
    except Exception:
        manager.disconnect(user_id, websocket)

async def poll_job_status(user_id: str, job_id: str, job_type: str):
    from app.database import async_session

    async with async_session() as db:
        while True:
            try:
                if job_type == "generation":
                    result = await db.execute(select(GenerationJob).where(GenerationJob.id == job_id))
                    job = result.scalar_one_or_none()
                elif job_type == "edit":
                    result = await db.execute(select(EditJob).where(EditJob.id == job_id))
                    job = result.scalar_one_or_none()
                elif job_type == "animation":
                    result = await db.execute(select(AnimationJob).where(AnimationJob.id == job_id))
                    job = result.scalar_one_or_none()
                else:
                    break

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

                await asyncio.sleep(2)

            except Exception as e:
                print(f"Error polling job {job_id}: {e}")
                break

@router.post("/push/register")
async def register_push(
    data: dict,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    from app.models import Device

    fingerprint = data.get("fingerprint")
    push_token = data.get("pushToken")

    if not fingerprint or not push_token:
        raise HTTPException(status_code=400, detail="Fingerprint and push token required")

    result = await db.execute(select(Device).where(Device.fingerprint == fingerprint))
    device = result.scalar_one_or_none()

    if device:
        device.push_token = push_token
        device.push_enabled = True
    else:
        device = Device(
            user_id=current_user.id,
            fingerprint=fingerprint,
            push_token=push_token,
            push_enabled=True,
            device_name=data.get("deviceName"),
            device_type=data.get("deviceType"),
            os=data.get("os"),
            browser=data.get("browser"),
        )
        db.add(device)

    await db.commit()
    return {"message": "Push token registered"}

@router.post("/push/send")
async def send_push(
    data: dict,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    from app.models import Device

    title = data.get("title", "VisionStudio AI")
    body = data.get("body", "")
    url = data.get("url", "/")

    result = await db.execute(
        select(Device).where(
            Device.user_id == current_user.id,
            Device.push_enabled == True
        )
    )
    devices = result.scalars().all()

    return {
        "sent": len(devices),
        "devices": [{"id": d.id, "name": d.device_name} for d in devices],
    }

from fastapi import APIRouter, Depends, UploadFile, File, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
import uuid
import os

from app.database import get_db
from app.models import User
from app.routers.auth import get_current_user
from app.services.storage import StorageService

router = APIRouter()
storage = StorageService()
MAX_SIZE_MB = int(os.getenv("MAX_UPLOAD_SIZE", "50"))

@router.post("/")
async def upload_file(
    file: UploadFile = File(...),
    current_user: User = Depends(get_current_user),
):
    # Validate file type
    allowed_types = {"image/jpeg", "image/png", "image/webp", "image/heic", "video/mp4", "audio/mpeg"}
    content_type = file.content_type or ""

    if not any(content_type.startswith(t.split("/")[0]) for t in allowed_types):
        raise HTTPException(status_code=400, detail="Invalid file type")

    # Validate size
    contents = await file.read()
    if len(contents) > MAX_SIZE_MB * 1024 * 1024:
        raise HTTPException(status_code=400, detail=f"File too large. Max {MAX_SIZE_MB}MB")

    # Generate unique key
    ext = file.filename.split(".")[-1] if "." in file.filename else "bin"
    key = f"uploads/{current_user.id}/{uuid.uuid4()}.{ext}"

    # Upload to storage
    url = await storage.upload(key, contents, content_type)

    return {"url": url, "key": key, "filename": file.filename}

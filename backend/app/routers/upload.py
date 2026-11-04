import os
import uuid

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from app.models import User
from app.routers.auth import get_current_user
from app.services.storage import StorageService

router = APIRouter()
storage = StorageService()
MAX_SIZE_MB = int(os.getenv("MAX_UPLOAD_SIZE", "50"))
_CHUNK = 1024 * 1024

# Exact allowlist of content types, keyed to the extension we store them under. The type is
# detected from the file's bytes; the client's Content-Type and filename are never trusted.
# SVG/HTML and other active content are deliberately excluded.
ALLOWED_TYPES: dict[str, str] = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/heic": "heic",
    "image/heif": "heif",
    "video/mp4": "mp4",
    "audio/mpeg": "mp3",
}


async def read_limited(file: UploadFile, max_bytes: int) -> bytes:
    """Read at most max_bytes, rejecting the upload as soon as it grows past the limit."""
    chunks: list[bytes] = []
    total = 0
    while chunk := await file.read(_CHUNK):
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(status_code=413, detail=f"File too large. Max {MAX_SIZE_MB}MB")
        chunks.append(chunk)
    return b"".join(chunks)


_HEIF_BRANDS = {b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx"}
_HEIF_GENERIC_BRANDS = {b"mif1", b"msf1"}
_MP4_BRANDS = {b"isom", b"iso2", b"iso4", b"iso5", b"iso6", b"mp41", b"mp42", b"avc1", b"dash", b"M4V "}


def detect_type(contents: bytes) -> str | None:
    """Identify the file from its magic bytes (no libmagic dependency needed)."""
    head = contents[:16]
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand in _HEIF_BRANDS:
            return "image/heic"
        if brand in _HEIF_GENERIC_BRANDS:
            return "image/heif"
        if brand in _MP4_BRANDS:
            return "video/mp4"
        return None
    if head.startswith(b"ID3") or (len(head) >= 2 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0):
        return "audio/mpeg"
    return None


@router.post("/")
async def upload_file(
    file: UploadFile = File(...),
    current_user: User = Depends(get_current_user),
):
    if file.size is not None and file.size > MAX_SIZE_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"File too large. Max {MAX_SIZE_MB}MB")

    contents = await read_limited(file, MAX_SIZE_MB * 1024 * 1024)
    if not contents:
        raise HTTPException(status_code=400, detail="Empty file")

    content_type = detect_type(contents)
    ext = ALLOWED_TYPES.get(content_type) if content_type else None
    if content_type is None or ext is None:
        raise HTTPException(status_code=415, detail="Unsupported file type")

    key = f"uploads/{current_user.id}/{uuid.uuid4()}.{ext}"
    url = await storage.upload(key, contents, content_type)

    # Echo back only a sanitised display name; it is never used to build paths.
    display_name = os.path.basename(file.filename or "")[:255]
    return {"url": url, "key": key, "filename": display_name, "contentType": content_type}

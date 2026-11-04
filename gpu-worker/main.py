import hmac
import logging
import os
import tempfile
import uuid
from io import BytesIO
from typing import Literal
from urllib.parse import urlparse

import httpx
import torch
from diffusers import DiffusionPipeline, StableDiffusionXLPipeline
from fastapi import Depends, FastAPI, Header, HTTPException
from PIL import Image
from pydantic import BaseModel, Field

logger = logging.getLogger("gpu-worker")

# Internal service: no CORS (browsers must never call it directly) and no OpenAPI docs.
app = FastAPI(title="VisionStudio GPU Worker", docs_url=None, redoc_url=None, openapi_url=None)

WORKER_TOKEN = os.getenv("GPU_WORKER_TOKEN", "")
if len(WORKER_TOKEN) < 32:
    raise RuntimeError("GPU_WORKER_TOKEN must be set to a random value of at least 32 characters")

# Media can only be fetched over https from these hosts (our R2/S3 bucket), never from
# arbitrary URLs: that would let callers reach cloud metadata or internal services (SSRF).
ALLOWED_SOURCE_HOSTS = {
    h.strip().lower() for h in os.getenv("ALLOWED_MEDIA_HOSTS", "").split(",") if h.strip()
}
if os.getenv("R2_PUBLIC_URL"):
    ALLOWED_SOURCE_HOSTS.add((urlparse(os.environ["R2_PUBLIC_URL"]).hostname or "").lower())
MAX_SOURCE_BYTES = int(os.getenv("MAX_SOURCE_MB", "50")) * 1024 * 1024
Image.MAX_IMAGE_PIXELS = 50_000_000  # decompression-bomb guard


def require_worker_token(x_worker_token: str = Header(default="")) -> None:
    if not hmac.compare_digest(x_worker_token.encode(), WORKER_TOKEN.encode()):
        raise HTTPException(status_code=401, detail="Unauthorized")


def check_source_url(url: str) -> str:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if (
        parsed.scheme != "https"
        or not host
        or parsed.username
        or parsed.password
        or host not in ALLOWED_SOURCE_HOSTS
    ):
        raise HTTPException(status_code=400, detail="Source URL not allowed")
    return url


async def fetch_source(url: str) -> bytes:
    check_source_url(url)
    # follow_redirects=False so an allowed host cannot bounce us to an internal address.
    async with httpx.AsyncClient(timeout=60.0, follow_redirects=False) as client:
        async with client.stream("GET", url) as response:
            if response.status_code != 200:
                raise HTTPException(status_code=400, detail="Could not fetch source")
            chunks, total = [], 0
            async for chunk in response.aiter_bytes():
                total += len(chunk)
                if total > MAX_SOURCE_BYTES:
                    raise HTTPException(status_code=413, detail="Source too large")
                chunks.append(chunk)
    return b"".join(chunks)

# Model loading (lazy)
models = {}

def get_model(model_name: str):
    if model_name not in models:
        device = "cuda" if torch.cuda.is_available() else "cpu"

        if model_name == "flux-dev":
            pipe = DiffusionPipeline.from_pretrained(
                "black-forest-labs/FLUX.1-dev",
                torch_dtype=torch.float16 if device == "cuda" else torch.float32,
            )
        elif model_name == "sdxl":
            pipe = StableDiffusionXLPipeline.from_pretrained(
                "stabilityai/stable-diffusion-xl-base-1.0",
                torch_dtype=torch.float16 if device == "cuda" else torch.float32,
            )
        else:
            pipe = StableDiffusionXLPipeline.from_pretrained(
                "stabilityai/stable-diffusion-xl-base-1.0",
                torch_dtype=torch.float16 if device == "cuda" else torch.float32,
            )

        pipe = pipe.to(device)
        if device == "cuda":
            pipe.enable_xformers_memory_efficient_attention()

        models[model_name] = pipe

    return models[model_name]

class GenerateRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)
    negative_prompt: str | None = Field(default="", max_length=2000)
    model: Literal["flux-dev", "flux-schnell", "sdxl"] = "flux-dev"
    width: int = Field(default=576, ge=256, le=1536)
    height: int = Field(default=1024, ge=256, le=1536)
    seed: int | None = Field(default=None, ge=0, le=2**31 - 1)
    steps: int = Field(default=30, ge=1, le=100)
    cfg_scale: float = Field(default=7.5, ge=0, le=30)
    batch_size: int = Field(default=1, ge=1, le=4)

class EditRequest(BaseModel):
    source_url: str = Field(max_length=2048)
    operation: str = Field(max_length=64)
    prompt: str | None = Field(default=None, max_length=2000)
    mask_url: str | None = Field(default=None, max_length=2048)
    strength: float = Field(default=0.75, ge=0, le=1)
    preserve_face: bool = True

class AnimateRequest(BaseModel):
    source_url: str = Field(max_length=2048)
    animation_type: str = Field(max_length=64)
    duration: int = Field(default=3, ge=1, le=10)
    fps: int = Field(default=24, ge=1, le=60)
    audio_url: str | None = Field(default=None, max_length=2048)
    format: Literal["mp4", "gif", "webm"] = "mp4"

@app.get("/health")
async def health():
    # Unauthenticated liveness probe: no hardware or model details.
    return {"status": "healthy"}

@app.post("/generate", dependencies=[Depends(require_worker_token)])
async def generate(req: GenerateRequest):
    try:
        pipe = get_model(req.model)
        device = "cuda" if torch.cuda.is_available() else "cpu"

        generator = torch.Generator(device=device)
        if req.seed is not None:
            generator.manual_seed(req.seed)
        else:
            generator = generator.manual_seed(torch.randint(0, 2147483647, (1,)).item())

        images = []
        for i in range(req.batch_size):
            result = pipe(
                prompt=req.prompt,
                negative_prompt=req.negative_prompt,
                width=req.width,
                height=req.height,
                num_inference_steps=req.steps,
                guidance_scale=req.cfg_scale,
                generator=generator.manual_seed(generator.initial_seed() + i),
            )
            images.append(result.images[0])

        # Save and upload
        urls = []
        for img in images:
            filename = f"{uuid.uuid4()}.png"
            temp_path = os.path.join(tempfile.gettempdir(), filename)
            img.save(temp_path, "PNG")

            # Upload to storage (R2/S3)
            url = await upload_to_storage(temp_path, filename)
            urls.append(url)
            os.remove(temp_path)

        return {"images": urls, "seed": generator.initial_seed()}

    except HTTPException:
        raise
    except Exception:
        logger.exception("generate failed")
        raise HTTPException(status_code=500, detail="Generation failed") from None

@app.post("/edit", dependencies=[Depends(require_worker_token)])
async def edit(req: EditRequest):
    if req.mask_url:
        check_source_url(req.mask_url)
    try:
        source_img = Image.open(BytesIO(await fetch_source(req.source_url)))
        source_img.load()

        # Apply edit based on operation
        # In production, this uses ControlNet, SAM, inpainting models
        # For MVP, return processed image

        filename = f"{uuid.uuid4()}.png"
        temp_path = os.path.join(tempfile.gettempdir(), filename)
        source_img.save(temp_path, "PNG")

        url = await upload_to_storage(temp_path, filename)
        os.remove(temp_path)

        return {"image": url}

    except HTTPException:
        raise
    except Exception:
        logger.exception("edit failed")
        raise HTTPException(status_code=500, detail="Edit failed") from None

@app.post("/animate", dependencies=[Depends(require_worker_token)])
async def animate(req: AnimateRequest):
    check_source_url(req.source_url)
    if req.audio_url:
        check_source_url(req.audio_url)
    # The previous implementation uploaded a temp file that was never written. Until a real
    # animation pipeline (AnimateDiff / LivePortrait / SVD) lands, report that honestly.
    # 501 is a 4xx-style permanent answer for the job queue, so it is not retried.
    raise HTTPException(status_code=501, detail="Animation is not implemented yet")


async def upload_to_storage(file_path: str, filename: str) -> str:
    # Upload to R2/S3
    import boto3

    client = boto3.client(
        "s3",
        endpoint_url=f"https://{os.getenv('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com",
        aws_access_key_id=os.getenv("R2_ACCESS_KEY_ID"),
        aws_secret_access_key=os.getenv("R2_SECRET_ACCESS_KEY"),
    )
    bucket = os.getenv("R2_BUCKET_NAME", "visionstudio-media")
    key = f"results/{filename}"

    client.upload_file(file_path, bucket, key)

    public_url = os.getenv("R2_PUBLIC_URL", "")
    if public_url:
        return f"{public_url}/{key}"

    return client.generate_presigned_url(
        "get_object",
        Params={"Bucket": bucket, "Key": key},
        ExpiresIn=3600,
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=os.getenv("HOST", "127.0.0.1"), port=8001)

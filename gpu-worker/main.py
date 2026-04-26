from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List
import torch
import os
import uuid
import tempfile
from diffusers import StableDiffusionXLPipeline, DiffusionPipeline
from PIL import Image
import httpx
import asyncio

app = FastAPI(title="VisionStudio GPU Worker")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

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
    prompt: str
    negative_prompt: Optional[str] = ""
    model: str = "flux-dev"
    width: int = 576
    height: int = 1024
    seed: Optional[int] = None
    steps: int = 30
    cfg_scale: float = 7.5
    batch_size: int = 1

class EditRequest(BaseModel):
    source_url: str
    operation: str
    prompt: Optional[str] = None
    mask_url: Optional[str] = None
    strength: float = 0.75
    preserve_face: bool = True

class AnimateRequest(BaseModel):
    source_url: str
    animation_type: str
    duration: int = 3
    fps: int = 24
    audio_url: Optional[str] = None
    format: str = "mp4"

@app.get("/health")
async def health():
    return {
        "status": "healthy",
        "gpu": torch.cuda.is_available(),
        "gpu_name": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu",
        "models_loaded": list(models.keys()),
    }

@app.post("/generate")
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

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/edit")
async def edit(req: EditRequest):
    try:
        # Download source image
        async with httpx.AsyncClient() as client:
            response = await client.get(req.source_url)
            response.raise_for_status()
            source_img = Image.open(BytesIO(response.content))

        # Apply edit based on operation
        # In production, this uses ControlNet, SAM, inpainting models
        # For MVP, return processed image

        filename = f"{uuid.uuid4()}.png"
        temp_path = os.path.join(tempfile.gettempdir(), filename)
        source_img.save(temp_path, "PNG")

        url = await upload_to_storage(temp_path, filename)
        os.remove(temp_path)

        return {"image": url}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/animate")
async def animate(req: AnimateRequest):
    try:
        # Download source
        async with httpx.AsyncClient() as client:
            response = await client.get(req.source_url)
            response.raise_for_status()

        # In production: AnimateDiff, LivePortrait, SVD, Wav2Lip
        # For MVP, return placeholder

        filename = f"{uuid.uuid4()}.mp4"
        temp_path = os.path.join(tempfile.gettempdir(), filename)

        # Create simple video (placeholder)
        # In production, this generates actual animation

        url = await upload_to_storage(temp_path, filename)

        return {"video": url, "preview": url}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

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

from io import BytesIO

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)

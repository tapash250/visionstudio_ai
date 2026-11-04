"""Request models: every write endpoint validates its input instead of taking a raw dict."""
from typing import Literal
from urllib.parse import urlparse

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

from app.config import allowed_media_hosts


class _Strict(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)


def validate_media_url(value: str | None) -> str | None:
    """Only accept https URLs on our own media hosts (prevents SSRF via the GPU worker)."""
    if value is None:
        return None
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or not host or parsed.username or parsed.password:
        raise ValueError("must be an https URL")
    if host not in allowed_media_hosts():
        raise ValueError("must point at an uploaded file on the media host")
    return value


class RegisterRequest(_Strict):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    name: str = Field(default="", max_length=100)


class MatureConsentRequest(_Strict):
    confirmed: bool


class RefreshRequest(_Strict):
    refresh_token: str = Field(min_length=1, max_length=4096)


AspectRatio = Literal["1:1", "16:9", "9:16", "4:5", "3:2"]
ModelName = Literal["flux-dev", "flux-schnell", "sdxl"]


class GenerateRequest(_Strict):
    prompt: str = Field(min_length=1, max_length=2000)
    negative_prompt: str = Field(default="", max_length=2000, alias="negativePrompt")
    model: ModelName = "flux-dev"
    style: str | None = Field(default=None, max_length=64)
    aspect_ratio: AspectRatio = Field(default="9:16", alias="aspectRatio")
    seed: int | None = Field(default=None, ge=0, le=2**31 - 1)
    steps: int = Field(default=30, ge=1, le=100)
    cfg_scale: float = Field(default=7.5, ge=0, le=30, alias="cfgScale")
    batch_size: int = Field(default=1, ge=1, le=4, alias="batchSize")

    @field_validator("prompt")
    @classmethod
    def _strip(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Prompt is required")
        return v


class EnhanceRequest(_Strict):
    prompt: str = Field(min_length=1, max_length=2000)


EditOperation = Literal[
    "INPAINT", "OUTPAINT", "REMOVE_OBJECT", "REMOVE_OUTFIT", "REMOVE_BACKGROUND",
    "REPLACE_BACKGROUND", "FACE_RESTORE", "FACE_ENHANCE", "SKIN_RETOUCH", "LIGHTING", "SHARPEN",
    "REPLACE_CLOTHING", "CHANGE_STYLE", "COLOR_SWAP", "ADD_ACCESSORY",
]


class EditRequest(_Strict):
    source_url: str = Field(alias="sourceUrl", max_length=2048)
    operation: EditOperation
    prompt: str | None = Field(default=None, max_length=2000)
    mask_url: str | None = Field(default=None, alias="maskUrl", max_length=2048)
    strength: float = Field(default=0.75, ge=0, le=1)
    preserve_face: bool = Field(default=True, alias="preserveFace")

    _check_source = field_validator("source_url")(validate_media_url)
    _check_mask = field_validator("mask_url")(validate_media_url)


class AnimateRequest(_Strict):
    source_url: str = Field(alias="sourceUrl", max_length=2048)
    source_type: Literal["image", "video"] = Field(default="image", alias="sourceType")
    animation_type: str = Field(
        alias="animationType", min_length=1, max_length=64, pattern=r"^[a-z_]+$"
    )
    duration: int = Field(default=3, ge=1, le=10)
    fps: int = Field(default=24, ge=1, le=60)
    audio_url: str | None = Field(default=None, alias="audioUrl", max_length=2048)
    format: Literal["mp4", "gif", "webm"] = "mp4"

    _check_source = field_validator("source_url")(validate_media_url)
    _check_audio = field_validator("audio_url")(validate_media_url)


class ProjectUpdateRequest(_Strict):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2000)
    is_public: bool | None = Field(default=None, alias="isPublic")
    is_mature: bool | None = Field(default=None, alias="isMature")
    tags: list[str] | None = Field(default=None, max_length=20)


class BanRequest(_Strict):
    reason: str = Field(default="Violation of terms", max_length=500)


class PushRegisterRequest(_Strict):
    fingerprint: str = Field(min_length=1, max_length=256)
    push_token: str = Field(alias="pushToken", min_length=1, max_length=4096)
    device_name: str | None = Field(default=None, alias="deviceName", max_length=100)
    device_type: str | None = Field(default=None, alias="deviceType", max_length=50)
    os: str | None = Field(default=None, max_length=50)
    browser: str | None = Field(default=None, max_length=50)

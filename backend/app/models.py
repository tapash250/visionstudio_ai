from sqlalchemy import Column, String, DateTime, Boolean, Integer, Float, Text, ARRAY, JSON, ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import relationship
import uuid
from datetime import datetime

from app.database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    email = Column(String, unique=True, nullable=False)
    email_verified = Column(DateTime, nullable=True)
    password_hash = Column(String, nullable=True)
    name = Column(String, nullable=True)
    avatar = Column(String, nullable=True)

    preferences = Column(JSON, default=dict)
    theme = Column(String, default="dark")
    language = Column(String, default="en")

    mature_enabled = Column(Boolean, default=False)
    trust_score = Column(Integer, default=100)
    banned_at = Column(DateTime, nullable=True)
    ban_reason = Column(String, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    projects = relationship("Project", back_populates="user", cascade="all, delete-orphan")
    generation_jobs = relationship("GenerationJob", back_populates="user", cascade="all, delete-orphan")
    edit_jobs = relationship("EditJob", back_populates="user", cascade="all, delete-orphan")
    animation_jobs = relationship("AnimationJob", back_populates="user", cascade="all, delete-orphan")

class Project(Base):
    __tablename__ = "projects"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id"), nullable=False)
    title = Column(String, nullable=False)
    description = Column(String, nullable=True)

    thumbnail_url = Column(String, nullable=True)
    source_url = Column(String, nullable=True)
    result_url = Column(String, nullable=True)

    type = Column(String, default="GENERATION")
    status = Column(String, default="DRAFT")

    is_public = Column(Boolean, default=False)
    is_mature = Column(Boolean, default=False)
    is_encrypted = Column(Boolean, default=False)

    metadata = Column(JSON, default=dict)
    tags = Column(ARRAY(String), default=list)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    user = relationship("User", back_populates="projects")
    generation_job = relationship("GenerationJob", back_populates="project", uselist=False)
    edit_job = relationship("EditJob", back_populates="project", uselist=False)
    animation_job = relationship("AnimationJob", back_populates="project", uselist=False)

class GenerationJob(Base):
    __tablename__ = "generation_jobs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    project_id = Column(String, ForeignKey("projects.id"), unique=True, nullable=False)
    user_id = Column(String, ForeignKey("users.id"), nullable=False)

    prompt = Column(Text, nullable=False)
    negative_prompt = Column(Text, nullable=True)
    enhanced_prompt = Column(Text, nullable=True)

    model = Column(String, default="flux-dev")
    style = Column(String, nullable=True)
    aspect_ratio = Column(String, default="9:16")
    seed = Column(Integer, nullable=True)
    steps = Column(Integer, default=30)
    cfg_scale = Column(Float, default=7.5)
    batch_size = Column(Integer, default=1)
    batch_index = Column(Integer, default=0)

    result_urls = Column(ARRAY(String), default=list)
    selected_url = Column(String, nullable=True)

    status = Column(String, default="PENDING")
    priority = Column(Integer, default=5)
    worker_id = Column(String, nullable=True)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    error = Column(String, nullable=True)

    compute_time = Column(Float, nullable=True)
    gpu_type = Column(String, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="generation_jobs")
    project = relationship("Project", back_populates="generation_job")

class EditJob(Base):
    __tablename__ = "edit_jobs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    project_id = Column(String, ForeignKey("projects.id"), unique=True, nullable=False)
    user_id = Column(String, ForeignKey("users.id"), nullable=False)

    source_url = Column(String, nullable=False)
    operation = Column(String, nullable=False)
    mask_url = Column(String, nullable=True)

    prompt = Column(Text, nullable=True)
    strength = Column(Float, default=0.75)
    preserve_face = Column(Boolean, default=True)

    result_url = Column(String, nullable=True)
    before_after = Column(JSON, nullable=True)

    status = Column(String, default="PENDING")
    priority = Column(Integer, default=5)
    worker_id = Column(String, nullable=True)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    error = Column(String, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="edit_jobs")
    project = relationship("Project", back_populates="edit_job")

class AnimationJob(Base):
    __tablename__ = "animation_jobs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    project_id = Column(String, ForeignKey("projects.id"), unique=True, nullable=False)
    user_id = Column(String, ForeignKey("users.id"), nullable=False)

    source_url = Column(String, nullable=False)
    source_type = Column(String, default="image")

    animation_type = Column(String, nullable=False)
    duration = Column(Integer, default=3)
    fps = Column(Integer, default=24)
    audio_url = Column(String, nullable=True)

    result_url = Column(String, nullable=True)
    preview_url = Column(String, nullable=True)
    format = Column(String, default="mp4")

    status = Column(String, default="PENDING")
    priority = Column(Integer, default=5)
    worker_id = Column(String, nullable=True)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    error = Column(String, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="animation_jobs")
    project = relationship("Project", back_populates="animation_job")

class StylePreset(Base):
    __tablename__ = "style_presets"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    name = Column(String, unique=True, nullable=False)
    label = Column(String, nullable=False)
    category = Column(String, nullable=False)
    description = Column(String, nullable=True)

    prompt_prefix = Column(Text, nullable=True)
    prompt_suffix = Column(Text, nullable=True)
    negative_prompt = Column(Text, nullable=True)

    thumbnail_url = Column(String, nullable=True)
    color_accent = Column(String, nullable=True)

    model = Column(String, default="flux-dev")
    cfg_scale = Column(Float, default=7.0)
    steps = Column(Integer, default=30)

    is_mature = Column(Boolean, default=False)
    use_count = Column(Integer, default=0)

    created_at = Column(DateTime, default=datetime.utcnow)

class ModerationLog(Base):
    __tablename__ = "moderation_logs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id"), nullable=False)

    content_type = Column(String, nullable=False)
    content_url = Column(String, nullable=True)
    prompt = Column(Text, nullable=True)

    action = Column(String, nullable=False)
    score = Column(Float, nullable=True)
    categories = Column(ARRAY(String), default=list)
    reason = Column(String, nullable=True)

    reviewed_by = Column(String, nullable=True)
    reviewed_at = Column(DateTime, nullable=True)
    appeal_status = Column(String, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="moderation_logs")

class MatureConsent(Base):
    __tablename__ = "mature_consents"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id"), unique=True, nullable=False)
    consent_given = Column(Boolean, nullable=False)
    consent_date = Column(DateTime, nullable=False)
    verified_method = Column(String, nullable=False)
    ip_address = Column(String, nullable=True)
    session_timeout = Column(Integer, default=30)
    last_accessed = Column(DateTime, nullable=False)

    user = relationship("User", back_populates="mature_consent")

# Add relationship to User
User.mature_consent = relationship("MatureConsent", back_populates="user", uselist=False)
User.moderation_logs = relationship("ModerationLog", back_populates="user", cascade="all, delete-orphan")

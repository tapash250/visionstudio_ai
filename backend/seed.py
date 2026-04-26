#!/usr/bin/env python3
"""Seed script for VisionStudio AI backend database."""

import asyncio
import os
import sys

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlalchemy.ext.asyncio import AsyncSession
from app.database import async_session, init_db
from app.models import StylePreset, User
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

STYLE_PRESETS = [
    {
        "name": "realistic",
        "label": "Realistic",
        "category": "Photography",
        "description": "Photorealistic images with natural lighting",
        "prompt_prefix": "photorealistic, highly detailed, 8k uhd, professional photography, natural lighting,",
        "prompt_suffix": "sharp focus, realistic textures, lifelike",
        "negative_prompt": "cartoon, anime, painting, illustration, 3d render, deformed, blurry",
        "color_accent": "#f59e0b",
        "model": "flux-dev",
        "cfg_scale": 7.5,
        "steps": 30,
    },
    {
        "name": "anime",
        "label": "Anime",
        "category": "Art",
        "description": "Japanese anime and manga style artwork",
        "prompt_prefix": "anime style, manga illustration, vibrant colors, detailed anime art,",
        "prompt_suffix": "studio ghibli inspired, clean lines, expressive",
        "negative_prompt": "photorealistic, 3d render, western cartoon, blurry, low quality",
        "color_accent": "#ec4899",
        "model": "flux-dev",
        "cfg_scale": 7.0,
        "steps": 28,
    },
    {
        "name": "cinematic",
        "label": "Cinematic",
        "category": "Photography",
        "description": "Movie-quality cinematic shots",
        "prompt_prefix": "cinematic shot, film still, dramatic lighting, depth of field, anamorphic lens,",
        "prompt_suffix": "color graded, movie scene, epic composition",
        "negative_prompt": "snapshot, amateur, overexposed, underexposed, flat lighting",
        "color_accent": "#3b82f6",
        "model": "flux-dev",
        "cfg_scale": 7.0,
        "steps": 35,
    },
    {
        "name": "fantasy",
        "label": "Fantasy",
        "category": "Art",
        "description": "Magical fantasy worlds and creatures",
        "prompt_prefix": "fantasy art, magical atmosphere, ethereal lighting, mystical,",
        "prompt_suffix": "detailed fantasy world, imaginative, otherworldly",
        "negative_prompt": "modern, urban, mundane, photorealistic, boring",
        "color_accent": "#10b981",
        "model": "flux-dev",
        "cfg_scale": 7.5,
        "steps": 32,
    },
    {
        "name": "fashion",
        "label": "Fashion",
        "category": "Photography",
        "description": "High-end fashion photography",
        "prompt_prefix": "fashion photography, haute couture, editorial, runway,",
        "prompt_suffix": "stylish, trendy, designer clothing, vogue style",
        "negative_prompt": "casual, sloppy, outdated, poorly dressed",
        "color_accent": "#8b5cf6",
        "model": "flux-dev",
        "cfg_scale": 7.0,
        "steps": 30,
    },
    {
        "name": "cyberpunk",
        "label": "Cyberpunk",
        "category": "Art",
        "description": "Neon-lit futuristic cyberpunk scenes",
        "prompt_prefix": "cyberpunk, neon lights, futuristic city, dystopian, holographic,",
        "prompt_suffix": "blade runner style, high tech, rain, reflections",
        "negative_prompt": "pastoral, rural, natural, low tech, medieval",
        "color_accent": "#06b6d4",
        "model": "flux-dev",
        "cfg_scale": 7.5,
        "steps": 35,
    },
    {
        "name": "portrait",
        "label": "Portrait",
        "category": "Photography",
        "description": "Professional portrait photography",
        "prompt_prefix": "professional portrait, studio lighting, headshot,",
        "prompt_suffix": "sharp eyes, skin detail, flattering light, bokeh background",
        "negative_prompt": "full body, landscape, group photo, blurry face",
        "color_accent": "#f43f5e",
        "model": "flux-dev",
        "cfg_scale": 7.0,
        "steps": 30,
    },
    {
        "name": "3d",
        "label": "3D Render",
        "category": "Art",
        "description": "High-quality 3D rendered images",
        "prompt_prefix": "3d render, octane render, unreal engine 5, cgi,",
        "prompt_suffix": "ray tracing, subsurface scattering, physically based rendering",
        "negative_prompt": "2d, flat, hand drawn, sketch, painting",
        "color_accent": "#0ea5e9",
        "model": "flux-dev",
        "cfg_scale": 7.5,
        "steps": 35,
    },
    {
        "name": "glamour",
        "label": "Glamour",
        "category": "Mature",
        "description": "Glamour and boudoir photography (18+)",
        "prompt_prefix": "glamour photography, elegant, sophisticated,",
        "prompt_suffix": "artistic, tasteful, professional lighting",
        "negative_prompt": "explicit, vulgar, amateur, poor quality",
        "color_accent": "#be185d",
        "model": "flux-dev",
        "cfg_scale": 7.0,
        "steps": 30,
        "is_mature": True,
    },
]

async def seed_styles(db: AsyncSession):
    """Seed style presets."""
    from sqlalchemy import select

    for preset_data in STYLE_PRESETS:
        result = await db.execute(
            select(StylePreset).where(StylePreset.name == preset_data["name"])
        )
        existing = result.scalar_one_or_none()

        if not existing:
            preset = StylePreset(**preset_data)
            db.add(preset)
            print(f"  ✅ Created style: {preset_data['label']}")
        else:
            print(f"  ⏭️  Style exists: {preset_data['label']}")

    await db.commit()

async def seed_admin(db: AsyncSession):
    """Seed admin user."""
    from sqlalchemy import select

    result = await db.execute(select(User).where(User.email == "admin@visionstudio.app"))
    existing = result.scalar_one_or_none()

    if not existing:
        admin = User(
            email="admin@visionstudio.app",
            password_hash=pwd_context.hash("admin123"),
            name="Admin",
            trust_score=100,
        )
        db.add(admin)
        await db.commit()
        print("  ✅ Created admin user: admin@visionstudio.app / admin123")
    else:
        print("  ⏭️  Admin user exists")

async def main():
    print("🌱 Seeding VisionStudio AI database...")
    print("")

    # Initialize database
    print("📦 Initializing database...")
    await init_db()
    print("  ✅ Database initialized")
    print("")

    async with async_session() as db:
        print("🎨 Seeding style presets...")
        await seed_styles(db)
        print("")

        print("👤 Seeding admin user...")
        await seed_admin(db)
        print("")

    print("✅ Seeding complete!")

if __name__ == "__main__":
    asyncio.run(main())

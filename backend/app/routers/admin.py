from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func
from datetime import datetime, timedelta

from app.database import get_db
from app.models import User, Project, GenerationJob, EditJob, AnimationJob, ModerationLog
from app.routers.auth import get_current_user

router = APIRouter()

async def require_admin(current_user: User = Depends(get_current_user)):
    # In production, check admin role
    if not current_user.email.endswith("@visionstudio.app"):
        raise HTTPException(status_code=403, detail="Admin access required")
    return current_user

@router.get("/dashboard")
async def admin_dashboard(
    admin: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
):
    # Stats
    total_users = await db.execute(select(func.count(User.id)))
    total_projects = await db.execute(select(func.count(Project.id)))
    total_generations = await db.execute(select(func.count(GenerationJob.id)))
    total_edits = await db.execute(select(func.count(EditJob.id)))
    total_animations = await db.execute(select(func.count(AnimationJob.id)))

    # Recent jobs
    recent_jobs = await db.execute(
        select(GenerationJob).order_by(GenerationJob.created_at.desc()).limit(10)
    )

    # Moderation logs
    recent_mod = await db.execute(
        select(ModerationLog).order_by(ModerationLog.created_at.desc()).limit(20)
    )

    return {
        "stats": {
            "users": total_users.scalar(),
            "projects": total_projects.scalar(),
            "generations": total_generations.scalar(),
            "edits": total_edits.scalar(),
            "animations": total_animations.scalar(),
        },
        "recentJobs": [
            {"id": j.id, "status": j.status, "prompt": j.prompt[:50], "createdAt": j.created_at}
            for j in recent_jobs.scalars()
        ],
        "moderationLogs": [
            {"id": m.id, "action": m.action, "reason": m.reason, "createdAt": m.created_at}
            for m in recent_mod.scalars()
        ],
    }

@router.get("/users")
async def list_users(
    admin: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).order_by(User.created_at.desc()).limit(100))
    users = result.scalars().all()
    return {
        "users": [
            {
                "id": u.id,
                "email": u.email,
                "name": u.name,
                "trustScore": u.trust_score,
                "bannedAt": u.banned_at,
                "createdAt": u.created_at,
            }
            for u in users
        ]
    }

@router.post("/users/{user_id}/ban")
async def ban_user(
    user_id: str,
    data: dict,
    admin: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    user.banned_at = datetime.utcnow()
    user.ban_reason = data.get("reason", "Violation of terms")
    await db.commit()
    return {"message": "User banned"}

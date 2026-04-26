from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, desc
from typing import Optional

from app.database import get_db
from app.models import User, Project
from app.routers.auth import get_current_user

router = APIRouter()

@router.get("/")
async def list_projects(
    type: Optional[str] = None,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    query = select(Project).where(Project.user_id == current_user.id)
    if type:
        query = query.where(Project.type == type.upper())
    query = query.order_by(desc(Project.created_at))

    result = await db.execute(query)
    projects = result.scalars().all()

    return {
        "projects": [
            {
                "id": p.id,
                "title": p.title,
                "description": p.description,
                "thumbnailUrl": p.thumbnail_url,
                "sourceUrl": p.source_url,
                "resultUrl": p.result_url,
                "type": p.type,
                "status": p.status,
                "isPublic": p.is_public,
                "isMature": p.is_mature,
                "createdAt": p.created_at.isoformat(),
                "updatedAt": p.updated_at.isoformat(),
            }
            for p in projects
        ]
    }

@router.get("/{project_id}")
async def get_project(
    project_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == current_user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")

    return {
        "id": project.id,
        "title": project.title,
        "description": project.description,
        "thumbnailUrl": project.thumbnail_url,
        "sourceUrl": project.source_url,
        "resultUrl": project.result_url,
        "type": project.type,
        "status": project.status,
        "isPublic": project.is_public,
        "isMature": project.is_mature,
        "metadata": project.metadata,
        "tags": project.tags,
        "createdAt": project.created_at.isoformat(),
        "updatedAt": project.updated_at.isoformat(),
    }

@router.post("/{project_id}")
async def update_project(
    project_id: str,
    data: dict,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == current_user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")

    if "title" in data:
        project.title = data["title"]
    if "description" in data:
        project.description = data["description"]
    if "isPublic" in data:
        project.is_public = data["isPublic"]
    if "isMature" in data:
        project.is_mature = data["isMature"]
    if "tags" in data:
        project.tags = data["tags"]

    await db.commit()
    await db.refresh(project)
    return {"id": project.id, "message": "Updated"}

@router.post("/{project_id}/delete")
async def delete_project(
    project_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == current_user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")

    await db.delete(project)
    await db.commit()
    return {"message": "Deleted"}

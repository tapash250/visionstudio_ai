from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.database import get_db
from app.models import StylePreset

router = APIRouter()

@router.get("/")
async def get_styles(
    category: str | None = None,
    include_mature: bool = False,
    db: AsyncSession = Depends(get_db),
):
    query = select(StylePreset)
    if category:
        query = query.where(StylePreset.category == category)
    if not include_mature:
        query = query.where(StylePreset.is_mature == False)

    result = await db.execute(query)
    styles = result.scalars().all()

    return {
        "styles": [
            {
                "id": s.id,
                "name": s.name,
                "label": s.label,
                "category": s.category,
                "description": s.description,
                "thumbnailUrl": s.thumbnail_url,
                "colorAccent": s.color_accent,
                "isMature": s.is_mature,
            }
            for s in styles
        ]
    }

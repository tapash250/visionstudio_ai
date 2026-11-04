from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models import User
from app.schemas import MatureConsentRequest, RefreshRequest, RegisterRequest
from app.security import (
    ACCESS_TOKEN_EXPIRE_MINUTES,
    create_access_token,
    create_refresh_token,
    decode_token,
    hash_password,
    verify_password,
)

router = APIRouter()
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/token")

# Computed once so failed logins for unknown emails cost the same as for known ones.
_DUMMY_HASH = hash_password("timing-equaliser-not-a-real-password")


async def get_current_user(
    token: str = Depends(oauth2_scheme), db: AsyncSession = Depends(get_db)
) -> User:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    payload = decode_token(token, "access")
    if payload is None:
        raise credentials_exception

    result = await db.execute(select(User).where(User.id == payload["sub"]))
    user = result.scalar_one_or_none()
    if user is None:
        raise credentials_exception
    if user.banned_at is not None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account suspended")
    return user


@router.post("/register")
async def register(data: RegisterRequest, db: AsyncSession = Depends(get_db)):
    email = data.email.lower()
    result = await db.execute(select(User).where(User.email == email))
    if result.scalar_one_or_none():
        raise HTTPException(status_code=400, detail="Email already registered")

    user = User(email=email, password_hash=hash_password(data.password), name=data.name)
    db.add(user)
    await db.commit()
    await db.refresh(user)

    return {"id": user.id, "email": user.email, "message": "Account created"}


@router.post("/token")
async def login(
    form_data: OAuth2PasswordRequestForm = Depends(), db: AsyncSession = Depends(get_db)
):
    result = await db.execute(select(User).where(User.email == form_data.username.lower()))
    user = result.scalar_one_or_none()

    password_ok = verify_password(form_data.password, user.password_hash if user else _DUMMY_HASH)
    if not user or not password_ok:
        raise HTTPException(status_code=401, detail="Invalid credentials")
    if user.banned_at is not None:
        raise HTTPException(status_code=403, detail="Account suspended")

    return {
        "access_token": create_access_token({"sub": user.id, "email": user.email}),
        "refresh_token": create_refresh_token({"sub": user.id}),
        "token_type": "bearer",
        "expires_in": ACCESS_TOKEN_EXPIRE_MINUTES * 60,
    }


@router.post("/refresh")
async def refresh_token(data: RefreshRequest, db: AsyncSession = Depends(get_db)):
    # The token travels in the JSON body, not the query string, so it is not written to logs.
    payload = decode_token(data.refresh_token, "refresh")
    if payload is None:
        raise HTTPException(status_code=401, detail="Invalid refresh token")

    result = await db.execute(select(User).where(User.id == payload["sub"]))
    user = result.scalar_one_or_none()
    if not user or user.banned_at is not None:
        raise HTTPException(status_code=401, detail="Invalid refresh token")

    access_token = create_access_token({"sub": user.id, "email": user.email})
    return {"access_token": access_token, "token_type": "bearer"}


@router.get("/me")
async def get_me(current_user: User = Depends(get_current_user)):
    return {
        "id": current_user.id,
        "email": current_user.email,
        "name": current_user.name,
        "avatar": current_user.avatar,
        "mature_enabled": current_user.mature_enabled,
    }


@router.post("/mature-consent")
async def mature_consent(
    consent: MatureConsentRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not consent.confirmed:
        raise HTTPException(status_code=400, detail="Consent required")

    current_user.mature_enabled = True
    await db.commit()
    return {"mature_enabled": True}

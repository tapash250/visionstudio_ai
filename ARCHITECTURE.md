# VisionStudio AI — System Architecture
## Mobile-First PWA for AI Image Generation, Editing & Animation

---

## 1. HIGH-LEVEL ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER (PWA)                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │   Next.js    │  │  Service     │  │  IndexedDB   │  │  Background     │  │
│  │   App Router │  │  Worker (SW) │  │  + Cache API │  │  Sync API       │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
└─────────┼─────────────────┼─────────────────┼───────────────────┼───────────┘
          │ HTTPS/WSS       │                 │                   │
          ▼                 ▼                 ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           EDGE / CDN LAYER                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │  Cloudflare / Vercel Edge — Static Assets, Manifest, Icons, SW      │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY LAYER                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │   FastAPI    │  │  Rate Limiter│  │  JWT Auth    │  │  Request        │  │
│  │   (NestJS)   │  │  (Redis)     │  │  Middleware  │  │  Validator      │  │
│  └──────┬───────┘  └──────────────┘  └──────────────┘  └─────────────────┘  │
└─────────┼─────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION SERVICES                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  Auth Svc    │  │  Generation  │  │  Editing Svc │  │  Animation Svc  │  │
│  │  (OAuth+OTP) │  │  (Queue)     │  │  (SAM/Control│  │  (AnimateDiff)  │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  Project Svc │  │  Moderation  │  │  Storage Svc │  │  Admin Svc      │  │
│  │  (CRUD)      │  │  (AI+Human)  │  │  (S3/R2)     │  │  (Analytics)    │  │
│  └──────┬───────┘  └──────────────┘  └──────┬───────┘  └─────────────────┘  │
└─────────┼─────────────────────────────────────┼─────────────────────────────┘
          │                                     │
          ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA & QUEUE LAYER                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  PostgreSQL  │  │  Redis       │  │  BullMQ /    │  │  S3 / R2        │  │
│  │  (Prisma)    │  │  (Sessions)  │  │  Celery      │  │  (Media Store)  │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         GPU INFERENCE CLUSTER                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  RunPod GPU  │  │  Modal       │  │  AWS g4/g5   │  │  Self-hosted    │  │
│  │  Workers     │  │  Functions   │  │  (SageMaker) │  │  A100 Cluster   │  │
│  │  (FLUX/SDXL) │  │  (Serverless)│  │  (Endpoints) │  │  (On-prem)      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. TECH STACK MATRIX

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Frontend** | Next.js 14 (App Router) | SSR/SSG, PWA, Routing |
| **Frontend** | React 18 + TypeScript | UI Components |
| **Frontend** | Tailwind CSS | Utility-first styling |
| **Frontend** | Framer Motion | Gestures, animations |
| **Frontend** | Zustand | Global state management |
| **Frontend** | Workbox | Service Worker generation |
| **Backend** | FastAPI (Python) | High-performance API |
| **Backend** | Uvicorn + Gunicorn | ASGI server |
| **Database** | PostgreSQL 15 | Primary datastore |
| **ORM** | Prisma (TS) + SQLAlchemy (Py) | Type-safe DB access |
| **Cache** | Redis 7 | Sessions, rate limits, queues |
| **Queue** | BullMQ (Node) / Celery (Py) | Background jobs |
| **Storage** | Cloudflare R2 / AWS S3 | Object storage |
| **AI GPU** | RunPod / Modal / AWS | Inference workers |
| **Auth** | NextAuth.js + FastAPI JWT | OAuth, OTP, sessions |
| **Deploy** | Vercel (FE) + Docker (BE) | CI/CD |

---

## 3. PWA ARCHITECTURE

### Service Worker Strategy: Stale-While-Revalidate + Network-First Hybrid

```
Route Patterns:
├── / (Shell)           → CacheFirst (precached)
├── /generate           → CacheFirst (precached)
├── /edit/*             → CacheFirst (precached)
├── /animate/*          → CacheFirst (precached)
├── /projects           → NetworkFirst + offline fallback
├── /api/*              → NetworkOnly (with BackgroundSync queue)
├── /_next/static/*     → CacheFirst (immutable)
├── /images/*           → CacheFirst (max 100 entries, 30d expiry)
└── *.mp4, *.webm       → RangeRequests + CacheFirst
```

### Background Sync Flow
```
User submits generation (offline)
    → SW intercepts POST /api/generate
    → Queue stored in IndexedDB (background-sync tag: "generate")
    → UI shows "Queued — will sync when online"
    → Device comes online
    → SW fires sync event
    → Replay queued requests in order
    → Push notification on completion
```

### Installability Checklist
- [x] `manifest.json` with `display: standalone`
- [x] Icons: 72x72, 96x96, 128x128, 144x144, 152x152, 192x192, 384x384, 512x512
- [x] `theme-color` + `background-color` for splash screen
- [x] `start_url: "/?source=pwa"`
- [x] `scope: "/"`
- [x] `shortcuts` for Generate, Edit, Projects
- [x] `screenshots` for install prompt rich UI
- [x] `related_applications` empty (web-only)

---

## 4. DATABASE SCHEMA (Prisma)

See `/database/schema.prisma` for full implementation.

### Core Entities
- `User` — Auth, profile, preferences, mature mode consent
- `Project` — Image/video projects with metadata
- `GenerationJob` — Async AI generation tasks
- `EditJob` — Image editing operations
- `AnimationJob` — Video animation tasks
- `StylePreset` — Prebuilt style configurations
- `PromptHistory` — User prompt log for suggestions
- `Device` — Registered devices for push/PWA
- `ModerationLog` — Content moderation audit trail
- `MatureConsent` — 18+ verification records

---

## 5. SECURITY ARCHITECTURE

```
┌────────────────────────────────────────┐
│         SECURITY LAYERS                │
├────────────────────────────────────────┤
│ 1. WAF (Cloudflare) — DDoS, bot filter │
├────────────────────────────────────────┤
│ 2. Rate Limiting (Redis)               │
│    - 10 req/min anonymous              │
│    - 60 req/min authenticated          │
│    - 5 generation jobs/min per device  │
├────────────────────────────────────────┤
│ 3. JWT Auth (RS256, 15min access)      │
│    + Refresh token rotation (7d)       │
├────────────────────────────────────────┤
│ 4. Signed URLs (S3 presigned, 1h)      │
│    + Media access control via proxy    │
├────────────────────────────────────────┤
│ 5. Input Validation (Zod + Pydantic)   │
│    + Prompt injection filtering        │
├────────────────────────────────────────┤
│ 6. Content Moderation                  │
│    - AWS Rekognition / Azure Content   │
│    - Custom NSFW classifier            │
│    - Mature mode gate (18+ only)       │
├────────────────────────────────────────┤
│ 7. Audit Logging                       │
│    - Immutable log stream              │
│    - 90-day retention                  │
└────────────────────────────────────────┘
```

---

## 6. SCALING ROADMAP

### Phase 1: MVP (1-10K users)
- Vercel Hobby + 1x FastAPI container
- Shared PostgreSQL (Supabase/Railway)
- Redis Cloud free tier
- RunPod serverless GPU

### Phase 2: Growth (10K-100K users)
- Vercel Pro + FastAPI auto-scaling (K8s)
- PostgreSQL read replicas
- Redis Cluster
- Dedicated GPU nodes + queue workers

### Phase 3: Scale (100K-1M users)
- Multi-region CDN
- Sharded PostgreSQL
- Redis Sentinel
- GPU auto-scaling (Modal/RunPod)
- Edge functions for auth

### Phase 4: Global (1M+ users)
- Regional API deployments
- Multi-AZ database
- Global Redis cache
- On-prem GPU cluster + cloud burst
- Microservices split

---

## 7. AI PIPELINE ARCHITECTURE

### Generation Pipeline
```
Prompt → Enhancer LLM → Negative Prompt Merge → Model Router
    → FLUX (quality) / SDXL (speed) / Juggernaut (realism)
    → 4-step latent consistency (fast preview)
    → Full 50-step for selected image
    → Upscale (Real-ESRGAN / 4x-UltraSharp)
    → WebP export + thumbnail generation
    → Upload to R2 → Notify via WebSocket
```

### Editing Pipeline
```
Image Upload → SAM segmentation → Mask generation
    → Inpainting (SDXL-inpaint / Fooocus)
    → ControlNet (pose/preserve)
    → Face restoration (CodeFormer/GFPGAN)
    → Background removal (RMBG/BiRefNet)
    → Post-processing pipeline
    → Export HD
```

### Animation Pipeline
```
Image → Face detection (RetinaFace) → Landmark extraction
    → Motion model selection:
        - LivePortrait (face animation)
        - AnimateDiff (character motion)
        - Stable Video Diffusion (camera motion)
        - Wav2Lip (lip sync)
    → Frame generation (16-24fps)
    → FFmpeg encoding (H.264/VP9)
    → Adaptive bitrate packaging
    → Upload + notify
```

---

*Document Version: 1.0 | Generated: 2026-04-26*

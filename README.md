# VisionStudio AI

<p align="center">
  <img src="frontend/public/icons/icon-192x192.png" alt="VisionStudio AI" width="120">
</p>

<h1 align="center">VisionStudio AI</h1>

<p align="center">
  <strong>Mobile-First PWA for AI Image Generation, Editing & Animation</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#deployment">Deployment</a> •
  <a href="#api">API</a> •
  <a href="#contributing">Contributing</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Next.js-14-black" alt="Next.js">
  <img src="https://img.shields.io/badge/FastAPI-0.109-009688" alt="FastAPI">
  <img src="https://img.shields.io/badge/TypeScript-5.3-blue" alt="TypeScript">
  <img src="https://img.shields.io/badge/Python-3.11-3776AB" alt="Python">
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED" alt="Docker">
  <img src="https://img.shields.io/badge/Kubernetes-Ready-326CE5" alt="Kubernetes">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
</p>

---

## Features

### 🎨 AI Image Generation
- **Text-to-Image** with FLUX, SDXL, Juggernaut XL
- **12+ Style Presets**: Realistic, Anime, Cinematic, Fantasy, Fashion, Cyberpunk, and more
- **Smart Prompt Enhancement** with AI
- **Negative Prompts** for fine control
- **Batch Generation** (up to 4 images)
- **Seed Control** for reproducibility
- **5 Aspect Ratios**: 1:1, 16:9, 9:16 (mobile-first), 4:5, 3:2

### ✏️ Advanced Image Editing
- **Inpainting** with brush mask (touch/stylus support)
- **Object Removal** — erase unwanted elements
- **Background Removal/Replacement**
- **Face Restoration & Enhancement**
- **Skin Retouching**
- **Outfit Changing** — casual to formal, seasonal styles
- **Color Swapping**
- **Undo/Redo History Stack**

### 🎬 Image to Animation
- **10 Animation Types**: Blink, Smile, Head Motion, Talking, Lip Sync, Cinematic Zoom, 3D Parallax, Dance, Camera Pan, Background Motion
- **Export Formats**: MP4, WebM, GIF
- **Adaptive Bitrate** for mobile playback
- **Audio Lip Sync** support

### 📱 Progressive Web App
- **Installable** on Android (Add to Home Screen)
- **Offline Support** with Service Worker caching
- **Background Sync** for queued generation tasks
- **Push Notifications** for job completion
- **Native App Feel** with bottom navigation
- **Touch-Optimized** editing tools
- **Gesture Support**: pinch, drag, zoom

### 🔒 Security & Privacy
- **JWT Authentication** with refresh token rotation
- **OAuth 2.0**: Google, GitHub login
- **Rate Limiting** per device/IP
- **Content Moderation** for prompts and images
- **Signed URLs** for media access
- **Mature Content Mode** (18+) with PIN protection
- **Encrypted Storage** for sensitive content

### 🌍 Universal Access
- **No Subscriptions** — all features free
- **No Paywalls** — no locked tiers
- **No Billing** — completely free to use

---

## Quick Start

### Prerequisites
- Node.js 20+
- Python 3.11+
- Docker & Docker Compose (optional)
- GPU with CUDA 12.1+ (for AI inference)

### One-Command Setup
```bash
git clone https://github.com/visionstudio/visionstudio-ai.git
cd visionstudio-ai
./setup.sh
```

### Manual Setup

**Frontend:**
```bash
cd frontend
npm install
npm run db:seed
npm run dev
```

**Backend:**
```bash
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
alembic upgrade head
python seed.py
uvicorn app.main:app --reload
```

**GPU Worker:**
```bash
cd gpu-worker
pip install torch diffusers transformers
python main.py
```

**Docker (Full Stack):**
```bash
docker-compose up --build
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         CLIENT (PWA)                         │
│  Next.js 14 • React 18 • TypeScript • Tailwind CSS          │
│  Framer Motion • Zustand • Service Worker • IndexedDB       │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS / WSS
┌──────────────────────────┴──────────────────────────────────┐
│                        API GATEWAY                           │
│  Nginx • Rate Limiting • SSL Termination • Load Balancing   │
└──────────────────────────┬──────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐  ┌────────▼────────┐  ┌─────▼──────┐
│   Frontend   │  │  FastAPI        │  │  WebSocket │
│   (Next.js)  │  │  (Python)       │  │  (Real-time│
│              │  │                 │  │   updates) │
└──────────────┘  └────────┬────────┘  └────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐  ┌────────▼────────┐  ┌─────▼──────┐
│  PostgreSQL  │  │  Redis          │  │  R2/S3     │
│  (Primary)   │  │  (Cache/Queue)  │  │  (Storage) │
└──────────────┘  └─────────────────┘  └────────────┘
                           │
                    ┌──────▼──────┐
                    │  Celery     │
                    │  Workers    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  GPU Worker │
                    │  (CUDA)     │
                    │  FLUX/SDXL  │
                    └─────────────┘
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | Next.js 14, React 18, TypeScript |
| **Styling** | Tailwind CSS, CSS Custom Properties |
| **Animations** | Framer Motion |
| **State** | Zustand, React Query |
| **Auth** | NextAuth.js, JWT |
| **Backend** | FastAPI, SQLAlchemy, Celery |
| **Database** | PostgreSQL 15 |
| **Cache/Queue** | Redis 7 |
| **Storage** | Cloudflare R2 / AWS S3 |
| **AI Models** | FLUX, SDXL, AnimateDiff, LivePortrait |
| **Deploy** | Docker, Kubernetes, Terraform |
| **Monitoring** | Prometheus, Grafana |
| **Testing** | Vitest, Playwright, pytest |

---

## Deployment

### Docker Compose (Recommended)
```bash
docker-compose up --build
```

### Kubernetes
```bash
kubectl apply -f k8s/
```

### Terraform (AWS + Cloudflare)
```bash
cd terraform
terraform init
terraform plan
terraform apply
```

See [DEPLOYMENT.md](DEPLOYMENT.md) for detailed instructions.

---

## API

### Authentication
```http
POST /api/auth/register
POST /api/auth/token
GET  /api/auth/me
```

### Generation
```http
POST /api/generate/
GET  /api/generate/{jobId}
POST /api/generate/enhance
```

### Editing
```http
POST /api/edit/
GET  /api/edit/{jobId}
```

### Animation
```http
POST /api/animate/
GET  /api/animate/{jobId}
```

See [API_DOCUMENTATION.md](API_DOCUMENTATION.md) for complete reference.

---

## Scaling

| Phase | Users | Monthly Cost |
|-------|-------|-------------|
| MVP | 10K | $100 |
| Growth | 100K | $1,500 |
| Scale | 500K | $8,000 |
| Global | 1M | $20,000 |

See [SCALING.md](SCALING.md) for the full roadmap.

---

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Commands

```bash
# Frontend
npm run dev          # Dev server
npm run build        # Production build
npm run test         # Unit tests
npm run test:e2e     # E2E tests
npm run storybook    # Component docs

# Backend
uvicorn app.main:app --reload   # Dev server
pytest                          # Run tests
alembic upgrade head            # Run migrations
black app/                      # Format code

# Full stack
docker-compose up --build       # Start everything
./scripts/deploy.sh production  # Deploy to production
./scripts/health-check.sh       # Check system health
```

---

## Security

If you discover a security vulnerability, please email **security@visionstudio.app** instead of opening a public issue. See [SECURITY.md](SECURITY.md) for details.

---

## License

[MIT License](LICENSE) — Free for personal and commercial use.

---

<p align="center">
  Built with ❤️ by the VisionStudio AI Team
</p>

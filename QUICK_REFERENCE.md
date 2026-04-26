# VisionStudio AI — Quick Reference

## Development Commands

### Frontend
```bash
cd frontend
npm install          # Install dependencies
npm run dev          # Start dev server (http://localhost:3000)
npm run build        # Production build
npm run lint         # Run ESLint
npm run test         # Run unit tests
npm run test:e2e     # Run E2E tests
npx prisma generate  # Generate Prisma client
npx prisma db push   # Push schema to database
npx prisma studio    # Open Prisma Studio
npm run db:seed      # Seed style presets
```

### Backend
```bash
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

uvicorn app.main:app --reload --port 8000   # Start server
alembic upgrade head                          # Run migrations
alembic revision --autogenerate -m "desc"    # Create migration
python seed.py                                # Seed database
pytest                                        # Run tests
pytest --cov=app                              # Run with coverage
black app/                                    # Format code
ruff check app/                               # Lint code
```

### GPU Worker
```bash
cd gpu-worker
python3 -m venv venv
source venv/bin/activate
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
pip install diffusers transformers accelerate xformers

python main.py                                  # Start worker
# Or with uvicorn:
uvicorn main:app --host 0.0.0.0 --port 8001
```

### Docker (Full Stack)
```bash
# Start everything
docker-compose up --build

# Start specific services
docker-compose up -d db redis
docker-compose up -d backend
docker-compose up -d frontend
docker-compose up -d worker
docker-compose up -d gpu-worker

# View logs
docker-compose logs -f backend
docker-compose logs -f worker

# Stop everything
docker-compose down -v
```

## API Endpoints

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/auth/register` | POST | No | Create account |
| `/api/auth/token` | POST | No | Login |
| `/api/auth/me` | GET | Yes | Get current user |
| `/api/generate/` | POST | Yes | Create generation |
| `/api/generate/{id}` | GET | Yes | Check status |
| `/api/edit/` | POST | Yes | Create edit |
| `/api/animate/` | POST | Yes | Create animation |
| `/api/projects/` | GET | Yes | List projects |
| `/api/upload/` | POST | Yes | Upload file |
| `/api/styles/` | GET | No | List styles |
| `/ws` | WS | Yes | Real-time updates |

## Environment Variables

### Frontend (.env.local)
```
NEXT_PUBLIC_API_URL=http://localhost:8000
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=<32-char-secret>
GOOGLE_CLIENT_ID=<id>
GOOGLE_CLIENT_SECRET=<secret>
GITHUB_CLIENT_ID=<id>
GITHUB_CLIENT_SECRET=<secret>
```

### Backend (.env)
```
DATABASE_URL=postgresql+asyncpg://user:pass@localhost:5432/db
REDIS_URL=redis://localhost:6379/0
JWT_SECRET_KEY=<64-char-secret>
R2_ACCOUNT_ID=<id>
R2_ACCESS_KEY_ID=<key>
R2_SECRET_ACCESS_KEY=<secret>
R2_BUCKET_NAME=visionstudio-media
GPU_WORKER_URL=http://localhost:8001
```

## File Structure Quick Nav

```
visionstudio-ai/
├── frontend/          # Next.js 14 PWA
│   ├── src/app/       # App Router pages
│   ├── src/components/# React components
│   ├── src/hooks/     # Custom hooks
│   ├── src/stores/    # Zustand stores
│   ├── src/lib/       # Utilities
│   ├── src/types/     # TypeScript types
│   └── prisma/        # Database schema
├── backend/           # FastAPI
│   ├── app/routers/   # API routes
│   ├── app/services/  # Business logic
│   ├── app/workers/   # Celery tasks
│   └── alembic/       # Migrations
├── gpu-worker/        # CUDA inference
├── docker/            # Docker configs
└── docs/              # Documentation
```

## Tech Stack

| Layer | Tech |
|-------|------|
| Frontend | Next.js 14, React 18, TypeScript, Tailwind CSS |
| State | Zustand, React Query |
| Animations | Framer Motion |
| Backend | FastAPI, SQLAlchemy, Celery |
| Database | PostgreSQL 15 |
| Cache/Queue | Redis 7 |
| Storage | Cloudflare R2 / AWS S3 |
| AI | FLUX, SDXL, AnimateDiff |
| Deploy | Docker, Vercel, RunPod |

## Support

- 📧 Email: support@visionstudio.app
- 💬 Discord: discord.gg/visionstudio
- 🐛 Issues: github.com/visionstudio/visionstudio-ai/issues

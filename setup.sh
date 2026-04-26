#!/bin/bash
set -e

echo "🚀 VisionStudio AI — Setup Script"
echo "===================================="
echo ""

# Check prerequisites
echo "📋 Checking prerequisites..."

if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js 20+ first."
    exit 1
fi

if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not installed. Please install Python 3.11+ first."
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo "⚠️  Docker is not installed. You'll need Docker for the full stack."
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 20 ]; then
    echo "❌ Node.js version must be 20+. Current: $(node -v)"
    exit 1
fi

PYTHON_VERSION=$(python3 --version | cut -d' ' -f2 | cut -d'.' -f1,2)
echo "✅ Node.js $(node -v)"
echo "✅ Python $PYTHON_VERSION"
echo ""

# Setup frontend
echo "📦 Setting up frontend..."
cd frontend

if [ ! -f ".env.local" ]; then
    cp .env.example .env.local
    echo "📝 Created frontend/.env.local (please edit with your values)"
fi

echo "📥 Installing frontend dependencies..."
npm install

echo "🔧 Generating Prisma client..."
npx prisma generate

cd ..

# Setup backend
echo ""
echo "📦 Setting up backend..."
cd backend

if [ ! -f ".env" ]; then
    cp .env.example .env
    echo "📝 Created backend/.env (please edit with your values)"
fi

echo "📥 Installing backend dependencies..."
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

cd ..

# Setup GPU worker
echo ""
echo "📦 Setting up GPU worker..."
cd gpu-worker

if [ ! -f ".env" ]; then
    echo "GPU_WORKER_URL=http://localhost:8001" > .env
    echo "R2_ACCOUNT_ID=" >> .env
    echo "R2_ACCESS_KEY_ID=" >> .env
    echo "R2_SECRET_ACCESS_KEY=" >> .env
    echo "R2_BUCKET_NAME=visionstudio-media" >> .env
    echo "📝 Created gpu-worker/.env (please edit with your values)"
fi

cd ..

# Docker setup
echo ""
echo "🐳 Docker setup..."
if command -v docker &> /dev/null; then
    echo "✅ Docker found"
    echo "   To start the full stack, run: docker-compose up --build"
else
    echo "⚠️  Docker not found. Skipping Docker setup."
fi

echo ""
echo "===================================="
echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Edit backend/.env with your credentials"
echo "  2. Edit frontend/.env.local with your API URL"
echo "  3. Start infrastructure: docker-compose up -d db redis"
echo "  4. Run migrations: cd backend && alembic upgrade head"
echo "  5. Seed database: cd frontend && npm run db:seed"
echo "  6. Start backend: cd backend && uvicorn app.main:app --reload"
echo "  7. Start frontend: cd frontend && npm run dev"
echo ""
echo "Or start everything with Docker:"
echo "  docker-compose up --build"
echo ""

#!/bin/bash
set -e

# VisionStudio AI — Production Deployment Script
# Usage: ./deploy.sh [staging|production] [version]

ENV=${1:-staging}
VERSION=${2:-latest}

echo "🚀 Deploying VisionStudio AI to $ENV (version: $VERSION)"
echo "=================================================="

# Validate environment
if [ "$ENV" != "staging" ] && [ "$ENV" != "production" ]; then
    echo "❌ Invalid environment. Use 'staging' or 'production'"
    exit 1
fi

# Load environment variables
if [ -f ".env.$ENV" ]; then
    export $(cat .env.$ENV | xargs)
    echo "✅ Loaded .env.$ENV"
else
    echo "❌ .env.$ENV not found"
    exit 1
fi

# Build images
echo ""
echo "🔨 Building images..."
docker build -t visionstudio/frontend:$VERSION frontend/
docker build -t visionstudio/backend:$VERSION backend/
docker build -t visionstudio/gpu-worker:$VERSION gpu-worker/

# Push to registry
echo ""
echo "📤 Pushing to registry..."
docker push visionstudio/frontend:$VERSION
docker push visionstudio/backend:$VERSION
docker push visionstudio/gpu-worker:$VERSION

# Deploy
echo ""
echo "📦 Deploying..."
if [ "$ENV" == "production" ]; then
    docker-compose -f docker-compose.prod.yml pull
    docker-compose -f docker-compose.prod.yml up -d
else
    docker-compose pull
    docker-compose up -d
fi

# Health check
echo ""
echo "🏥 Running health checks..."
sleep 10

HEALTH_URL="http://localhost:8000/health"
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -sf $HEALTH_URL > /dev/null; then
        echo "✅ Backend is healthy"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Retry $RETRY_COUNT/$MAX_RETRIES..."
    sleep 2
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "❌ Health check failed"
    exit 1
fi

echo ""
echo "=================================================="
echo "✅ Deployment complete!"
echo ""
echo "Frontend: https://visionstudio.app"
echo "API:      https://api.visionstudio.app"
echo "Health:   $HEALTH_URL"
echo ""

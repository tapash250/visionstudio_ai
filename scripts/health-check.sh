#!/bin/bash

# VisionStudio AI Health Check Script

API_URL=${API_URL:-"http://localhost:8000"}
FRONTEND_URL=${FRONTEND_URL:-"http://localhost:3000"}
GPU_URL=${GPU_URL:-"http://localhost:8001"}

CHECKS_PASSED=0
CHECKS_TOTAL=0

check_service() {
    local name=$1
    local url=$2
    local endpoint=$3

    CHECKS_TOTAL=$((CHECKS_TOTAL + 1))

    if curl -sf "${url}${endpoint}" > /dev/null 2>&1; then
        echo "  ✅ $name is healthy"
        CHECKS_PASSED=$((CHECKS_PASSED + 1))
        return 0
    else
        echo "  ❌ $name is unhealthy"
        return 1
    fi
}

echo "🏥 VisionStudio AI Health Check"
echo "================================"

check_service "Backend API" "$API_URL" "/health"
check_service "Frontend" "$FRONTEND_URL" "/"
check_service "GPU Worker" "$GPU_URL" "/health"

# Check database
CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
if docker-compose exec -T db pg_isready -U visionstudio > /dev/null 2>&1; then
    echo "  ✅ Database is ready"
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
else
    echo "  ❌ Database is not ready"
fi

# Check Redis
CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
if docker-compose exec -T redis redis-cli ping | grep -q "PONG"; then
    echo "  ✅ Redis is responding"
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
else
    echo "  ❌ Redis is not responding"
fi

echo ""
echo "================================"
echo "Results: $CHECKS_PASSED/$CHECKS_TOTAL checks passed"

if [ $CHECKS_PASSED -eq $CHECKS_TOTAL ]; then
    echo "✅ All systems operational"
    exit 0
else
    echo "❌ Some systems are unhealthy"
    exit 1
fi

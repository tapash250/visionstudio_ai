# VisionStudio AI — Deployment Guide

## Production Deployment Options

### Option 1: Vercel + Railway (Recommended for MVP)

#### Frontend (Vercel)
```bash
cd frontend
# Install Vercel CLI
npm i -g vercel

# Set environment variables
vercel env add NEXT_PUBLIC_API_URL
# Enter: https://api.visionstudio.app

vercel env add NEXTAUTH_SECRET
# Generate: openssl rand -base64 32

vercel env add NEXTAUTH_URL
# Enter: https://visionstudio.app

# Deploy
vercel --prod
```

#### Backend (Railway/Render)
```bash
cd backend
# Push to GitHub, connect Railway
# Set environment variables in Railway dashboard:
# - DATABASE_URL (PostgreSQL)
# - REDIS_URL (Redis Cloud / Upstash)
# - JWT_SECRET_KEY
# - R2_* credentials
# - GPU_WORKER_URL
```

#### GPU Workers (RunPod Serverless)
1. Create RunPod account
2. Deploy serverless endpoint with FLUX/SDXL
3. Set `GPU_WORKER_URL` to endpoint URL
4. Configure auto-scaling (0-10 workers)

---

### Option 2: Docker Compose (Self-Hosted)

```bash
# 1. Clone repo
git clone <repo-url>
cd visionstudio-ai

# 2. Configure environment
cp backend/.env.example backend/.env
# Edit backend/.env with production values

# 3. Start infrastructure
docker-compose up -d db redis

# 4. Run migrations
docker-compose exec backend alembic upgrade head

# 5. Seed database
docker-compose exec backend python -c "from app.database import init_db; import asyncio; asyncio.run(init_db())"

# 6. Start all services
docker-compose up -d

# 7. Verify
# Frontend: http://localhost
# Backend API: http://localhost/api
# GPU Worker: http://localhost:8001
```

---

### Option 3: Kubernetes (Scale)

```yaml
# k8s/frontend-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: visionstudio-frontend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: frontend
  template:
    metadata:
      labels:
        app: frontend
    spec:
      containers:
      - name: frontend
        image: visionstudio/frontend:latest
        ports:
        - containerPort: 3000
        env:
        - name: NEXT_PUBLIC_API_URL
          value: "https://api.visionstudio.app"
---
# k8s/backend-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: visionstudio-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: backend
  template:
    metadata:
      labels:
        app: backend
    spec:
      containers:
      - name: backend
        image: visionstudio/backend:latest
        ports:
        - containerPort: 8000
        envFrom:
        - secretRef:
            name: visionstudio-secrets
---
# k8s/gpu-worker-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: visionstudio-gpu
spec:
  replicas: 2
  selector:
    matchLabels:
      app: gpu-worker
  template:
    metadata:
      labels:
        app: gpu-worker
    spec:
      nodeSelector:
        cloud.google.com/gke-accelerator: nvidia-tesla-a100
      containers:
      - name: gpu-worker
        image: visionstudio/gpu-worker:latest
        resources:
          limits:
            nvidia.com/gpu: 1
        ports:
        - containerPort: 8001
```

Deploy:
```bash
kubectl apply -f k8s/
kubectl set image deployment/visionstudio-frontend frontend=visionstudio/frontend:v1.1
kubectl rollout status deployment/visionstudio-frontend
```

---

## SSL / HTTPS Setup

### Using Cloudflare (Recommended)
1. Point domain to Cloudflare
2. Enable "Full (Strict)" SSL mode
3. Set origin certificate in nginx
4. Enable HTTP/2 and HTTP/3

### Using Let's Encrypt
```bash
docker run -it --rm   -v $(pwd)/docker/ssl:/etc/letsencrypt   -p 80:80 certbot/certbot certonly   --standalone -d visionstudio.app -d www.visionstudio.app
```

---

## Monitoring & Observability

### Prometheus + Grafana
```yaml
# Add to docker-compose
  prometheus:
    image: prom/prometheus
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana
    ports:
      - "3001:3000"
    volumes:
      - grafana_data:/var/lib/grafana
```

### Key Metrics
- API request latency (p50, p95, p99)
- GPU utilization per worker
- Queue depth per job type
- Generation success/failure rate
- User registration rate
- Storage usage growth

---

## Scaling Roadmap

### Phase 1: MVP (0-10K users)
- Vercel Hobby (frontend)
- Railway Starter (backend + DB)
- Redis Cloud Free
- RunPod Serverless (GPU)
- Cloudflare R2 (storage)
- **Cost: ~$50-100/month**

### Phase 2: Growth (10K-100K users)
- Vercel Pro ($20/mo)
- Railway Pro + read replicas
- Redis Cloud Pro
- Dedicated RunPod GPU nodes
- CDN + edge caching
- **Cost: ~$500-1500/month**

### Phase 3: Scale (100K-1M users)
- Multi-region Vercel
- Kubernetes cluster (GKE/EKS)
- PostgreSQL HA (Cloud SQL)
- Redis Cluster
- GPU auto-scaling (Modal/RunPod)
- **Cost: ~$5000-15000/month**

### Phase 4: Global (1M+ users)
- Regional deployments
- Sharded PostgreSQL
- Global Redis
- On-prem GPU cluster + cloud burst
- Microservices split
- **Cost: $50K+/month**

---

## Backup Strategy

### Database
```bash
# Automated daily backups
pg_dump $DATABASE_URL | gzip > backup-$(date +%Y%m%d).sql.gz

# Point-in-time recovery
# Enable WAL archiving in PostgreSQL
```

### Media Storage
- R2/S3 versioning enabled
- Cross-region replication
- Lifecycle rules: move to cold storage after 90 days

---

## Security Checklist

- [ ] JWT secret rotated (min 256-bit)
- [ ] OAuth credentials from secure vault
- [ ] Database credentials not in code
- [ ] Rate limiting enabled
- [ ] CORS restricted to production domain
- [ ] Content moderation active
- [ ] SSL/TLS enforced (HSTS)
- [ ] Security headers configured
- [ ] Audit logging enabled
- [ ] DDoS protection (Cloudflare)
- [ ] Container images scanned (Trivy)
- [ ] Dependency updates automated (Dependabot)

---

## Troubleshooting

### Common Issues

**"Cannot connect to database"**
```bash
# Check PostgreSQL is running
docker-compose ps db
docker-compose logs db

# Verify connection string format
# Must be: postgresql+asyncpg://user:pass@host:5432/db
```

**"GPU worker timeout"**
```bash
# Check GPU worker health
curl http://gpu-worker:8001/health

# Verify CUDA is available
docker-compose exec gpu-worker nvidia-smi
```

**"Redis connection refused"**
```bash
docker-compose up -d redis
docker-compose exec redis redis-cli ping
```

**"CORS errors in browser"**
```bash
# Verify CORS_ORIGINS includes your frontend domain
# Format: https://visionstudio.app,https://www.visionstudio.app
```

# Troubleshooting Guide

## Frontend Issues

### "Module not found" errors
```bash
cd frontend
rm -rf node_modules package-lock.json
npm install
```

### "Prisma Client not found"
```bash
cd frontend
npx prisma generate
```

### PWA not installable
- Check that `manifest.json` is accessible at `/manifest.json`
- Verify service worker is registered in browser DevTools > Application > Service Workers
- Ensure HTTPS is enabled (PWA requires secure context)
- Check that icons exist in `/icons/` directory

### Build fails with "out of memory"
```bash
export NODE_OPTIONS="--max-old-space-size=4096"
npm run build
```

### Tailwind classes not working
```bash
cd frontend
rm -rf .next
npm run dev
```

---

## Backend Issues

### "Cannot connect to database"
```bash
# Check PostgreSQL is running
docker-compose ps db
docker-compose logs db

# Test connection
psql postgresql://visionstudio:visionstudio@localhost:5432/visionstudio -c "SELECT 1"
```

### "Alembic revision not found"
```bash
cd backend
alembic stamp head
alembic upgrade head
```

### "ImportError: cannot import name"
```bash
# Reinstall dependencies
cd backend
source venv/bin/activate
pip install -r requirements.txt --force-reinstall
```

### "JWT decode error"
- Verify `JWT_SECRET_KEY` is set in `.env`
- Ensure secret key is at least 32 characters
- Check token hasn't expired (15 minutes for access tokens)

### "Redis connection refused"
```bash
docker-compose up -d redis
docker-compose exec redis redis-cli ping
```

### "CORS errors"
- Verify `CORS_ORIGINS` includes your frontend URL
- Format: `https://visionstudio.app,https://www.visionstudio.app`
- For local dev: `http://localhost:3000`

---

## GPU Worker Issues

### "CUDA out of memory"
- Reduce batch size
- Use smaller model (SDXL instead of FLUX)
- Enable model offloading: `pipe.enable_model_cpu_offload()`
- Use `torch.float16` instead of `float32`

### "Model download fails"
- Check internet connection
- Verify HuggingFace token if using gated models
- Set `HF_HOME` to a directory with sufficient space

### "GPU not detected"
```bash
# Check CUDA
nvidia-smi

# Check PyTorch CUDA
python3 -c "import torch; print(torch.cuda.is_available())"
```

---

## Docker Issues

### "Port already in use"
```bash
# Find process using port
lsof -i :3000
lsof -i :8000
lsof -i :5432

# Kill process or change ports in docker-compose.yml
```

### "Container exits immediately"
```bash
# Check logs
docker-compose logs backend
docker-compose logs frontend
```

### "Permission denied"
```bash
# Fix permissions
sudo chown -R $USER:$USER .
```

---

## Common Errors

### "Rate limit exceeded"
- Wait 1 minute before retrying
- Check your request frequency
- Authenticated users have higher limits

### "Content blocked by moderation"
- Review your prompt for prohibited terms
- Check moderation logs in admin panel
- Contact support if you believe this is an error

### "Upload failed"
- Check file size (max 50MB)
- Verify file type (JPG, PNG, WebP, HEIC)
- Check storage credentials (R2/S3)

### "Job failed"
- Check job error message in response
- Verify GPU worker is healthy: `GET /health`
- Check worker logs for specific errors

---

## Performance Issues

### Slow image generation
- Use faster models (SDXL vs FLUX)
- Reduce number of inference steps
- Enable xformers memory efficient attention
- Use GPU with more VRAM

### Slow page load
- Enable CDN caching
- Optimize images (WebP format)
- Enable gzip compression
- Use lazy loading for heavy components

### High memory usage
- Reduce batch size
- Enable model CPU offloading
- Use gradient checkpointing
- Clear cache regularly

---

## Getting Help

1. Check logs: `docker-compose logs -f`
2. Review this troubleshooting guide
3. Search existing issues on GitHub
4. Join our Discord community
5. Email support@visionstudio.app

## Debug Mode

Enable debug logging:
```bash
# Frontend
NEXT_PUBLIC_DEBUG=true npm run dev

# Backend
LOG_LEVEL=debug uvicorn app.main:app --reload

# GPU Worker
LOG_LEVEL=debug python3 main.py
```

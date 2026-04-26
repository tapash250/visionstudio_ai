# Changelog

All notable changes to VisionStudio AI will be documented in this file.

## [1.0.0] - 2026-04-26

### Added
- Initial release of VisionStudio AI
- AI Image Generation with FLUX and SDXL models
- 12 prebuilt style presets (Realistic, Anime, Cinematic, etc.)
- Advanced Image Editor with 10+ tools
- Image to Animation engine (10 animation types)
- Mobile-first PWA with offline support
- Background sync for queued generation tasks
- Push notifications for job completion
- JWT authentication with Google/GitHub OAuth
- PIN-protected mature content mode (18+)
- Project dashboard with grid/list views
- Rate limiting and content moderation
- Docker Compose deployment
- GPU worker with CUDA support

### Security
- JWT access/refresh token rotation
- Rate limiting per device/IP
- Content moderation for prompts and images
- Signed URLs for media access
- Audit logging infrastructure

### Performance
- Service worker caching (stale-while-revalidate)
- Lazy loading for heavy modules
- Image optimization (WebP/AVIF)
- GPU-accelerated inference
- Redis-based caching

## [Unreleased]

### Planned
- Real-time collaborative editing
- Video generation from text
- 3D model generation
- Custom model training
- API for third-party integrations
- iOS native app wrapper

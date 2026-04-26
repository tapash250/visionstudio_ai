# Contributing to VisionStudio AI

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## Development Setup

### Prerequisites
- Node.js 20+
- Python 3.11+
- Docker & Docker Compose
- Git

### Quick Start
```bash
# Clone the repo
git clone https://github.com/visionstudio/visionstudio-ai.git
cd visionstudio-ai

# Install dependencies
make install

# Start development environment
make dev
```

## Project Structure

- `frontend/` — Next.js 14 PWA (React, TypeScript, Tailwind)
- `backend/` — FastAPI Python backend
- `gpu-worker/` — CUDA-based AI inference worker
- `docker/` — Docker Compose and nginx configs

## Coding Standards

### Frontend
- TypeScript strict mode enabled
- Use functional components with hooks
- Mobile-first responsive design
- Follow existing component patterns
- Use `cn()` utility for class merging

### Backend
- Follow PEP 8 style guide
- Use type hints throughout
- Write async functions for I/O operations
- Add docstrings to public APIs
- Keep functions focused and small

## Testing

### Frontend
```bash
cd frontend
npm run test
npm run test:e2e
```

### Backend
```bash
cd backend
pytest
pytest --cov=app
```

## Pull Request Process

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests and linting
5. Commit with clear messages
6. Push to your fork
7. Open a Pull Request

## Commit Message Format

```
type(scope): subject

body (optional)

footer (optional)
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Example:
```
feat(generate): add batch generation support

- Support generating up to 4 images at once
- Add batch size selector to UI
- Update API to handle batch requests
```

## Code Review

All submissions require review before merging. Reviewers will check:
- Code quality and readability
- Test coverage
- Mobile responsiveness
- Performance impact
- Security considerations

## Reporting Issues

When reporting bugs, please include:
- Steps to reproduce
- Expected vs actual behavior
- Screenshots (if applicable)
- Device/browser information
- Error messages/logs

## Security

If you discover a security vulnerability, please email security@visionstudio.app instead of opening a public issue.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

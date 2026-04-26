.PHONY: help install dev build deploy clean

help:
	@echo "VisionStudio AI — Development Commands"
	@echo ""
	@echo "  make install     Install all dependencies"
	@echo "  make dev         Start development servers"
	@echo "  make build       Build production images"
	@echo "  make deploy      Deploy to production"
	@echo "  make clean       Clean build artifacts"
	@echo "  make db-migrate  Run database migrations"
	@echo "  make db-seed     Seed database with presets"
	@echo "  make test        Run test suite"
	@echo "  make lint        Run linters"

install:
	cd frontend && npm install
	cd backend && pip install -r requirements.txt

dev:
	docker-compose up -d db redis
	cd backend && alembic upgrade head &
	cd frontend && npm run dev &
	cd backend && uvicorn app.main:app --reload --port 8000

build:
	docker-compose build

deploy:
	docker-compose -f docker/docker-compose.yml up -d

clean:
	cd frontend && rm -rf .next node_modules
	cd backend && find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	cd backend && find . -type f -name "*.pyc" -delete
	docker-compose down -v

db-migrate:
	cd backend && alembic upgrade head

db-seed:
	cd frontend && npm run db:seed

test:
	cd frontend && npm test
	cd backend && pytest

lint:
	cd frontend && npm run lint
	cd backend && flake8 app/

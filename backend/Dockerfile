FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini .

RUN useradd --create-home --uid 10001 app && chown -R app /app
USER app

# Run migrations and start
CMD alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 4

FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04

WORKDIR /app

# Install Python and dependencies
RUN apt-get update && apt-get install -y \
    python3-pip \
    python3-dev \
    git \
    && rm -rf /var/lib/apt/lists/*

# Install PyTorch with CUDA
RUN pip3 install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121

# Install diffusers and other ML libs (pinned)
COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt xformers

# Copy worker code and drop root
COPY main.py .
RUN useradd --create-home --uid 10001 worker && mkdir -p /app/cache && chown -R worker /app
USER worker
ENV HF_HOME=/app/cache

EXPOSE 8001

CMD ["python3", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]

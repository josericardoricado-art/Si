FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1
ENV DEBIAN_FRONTEND=noninteractive

WORKDIR /app

# Dependências do sistema
RUN apt-get update && apt-get install -y \
    ffmpeg \
    build-essential \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Versões compatíveis do pip/setuptools
RUN python -m pip install --upgrade pip==24.3.1
RUN python -m pip install setuptools==80.9.0 wheel

# Instala primeiro o Whisper sem isolamento de build
RUN pip install --no-build-isolation openai-whisper==20240930

# Instala o restante
COPY requirements.txt .

RUN pip install -r requirements.txt

# Copia o projeto
COPY . .

# Porta do Render
ENV PORT=10000

EXPOSE 10000

CMD ["python", "server.py"]

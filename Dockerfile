FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1
ENV WHISPER_MODEL=tiny

WORKDIR /app

# ==========================================
# SISTEMA
# ==========================================

RUN apt-get update && apt-get install -y \
    ffmpeg \
    git \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

# ==========================================
# PYTHON
# ==========================================

RUN python -m pip install --upgrade pip setuptools wheel

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

# ==========================================
# ARQUIVOS DO PROJETO
# ==========================================

COPY . .

# ==========================================
# INSTALAR IDIOMAS ARGOS
# ==========================================

RUN python install_languages.py

# ==========================================
# DIRETÓRIOS
# ==========================================

RUN mkdir -p /app/uploads \
    /app/outputs \
    /app/live_audio

# ==========================================
# PORTA RENDER
# ==========================================

ENV PORT=10000

EXPOSE 10000

# ==========================================
# SERVIDOR
# ==========================================

CMD ["node", "server.js"]

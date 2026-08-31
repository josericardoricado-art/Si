FROM node:20-bookworm

WORKDIR /app

# ==========================================
# Python + FFmpeg
# ==========================================

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    ffmpeg \
    build-essential \
    git \
    && rm -rf /var/lib/apt/lists/*

# ==========================================
# Node.js
# ==========================================

COPY package*.json ./

RUN npm install

# ==========================================
# Ambiente Python
# ==========================================

RUN python3 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"
ENV PYTHONUNBUFFERED="1"

# ==========================================
# Ferramentas Python
# ==========================================

RUN pip install --no-cache-dir --upgrade \
    pip \
    setuptools \
    wheel

# ==========================================
# Dependências Python
# ==========================================

COPY requirements.txt ./

RUN pip install --no-cache-dir -r requirements.txt

# ==========================================
# Copiar aplicação
# ==========================================

COPY . .

# ==========================================
# Diretórios
# ==========================================

RUN mkdir -p uploads outputs live_audio

# ==========================================
# IMPORTANTE
# ==========================================
# NÃO executar install_languages.py durante
# o build do Docker.
#
# RUN python3 install_languages.py
# foi removido.
# ==========================================

# Modelo Whisper
ENV WHISPER_MODEL=base

# Porta do Render
ENV PORT=10000

EXPOSE 10000

# ==========================================
# Iniciar servidor
# ==========================================

CMD ["node", "server.js"]

# Dockerfile - Backend de Dublagem Automática
# Node.js + Python + FFmpeg
# Estrutura esperada:
# index.html
# server.js
# package.json
# pipeline.py
# requirements.txt
# install_languages.py
# Dockerfile

FROM node:20-bookworm

# Evita perguntas interativas durante a instalação
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1

# Instala Python, pip, FFmpeg e ferramentas necessárias
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    ffmpeg \
    build-essential \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Diretório do aplicativo
WORKDIR /app

# -----------------------------
# Dependências Node.js
# -----------------------------
COPY package*.json ./
RUN npm install --omit=dev

# -----------------------------
# Dependências Python
# -----------------------------
COPY requirements.txt ./

# Instala as dependências Python
RUN pip3 install --break-system-packages -r requirements.txt

# -----------------------------
# Arquivos do projeto
# -----------------------------
COPY server.js ./
COPY pipeline.py ./
COPY install_languages.py ./
COPY index.html ./

# Cria diretórios usados pelo backend
RUN mkdir -p /app/uploads /app/outputs

# -----------------------------
# Instala os idiomas do Argos
# -----------------------------
# O script baixa os pacotes de tradução durante o build.
# Se o build ficar pesado ou falhar por disponibilidade do pacote,
# essa etapa poderá ser movida para um serviço de inicialização.
RUN python3 install_languages.py

# Porta usada pelo Render
EXPOSE 10000

# O Render fornece PORT automaticamente.
CMD ["node", "server.js"]

FROM node:20-bookworm

WORKDIR /app

# Instala Python, FFmpeg e ferramentas necessárias
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    ffmpeg \
    build-essential \
    git \
    && rm -rf /var/lib/apt/lists/*

# Instala as dependências Node
COPY package*.json ./
RUN npm install

# Cria ambiente Python
RUN python3 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"
ENV PYTHONUNBUFFERED="1"

# Atualiza ferramentas Python
RUN pip install --no-cache-dir --upgrade \
    pip \
    setuptools \
    wheel

# Instala dependências Python
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Copia os arquivos do projeto
COPY . .

# Cria as pastas necessárias
RUN mkdir -p uploads outputs live_audio

# Instala os idiomas do Argos
RUN python3 install_languages.py

# Porta do Render
ENV PORT=10000

EXPOSE 10000

# Inicia o backend
CMD ["node", "server.js"]

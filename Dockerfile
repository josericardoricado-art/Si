FROM node:20-bookworm

WORKDIR /app

# FFmpeg é necessário para processar vídeo e áudio
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg \
    python3 \
    python3-pip \
    python3-venv \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Copiar arquivos do projeto
COPY package*.json ./

# Instalar dependências Node
RUN npm install

# Criar ambiente Python
RUN python3 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"

# Atualizar ferramentas Python
RUN pip install --no-cache-dir --upgrade pip setuptools wheel

# Copiar requirements
COPY requirements.txt ./

# Instalar dependências Python
RUN pip install --no-cache-dir -r requirements.txt

# Copiar o restante do projeto
COPY . .

# Diretórios utilizados pelo aplicativo
RUN mkdir -p uploads outputs live_audio

# Porta utilizada pelo Render
ENV PORT=10000

EXPOSE 10000

# Iniciar o servidor
CMD ["node", "server.js"]

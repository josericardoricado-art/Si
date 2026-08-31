# ==========================================
# BACKEND DE DUBLAGEM AUTOMÁTICA
# ==========================================

FROM python:3.11-slim-bookworm

# Evita arquivos .pyc e mantém logs imediatamente visíveis
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Porta padrão do Render
ENV PORT=10000

# ==========================================
# PACOTES DO SISTEMA
# ==========================================

RUN apt-get update && apt-get install -y \
    ffmpeg \
    nodejs \
    npm \
    git \
    build-essential \
    gcc \
    g++ \
    libsndfile1 \
    libsndfile1-dev \
    libgomp1 \
    espeak-ng \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ==========================================
# DIRETÓRIO DA APLICAÇÃO
# ==========================================

WORKDIR /app

# ==========================================
# ATUALIZA PIP
# ==========================================

RUN python -m pip install --upgrade pip setuptools wheel

# ==========================================
# COPIA DEPENDÊNCIAS PYTHON
# ==========================================

COPY requirements.txt .

# Instala dependências Python
RUN pip install --no-cache-dir -r requirements.txt

# ==========================================
# COPIA PACKAGE.JSON
# ==========================================

COPY package.json .

# Instala dependências Node.js
RUN npm install --omit=dev

# ==========================================
# COPIA O RESTANTE DO PROJETO
# ==========================================

COPY server.js .
COPY pipeline.py .
COPY install_languages.py .
COPY index.html .
COPY README.md .

# ==========================================
# CRIA DIRETÓRIOS
# ==========================================

RUN mkdir -p /app/uploads /app/outputs

# ==========================================
# PORTA DO RENDER
# ==========================================

EXPOSE 10000

# ==========================================
# INICIA O BACKEND
# ==========================================

CMD ["node", "server.js"]

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIPER_DATA_DIR=/app/voices
ENV AUDIO_DIR=/tmp/si_audio

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates \
       curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p /app/voices /tmp/si_audio

# Baixa a voz portuguesa durante o build.
# Outras vozes poderão ser baixadas quando forem usadas.
RUN python -m piper.download_voices \
    pt_BR-faber-medium \
    --data-dir /app/voices

EXPOSE 10000

CMD ["gunicorn", "--bind", "0.0.0.0:10000", "--workers", "1", "--threads", "8", "--timeout", "0", "server:app"]

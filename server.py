import os
import re
import uuid
import time
import json
import base64
import threading
import subprocess
import tempfile
from collections import deque

import requests
from flask import Flask, request, jsonify, Response, send_file
from flask_cors import CORS
import yt_dlp
import imageio_ffmpeg

# ============================================================

# APP

# ============================================================

app = Flask(**name**)
CORS(app)

# ============================================================

# CONFIGURAÇÃO

# ============================================================

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "").strip()

AGENT_ID = os.getenv(
"AGENT_ID",
"agent_1601m1q929bhf2zvts65479fyzdw"
).strip()

VOICE_ID = os.getenv(
"VOICE_ID",
"cjVigY5qzO86Huf0OWal"
).strip()

ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"

PORT = int(os.getenv("PORT", "10000"))

# Português como idioma principal do seu aplicativo

TARGET_LANGUAGE = "pt"

# Tamanho aproximado de cada bloco de áudio

CHUNK_SECONDS = 6

# Máximo de caracteres enviados para voz por bloco

MAX_TTS_CHARS = 700

# ============================================================

# MEMÓRIA

# ============================================================

jobs = {}

audio_queues = {}

lock = threading.Lock()

# ============================================================

# HEADERS ELEVENLABS

# ============================================================

def eleven_headers():

```
return {
    "xi-api-key": ELEVENLABS_API_KEY
}
```

# ============================================================

# YOUTUBE ID

# ============================================================

def extract_youtube_id(url):

```
if not url:
    return None

patterns = [
    r"(?:youtube\.com/live/)([^?&#/]+)",
    r"(?:youtube\.com/watch\?v=)([^&#]+)",
    r"(?:youtu\.be/)([^?&#/]+)",
    r"(?:youtube\.com/embed/)([^?&#/]+)"
]

for pattern in patterns:

    match = re.search(
        pattern,
        url,
        re.IGNORECASE
    )

    if match:
        return match.group(1)

return None
```

# ============================================================

# PEGAR URL DE STREAM DO YOUTUBE

# ============================================================

def get_youtube_stream_url(youtube_url):

```
ydl_opts = {
    "quiet": True,
    "no_warnings": True,
    "noplaylist": True,
    "format": "bestaudio/best",
    "extractor_args": {
        "youtube": {
            "player_client": [
                "android",
                "web"
            ]
        }
    }
}

try:

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:

        info = ydl.extract_info(
            youtube_url,
            download=False
        )

    if not info:
        raise Exception(
            "O YouTube não retornou informações da transmissão."
        )

    stream_url = info.get("url")

    if not stream_url:

        formats = info.get(
            "formats",
            []
        )

        audio_formats = [
            f for f in formats
            if f.get("acodec") != "none"
            and f.get("url")
        ]

        if audio_formats:

            audio_formats.sort(
                key=lambda x: (
                    x.get("abr") or 0
                ),
                reverse=True
            )

            stream_url = audio_formats[0]["url"]

    if not stream_url:

        raise Exception(
            "Não foi possível encontrar o endereço do áudio da live."
        )

    return stream_url

except Exception as error:

    raise Exception(
        "Não foi possível obter o áudio da YouTube Live: "
        + str(error)
    )
```

# ============================================================

# ELEVENLABS SPEECH TO TEXT

# ============================================================

def eleven_transcribe(audio_path):

```
url = (
    ELEVENLABS_BASE_URL
    "/speech-to-text"
)

with open(
    audio_path,
    "rb"
) as audio_file:

    files = {
        "file": (
            "audio.wav",
            audio_file,
            "audio/wav"
        )
    }

    data = {
        "model_id": "scribe_v2",
        "language_code": "auto",
        "tag_audio_events": "false"
    }

    response = requests.post(
        url,
        headers=eleven_headers(),
        files=files,
        data=data,
        timeout=120
    )

if not response.ok:

    raise Exception(
        "ElevenLabs STT "
        + str(response.status_code)
        + ": "
        + response.text
    )

result = response.json()

return (
    result.get("text")
    or ""
).strip()
```

# ============================================================

# TRADUÇÃO

#

# Não usamos OpenAI.

#

# A tradução é feita por um endpoint público de tradução

# somente para transformar o texto antes do TTS.

# ============================================================

def translate_to_portuguese(text):

```
text = (text or "").strip()

if not text:
    return ""

# Se já estiver em português, não precisa traduzir.
# A detecção exata é deixada para o serviço de tradução.

url = "https://translate.googleapis.com/translate_a/single"

params = {
    "client": "gtx",
    "sl": "auto",
    "tl": "pt",
    "dt": "t",
    "q": text
}

response = requests.get(
    url,
    params=params,
    timeout=30
)

if not response.ok:

    raise Exception(
        "Erro no serviço de tradução: "
        + str(response.status_code)
    )

data = response.json()

translated_parts = []

if isinstance(data, list) and data:

    sentences = data[0]

    if isinstance(sentences, list):

        for item in sentences:

            if (
                isinstance(item, list)
                and len(item) > 0
                and item[0]
            ):

                translated_parts.append(
                    str(item[0])
                )

translated = "".join(
    translated_parts
).strip()

if not translated:

    return text

return translated
```

# ============================================================

# ELEVENLABS TEXT TO SPEECH

# ============================================================

def eleven_tts(text):

```
text = (text or "").strip()

if not text:
    return None

if len(text) > MAX_TTS_CHARS:

    text = text[
        :MAX_TTS_CHARS
    ]

url = (
    ELEVENLABS_BASE_URL
    "/text-to-speech/"
    + VOICE_ID
)

params = {
    "output_format": "mp3_44100_128"
}

payload = {
    "text": text,
    "model_id": "eleven_multilingual_v2"
}

response = requests.post(
    url,
    headers={
        **eleven_headers(),
        "Content-Type": "application/json"
    },
    params=params,
    json=payload,
    timeout=120
)

if not response.ok:

    raise Exception(
        "ElevenLabs TTS "
        + str(response.status_code)
        + ": "
        + response.text
    )

return response.content
```

# ============================================================

# PROCESSAR UM BLOCO

# ============================================================

def process_audio_chunk(
live_id,
wav_path
):

```
try:

    jobs[live_id]["message"] = (
        "🎙️ Transcrevendo áudio..."
    )

    original_text = eleven_transcribe(
        wav_path
    )

    if not original_text:

        return

    jobs[live_id]["lastTranscript"] = (
        original_text
    )

    jobs[live_id]["message"] = (
        "🌎 Traduzindo para português..."
    )

    translated_text = (
        translate_to_portuguese(
            original_text
        )
    )

    jobs[live_id]["lastTranslation"] = (
        translated_text
    )

    jobs[live_id]["message"] = (
        "🔊 Gerando voz da tradução..."
    )

    audio_data = eleven_tts(
        translated_text
    )

    if audio_data:

        audio_id = str(
            uuid.uuid4()
        )

        audio_path = os.path.join(
            tempfile.gettempdir(),
            "si_audio_"
            + audio_id
            + ".mp3"
        )

        with open(
            audio_path,
            "wb"
        ) as audio_file:

            audio_file.write(
                audio_data
            )

        audio_queues[live_id].append(
            {
                "id": audio_id,
                "path": audio_path,
                "text": translated_text
            }
        )

        jobs[live_id]["audioCapture"] = (
            "running"
        )

        jobs[live_id]["message"] = (
            "🟢 Tradução ativa e voz da "
            "ElevenLabs pronta."
        )

except Exception as error:

    jobs[live_id]["lastError"] = str(
        error
    )

    jobs[live_id]["message"] = (
        "⚠️ "
        + str(error)
    )

finally:

    try:

        if os.path.exists(wav_path):

            os.remove(wav_path)

    except Exception:
        pass
```

# ============================================================

# CAPTURA CONTÍNUA DO YOUTUBE

# ============================================================

def capture_youtube_live(
live_id,
youtube_url
):

```
ffmpeg_path = imageio_ffmpeg.get_ffmpeg_exe()

process = None

try:

    jobs[live_id]["status"] = (
        "capturing"
    )

    jobs[live_id]["audioCapture"] = (
        "starting"
    )

    jobs[live_id]["message"] = (
        "🔴 Obtendo áudio da YouTube Live..."
    )

    stream_url = (
        get_youtube_stream_url(
            youtube_url
        )
    )

    jobs[live_id]["message"] = (
        "🎙️ Áudio da live conectado."
    )

    process = subprocess.Popen(
        [
            ffmpeg_path,

            "-hide_banner",
            "-loglevel",
            "error",

            "-i",
            stream_url,

            "-vn",

            "-ac",
            "1",

            "-ar",
            "16000",

            "-f",
            "s16le",

            "pipe:1"
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0
    )

    bytes_per_second = (
        16000 * 2
    )

    chunk_size = (
        bytes_per_second
        * CHUNK_SECONDS
    )

    jobs[live_id]["audioCapture"] = (
        "running"
    )

    jobs[live_id]["status"] = (
        "translating"
    )

    while jobs[live_id]["status"] not in (
        "stopped",
        "error"
    ):

        raw_audio = (
            process.stdout.read(
                chunk_size
            )
        )

        if not raw_audio:

            raise Exception(
                "O fluxo de áudio do YouTube foi encerrado."
            )

        wav_path = os.path.join(
            tempfile.gettempdir(),
            "si_chunk_"
            + str(uuid.uuid4())
            + ".wav"
        )

        with open(
            wav_path,
            "wb"
        ) as wav_file:

            # WAV PCM 16-bit mono 16 kHz

            import wave

            with wave.open(
                wav_path,
                "wb"
            ) as wf:

                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(16000)
                wf.writeframes(
                    raw_audio
                )

        threading.Thread(
            target=process_audio_chunk,
            args=(
                live_id,
                wav_path
            ),
            daemon=True
        ).start()

    if process:

        process.terminate()

except Exception as error:

    if jobs.get(live_id):

        jobs[live_id]["status"] = (
            "error"
        )

        jobs[live_id]["audioCapture"] = (
            "error"
        )

        jobs[live_id]["lastError"] = (
            str(error)
        )

        jobs[live_id]["message"] = (
            "❌ "
            + str(error)
        )

finally:

    try:

        if process:

            process.kill()

    except Exception:
        pass
```

# ============================================================

# HOME

# ============================================================

@app.get("/")
def home():

```
return jsonify(
    {
        "ok": True,
        "service": "SI Tradutor Live",
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "message": "Servidor funcionando."
    }
)
```

# ============================================================

# HEALTH

# ============================================================

@app.get("/api/health")
def health():

```
if not ELEVENLABS_API_KEY:

    return jsonify(
        {
            "ok": False,
            "message":
                "ELEVENLABS_API_KEY não configurada."
        }
    ), 500

return jsonify(
    {
        "ok": True,
        "message":
            "Servidor conectado à ElevenLabs.",
        "provider":
            "ElevenLabs",
        "agent_id":
            AGENT_ID
    }
)
```

# ============================================================

# INICIAR YOUTUBE LIVE

# ============================================================

@app.post("/api/youtube-live")
def youtube_live():

```
if not ELEVENLABS_API_KEY:

    return jsonify(
        {
            "ok": False,
            "error":
                "ELEVENLABS_API_KEY não configurada no Render."
        }
    ), 500

data = (
    request.get_json(
        silent=True
    )
    or {}
)

youtube_url = str(
    data.get(
        "url",
        ""
    )
).strip()

if not youtube_url:

    return jsonify(
        {
            "ok": False,
            "error":
                "Cole o link da YouTube Live."
        }
    ), 400

video_id = (
    extract_youtube_id(
        youtube_url
    )
)

if not video_id:

    return jsonify(
        {
            "ok": False,
            "error":
                "Não foi possível identificar o vídeo do YouTube."
        }
    ), 400

live_id = str(
    uuid.uuid4()
)

with lock:

    jobs[live_id] = {

        "liveId":
            live_id,

        "youtubeId":
            video_id,

        "youtubeUrl":
            youtube_url,

        "targetLang":
            TARGET_LANGUAGE,

        "status":
            "starting",

        "audioCapture":
            "starting",

        "lastTranscript":
            "",

        "lastTranslation":
            "",

        "lastError":
            "",

        "message":
            "Iniciando captura da live...",

        "createdAt":
            time.time()
    }

    audio_queues[live_id] = deque()

threading.Thread(
    target=capture_youtube_live,
    args=(
        live_id,
        youtube_url
    ),
    daemon=True
).start()

return jsonify(
    {
        "ok": True,
        "liveId":
            live_id,
        "youtubeId":
            video_id,
        "targetLang":
            TARGET_LANGUAGE,
        "status":
            "starting",
        "message":
            "Live iniciada. Capturando áudio no servidor."
    }
)
```

# ============================================================

# STATUS

# ============================================================

@app.get("/api/youtube-live/<live_id>")
def youtube_live_status(
live_id
):

```
job = jobs.get(
    live_id
)

if not job:

    return jsonify(
        {
            "ok": False,
            "error":
                "Live não encontrada."
        }
    ), 404

return jsonify(
    {
        "ok": True,

        "liveId":
            live_id,

        "status":
            job.get("status"),

        "youtubeId":
            job.get("youtubeId"),

        "audioCapture":
            job.get("audioCapture"),

        "lastTranscript":
            job.get("lastTranscript"),

        "lastTranslation":
            job.get("lastTranslation"),

        "message":
            job.get("message"),

        "error":
            job.get("lastError")
    }
)
```

# ============================================================

# ÁUDIO DISPONÍVEL

# ============================================================

@app.get(
"/api/youtube-live/<live_id>/audio"
)
def get_audio(
live_id
):

```
queue = audio_queues.get(
    live_id
)

if queue is None:

    return jsonify(
        {
            "ok": False,
            "error":
                "Live não encontrada."
        }
    ), 404

if not queue:

    return jsonify(
        {
            "ok": True,
            "available": False
        }
    )

item = queue.popleft()

path = item["path"]

if not os.path.exists(path):

    return jsonify(
        {
            "ok": True,
            "available": False
        }
    )

return send_file(
    path,
    mimetype="audio/mpeg",
    as_attachment=False,
    download_name="translation.mp3"
)
```

# ============================================================

# TEXTO/ÁUDIO SSE

# ============================================================

@app.get(
"/api/youtube-live/<live_id>/events"
)
def events(
live_id
):

```
if live_id not in jobs:

    return jsonify(
        {
            "ok": False,
            "error":
                "Live não encontrada."
        }
    ), 404

def generate():

    last_text = ""

    while True:

        job = jobs.get(
            live_id
        )

        if not job:
            break

        current_text = (
            job.get(
                "lastTranslation",
                ""
            )
        )

        if current_text != last_text:

            payload = json.dumps(
                {
                    "translation":
                        current_text,
                    "status":
                        job.get(
                            "status"
                        ),
                    "message":
                        job.get(
                            "message"
                        )
                },
                ensure_ascii=False
            )

            yield (
                "data: "
                + payload
                + "\n\n"
            )

            last_text = current_text

        if job.get(
            "status"
        ) == "stopped":

            break

        time.sleep(1)

return Response(
    generate(),
    mimetype="text/event-stream",
    headers={
        "Cache-Control":
            "no-cache",
        "X-Accel-Buffering":
            "no"
    }
)
```

# ============================================================

# PARAR

# ============================================================

@app.post(
"/api/youtube-live/<live_id>/stop"
)
def stop_live(
live_id
):

```
job = jobs.get(
    live_id
)

if not job:

    return jsonify(
        {
            "ok": False,
            "error":
                "Live não encontrada."
        }
    ), 404

job["status"] = (
    "stopped"
)

job["audioCapture"] = (
    "stopped"
)

job["message"] = (
    "🛑 Live parada."
)

return jsonify(
    {
        "ok": True,
        "liveId":
            live_id,
        "status":
            "stopped",
        "message":
            "Live parada."
    }
)
```

# ============================================================

# EXECUÇÃO RENDER

# ============================================================

if **name** == "**main**":

```
app.run(
    host="0.0.0.0",
    port=PORT,
    threaded=True
)
```

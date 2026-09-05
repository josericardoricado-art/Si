import os
import uuid
import time
import json
import threading
import subprocess
import tempfile

import requests

from flask import Flask, request, jsonify, Response
from flask_cors import CORS


# =========================================================
# CONFIGURAÇÃO
# =========================================================

app = Flask(__name__)
CORS(app)


ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY")

AGENT_ID = os.getenv(
    "AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
)

VOICE_ID = os.getenv(
    "VOICE_ID",
    "cjVigY5qzO86Huf0OWal"
)

ELEVENLABS_BASE_URL = (
    "https://api.elevenlabs.io/v1"
)


# =========================================================
# MEMÓRIA DOS TRABALHOS
# =========================================================

jobs = {}

jobs_lock = threading.Lock()


# =========================================================
# CONFIGURAÇÃO DE IDIOMAS
# =========================================================

ALLOWED_LANGUAGES = {
    "pt",
    "en",
    "es",
    "fr",
    "de",
    "it",
    "ja",
    "ko",
    "zh",
    "ar"
}


# =========================================================
# HEADERS ELEVENLABS
# =========================================================

def eleven_headers():

    if not ELEVENLABS_API_KEY:
        raise Exception(
            "ELEVENLABS_API_KEY não configurada no Render."
        )

    return {
        "xi-api-key": ELEVENLABS_API_KEY
    }


# =========================================================
# EXTRAIR ID DO YOUTUBE
# =========================================================

def extract_youtube_id(url):

    if not url:
        return None

    url = url.strip()

    patterns = [
        "/live/",
        "watch?v=",
        "youtu.be/",
        "/embed/",
        "youtube.com/shorts/"
    ]

    for pattern in patterns:

        if pattern in url:

            value = url.split(
                pattern,
                1
            )[1]

            value = value.split(
                "?",
                1
            )[0]

            value = value.split(
                "&",
                1
            )[0]

            value = value.split(
                "/",
                1
            )[0]

            if value:
                return value

    return None


# =========================================================
# URL ASSINADA DO AGENT
# =========================================================

def get_agent_signed_url():

    if not ELEVENLABS_API_KEY:
        raise Exception(
            "ELEVENLABS_API_KEY não configurada."
        )

    if not AGENT_ID:
        raise Exception(
            "AGENT_ID não configurado."
        )

    url = (
        f"{ELEVENLABS_BASE_URL}"
        "/convai/conversation/get-signed-url"
    )

    response = requests.get(
        url,
        headers=eleven_headers(),
        params={
            "agent_id": AGENT_ID
        },
        timeout=30
    )

    if not response.ok:

        raise Exception(
            "ElevenLabs erro "
            f"{response.status_code}: "
            f"{response.text}"
        )

    data = response.json()

    signed_url = data.get(
        "signed_url"
    )

    if not signed_url:

        raise Exception(
            "ElevenLabs não retornou signed_url."
        )

    return signed_url


# =========================================================
# OBTER INFORMAÇÕES DA LIVE
# =========================================================

def get_youtube_info(youtube_url):

    try:

        command = [
            "yt-dlp",
            "--dump-single-json",
            "--no-warnings",
            "--skip-download",
            youtube_url
        ]

        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=60
        )

        if result.returncode != 0:

            return None, (
                result.stderr.strip()
                or "Erro ao consultar YouTube."
            )

        info = json.loads(
            result.stdout
        )

        return info, None

    except FileNotFoundError:

        return None, (
            "yt-dlp não está instalado."
        )

    except subprocess.TimeoutExpired:

        return None, (
            "Tempo limite consultando YouTube."
        )

    except Exception as error:

        return None, str(error)


# =========================================================
# OBTER URL DE ÁUDIO
# =========================================================

def get_youtube_audio_url(youtube_url):

    try:

        command = [
            "yt-dlp",
            "-f",
            "bestaudio/best",
            "-g",
            "--no-warnings",
            youtube_url
        ]

        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=90
        )

        if result.returncode != 0:

            error = (
                result.stderr.strip()
                or "Não foi possível obter o áudio."
            )

            return None, error

        audio_url = (
            result.stdout
            .strip()
            .splitlines()
        )

        if not audio_url:

            return None, (
                "YouTube não retornou URL de áudio."
            )

        return audio_url[0], None

    except FileNotFoundError:

        return None, (
            "yt-dlp não está instalado."
        )

    except subprocess.TimeoutExpired:

        return None, (
            "Tempo limite obtendo áudio."
        )

    except Exception as error:

        return None, str(error)


# =========================================================
# ATUALIZAR JOB
# =========================================================

def update_job(
    job_id,
    **values
):

    with jobs_lock:

        if job_id in jobs:
            jobs[job_id].update(values)


# =========================================================
# PROCESSAMENTO DA LIVE
# =========================================================

def process_live(
    live_id,
    youtube_url,
    target_lang
):

    try:

        update_job(
            live_id,
            status="connecting",
            message="Conectando ao YouTube..."
        )

        audio_url, error = (
            get_youtube_audio_url(
                youtube_url
            )
        )

        if not audio_url:

            update_job(
                live_id,
                status="error",
                audioCapture="error",
                error=(
                    "Não foi possível obter o áudio "
                    f"da YouTube Live: {error}"
                ),
                message=(
                    "O YouTube não permitiu obter "
                    "o áudio da transmissão."
                )
            )

            return


        update_job(
            live_id,
            status="running",
            audioCapture="running",
            audioUrl=audio_url,
            message=(
                "🟢 Áudio da live conectado."
            )
        )


    except Exception as error:

        update_job(
            live_id,
            status="error",
            audioCapture="error",
            error=str(error),
            message="Erro processando a live."
        )


# =========================================================
# HOME
# =========================================================

@app.get("/")
def home():

    return jsonify({

        "ok": True,

        "service":
            "SI Tradutor Live",

        "provider":
            "ElevenLabs",

        "agent_id":
            AGENT_ID,

        "voice_id":
            VOICE_ID,

        "message":
            "Servidor funcionando."

    })


# =========================================================
# HEALTH
# =========================================================

@app.get("/api/health")
def health():

    if not ELEVENLABS_API_KEY:

        return jsonify({

            "ok": False,

            "message":
                "ELEVENLABS_API_KEY não configurada no Render."

        }), 500


    return jsonify({

        "ok": True,

        "message":
            "Servidor conectado à ElevenLabs.",

        "provider":
            "ElevenLabs",

        "agent_id":
            AGENT_ID,

        "voice_id":
            VOICE_ID

    })


# =========================================================
# AGENT
# =========================================================

@app.get("/api/agent")
def agent():

    try:

        signed_url = (
            get_agent_signed_url()
        )

        return jsonify({

            "ok": True,

            "agent_id":
                AGENT_ID,

            "signed_url":
                signed_url

        })

    except Exception as error:

        return jsonify({

            "ok": False,

            "error":
                str(error)

        }), 500


# =========================================================
# SIGNED URL
# =========================================================

@app.get("/api/elevenlabs/signed-url")
def elevenlabs_signed_url():

    try:

        signed_url = (
            get_agent_signed_url()
        )

        return jsonify({

            "ok": True,

            "agent_id":
                AGENT_ID,

            "signed_url":
                signed_url

        })

    except Exception as error:

        return jsonify({

            "ok": False,

            "error":
                str(error)

        }), 500


# =========================================================
# INICIAR YOUTUBE LIVE
# =========================================================

@app.post("/api/youtube-live")
def youtube_live():

    if not ELEVENLABS_API_KEY:

        return jsonify({

            "ok": False,

            "error":
                "ELEVENLABS_API_KEY não configurada."

        }), 500


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


    target_lang = str(
        data.get(
            "targetLang",
            "pt"
        )
    ).strip()


    if not youtube_url:

        return jsonify({

            "ok": False,

            "error":
                "Cole o link da YouTube Live."

        }), 400


    video_id = (
        extract_youtube_id(
            youtube_url
        )
    )


    if not video_id:

        return jsonify({

            "ok": False,

            "error":
                "Não foi possível identificar "
                "o vídeo do YouTube."

        }), 400


    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({

            "ok": False,

            "error":
                "Idioma de destino não suportado."

        }), 400


    live_id = str(
        uuid.uuid4()
    )


    with jobs_lock:

        jobs[live_id] = {

            "liveId":
                live_id,

            "youtubeUrl":
                youtube_url,

            "youtubeId":
                video_id,

            "targetLang":
                target_lang,

            "status":
                "queued",

            "audioCapture":
                "starting",

            "lastTranscript":
                "",

            "lastTranslation":
                "",

            "message":
                "Preparando captura de áudio.",

            "error":
                None,

            "createdAt":
                time.time()

        }


    thread = threading.Thread(

        target=process_live,

        args=(

            live_id,

            youtube_url,

            target_lang

        ),

        daemon=True

    )

    thread.start()


    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "youtubeId":
            video_id,

        "targetLang":
            target_lang,

        "status":
            "queued",

        "message":
            "Live recebida. "
            "Iniciando captura de áudio."

    })


# =========================================================
# STATUS DA LIVE
# =========================================================

@app.get("/api/youtube-live/<live_id>")
def youtube_live_status(
    live_id
):

    with jobs_lock:

        job = jobs.get(
            live_id
        )

        if job:
            job = dict(job)


    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404


    return jsonify({

        "ok": True,

        **job

    })


# =========================================================
# ÁUDIO DA LIVE
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>/audio"
)
def youtube_live_audio(
    live_id
):

    with jobs_lock:

        job = jobs.get(
            live_id
        )

        if job:
            job = dict(job)


    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404


    audio_url = job.get(
        "audioUrl"
    )


    if not audio_url:

        return jsonify({

            "ok": False,

            "audioCapture":
                job.get(
                    "audioCapture"
                ),

            "message":
                job.get(
                    "message"
                ),

            "error":
                job.get(
                    "error"
                )

        }), 409


    return jsonify({

        "ok": True,

        "audioCapture":
            job.get(
                "audioCapture"
            ),

        "audioUrl":
            audio_url

    })


# =========================================================
# PARAR LIVE
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/stop"
)
def stop_live(
    live_id
):

    with jobs_lock:

        job = jobs.get(
            live_id
        )

        if not job:

            return jsonify({

                "ok": False,

                "error":
                    "Live não encontrada."

            }), 404


        job["status"] = "stopped"

        job["audioCapture"] = "stopped"

        job["message"] = (
            "Live parada."
        )

        job["audioUrl"] = None


    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "status":
            "stopped",

        "message":
            "Live parada."

    })


# =========================================================
# TESTE DA VOZ ELEVENLABS
# =========================================================

@app.get("/api/voice")
def voice():

    if not ELEVENLABS_API_KEY:

        return jsonify({

            "ok": False,

            "error":
                "ELEVENLABS_API_KEY não configurada."

        }), 500


    if not VOICE_ID:

        return jsonify({

            "ok": False,

            "error":
                "VOICE_ID não configurado."

        }), 500


    return jsonify({

        "ok": True,

        "provider":
            "ElevenLabs",

        "voice_id":
            VOICE_ID,

        "message":
            "VOICE_ID configurado."

    })


# =========================================================
# EXECUÇÃO
# =========================================================

if __name__ == "__main__":

    port = int(
        os.getenv(
            "PORT",
            "10000"
        )
    )

    app.run(

        host="0.0.0.0",

        port=port

        )

import os
import uuid
import threading
import time
import requests

from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# =========================================================
# CONFIGURAÇÃO
# =========================================================

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY")
AGENT_ID = os.getenv(
    "AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
)

ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"

jobs = {}

# =========================================================
# AUXILIARES
# =========================================================

def eleven_headers():
    return {
        "xi-api-key": ELEVENLABS_API_KEY,
        "Content-Type": "application/json"
    }


def extract_youtube_id(url):
    """
    Extrai o ID de links comuns do YouTube.
    """

    if not url:
        return None

    url = url.strip()

    patterns = [
        "/live/",
        "watch?v=",
        "youtu.be/",
        "/embed/"
    ]

    for pattern in patterns:

        if pattern in url:

            value = url.split(pattern, 1)[1]

            value = value.split("?", 1)[0]
            value = value.split("&", 1)[0]
            value = value.split("/", 1)[0]

            if value:
                return value

    return None


# =========================================================
# URL ASSINADA ELEVENLABS
# =========================================================

def get_agent_signed_url():

    if not ELEVENLABS_API_KEY:
        raise Exception(
            "ELEVENLABS_API_KEY não configurada."
        )

    url = (
        f"{ELEVENLABS_BASE_URL}"
        "/convai/conversation/get-signed-url"
    )

    response = requests.get(
        url,
        headers={
            "xi-api-key": ELEVENLABS_API_KEY
        },
        params={
            "agent_id": AGENT_ID
        },
        timeout=30
    )

    if not response.ok:

        raise Exception(
            f"ElevenLabs erro {response.status_code}: "
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
# ROTA PRINCIPAL
# =========================================================

@app.get("/")
def home():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor Live",
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "message": "Servidor funcionando."
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

    if not AGENT_ID:

        return jsonify({
            "ok": False,
            "message":
                "AGENT_ID não configurado."
        }), 500

    return jsonify({
        "ok": True,
        "message":
            "Servidor conectado à ElevenLabs.",
        "agent_id": AGENT_ID
    })


# =========================================================
# TESTAR AGENT
# =========================================================

@app.get("/api/agent")

def agent():

    try:

        signed_url = get_agent_signed_url()

        return jsonify({
            "ok": True,
            "agent_id": AGENT_ID,
            "signed_url": signed_url
        })

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
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


    data = request.get_json(
        silent=True
    ) or {}


    youtube_url = str(
        data.get("url", "")
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


    video_id = extract_youtube_id(
        youtube_url
    )


    if not video_id:

        return jsonify({
            "ok": False,
            "error":
                "Não foi possível identificar o vídeo do YouTube."
        }), 400


    live_id = str(
        uuid.uuid4()
    )


    jobs[live_id] = {

        "liveId": live_id,

        "youtubeUrl":
            youtube_url,

        "youtubeId":
            video_id,

        "targetLang":
            target_lang,

        "status":
            "ready",

        "audioCapture":
            "not_available",

        "lastTranscript":
            "",

        "lastTranslation":
            "",

        "message":
            "Live identificada. "
            "O vídeo pode ser exibido no navegador.",

        "createdAt":
            time.time()

    }


    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "youtubeId":
            video_id,

        "status":
            "ready",

        "audioCapture":
            "not_available",

        "message":
            "Live conectada ao aplicativo. "
            "O áudio do iframe do YouTube "
            "não pode ser capturado diretamente "
            "pelo navegador."
    })


# =========================================================
# STATUS DA LIVE
# =========================================================

@app.get("/api/youtube-live/<live_id>")

def youtube_live_status(
    live_id
):

    job = jobs.get(
        live_id
    )


    if not job:

        return jsonify({
            "ok": False,
            "error":
                "Live não encontrada."
        }), 404


    return jsonify({

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
            job.get("message")
    })


# =========================================================
# ÁUDIO
#
# Esta rota fica preparada para o frontend consultar o
# estado do áudio. O navegador NÃO consegue puxar o áudio
# diretamente do iframe do YouTube.
# =========================================================

@app.get("/api/youtube-live/<live_id>/audio")

def youtube_live_audio(
    live_id
):

    job = jobs.get(
        live_id
    )


    if not job:

        return jsonify({
            "ok": False,
            "error":
                "Live não encontrada."
        }), 404


    return jsonify({

        "ok": False,

        "audioCapture":
            job.get(
                "audioCapture",
                "not_available"
            ),

        "message":
            "O áudio da transmissão do YouTube "
            "não está disponível diretamente "
            "a partir do iframe."
    }), 409


# =========================================================
# PARAR LIVE
# =========================================================

@app.post("/api/youtube-live/<live_id>/stop")

def stop_live(
    live_id
):

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

    job["message"] = "Live parada."


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
# URL ASSINADA PARA O FRONTEND
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

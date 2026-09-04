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
    "ELEVENLABS_AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
)

ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"

jobs = {}
live_jobs = {}


# =========================================================
# HEADERS ELEVENLABS
# =========================================================

def eleven_headers():
    return {
        "xi-api-key": ELEVENLABS_API_KEY
    }


# =========================================================
# HOME
# =========================================================

@app.get("/")
def home():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor & Dublagem IA",
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
            "message": "ELEVENLABS_API_KEY não configurada."
        }), 500

    return jsonify({
        "ok": True,
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "message": "Servidor conectado à ElevenLabs."
    })


# =========================================================
# SIGNED URL DO AGENT
# =========================================================

@app.get("/api/agent/signed-url")
def agent_signed_url():

    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "error": "ELEVENLABS_API_KEY não configurada."
        }), 500

    if not AGENT_ID:

        return jsonify({
            "ok": False,
            "error": "ELEVENLABS_AGENT_ID não configurado."
        }), 500

    try:

        response = requests.get(
            f"{ELEVENLABS_BASE_URL}/convai/conversation/get-signed-url",
            headers=eleven_headers(),
            params={
                "agent_id": AGENT_ID
            },
            timeout=30
        )

        if not response.ok:

            return jsonify({
                "ok": False,
                "error":
                    f"ElevenLabs erro {response.status_code}: "
                    f"{response.text}"
            }), response.status_code

        data = response.json()

        signed_url = data.get("signed_url")

        if not signed_url:

            return jsonify({
                "ok": False,
                "error":
                    "ElevenLabs não retornou signed_url."
            }), 500

        return jsonify({
            "ok": True,
            "signedUrl": signed_url,
            "agent_id": AGENT_ID
        })

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
        }), 500


# =========================================================
# IDENTIFICAR YOUTUBE
# =========================================================

def get_youtube_id(url):

    if not url:
        return None

    url = url.strip()

    import re

    patterns = [

        r"youtube\.com/live/([^?&#/]+)",

        r"youtube\.com/watch\?v=([^&#]+)",

        r"youtu\.be/([^?&#/]+)",

        r"youtube\.com/embed/([^?&#/]+)",

        r"youtube\.com/shorts/([^?&#/]+)"

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


# =========================================================
# YOUTUBE LIVE
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

    target_language = str(
        data.get("targetLang", "pt")
    ).strip().lower()

    if not youtube_url:

        return jsonify({
            "ok": False,
            "error":
                "Cole o link da YouTube Live."
        }), 400

    video_id = get_youtube_id(
        youtube_url
    )

    if not video_id:

        return jsonify({
            "ok": False,
            "error":
                "Não consegui identificar o vídeo do YouTube."
        }), 400

    allowed_languages = {
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

    if target_language not in allowed_languages:

        return jsonify({
            "ok": False,
            "error":
                "Idioma de destino não suportado."
        }), 400

    live_id = str(
        uuid.uuid4()
    )

    live_jobs[live_id] = {

        "status": "connected",

        "message":
            "Live identificada.",

        "youtube_url":
            youtube_url,

        "video_id":
            video_id,

        "target_language":
            target_language,

        "agent_id":
            AGENT_ID,

        "audio_capture":
            "not_started"

    }

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "videoId":
            video_id,

        "targetLang":
            target_language,

        "agentId":
            AGENT_ID,

        "status":
            "connected",

        "message":
            "YouTube Live identificada. "
            "A captura de áudio precisa ser feita no backend."

    })


# =========================================================
# STATUS DA LIVE
# =========================================================

@app.get("/api/youtube-live/<live_id>")
def youtube_live_status(live_id):

    live = live_jobs.get(
        live_id
    )

    if not live:

        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404

    return jsonify({
        "ok": True,
        "liveId": live_id,
        **live
    })


# =========================================================
# PARAR LIVE
# =========================================================

@app.post("/api/youtube-live/<live_id>/stop")
def stop_youtube_live(live_id):

    live = live_jobs.get(
        live_id
    )

    if not live:

        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404

    live["status"] = "stopped"

    live["message"] = (
        "Live parada."
    )

    return jsonify({
        "ok": True,
        "liveId": live_id,
        "status": "stopped"
    })


# =========================================================
# UPLOAD DE VÍDEO
# =========================================================

@app.post("/api/test-upload")
def test_upload():

    return jsonify({
        "ok": False,
        "error":
            "A rota de upload permanece disponível, "
            "mas esta versão está focada no YouTube Live."
    }), 501


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

import os
import io
import json
import time
import uuid
import base64
import threading
import subprocess
from collections import deque

import requests

from flask import Flask, request, jsonify, send_file
from flask_cors import CORS


# =========================================================
# APP
# =========================================================

app = Flask(__name__)
CORS(app)


# =========================================================
# CONFIGURAÇÃO
# =========================================================

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "").strip()

VOICE_ID = os.getenv(
    "VOICE_ID",
    "cjVigY5qzO86Huf0OWal"
).strip()

AGENT_ID = os.getenv(
    "AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
).strip()

PORT = int(os.getenv("PORT", "10000"))

ELEVENLABS_URL = "https://api.elevenlabs.io/v1"

TTS_MODEL = os.getenv(
    "TTS_MODEL",
    "eleven_multilingual_v2"
)

# Endpoint de exemplo para um serviço de STT.
# Pode ser substituído por OpenAI/Whisper posteriormente.
OPENAI_API_KEY = os.getenv(
    "OPENAI_API_KEY",
    ""
).strip()


# =========================================================
# JOBS
# =========================================================

jobs = {}

jobs_lock = threading.Lock()


# =========================================================
# CONFIGURAÇÕES
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


LANGUAGE_NAMES = {
    "pt": "Português",
    "en": "Inglês",
    "es": "Espanhol",
    "fr": "Francês",
    "de": "Alemão",
    "it": "Italiano",
    "ja": "Japonês",
    "ko": "Coreano",
    "zh": "Chinês",
    "ar": "Árabe"
}


# =========================================================
# HEADERS ELEVENLABS
# =========================================================

def eleven_headers():

    if not ELEVENLABS_API_KEY:

        raise RuntimeError(
            "ELEVENLABS_API_KEY não configurada no Render."
        )

    return {
        "xi-api-key": ELEVENLABS_API_KEY,
        "Content-Type": "application/json"
    }


# =========================================================
# YOUTUBE ID
# =========================================================

def extract_youtube_id(url):

    if not url:
        return None

    url = url.strip()

    patterns = [
        "youtube.com/live/",
        "youtube.com/watch?v=",
        "youtu.be/",
        "youtube.com/embed/",
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
# CRIAR JOB
# =========================================================

def create_job(youtube_url, target_lang):

    live_id = str(uuid.uuid4())

    with jobs_lock:

        jobs[live_id] = {

            "liveId": live_id,

            "youtubeUrl": youtube_url,

            "youtubeId":
                extract_youtube_id(
                    youtube_url
                ),

            "targetLang": target_lang,

            "status": "waiting_audio",

            "audioCapture": "waiting",

            "lastTranscript": "",

            "lastTranslation": "",

            "audioReady": False,

            "audioBase64": None,

            "message":
                "Aguardando captura de áudio do navegador.",

            "error": None,

            "createdAt": time.time(),

            "updatedAt": time.time()

        }

    return live_id


# =========================================================
# ATUALIZAR JOB
# =========================================================

def update_job(live_id, **values):

    with jobs_lock:

        if live_id not in jobs:
            return

        jobs[live_id].update(values)

        jobs[live_id]["updatedAt"] = time.time()


# =========================================================
# OBTER JOB
# =========================================================

def get_job(live_id):

    with jobs_lock:

        job = jobs.get(live_id)

        if not job:
            return None

        return dict(job)


# =========================================================
# TRADUÇÃO
# =========================================================

def translate_text(text, target_lang):

    text = (text or "").strip()

    if not text:
        return ""

    # =====================================================
    # IMPORTANTE
    # =====================================================
    #
    # Se já estiver em português, não precisa traduzir.
    #
    if target_lang == "pt":
        return text

    # =====================================================
    # OPENAI OPCIONAL
    # =====================================================

    if not OPENAI_API_KEY:

        return (
            "[Tradução não configurada] "
            + text
        )

    try:

        response = requests.post(

            "https://api.openai.com/v1/chat/completions",

            headers={
                "Authorization":
                    f"Bearer {OPENAI_API_KEY}",

                "Content-Type":
                    "application/json"
            },

            json={

                "model":
                    "gpt-4o-mini",

                "messages": [

                    {
                        "role":
                            "system",

                        "content":
                            (
                                "Você é um tradutor simultâneo. "
                                "Traduza o texto para "
                                f"{LANGUAGE_NAMES.get(target_lang, target_lang)}. "
                                "Mantenha o sentido original. "
                                "Não explique a tradução."
                            )
                    },

                    {
                        "role":
                            "user",

                        "content":
                            text
                    }

                ],

                "temperature":
                    0.2

            },

            timeout=45

        )

        if not response.ok:

            return (
                "[Erro de tradução] "
                + text
            )

        data = response.json()

        choices = data.get(
            "choices",
            []
        )

        if not choices:

            return text

        return (
            choices[0]
            .get("message", {})
            .get("content", text)
            .strip()
        )

    except Exception:

        return text


# =========================================================
# ELEVENLABS TTS
# =========================================================

def elevenlabs_tts(text):

    text = (text or "").strip()

    if not text:

        return None, "Texto vazio."

    if not ELEVENLABS_API_KEY:

        return None, (
            "ELEVENLABS_API_KEY não configurada."
        )

    if not VOICE_ID:

        return None, (
            "VOICE_ID não configurado."
        )

    url = (
        f"{ELEVENLABS_URL}"
        f"/text-to-speech/"
        f"{VOICE_ID}"
        "/stream"
    )

    payload = {

        "text":
            text,

        "model_id":
            TTS_MODEL,

        "voice_settings": {

            "stability":
                0.45,

            "similarity_boost":
                0.80,

            "style":
                0.0,

            "use_speaker_boost":
                True

        }

    }

    try:

        response = requests.post(

            url,

            headers=eleven_headers(),

            params={
                "output_format":
                    "mp3_22050_32"
            },

            json=payload,

            timeout=90

        )

        if not response.ok:

            return None, (
                "ElevenLabs "
                f"{response.status_code}: "
                f"{response.text}"
            )

        return response.content, None

    except Exception as error:

        return None, str(error)


# =========================================================
# GERAR VOZ PARA JOB
# =========================================================

def generate_voice_for_job(
    live_id,
    translated_text
):

    update_job(
        live_id,

        status="generating_voice",

        message=
            "Gerando voz traduzida com ElevenLabs.",

        audioReady=False
    )

    audio_data, error = elevenlabs_tts(
        translated_text
    )

    if error:

        update_job(
            live_id,

            status="error",

            error=error,

            message=
                "Erro gerando áudio com ElevenLabs."
        )

        return

    encoded = base64.b64encode(
        audio_data
    ).decode("ascii")

    update_job(
        live_id,

        status="translating",

        audioReady=True,

        audioBase64=encoded,

        message=
            "Áudio traduzido pronto."
    )


# =========================================================
# RECEBER TEXTO PARA TRADUÇÃO
# =========================================================

@app.post("/api/translate")
def translate_endpoint():

    data = (
        request.get_json(
            silent=True
        )
        or {}
    )

    live_id = str(
        data.get(
            "liveId",
            ""
        )
    ).strip()

    text = str(
        data.get(
            "text",
            ""
        )
    ).strip()

    target_lang = str(
        data.get(
            "targetLang",
            "pt"
        )
    ).strip()

    if not live_id:

        return jsonify({

            "ok": False,

            "error":
                "liveId não informado."

        }), 400

    if not text:

        return jsonify({

            "ok": False,

            "error":
                "Texto não informado."

        }), 400

    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({

            "ok": False,

            "error":
                "Idioma não suportado."

        }), 400

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    update_job(
        live_id,

        status="translating",

        lastTranscript=text,

        message=
            "Traduzindo texto..."
    )

    translated = translate_text(
        text,
        target_lang
    )

    update_job(
        live_id,

        lastTranslation=translated,

        message=
            "Tradução concluída."
    )

    # Gera a voz em background.
    thread = threading.Thread(

        target=generate_voice_for_job,

        args=(
            live_id,
            translated
        ),

        daemon=True
    )

    thread.start()

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "original":
            text,

        "translation":
            translated

    })


# =========================================================
# CRIAR LIVE
# =========================================================

@app.post("/api/youtube-live")
def youtube_live():

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

    youtube_id = extract_youtube_id(
        youtube_url
    )

    if not youtube_id:

        return jsonify({

            "ok": False,

            "error":
                "Link do YouTube inválido."

        }), 400

    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({

            "ok": False,

            "error":
                "Idioma não suportado."

        }), 400

    live_id = create_job(
        youtube_url,
        target_lang
    )

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "youtubeId":
            youtube_id,

        "targetLang":
            target_lang,

        "status":
            "waiting_audio",

        "message":
            (
                "Live criada. "
                "Agora o navegador precisa "
                "compartilhar o áudio."
            )

    })


# =========================================================
# STATUS
# =========================================================

@app.get("/api/youtube-live/<live_id>")
def youtube_live_status(live_id):

    job = get_job(live_id)

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
# ÁUDIO GERADO
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>/audio"
)
def get_audio(live_id):

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    audio_base64 = job.get(
        "audioBase64"
    )

    if not audio_base64:

        return jsonify({

            "ok": False,

            "audioReady":
                False,

            "message":
                "Áudio ainda não está pronto."

        }), 404

    try:

        audio_data = base64.b64decode(
            audio_base64
        )

        return send_file(

            io.BytesIO(audio_data),

            mimetype="audio/mpeg",

            as_attachment=False,

            download_name=
                f"{live_id}.mp3"

        )

    except Exception as error:

        return jsonify({

            "ok": False,

            "error":
                str(error)

        }), 500


# =========================================================
# LIMPAR ÁUDIO
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/audio/ack"
)
def acknowledge_audio(live_id):

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    update_job(
        live_id,

        audioReady=False,

        audioBase64=None
    )

    return jsonify({

        "ok": True

    })


# =========================================================
# CAPTURA INICIADA
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/capture-start"
)
def capture_start(live_id):

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    update_job(
        live_id,

        status="capturing",

        audioCapture="running",

        message=
            "Captura de áudio iniciada no navegador."
    )

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "status":
            "capturing"

    })


# =========================================================
# CAPTURA FINALIZADA
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/capture-stop"
)
def capture_stop(live_id):

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    update_job(
        live_id,

        status="stopped",

        audioCapture="stopped",

        message=
            "Captura de áudio parada."
    )

    return jsonify({

        "ok": True

    })


# =========================================================
# PARAR LIVE
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/stop"
)
def stop_live(live_id):

    job = get_job(live_id)

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    update_job(
        live_id,

        status="stopped",

        audioCapture="stopped",

        audioReady=False,

        audioBase64=None,

        message=
            "Live parada."
    )

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "status":
            "stopped"

    })


# =========================================================
# TESTE ELEVENLABS
# =========================================================

@app.get("/api/voice")
def test_voice():

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

        "model":
            TTS_MODEL

    })


# =========================================================
# HEALTH
# =========================================================

@app.get("/api/health")
def health():

    return jsonify({

        "ok":
            bool(ELEVENLABS_API_KEY),

        "service":
            "SI Tradutor Live",

        "provider":
            "ElevenLabs",

        "agent_id":
            AGENT_ID,

        "voice_id":
            VOICE_ID,

        "tts_model":
            TTS_MODEL,

        "capture":
            "browser",

        "message":
            (
                "Servidor funcionando."
                if ELEVENLABS_API_KEY
                else
                "ELEVENLABS_API_KEY não configurada."
            )

    })


# =========================================================
# HOME
# =========================================================

@app.get("/")
def home():

    return jsonify({

        "ok":
            True,

        "message":
            "Servidor funcionando.",

        "service":
            "SI Tradutor Live",

        "provider":
            "ElevenLabs",

        "voice_id":
            VOICE_ID,

        "agent_id":
            AGENT_ID

    })


# =========================================================
# EXECUÇÃO
# =========================================================

if __name__ == "__main__":

    print(
        "======================================"
    )

    print(
        " SI Tradutor Live"
    )

    print(
        " Servidor iniciado"
    )

    print(
        f" Porta: {PORT}"
    )

    print(
        f" Voice ID: {VOICE_ID}"
    )

    print(
        "======================================"
    )

    app.run(
        host="0.0.0.0",
        port=PORT,
        threaded=True
        )

import os
import uuid
import time
import base64
import threading

import requests

from flask import Flask, request, jsonify
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

AGENT_ID = os.getenv(
    "AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
).strip()

VOICE_ID = os.getenv(
    "VOICE_ID",
    "cjVigY5qzO86Huf0OWal"
).strip()

PORT = int(os.getenv("PORT", "10000"))


# =========================================================
# TRABALHOS
# =========================================================

jobs = {}
jobs_lock = threading.Lock()


# =========================================================
# IDIOMAS
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
        "xi-api-key": ELEVENLABS_API_KEY,
        "Content-Type": "application/json"
    }


# =========================================================
# ATUALIZAR JOB
# =========================================================

def update_job(job_id, **values):

    with jobs_lock:

        if job_id in jobs:
            jobs[job_id].update(values)


# =========================================================
# HEALTH
# =========================================================

@app.get("/")
def home():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor Live",
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "voice_id": VOICE_ID,
        "message": "Servidor funcionando."
    })


@app.get("/api/health")
def health():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor Live",
        "provider": "ElevenLabs",
        "elevenlabs": bool(ELEVENLABS_API_KEY),
        "agent_id": AGENT_ID,
        "voice_id": VOICE_ID,
        "message": "Servidor funcionando."
    })


# =========================================================
# CONFIGURAÇÃO
# =========================================================

@app.get("/api/config")
def config():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor Live",
        "agent_id": AGENT_ID,
        "voice_id": VOICE_ID,
        "languages": sorted(
            list(ALLOWED_LANGUAGES)
        )
    })


# =========================================================
# CRIAR SESSÃO
# =========================================================

@app.post("/api/session")
def create_session():

    data = (
        request.get_json(
            silent=True
        )
        or {}
    )

    target_lang = str(
        data.get(
            "targetLang",
            "pt"
        )
    ).strip().lower()

    youtube_url = str(
        data.get(
            "youtubeUrl",
            ""
        )
    ).strip()

    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({
            "ok": False,
            "error": "Idioma não suportado."
        }), 400

    session_id = str(
        uuid.uuid4()
    )

    with jobs_lock:

        jobs[session_id] = {

            "sessionId": session_id,

            "youtubeUrl": youtube_url,

            "targetLang": target_lang,

            "status": "ready",

            "audioCapture": "waiting",

            "lastTranscript": "",

            "lastTranslation": "",

            "audio": None,

            "error": None,

            "createdAt": time.time()

        }

    return jsonify({

        "ok": True,

        "sessionId": session_id,

        "targetLang": target_lang,

        "status": "ready",

        "message": "Sessão criada."

    })


# =========================================================
# STATUS
# =========================================================

@app.get("/api/session/<session_id>")
def session_status(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

        if job:
            job = dict(job)

    if not job:

        return jsonify({
            "ok": False,
            "error": "Sessão não encontrada."
        }), 404

    return jsonify({
        "ok": True,
        **job
    })


# =========================================================
# INICIAR CAPTURA
# =========================================================

@app.post("/api/session/<session_id>/start")
def start_session(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

        if not job:

            return jsonify({
                "ok": False,
                "error": "Sessão não encontrada."
            }), 404

        job["status"] = "capturing"

        job["audioCapture"] = "running"

        job["message"] = (
            "Recebendo áudio do navegador."
        )

    return jsonify({

        "ok": True,

        "sessionId": session_id,

        "status": "capturing",

        "audioCapture": "running",

        "message":
            "Captura de áudio iniciada."

    })


# =========================================================
# RECEBER ÁUDIO
# =========================================================

@app.post("/api/session/<session_id>/audio")
def receive_audio(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Sessão não encontrada."
        }), 404


    # -----------------------------------------------------
    # Verificar arquivo
    # -----------------------------------------------------

    audio_file = request.files.get(
        "audio"
    )


    if audio_file:

        audio_bytes = audio_file.read()

        if not audio_bytes:

            return jsonify({
                "ok": False,
                "error": "Áudio vazio."
            }), 400

        audio_size = len(
            audio_bytes
        )

        update_job(
            session_id,
            audioCapture="running",
            lastAudioSize=audio_size,
            lastAudioAt=time.time(),
            message="Áudio recebido."
        )

        return jsonify({

            "ok": True,

            "sessionId":
                session_id,

            "received": True,

            "bytes":
                audio_size,

            "message":
                "Bloco de áudio recebido."

        })


    # -----------------------------------------------------
    # Base64
    # -----------------------------------------------------

    data = (
        request.get_json(
            silent=True
        )
        or {}
    )

    audio_base64 = data.get(
        "audio"
    )


    if audio_base64:

        try:

            audio_bytes = base64.b64decode(
                audio_base64
            )

        except Exception:

            return jsonify({
                "ok": False,
                "error": "Áudio Base64 inválido."
            }), 400


        update_job(
            session_id,
            audioCapture="running",
            lastAudioSize=len(audio_bytes),
            lastAudioAt=time.time(),
            message="Áudio recebido."
        )


        return jsonify({

            "ok": True,

            "sessionId":
                session_id,

            "received": True,

            "bytes":
                len(audio_bytes),

            "message":
                "Bloco de áudio recebido."

        })


    return jsonify({

        "ok": False,

        "error":
            "Envie um arquivo no campo 'audio'."

    }), 400


# =========================================================
# TEXTO PARA TRADUÇÃO
# =========================================================
#
# Este endpoint recebe texto transcrito.
# Depois podemos conectar Whisper/Google/Deepgram/etc.
#
# =========================================================

@app.post("/api/session/<session_id>/text")
def receive_text(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Sessão não encontrada."
        }), 404


    data = (
        request.get_json(
            silent=True
        )
        or {}
    )


    text = str(
        data.get(
            "text",
            ""
        )
    ).strip()


    if not text:

        return jsonify({
            "ok": False,
            "error": "Texto vazio."
        }), 400


    update_job(
        session_id,
        lastTranscript=text,
        status="translating",
        message="Texto recebido."
    )


    # -----------------------------------------------------
    # Nesta primeira versão, se o texto já estiver em
    # português, devolvemos o próprio texto.
    #
    # A etapa de tradução automática será conectada
    # posteriormente ao motor de tradução.
    # -----------------------------------------------------

    target_lang = job.get(
        "targetLang",
        "pt"
    )


    if target_lang == "pt":

        translated = text

    else:

        translated = text


    update_job(

        session_id,

        lastTranslation=translated,

        status="running",

        message="Tradução processada."

    )


    return jsonify({

        "ok": True,

        "sessionId":
            session_id,

        "original":
            text,

        "translation":
            translated,

        "targetLang":
            target_lang

    })


# =========================================================
# TEXT-TO-SPEECH ELEVENLABS
# =========================================================

@app.post("/api/session/<session_id>/tts")
def text_to_speech(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Sessão não encontrada."
        }), 404


    data = (
        request.get_json(
            silent=True
        )
        or {}
    )


    text = str(
        data.get(
            "text",
            ""
        )
    ).strip()


    if not text:

        return jsonify({
            "ok": False,
            "error": "Texto para voz não informado."
        }), 400


    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "error":
                "ELEVENLABS_API_KEY não configurada no Render."
        }), 500


    if not VOICE_ID:

        return jsonify({
            "ok": False,
            "error":
                "VOICE_ID não configurado."
        }), 500


    url = (
        "https://api.elevenlabs.io/v1/text-to-speech/"
        + VOICE_ID
    )


    payload = {

        "text": text,

        "model_id":
            "eleven_multilingual_v2",

        "voice_settings": {

            "stability": 0.5,

            "similarity_boost": 0.75

        }

    }


    try:

        response = requests.post(

            url,

            headers={
                "xi-api-key":
                    ELEVENLABS_API_KEY,

                "Content-Type":
                    "application/json",

                "Accept":
                    "audio/mpeg"

            },

            json=payload,

            timeout=60

        )


        if not response.ok:

            return jsonify({

                "ok": False,

                "error":
                    "ElevenLabs retornou "
                    f"{response.status_code}",

                "details":
                    response.text

            }), 500


        audio_base64 = base64.b64encode(
            response.content
        ).decode("utf-8")


        update_job(

            session_id,

            status="running",

            lastTranslation=text,

            message="Áudio gerado pela ElevenLabs."

        )


        return jsonify({

            "ok": True,

            "sessionId":
                session_id,

            "voiceId":
                VOICE_ID,

            "audio":
                audio_base64,

            "mimeType":
                "audio/mpeg"

        })


    except requests.RequestException as error:

        return jsonify({

            "ok": False,

            "error":
                f"Erro comunicando com ElevenLabs: {error}"

        }), 500


# =========================================================
# TESTE DA VOZ
# =========================================================

@app.get("/api/voice")
def test_voice():

    return jsonify({

        "ok":
            bool(ELEVENLABS_API_KEY),

        "provider":
            "ElevenLabs",

        "voice_id":
            VOICE_ID,

        "agent_id":
            AGENT_ID,

        "message":
            "VOICE_ID configurado."

    })


# =========================================================
# PARAR SESSÃO
# =========================================================

@app.post("/api/session/<session_id>/stop")
def stop_session(session_id):

    with jobs_lock:

        job = jobs.get(
            session_id
        )

        if not job:

            return jsonify({
                "ok": False,
                "error": "Sessão não encontrada."
            }), 404


        job["status"] = "stopped"

        job["audioCapture"] = "stopped"

        job["message"] = "Sessão parada."


    return jsonify({

        "ok": True,

        "sessionId":
            session_id,

        "status":
            "stopped",

        "message":
            "Sessão parada."

    })


# =========================================================
# EXECUÇÃO
# =========================================================

if __name__ == "__main__":

    print(
        "========================================"
    )

    print(
        "SI Tradutor Live"
    )

    print(
        "Servidor iniciando..."
    )

    print(
        f"Porta: {PORT}"
    )

    print(
        f"Agent ID: {AGENT_ID}"
    )

    print(
        f"Voice ID: {VOICE_ID}"
    )

    print(
        "========================================"
    )


    app.run(

        host="0.0.0.0",

        port=PORT,

        debug=False

    )

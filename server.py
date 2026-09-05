import os
import uuid
import time
import json
import threading
import subprocess
import tempfile
import base64
import shutil

import requests

from flask import Flask, request, jsonify, Response
from flask_cors import CORS


# =========================================================
# APP
# =========================================================

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

VOICE_ID = os.getenv(
    "VOICE_ID",
    "cjVigY5qzO86Huf0OWal"
)

ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"

PORT = int(os.getenv("PORT", "10000"))

jobs = {}
jobs_lock = threading.Lock()


# =========================================================
# IDIOMAS
# =========================================================

ALLOWED_LANGUAGES = {
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
# UTILITÁRIOS
# =========================================================

def update_job(job_id, **values):
    with jobs_lock:
        if job_id in jobs:
            jobs[job_id].update(values)


def get_job(job_id):
    with jobs_lock:
        job = jobs.get(job_id)

        if job:
            return dict(job)

    return None


def extract_youtube_id(url):
    if not url:
        return None

    url = url.strip()

    patterns = [
        "/live/",
        "watch?v=",
        "youtu.be/",
        "/embed/",
        "/shorts/"
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
# ELEVENLABS
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


def get_agent_signed_url():
    url = (
        ELEVENLABS_BASE_URL
        + "/convai/conversation/get-signed-url"
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
            f"ElevenLabs erro {response.status_code}: "
            f"{response.text}"
        )

    data = response.json()

    signed_url = data.get("signed_url")

    if not signed_url:
        raise Exception(
            "ElevenLabs não retornou signed_url."
        )

    return signed_url


# =========================================================
# ELEVENLABS TTS
# =========================================================

def elevenlabs_tts(text):
    """
    Converte texto traduzido em MP3 usando ElevenLabs.
    """

    if not text:
        return None

    if not ELEVENLABS_API_KEY:
        raise Exception(
            "ELEVENLABS_API_KEY não configurada."
        )

    if not VOICE_ID:
        raise Exception(
            "VOICE_ID não configurado."
        )

    url = (
        ELEVENLABS_BASE_URL
        + "/text-to-speech/"
        + VOICE_ID
    )

    payload = {
        "text": text,
        "model_id": "eleven_multilingual_v2",
        "voice_settings": {
            "stability": 0.5,
            "similarity_boost": 0.75
        }
    }

    response = requests.post(
        url,
        headers=eleven_headers(),
        json=payload,
        timeout=60
    )

    if not response.ok:
        raise Exception(
            f"ElevenLabs TTS erro "
            f"{response.status_code}: "
            f"{response.text}"
        )

    return response.content


# =========================================================
# TRADUÇÃO
# =========================================================

def translate_text(text, target_lang):
    """
    Tradução simples usando MyMemory.

    Para produção com maior qualidade, pode ser substituída
    por outro provedor de tradução.
    """

    if not text:
        return ""

    if target_lang == "pt":
        return text

    try:
        url = "https://api.mymemory.translated.net/get"

        response = requests.get(
            url,
            params={
                "q": text,
                "langpair": f"auto|{target_lang}"
            },
            timeout=30
        )

        if not response.ok:
            return text

        data = response.json()

        translated = (
            data.get("responseData", {})
            .get("translatedText")
        )

        if translated:
            return translated

    except Exception:
        pass

    return text


# =========================================================
# YOUTUBE / YT-DLP
# =========================================================

def get_youtube_audio_url(youtube_url):

    try:
        command = [
            "yt-dlp",
            "-f",
            "bestaudio/best",
            "-g",
            "--no-playlist",
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
                or result.stdout.strip()
                or "Erro desconhecido do yt-dlp."
            )

            return None, error

        urls = [
            line.strip()
            for line in result.stdout.splitlines()
            if line.strip()
        ]

        if not urls:
            return None, "YouTube não retornou URL de áudio."

        return urls[0], None

    except FileNotFoundError:
        return None, "yt-dlp não está instalado."

    except subprocess.TimeoutExpired:
        return None, "Tempo limite obtendo áudio do YouTube."

    except Exception as error:
        return None, str(error)


# =========================================================
# CAPTURA DE ÁUDIO
# =========================================================

def download_audio(audio_url, output_file):

    command = [
        "ffmpeg",
        "-y",
        "-i",
        audio_url,
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-t",
        "15",
        output_file
    ]

    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=40
    )

    if result.returncode != 0:

        raise Exception(
            result.stderr[-3000:]
        )

    if not os.path.exists(output_file):
        raise Exception(
            "FFmpeg não gerou o arquivo de áudio."
        )

    if os.path.getsize(output_file) == 0:
        raise Exception(
            "Arquivo de áudio vazio."
        )


# =========================================================
# TRANSCRIÇÃO
# =========================================================

def transcribe_audio(audio_file):

    """
    Esta função tenta usar o Whisper instalado no ambiente.

    Se Whisper não estiver instalado, retorna uma mensagem
    informando que a transcrição precisa ser configurada.
    """

    try:
        import whisper

    except ImportError:
        raise Exception(
            "Whisper não está instalado no servidor."
        )

    model_name = os.getenv(
        "WHISPER_MODEL",
        "tiny"
    )

    model = whisper.load_model(
        model_name
    )

    result = model.transcribe(
        audio_file,
        fp16=False
    )

    text = (
        result.get("text", "")
        .strip()
    )

    return text


# =========================================================
# PROCESSAMENTO
# =========================================================

def process_live(
    live_id,
    youtube_url,
    target_lang
):

    temp_dir = tempfile.mkdtemp(
        prefix="si_live_"
    )

    try:

        # -------------------------------------------------
        # 1. YOUTUBE
        # -------------------------------------------------

        update_job(
            live_id,
            status="capturing",
            audioCapture="connecting",
            message="🎙️ Obtendo áudio da YouTube Live..."
        )

        audio_url, error = (
            get_youtube_audio_url(
                youtube_url
            )
        )

        if not audio_url:

            if (
                "Sign in to confirm" in error
                or "not a bot" in error
            ):
                error_message = (
                    "O YouTube bloqueou a captura "
                    "automática desta transmissão "
                    "porque solicitou verificação "
                    "de bot/login."
                )
            else:
                error_message = (
                    "Não foi possível obter o áudio "
                    f"da YouTube Live: {error}"
                )

            update_job(
                live_id,
                status="error",
                audioCapture="error",
                error=error_message,
                message=error_message
            )

            return


        update_job(
            live_id,
            status="capturing",
            audioCapture="running",
            message=(
                "🟢 Áudio conectado. "
                "Iniciando transcrição..."
            )
        )


        # -------------------------------------------------
        # LOOP
        # -------------------------------------------------

        while True:

            job = get_job(live_id)

            if not job:
                break

            if job.get("status") == "stopped":
                break


            # ---------------------------------------------
            # CAPTURAR 15 SEGUNDOS
            # ---------------------------------------------

            audio_file = os.path.join(
                temp_dir,
                f"{uuid.uuid4()}.wav"
            )

            try:

                download_audio(
                    audio_url,
                    audio_file
                )

            except Exception as error:

                update_job(
                    live_id,
                    status="error",
                    audioCapture="error",
                    error=(
                        "Erro capturando áudio: "
                        + str(error)
                    ),
                    message=(
                        "❌ Erro capturando áudio."
                    )
                )

                break


            # ---------------------------------------------
            # TRANSCRIÇÃO
            # ---------------------------------------------

            update_job(
                live_id,
                status="transcribing",
                message="🎙️ Transcrevendo áudio..."
            )

            try:

                text = transcribe_audio(
                    audio_file
                )

            except Exception as error:

                update_job(
                    live_id,
                    status="error",
                    error=str(error),
                    message=(
                        "❌ Erro na transcrição."
                    )
                )

                break


            if not text:

                try:
                    os.remove(audio_file)
                except Exception:
                    pass

                continue


            update_job(
                live_id,
                lastTranscript=text
            )


            # ---------------------------------------------
            # TRADUÇÃO
            # ---------------------------------------------

            update_job(
                live_id,
                status="translating",
                message=(
                    "🌎 Traduzindo para "
                    + ALLOWED_LANGUAGES.get(
                        target_lang,
                        target_lang
                    )
                    + "..."
                )
            )

            translated = translate_text(
                text,
                target_lang
            )


            update_job(
                live_id,
                lastTranslation=translated
            )


            # ---------------------------------------------
            # ELEVENLABS
            # ---------------------------------------------

            update_job(
                live_id,
                status="speaking",
                message=(
                    "🔊 Gerando voz com ElevenLabs..."
                )
            )

            try:

                audio_data = elevenlabs_tts(
                    translated
                )

                audio_base64 = base64.b64encode(
                    audio_data
                ).decode("utf-8")

                update_job(
                    live_id,
                    status="running",
                    audioCapture="running",
                    audioBase64=audio_base64,
                    message=(
                        "🟢 Tradução ativa e "
                        "conectada ao servidor."
                    )
                )

            except Exception as error:

                update_job(
                    live_id,
                    status="error",
                    error=str(error),
                    message=(
                        "❌ Erro gerando voz "
                        "na ElevenLabs."
                    )
                )

                break


            # ---------------------------------------------
            # LIMPEZA
            # ---------------------------------------------

            try:
                os.remove(audio_file)
            except Exception:
                pass


            # ---------------------------------------------
            # ESPERAR
            # ---------------------------------------------

            time.sleep(1)


    except Exception as error:

        update_job(
            live_id,
            status="error",
            audioCapture="error",
            error=str(error),
            message="❌ Erro no processamento da live."
        )

    finally:

        shutil.rmtree(
            temp_dir,
            ignore_errors=True
        )


# =========================================================
# HOME
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


# =========================================================
# HEALTH
# =========================================================

@app.get("/api/health")
def health():

    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "message": (
                "ELEVENLABS_API_KEY "
                "não configurada no Render."
            )
        }), 500

    return jsonify({
        "ok": True,
        "message": (
            "Servidor conectado à ElevenLabs."
        ),
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "voice_id": VOICE_ID
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
            "agent_id": AGENT_ID,
            "signed_url": signed_url
        })

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
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
            "agent_id": AGENT_ID,
            "signed_url": signed_url
        })

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
        }), 500


# =========================================================
# TESTE DA VOZ
# =========================================================

@app.get("/api/voice")
def voice():

    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "error": (
                "ELEVENLABS_API_KEY "
                "não configurada."
            )
        }), 500

    try:

        audio = elevenlabs_tts(
            "Teste de voz do SI Tradutor Live."
        )

        return Response(
            audio,
            mimetype="audio/mpeg",
            headers={
                "Cache-Control": "no-cache"
            }
        )

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
            "error": (
                "ELEVENLABS_API_KEY "
                "não configurada."
            )
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
            "error": (
                "Cole o link da YouTube Live."
            )
        }), 400


    video_id = (
        extract_youtube_id(
            youtube_url
        )
    )


    if not video_id:

        return jsonify({
            "ok": False,
            "error": (
                "Não foi possível identificar "
                "o vídeo do YouTube."
            )
        }), 400


    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({
            "ok": False,
            "error": (
                "Idioma de destino não suportado."
            )
        }), 400


    live_id = str(
        uuid.uuid4()
    )


    with jobs_lock:

        jobs[live_id] = {

            "liveId": live_id,

            "youtubeUrl": youtube_url,

            "youtubeId": video_id,

            "targetLang": target_lang,

            "status": "queued",

            "audioCapture": "starting",

            "lastTranscript": "",

            "lastTranslation": "",

            "audioBase64": None,

            "error": None,

            "message": (
                "Preparando captura de áudio..."
            ),

            "createdAt": time.time()
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

        "liveId": live_id,

        "youtubeId": video_id,

        "targetLang": target_lang,

        "status": "queued",

        "message": (
            "🔴 Live iniciada. "
            "Preparando tradução."
        )

    })


# =========================================================
# STATUS
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>"
)
def youtube_live_status(live_id):

    job = get_job(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404


    return jsonify({
        "ok": True,
        **job
    })


# =========================================================
# ÁUDIO
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>/audio"
)
def youtube_live_audio(live_id):

    job = get_job(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404


    audio_base64 = job.get(
        "audioBase64"
    )


    if not audio_base64:

        return jsonify({
            "ok": False,
            "audioCapture": job.get(
                "audioCapture"
            ),
            "status": job.get(
                "status"
            ),
            "message": job.get(
                "message"
            ),
            "error": job.get(
                "error"
            )
        }), 409


    try:

        audio_data = base64.b64decode(
            audio_base64
        )

        return Response(
            audio_data,
            mimetype="audio/mpeg",
            headers={
                "Cache-Control":
                    "no-cache"
            }
        )

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
        }), 500


# =========================================================
# PARAR
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/stop"
)
def stop_live(live_id):

    job = get_job(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404


    update_job(
        live_id,
        status="stopped",
        audioCapture="stopped",
        audioBase64=None,
        message="🛑 Live parada."
    )


    return jsonify({
        "ok": True,
        "liveId": live_id,
        "status": "stopped",
        "message": "Live parada."
    })


# =========================================================
# EXECUÇÃO
# =========================================================

if __name__ == "__main__":

    print(
        "======================================"
    )

    print(
        "SI Tradutor Live"
    )

    print(
        "Servidor iniciado"
    )

    print(
        f"Porta: {PORT}"
    )

    print(
        f"Agent: {AGENT_ID}"
    )

    print(
        f"Voice: {VOICE_ID}"
    )

    print(
        "======================================"
    )

    app.run(
        host="0.0.0.0",
        port=PORT
        )

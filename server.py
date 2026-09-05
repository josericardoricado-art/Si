import os
import uuid
import time
import threading
import base64
import io

import requests

from flask import Flask, request, jsonify, Response
from flask_cors import CORS


# =========================================================
# SI TRADUTOR LIVE
# YouTube Live → Áudio do navegador → Tradução → ElevenLabs
# =========================================================


# =========================================================
# FLASK
# =========================================================

app = Flask(__name__)

CORS(
    app,
    resources={
        r"/*": {
            "origins": "*"
        }
    }
)


# =========================================================
# CONFIGURAÇÕES
# =========================================================

ELEVENLABS_API_KEY = os.getenv(
    "ELEVENLABS_API_KEY",
    ""
).strip()


AGENT_ID = os.getenv(
    "AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
).strip()


VOICE_ID = os.getenv(
    "VOICE_ID",
    "cjVigY5qzO86Huf0OWal"
).strip()


ELEVENLABS_BASE_URL = (
    "https://api.elevenlabs.io/v1"
)


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
# MEMÓRIA DOS TRABALHOS
# =========================================================

jobs = {}

jobs_lock = threading.Lock()


# =========================================================
# ÁUDIO GERADO
# =========================================================

audio_cache = {}

audio_lock = threading.Lock()


# =========================================================
# HEADERS ELEVENLABS
# =========================================================

def elevenlabs_headers():

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

def update_job(
    job_id,
    **values
):

    with jobs_lock:

        if job_id in jobs:

            jobs[job_id].update(
                values
            )


# =========================================================
# CRIAR JOB
# =========================================================

def create_job(
    youtube_url,
    youtube_id,
    target_lang
):

    job_id = str(
        uuid.uuid4()
    )

    job = {

        "liveId": job_id,

        "youtubeUrl":
            youtube_url,

        "youtubeId":
            youtube_id,

        "targetLang":
            target_lang,

        "status":
            "waiting_audio",

        "audioCapture":
            "waiting",

        "translationStatus":
            "waiting",

        "lastTranscript":
            "",

        "lastTranslation":
            "",

        "audioReady":
            False,

        "message":
            "Aguardando áudio do navegador.",

        "error":
            None,

        "createdAt":
            time.time(),

        "updatedAt":
            time.time()
    }

    with jobs_lock:

        jobs[job_id] = job

    return job_id


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

        if pattern not in url:

            continue

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
# SIGNED URL ELEVENLABS
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
        ELEVENLABS_BASE_URL
        + "/convai/conversation/get-signed-url"
    )

    response = requests.get(

        url,

        headers={
            "xi-api-key":
                ELEVENLABS_API_KEY
        },

        params={
            "agent_id":
                AGENT_ID
        },

        timeout=30
    )

    if not response.ok:

        raise Exception(
            "ElevenLabs erro "
            + str(response.status_code)
            + ": "
            + response.text
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
# GERAR ÁUDIO COM ELEVENLABS
# =========================================================

def generate_elevenlabs_audio(
    text
):

    if not text:

        raise Exception(
            "Texto vazio para geração de áudio."
        )

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

        "text":
            text,

        "model_id":
            "eleven_multilingual_v2",

        "voice_settings": {

            "stability":
                0.5,

            "similarity_boost":
                0.75
        }
    }

    headers = {
        "xi-api-key":
            ELEVENLABS_API_KEY,

        "Content-Type":
            "application/json",

        "Accept":
            "audio/mpeg"
    }

    response = requests.post(

        url,

        json=payload,

        headers=headers,

        timeout=90
    )

    if not response.ok:

        raise Exception(
            "ElevenLabs TTS erro "
            + str(response.status_code)
            + ": "
            + response.text
        )

    if not response.content:

        raise Exception(
            "ElevenLabs não retornou áudio."
        )

    return response.content


# =========================================================
# TESTAR ELEVENLABS
# =========================================================

def test_elevenlabs():

    if not ELEVENLABS_API_KEY:

        return False, (
            "ELEVENLABS_API_KEY não configurada."
        )

    try:

        response = requests.get(

            ELEVENLABS_BASE_URL
            + "/user",

            headers={
                "xi-api-key":
                    ELEVENLABS_API_KEY
            },

            timeout=20
        )

        if response.ok:

            return True, "ElevenLabs conectado."

        return False, (
            "ElevenLabs retornou "
            + str(response.status_code)
        )

    except Exception as error:

        return False, str(error)


# =========================================================
# HOME
# =========================================================

@app.get("/")
def home():

    return jsonify({

        "ok":
            True,

        "service":
            "SI Tradutor Live",

        "provider":
            "ElevenLabs",

        "agent_id":
            AGENT_ID,

        "voice_id":
            VOICE_ID,

        "architecture":
            "YouTube Browser Audio → Render → ElevenLabs",

        "message":
            "Servidor funcionando."

    })


# =========================================================
# HEALTH
# =========================================================

@app.get("/api/health")
def health():

    eleven_ok, eleven_message = (
        test_elevenlabs()
    )

    return jsonify({

        "ok":
            eleven_ok,

        "server":
            True,

        "elevenlabs":
            eleven_ok,

        "message":
            eleven_message,

        "provider":
            "ElevenLabs",

        "service":
            "SI Tradutor Live",

        "agent_id":
            AGENT_ID,

        "voice_id":
            VOICE_ID

    }), (200 if eleven_ok else 500)


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

            "ok":
                True,

            "agent_id":
                AGENT_ID,

            "signed_url":
                signed_url

        })

    except Exception as error:

        return jsonify({

            "ok":
                False,

            "error":
                str(error)

        }), 500


# =========================================================
# SIGNED URL
# =========================================================

@app.get(
    "/api/elevenlabs/signed-url"
)
def elevenlabs_signed_url():

    try:

        signed_url = (
            get_agent_signed_url()
        )

        return jsonify({

            "ok":
                True,

            "agent_id":
                AGENT_ID,

            "signed_url":
                signed_url

        })

    except Exception as error:

        return jsonify({

            "ok":
                False,

            "error":
                str(error)

        }), 500


# =========================================================
# INICIAR LIVE
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
    ).strip().lower()


    if not youtube_url:

        return jsonify({

            "ok":
                False,

            "error":
                "Cole o link da YouTube Live."

        }), 400


    youtube_id = (
        extract_youtube_id(
            youtube_url
        )
    )


    if not youtube_id:

        return jsonify({

            "ok":
                False,

            "error":
                "Link do YouTube inválido."

        }), 400


    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({

            "ok":
                False,

            "error":
                "Idioma não suportado."

        }), 400


    live_id = create_job(

        youtube_url,

        youtube_id,

        target_lang

    )


    return jsonify({

        "ok":
            True,

        "liveId":
            live_id,

        "youtubeId":
            youtube_id,

        "targetLang":
            target_lang,

        "status":
            "waiting_audio",

        "audioCapture":
            "waiting",

        "message":
            "Live criada. "
            "Aguardando áudio capturado pelo navegador."

    })


# =========================================================
# RECEBER ÁUDIO DO NAVEGADOR
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/audio"
)
def receive_audio(
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

            "ok":
                False,

            "error":
                "Live não encontrada."

        }), 404


    # -----------------------------------------------------
    # OPÇÃO 1 — arquivo multipart
    # -----------------------------------------------------

    audio_file = request.files.get(
        "audio"
    )


    if audio_file:

        try:

            audio_data = (
                audio_file.read()
            )

        except Exception as error:

            return jsonify({

                "ok":
                    False,

                "error":
                    "Erro lendo áudio: "
                    + str(error)

            }), 400

    else:

        # -------------------------------------------------
        # OPÇÃO 2 — JSON base64
        # -------------------------------------------------

        data = (
            request.get_json(
                silent=True
            )
            or {}
        )

        audio_base64 = data.get(
            "audio"
        )

        if not audio_base64:

            return jsonify({

                "ok":
                    False,

                "error":
                    "Nenhum áudio recebido."

            }), 400

        try:

            if "," in audio_base64:

                audio_base64 = (
                    audio_base64.split(
                        ",",
                        1
                    )[1]
                )

            audio_data = (
                base64.b64decode(
                    audio_base64
                )
            )

        except Exception as error:

            return jsonify({

                "ok":
                    False,

                "error":
                    "Áudio base64 inválido: "
                    + str(error)

            }), 400


    if not audio_data:

        return jsonify({

            "ok":
                False,

            "error":
                "O áudio recebido está vazio."

        }), 400


    # -----------------------------------------------------
    # Limite de segurança
    # -----------------------------------------------------

    max_audio_size = (
        8 * 1024 * 1024
    )

    if len(audio_data) > max_audio_size:

        return jsonify({

            "ok":
                False,

            "error":
                "Arquivo de áudio muito grande."

        }), 413


    # -----------------------------------------------------
    # Guardar último áudio
    # -----------------------------------------------------

    with audio_lock:

        audio_cache[live_id] = {

            "data":
                audio_data,

            "content_type":
                request.files.get(
                    "audio"
                ).content_type
                if request.files.get("audio")
                else "audio/webm",

            "createdAt":
                time.time()
        }


    update_job(

        live_id,

        status="audio_received",

        audioCapture="running",

        message="Áudio recebido do navegador.",

        audioReady=True,

        updatedAt=time.time()

    )


    return jsonify({

        "ok":
            True,

        "liveId":
            live_id,

        "audioCapture":
            "running",

        "bytes":
            len(audio_data),

        "message":
            "Áudio recebido."

    })


# =========================================================
# OBTER ÚLTIMO ÁUDIO
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>/audio"
)
def get_audio(
    live_id
):

    with audio_lock:

        audio = audio_cache.get(
            live_id
        )

        if audio:

            audio = dict(audio)


    if not audio:

        return jsonify({

            "ok":
                False,

            "error":
                "Nenhum áudio disponível."

        }), 404


    return Response(

        audio["data"],

        mimetype=audio.get(
            "content_type",
            "audio/webm"
        ),

        headers={

            "Cache-Control":
                "no-cache",

            "X-Live-ID":
                live_id
        }

    )


# =========================================================
# STATUS
# =========================================================

@app.get(
    "/api/youtube-live/<live_id>"
)
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

            "ok":
                False,

            "error":
                "Live não encontrada."

        }), 404


    return jsonify({

        "ok":
            True,

        **job

    })


# =========================================================
# ATUALIZAR TEXTO DA TRADUÇÃO
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/translation"
)
def update_translation(
    live_id
):

    with jobs_lock:

        if live_id not in jobs:

            return jsonify({

                "ok":
                    False,

                "error":
                    "Live não encontrada."

            }), 404


    data = (
        request.get_json(
            silent=True
        )
        or {}
    )


    transcript = str(
        data.get(
            "transcript",
            ""
        )
    ).strip()


    translation = str(
        data.get(
            "translation",
            ""
        )
    ).strip()


    if transcript:

        update_job(

            live_id,

            lastTranscript=
                transcript

        )


    if translation:

        update_job(

            live_id,

            lastTranslation=
                translation,

            translationStatus=
                "translated",

            status=
                "translating"

        )


    return jsonify({

        "ok":
            True,

        "liveId":
            live_id,

        "lastTranscript":
            transcript,

        "lastTranslation":
            translation

    })


# =========================================================
# GERAR VOZ ELEVENLABS
# =========================================================

@app.post(
    "/api/youtube-live/<live_id>/tts"
)
def text_to_speech(
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

            "ok":
                False,

            "error":
                "Live não encontrada."

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

        text = str(
            job.get(
                "lastTranslation",
                ""
            )
        ).strip()


    if not text:

        return jsonify({

            "ok":
                False,

            "error":
                "Nenhum texto para transformar em voz."

        }), 400


    try:

        update_job(

            live_id,

            status="generating_audio",

            message=
                "Gerando voz com ElevenLabs.",

            error=None

        )


        audio_data = (
            generate_elevenlabs_audio(
                text
            )
        )


        audio_id = str(
            uuid.uuid4()
        )


        with audio_lock:

            audio_cache[
                audio_id
            ] = {

                "data":
                    audio_data,

                "content_type":
                    "audio/mpeg",

                "createdAt":
                    time.time()
            }


        update_job(

            live_id,

            status="ready",

            audioReady=True,

            translationStatus=
                "translated",

            message=
                "Tradução e voz prontas.",

            audioId=
                audio_id,

            updatedAt=
                time.time()

        )


        return jsonify({

            "ok":
                True,

            "liveId":
                live_id,

            "audioId":
                audio_id,

            "text":
                text,

            "message":
                "Áudio gerado pela ElevenLabs."

        })


    except Exception as error:

        update_job(

            live_id,

            status="error",

            error=str(error),

            message=
                "Erro gerando voz com ElevenLabs."

        )


        return jsonify({

            "ok":
                False,

            "error":
                str(error)

        }), 500


# =========================================================
# TOCAR ÁUDIO GERADO
# =========================================================

@app.get(
    "/api/audio/<audio_id>"
)
def serve_generated_audio(
    audio_id
):

    with audio_lock:

        audio = audio_cache.get(
            audio_id
        )

        if audio:

            audio = dict(audio)


    if not audio:

        return jsonify({

            "ok":
                False,

            "error":
                "Áudio não encontrado."

        }), 404


    return Response(

        audio["data"],

        mimetype="audio/mpeg",

        headers={

            "Cache-Control":
                "no-cache"
        }

    )


# =========================================================
# TESTE DA VOZ
# =========================================================

@app.get("/api/voice")
def voice():

    if not ELEVENLABS_API_KEY:

        return jsonify({

            "ok":
                False,

            "error":
                "ELEVENLABS_API_KEY não configurada."

        }), 500


    if not VOICE_ID:

        return jsonify({

            "ok":
                False,

            "error":
                "VOICE_ID não configurado."

        }), 500


    return jsonify({

        "ok":
            True,

        "provider":
            "ElevenLabs",

        "voice_id":
            VOICE_ID,

        "message":
            "VOICE_ID configurado."

    })


# =========================================================
# TESTE TTS
# =========================================================

@app.post("/api/test-voice")
def test_voice():

    data = (
        request.get_json(
            silent=True
        )
        or {}
    )


    text = str(
        data.get(
            "text",
            "Olá! Este é um teste do SI Tradutor Live."
        )
    ).strip()


    try:

        audio_data = (
            generate_elevenlabs_audio(
                text
            )
        )


        audio_id = str(
            uuid.uuid4()
        )


        with audio_lock:

            audio_cache[
                audio_id
            ] = {

                "data":
                    audio_data,

                "content_type":
                    "audio/mpeg",

                "createdAt":
                    time.time()
            }


        return jsonify({

            "ok":
                True,

            "audioId":
                audio_id,

            "voice_id":
                VOICE_ID,

            "message":
                "Teste de voz concluído."

        })


    except Exception as error:

        return jsonify({

            "ok":
                False,

            "error":
                str(error)

        }), 500


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

                "ok":
                    False,

                "error":
                    "Live não encontrada."

            }), 404


        job["status"] = "stopped"

        job["audioCapture"] = "stopped"

        job["translationStatus"] = "stopped"

        job["audioReady"] = False

        job["message"] = (
            "Live parada."
        )

        job["updatedAt"] = (
            time.time()
        )


    with audio_lock:

        if live_id in audio_cache:

            del audio_cache[
                live_id
            ]


    return jsonify({

        "ok":
            True,

        "liveId":
            live_id,

        "status":
            "stopped",

        "message":
            "Live parada."

    })


# =========================================================
# LIMPEZA AUTOMÁTICA
# =========================================================

def cleanup_old_jobs():

    while True:

        try:

            now = time.time()

            expired_jobs = []

            with jobs_lock:

                for job_id, job in list(
                    jobs.items()
                ):

                    created_at = job.get(
                        "createdAt",
                        now
                    )

                    if (
                        now - created_at
                        > 3600
                    ):

                        expired_jobs.append(
                            job_id
                        )


                for job_id in expired_jobs:

                    jobs.pop(
                        job_id,
                        None
                    )


            with audio_lock:

                for key, value in list(
                    audio_cache.items()
                ):

                    created_at = value.get(
                        "createdAt",
                        now
                    )

                    if (
                        now - created_at
                        > 3600
                    ):

                        audio_cache.pop(
                            key,
                            None
                        )


        except Exception:

            pass


        time.sleep(
            300
        )


# =========================================================
# THREAD DE LIMPEZA
# =========================================================

cleanup_thread = threading.Thread(

    target=cleanup_old_jobs,

    daemon=True

)

cleanup_thread.start()


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
        "Porta:",
        port
    )

    print(
        "Agent ID:",
        AGENT_ID
    )

    print(
        "Voice ID:",
        VOICE_ID
    )

    print(
        "======================================"
    )


    app.run(

        host="0.0.0.0",

        port=port,

        debug=False

    )

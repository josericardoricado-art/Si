import os
import uuid
import time
import threading
import base64

import requests

from flask import Flask, request, jsonify, Response
from flask_cors import CORS


# =========================================================
# SI TRADUTOR LIVE
# Microfone / áudio da tela
#        ↓
# DeepL Voice
#        ↓
# Tradução + voz traduzida
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
# CONFIGURAÇÕES DEEPL
# =========================================================

DEEPL_API_KEY = os.getenv(
    "DEEPL_API_KEY",
    ""
).strip()


DEEPL_VOICE_URL = (
    "https://api.deepl.com/v3/voice/realtime"
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
# ÁUDIO
# =========================================================

audio_cache = {}

audio_lock = threading.Lock()


# =========================================================
# VERIFICAR DEEPL
# =========================================================

def check_deepl_key():

    if not DEEPL_API_KEY:

        raise Exception(
            "DEEPL_API_KEY não configurada no Render."
        )


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

            jobs[job_id][
                "updatedAt"
            ] = time.time()


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

        "liveId":
            job_id,

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
# NORMALIZAR IDIOMA DEEPL
# =========================================================

def normalize_target_language(
    language
):

    language = str(
        language or "pt"
    ).strip().lower()


    mapping = {

        "pt":
            "PT-BR",

        "pt-br":
            "PT-BR",

        "en":
            "EN",

        "en-us":
            "EN-US",

        "en-gb":
            "EN-GB",

        "es":
            "ES",

        "fr":
            "FR",

        "de":
            "DE",

        "it":
            "IT",

        "ja":
            "JA",

        "ko":
            "KO",

        "zh":
            "ZH",

        "ar":
            "AR"
    }


    return mapping.get(
        language,
        language.upper()
    )


# =========================================================
# CONTENT TYPE DO ÁUDIO
# =========================================================

def get_audio_content_type():

    content_type = (
        request.headers.get(
            "X-Audio-Content-Type"
        )
        or request.form.get(
            "contentType"
        )
        or "audio/webm;codecs=opus"
    )

    return content_type.strip()


# =========================================================
# CRIAR SESSÃO DEEPL VOICE
# =========================================================

def create_deepl_voice_session(
    target_lang,
    source_content_type="audio/webm;codecs=opus",
    source_lang=None
):

    check_deepl_key()


    target_language = (
        normalize_target_language(
            target_lang
        )
    )


    payload = {

        "source_media_content_type":
            source_content_type,

        "message_format":
            "json",

        "source_language_mode":
            "auto",

        "target_languages":
            [
                target_language
            ],

        "target_media_languages":
            [
                target_language
            ],

        "target_media_content_type":
            "audio/webm;codecs=opus",

        "target_media_voice":
            "female"
    }


    if source_lang:

        source_lang = str(
            source_lang
        ).strip().upper()

        if source_lang:

            payload[
                "source_language"
            ] = source_lang

            payload[
                "source_language_mode"
            ] = "fixed"


    headers = {

        "Authorization":
            "DeepL-Auth-Key "
            + DEEPL_API_KEY,

        "Content-Type":
            "application/json",

        "Accept":
            "application/json"
    }


    response = requests.post(

        DEEPL_VOICE_URL,

        headers=headers,

        json=payload,

        timeout=30
    )


    if not response.ok:

        raise Exception(
            "DeepL Voice erro "
            + str(response.status_code)
            + ": "
            + response.text
        )


    data = response.json()


    streaming_url = data.get(
        "streaming_url"
    )

    token = data.get(
        "token"
    )

    session_id = data.get(
        "session_id"
    )


    if not streaming_url:

        raise Exception(
            "DeepL não retornou streaming_url."
        )


    if not token:

        raise Exception(
            "DeepL não retornou token."
        )


    return {

        "streaming_url":
            streaming_url,

        "token":
            token,

        "session_id":
            session_id,

        "target_language":
            target_language
    }


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
            "DeepL Voice",

        "architecture":
            "Browser Audio → DeepL Voice → Translated Voice",

        "message":
            "Servidor funcionando."

    })


# =========================================================
# HEALTH
# =========================================================

@app.get("/api/health")
def health():

    if not DEEPL_API_KEY:

        return jsonify({

            "ok":
                False,

            "server":
                True,

            "deepl":
                False,

            "provider":
                "DeepL Voice",

            "message":
                "DEEPL_API_KEY não configurada."

        }), 500


    return jsonify({

        "ok":
            True,

        "server":
            True,

        "deepl":
            True,

        "provider":
            "DeepL Voice",

        "message":
            "DeepL Voice configurado."

    })


# =========================================================
# TESTAR DEEPL VOICE
# =========================================================

@app.get("/api/deepl/voice-test")
def deepl_voice_test():

    try:

        check_deepl_key()


        session = (
            create_deepl_voice_session(
                "pt"
            )
        )


        return jsonify({

            "ok":
                True,

            "provider":
                "DeepL Voice",

            "session_id":
                session.get(
                    "session_id"
                ),

            "streaming_url":
                session.get(
                    "streaming_url"
                ),

            "target_language":
                session.get(
                    "target_language"
                ),

            "message":
                "DeepL Voice respondeu e criou uma sessão."

        })


    except Exception as error:

        return jsonify({

            "ok":
                False,

            "provider":
                "DeepL Voice",

            "error":
                str(error)

        }), 500


# =========================================================
# CRIAR SESSÃO DEEPL VOICE
#
# O navegador usa o streaming_url + token
# para abrir o WebSocket do DeepL.
# =========================================================

@app.post("/api/deepl/voice-session")
def deepl_voice_session():

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


    source_lang = data.get(
        "sourceLang"
    )


    content_type = str(
        data.get(
            "contentType",
            "audio/webm;codecs=opus"
        )
    ).strip()


    if target_lang not in ALLOWED_LANGUAGES:

        return jsonify({

            "ok":
                False,

            "error":
                "Idioma de destino não suportado."

        }), 400


    try:

        session = (
            create_deepl_voice_session(

                target_lang,

                content_type,

                source_lang

            )
        )


        return jsonify({

            "ok":
                True,

            "provider":
                "DeepL Voice",

            "streaming_url":
                session[
                    "streaming_url"
                ],

            "token":
                session[
                    "token"
                ],

            "session_id":
                session.get(
                    "session_id"
                ),

            "target_language":
                session[
                    "target_language"
                ],

            "message":
                "Sessão DeepL Voice criada."

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
            "Live criada. Aguardando áudio."

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
    # MULTIPART
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
        # JSON BASE64
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


    content_type = (
        audio_file.content_type
        if audio_file
        else request.headers.get(
            "X-Audio-Content-Type",
            "audio/webm;codecs=opus"
        )
    )


    with audio_lock:

        audio_cache[live_id] = {

            "data":
                audio_data,

            "content_type":
                content_type,

            "createdAt":
                time.time()
        }


    update_job(

        live_id,

        status="audio_received",

        audioCapture="running",

        message=
            "Áudio recebido.",

        audioReady=True

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
# ATUALIZAR TRADUÇÃO
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
# NOVA ROTA TTS
#
# ATENÇÃO:
# DeepL Voice não funciona como um TTS tradicional.
#
# Esta rota cria uma sessão de voz.
# O áudio precisa ser enviado pelo WebSocket
# retornado pelo endpoint /api/deepl/voice-session.
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


    try:

        target_lang = job.get(
            "targetLang",
            "pt"
        )


        session = (
            create_deepl_voice_session(
                target_lang
            )
        )


        update_job(

            live_id,

            status=
                "voice_session_ready",

            translationStatus=
                "ready",

            message=
                "Sessão DeepL Voice pronta."

        )


        return jsonify({

            "ok":
                True,

            "provider":
                "DeepL Voice",

            "liveId":
                live_id,

            "streaming_url":
                session[
                    "streaming_url"
                ],

            "token":
                session[
                    "token"
                ],

            "session_id":
                session.get(
                    "session_id"
                ),

            "target_language":
                session[
                    "target_language"
                ],

            "message":
                "Conecte o áudio ao WebSocket do DeepL Voice."

        })


    except Exception as error:

        update_job(

            live_id,

            status="error",

            error=str(error),

            message=
                "Erro criando sessão DeepL Voice."

        )


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
        "DeepL Voice"
    )

    print(
        "Servidor iniciado"
    )

    print(
        "Porta:",
        port
    )

    print(
        "======================================"
    )


    app.run(

        host="0.0.0.0",

        port=port,

        debug=False

    )

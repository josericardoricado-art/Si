import os
import io
import re
import json
import time
import uuid
import base64
import wave
import queue
import threading
from pathlib import Path

import requests
import websocket

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS

from piper import PiperVoice


# ============================================================
# SI — TRADUTOR LIVE
# Deepgram = voz para texto
# DeepL     = tradução de texto
# Piper     = texto para voz
#
# NÃO usa OpenAI
# NÃO usa ElevenLabs
# NÃO usa DeepL Voice
# ============================================================


app = Flask(__name__)

CORS(
    app,
    resources={r"/*": {"origins": "*"}},
    supports_credentials=False
)


# ============================================================
# CONFIGURAÇÃO
# ============================================================

PORT = int(os.environ.get("PORT", "10000"))

DEEPGRAM_API_KEY = os.environ.get(
    "DEEPGRAM_API_KEY",
    ""
).strip()

DEEPL_API_KEY = os.environ.get(
    "DEEPL_API_KEY",
    ""
).strip()


# DeepL:
# Se sua chave for da API Free, usamos api-free.deepl.com.
# Se for Pro, pode colocar DEEPL_API_URL no Render.
DEEPL_API_URL = os.environ.get(
    "DEEPL_API_URL",
    "https://api-free.deepl.com/v2/translate"
).strip()


# Idioma padrão de saída
DEFAULT_TARGET_LANGUAGE = os.environ.get(
    "DEFAULT_TARGET_LANGUAGE",
    "pt"
).strip().lower()


# Diretório dos modelos Piper
PIPER_DATA_DIR = Path(
    os.environ.get(
        "PIPER_DATA_DIR",
        "/tmp/piper"
    )
)

PIPER_DATA_DIR.mkdir(
    parents=True,
    exist_ok=True
)


# Diretório dos áudios gerados
AUDIO_DIR = Path(
    os.environ.get(
        "AUDIO_DIR",
        "/tmp/si_audio"
    )
)

AUDIO_DIR.mkdir(
    parents=True,
    exist_ok=True
)


# ============================================================
# VOZES PIPER
# ============================================================

PIPER_VOICES = {

    "pt": "pt_BR-faber-medium",

    "en": "en_US-lessac-medium",

    "es": "es_ES-davefx-medium",

    "fr": "fr_FR-siwis-medium",

    "de": "de_DE-thorsten-medium",

    "it": "it_IT-riccardo-x_low",

    "ja": "ja_JA-hi_fi_captain-medium",

    "ko": "ko_KR-kss-medium",

    "zh": "zh_CN-huayan-x_low",

    "ar": "ar_JO-kareem-medium",

    "hi": "hi_IN-pratham-medium",

    "nl": "nl_NL-pim-medium",

    "pl": "pl_PL-gosia-medium",

    "ru": "ru_RU-denis-medium",

    "tr": "tr_TR-dfki-medium",

    "uk": "uk_UA-lada-x_low",

    "vi": "vi_VN-vivos-x_low",

    "id": "id_ID-news_tts-medium",
}


# Cache das vozes carregadas
piper_voices = {}

piper_lock = threading.Lock()


# ============================================================
# SESSÕES DE ÁUDIO
# ============================================================

audio_sessions = {}

audio_sessions_lock = threading.Lock()


# ============================================================
# UTILITÁRIOS
# ============================================================

def normalize_language(language):

    if not language:
        return DEFAULT_TARGET_LANGUAGE

    language = str(language).strip().lower()

    aliases = {
        "pt-br": "pt",
        "pt_br": "pt",
        "portuguese": "pt",
        "portugues": "pt",

        "en-us": "en",
        "en_us": "en",
        "english": "en",
        "ingles": "en",

        "es-es": "es",
        "es_es": "es",
        "spanish": "es",
        "espanhol": "es",
        "espanol": "es",

        "fr-fr": "fr",
        "french": "fr",
        "frances": "fr",
        "francais": "fr",

        "de-de": "de",
        "german": "de",
        "alemao": "de",
        "alemán": "de",

        "it-it": "it",
        "italian": "it",
        "italiano": "it",

        "ja-jp": "ja",
        "japanese": "ja",
        "japones": "ja",
        "japonês": "ja",

        "ko-kr": "ko",
        "korean": "ko",
        "coreano": "ko",

        "zh-cn": "zh",
        "zh_cn": "zh",
        "chinese": "zh",
        "chines": "zh",
        "chinês": "zh",

        "ar-sa": "ar",
        "arabic": "ar",
        "arabe": "ar",
        "árabe": "ar",

        "hi-in": "hi",
        "hindi": "hi",

        "nl-nl": "nl",
        "dutch": "nl",
        "holandes": "nl",
        "holandês": "nl",

        "pl-pl": "pl",
        "polish": "pl",
        "polones": "pl",
        "polonês": "pl",

        "ru-ru": "ru",
        "russian": "ru",
        "russo": "ru",

        "tr-tr": "tr",
        "turkish": "tr",
        "turco": "tr",

        "uk-ua": "uk",
        "ukrainian": "uk",
        "ucraniano": "uk",

        "vi-vn": "vi",
        "vietnamese": "vi",
        "vietnamita": "vi",

        "id-id": "id",
        "indonesian": "id",
        "indonesio": "id",
        "indonésio": "id",
    }

    language = aliases.get(
        language,
        language
    )

    if language not in PIPER_VOICES:
        language = DEFAULT_TARGET_LANGUAGE

    if language not in PIPER_VOICES:
        language = "pt"

    return language


def deepl_language(language):

    mapping = {
        "pt": "PT-BR",
        "en": "EN",
        "es": "ES",
        "fr": "FR",
        "de": "DE",
        "it": "IT",
        "ja": "JA",
        "ko": "KO",
        "zh": "ZH",
        "ar": "AR",
        "hi": "HI",
        "nl": "NL",
        "pl": "PL",
        "ru": "RU",
        "tr": "TR",
        "uk": "UK",
        "vi": "VI",
        "id": "ID",
    }

    return mapping.get(
        normalize_language(language),
        "PT-BR"
    )


def deepgram_language(language):

    mapping = {
        "pt": "pt-BR",
        "en": "en-US",
        "es": "es",
        "fr": "fr",
        "de": "de",
        "it": "it",
        "ja": "ja",
        "ko": "ko",
        "zh": "zh-CN",
        "ar": "ar",
        "hi": "hi",
        "nl": "nl",
        "pl": "pl",
        "ru": "ru",
        "tr": "tr",
        "uk": "uk",
        "vi": "vi",
        "id": "id",
    }

    return mapping.get(
        normalize_language(language),
        "pt-BR"
    )


def clean_text(text):

    if not text:
        return ""

    text = str(text)

    text = re.sub(
        r"\s+",
        " ",
        text
    )

    text = text.strip()

    if len(text) > 1000:
        text = text[:1000]

    return text


def pcm_to_wav(
    pcm_data,
    sample_rate=16000,
    channels=1,
    sample_width=2
):

    buffer = io.BytesIO()

    with wave.open(
        buffer,
        "wb"
    ) as wav_file:

        wav_file.setnchannels(channels)

        wav_file.setsampwidth(sample_width)

        wav_file.setframerate(sample_rate)

        wav_file.writeframes(
            pcm_data
        )

    return buffer.getvalue()


# ============================================================
# PIPER
# ============================================================

def find_piper_model(model_name):

    possible_paths = [

        PIPER_DATA_DIR /
        f"{model_name}.onnx",

        Path.cwd() /
        f"{model_name}.onnx",

        Path("/app") /
        f"{model_name}.onnx",

        Path("/app/voices") /
        f"{model_name}.onnx",

        Path("/voices") /
        f"{model_name}.onnx",
    ]

    for path in possible_paths:

        if path.exists():

            return path

    return None


def download_piper_voice(model_name):

    model_path = find_piper_model(
        model_name
    )

    if model_path:
        return model_path

    print(
        f"[PIPER] Baixando voz: {model_name}",
        flush=True
    )

    import subprocess

    command = [
        "python",
        "-m",
        "piper.download_voices",
        model_name,
        "--data-dir",
        str(PIPER_DATA_DIR)
    ]

    result = subprocess.run(
        command,
        capture_output=True,
        text=True
    )

    if result.stdout:
        print(
            result.stdout,
            flush=True
        )

    if result.stderr:
        print(
            result.stderr,
            flush=True
        )

    if result.returncode != 0:

        raise RuntimeError(
            "Não foi possível baixar a voz Piper "
            + model_name
        )

    model_path = find_piper_model(
        model_name
    )

    if not model_path:

        raise RuntimeError(
            "Modelo Piper não encontrado após download: "
            + model_name
        )

    return model_path


def get_piper_voice(language):

    language = normalize_language(
        language
    )

    with piper_lock:

        if language in piper_voices:

            return piper_voices[language]

        model_name = PIPER_VOICES[
            language
        ]

        model_path = download_piper_voice(
            model_name
        )

        print(
            f"[PIPER] Carregando: {model_path}",
            flush=True
        )

        voice = PiperVoice.load(
            str(model_path)
        )

        piper_voices[
            language
        ] = voice

        print(
            f"[PIPER] Voz pronta: {language}",
            flush=True
        )

        return voice


def generate_piper_audio(
    text,
    language
):

    text = clean_text(text)

    if not text:

        return None

    language = normalize_language(
        language
    )

    voice = get_piper_voice(
        language
    )

    filename = (
        f"{uuid.uuid4().hex}.wav"
    )

    output_path = (
        AUDIO_DIR /
        filename
    )

    print(
        f"[PIPER] Gerando áudio {language}: {text}",
        flush=True
    )

    with wave.open(
        str(output_path),
        "wb"
    ) as wav_file:

        voice.synthesize_wav(
            text,
            wav_file
        )

    return filename


# ============================================================
# DEEPL
# ============================================================

def translate_with_deepl(
    text,
    target_language
):

    text = clean_text(text)

    if not text:
        return ""

    if not DEEPL_API_KEY:

        raise RuntimeError(
            "DEEPL_API_KEY não configurada."
        )

    target = deepl_language(
        target_language
    )

    headers = {
        "Authorization":
            f"DeepL-Auth-Key {DEEPL_API_KEY}",

        "Content-Type":
            "application/json",
    }

    payload = {
        "text": [text],
        "target_lang": target,
    }

    response = requests.post(
        DEEPL_API_URL,
        headers=headers,
        json=payload,
        timeout=20
    )

    if response.status_code != 200:

        raise RuntimeError(
            "DeepL HTTP "
            + str(response.status_code)
            + ": "
            + response.text[:500]
        )

    data = response.json()

    translations = data.get(
        "translations",
        []
    )

    if not translations:

        raise RuntimeError(
            "DeepL não retornou tradução."
        )

    return clean_text(
        translations[0].get(
            "text",
            ""
        )
    )


# ============================================================
# DEEPGRAM
# ============================================================

def deepgram_url(
    target_language
):

    language = deepgram_language(
        target_language
    )

    params = [
        "encoding=linear16",
        "sample_rate=16000",
        "channels=1",
        "model=nova-3",
        f"language={language}",
        "interim_results=true",
        "punctuate=true",
        "smart_format=true",
        "endpointing=300",
        "utterance_end_ms=1000",
    ]

    return (
        "wss://api.deepgram.com/v1/listen?"
        + "&".join(params)
    )


def create_deepgram_connection(
    target_language
):

    if not DEEPGRAM_API_KEY:

        raise RuntimeError(
            "DEEPGRAM_API_KEY não configurada."
        )

    url = deepgram_url(
        target_language
    )

    print(
        "[DEEPGRAM] Conectando...",
        flush=True
    )

    ws = websocket.create_connection(
        url,
        header=[
            "Authorization: Token "
            + DEEPGRAM_API_KEY
        ],
        timeout=10
    )

    print(
        "[DEEPGRAM] Conectado.",
        flush=True
    )

    return ws


# ============================================================
# PROCESSAMENTO DA FRASE
# ============================================================

def process_final_transcript(
    job,
    transcript
):

    transcript = clean_text(
        transcript
    )

    if not transcript:
        return

    with job["lock"]:

        last_source = job.get(
            "last_source",
            ""
        )

        if transcript == last_source:

            return

        job["last_source"] = transcript

        job["source_text"] = transcript

        job["status"] = "translating"

    print(
        "[SI] Texto reconhecido: "
        + transcript,
        flush=True
    )

    try:

        translated = translate_with_deepl(
            transcript,
            job["target_language"]
        )

        with job["lock"]:

            job["translated_text"] = translated

            job["status"] = "generating_voice"

        print(
            "[SI] Tradução: "
            + translated,
            flush=True
        )

        filename = generate_piper_audio(
            translated,
            job["target_language"]
        )

        if filename:

            audio_url = (
                "/api/audio/file/"
                + filename
            )

            with job["lock"]:

                job["audio_url"] = audio_url

                job["audio_id"] = uuid.uuid4().hex

                job["audio_ready_at"] = time.time()

                job["status"] = "ready"

    except Exception as error:

        print(
            "[SI] Erro no processamento:",
            repr(error),
            flush=True
        )

        with job["lock"]:

            job["error"] = str(error)

            job["status"] = "error"


# ============================================================
# WORKER DEEPGRAM
# ============================================================

def deepgram_worker(
    job
):

    ws = None

    try:

        ws = create_deepgram_connection(
            job["target_language"]
        )

        job["ws"] = ws

        while True:

            if job.get("stop_event").is_set():

                break

            try:

                audio_data = job["audio_queue"].get(
                    timeout=0.5
                )

            except queue.Empty:

                continue

            if audio_data is None:

                break

            try:

                ws.send(
                    audio_data,
                    opcode=websocket.ABNF.OPCODE_BINARY
                )

            except Exception as send_error:

                print(
                    "[DEEPGRAM] Erro ao enviar:",
                    repr(send_error),
                    flush=True
                )

                break

            try:

                while True:

                    ws.settimeout(
                        0.01
                    )

                    try:

                        message = ws.recv()

                    except websocket.WebSocketTimeoutException:

                        break

                    if not message:

                        break

                    if isinstance(
                        message,
                        bytes
                    ):

                        continue

                    try:

                        data = json.loads(
                            message
                        )

                    except Exception:

                        continue

                    channel = data.get(
                        "channel",
                        {}
                    )

                    alternatives = channel.get(
                        "alternatives",
                        []
                    )

                    if not alternatives:

                        continue

                    transcript = clean_text(
                        alternatives[0].get(
                            "transcript",
                            ""
                        )
                    )

                    if not transcript:

                        continue

                    is_final = bool(
                        data.get(
                            "is_final",
                            False
                        )
                    )

                    speech_final = bool(
                        data.get(
                            "speech_final",
                            False
                        )
                    )

                    with job["lock"]:

                        job["interim_text"] = transcript

                    if is_final or speech_final:

                        process_final_transcript(
                            job,
                            transcript
                        )

            except Exception as recv_error:

                print(
                    "[DEEPGRAM] Erro ao receber:",
                    repr(recv_error),
                    flush=True
                )

                break

    except Exception as error:

        print(
            "[DEEPGRAM] Worker erro:",
            repr(error),
            flush=True
        )

        with job["lock"]:

            job["error"] = str(error)

            job["status"] = "error"

    finally:

        try:

            if ws:

                ws.close()

        except Exception:

            pass

        with job["lock"]:

            job["ws"] = None


# ============================================================
# CRIAR SESSÃO
# ============================================================

def create_audio_session(
    job_id,
    target_language
):

    target_language = normalize_language(
        target_language
    )

    job = {

        "job_id": job_id,

        "target_language":
            target_language,

        "status":
            "connecting",

        "source_text":
            "",

        "translated_text":
            "",

        "interim_text":
            "",

        "audio_url":
            None,

        "audio_id":
            None,

        "audio_ready_at":
            0,

        "error":
            None,

        "created_at":
            time.time(),

        "last_activity":
            time.time(),

        "last_source":
            "",

        "audio_queue":
            queue.Queue(
                maxsize=200
            ),

        "stop_event":
            threading.Event(),

        "lock":
            threading.Lock(),

        "ws":
            None,
    }

    with audio_sessions_lock:

        audio_sessions[
            job_id
        ] = job

    thread = threading.Thread(
        target=deepgram_worker,
        args=(job,),
        daemon=True
    )

    job["thread"] = thread

    thread.start()

    return job


# ============================================================
# ROTA PRINCIPAL
# ============================================================

@app.route(
    "/",
    methods=["GET"]
)
def home():

    return jsonify({

        "ok": True,

        "service":
            "SI Tradutor Live",

        "version":
            "16.0-Piper",

        "providers": {

            "stt":
                "Deepgram",

            "translation":
                "DeepL Text",

            "tts":
                "Piper",

        },

        "openai":
            False,

        "elevenlabs":
            False,

        "deepl_voice":
            False,

    })


# ============================================================
# HEALTH
# ============================================================

@app.route(
    "/api/health",
    methods=["GET"]
)
def health():

    return jsonify({

        "ok": True,

        "service":
            "SI Tradutor Live",

        "version":
            "16.0-Piper",

        "deepgram_configured":
            bool(DEEPGRAM_API_KEY),

        "deepl_configured":
            bool(DEEPL_API_KEY),

        "piper":
            True,

        "piper_voices_loaded":
            list(piper_voices.keys()),

        "openai":
            False,

        "elevenlabs":
            False,

        "deepl_voice":
            False,

    })


# ============================================================
# TESTE PIPER
# ============================================================

@app.route(
    "/api/test-piper",
    methods=["GET", "POST"]
)
def test_piper():

    if request.method == "POST":

        data = request.get_json(
            silent=True
        ) or {}

        text = data.get(
            "text",
            "Olá. Este é o teste de voz do SI."
        )

        language = data.get(
            "language",
            "pt"
        )

    else:

        text = request.args.get(
            "text",
            "Olá. Este é o teste de voz do SI."
        )

        language = request.args.get(
            "language",
            "pt"
        )

    try:

        filename = generate_piper_audio(
            text,
            language
        )

        return jsonify({

            "ok": True,

            "provider":
                "Piper",

            "language":
                normalize_language(language),

            "text":
                clean_text(text),

            "audioUrl":
                "/api/audio/file/" + filename,

        })

    except Exception as error:

        return jsonify({

            "ok": False,

            "provider":
                "Piper",

            "error":
                str(error),

        }), 500


# ============================================================
# RECEBER CHUNK DE ÁUDIO DO APK
# ============================================================

@app.route(
    "/api/audio/chunk",
    methods=["POST"]
)
def audio_chunk():

    data = request.get_json(
        silent=True
    )

    if not data:

        return jsonify({

            "ok": False,

            "error":
                "JSON não recebido."

        }), 400

    job_id = str(
        data.get(
            "jobId",
            ""
        )
    ).strip()

    if not job_id:

        return jsonify({

            "ok": False,

            "error":
                "jobId não informado."

        }), 400

    audio_base64 = data.get(
        "audio",
        ""
    )

    if not audio_base64:

        return jsonify({

            "ok": False,

            "error":
                "Áudio não informado."

        }), 400

    target_language = data.get(
        "targetLanguage",
        data.get(
            "targetLang",
            DEFAULT_TARGET_LANGUAGE
        )
    )

    target_language = normalize_language(
        target_language
    )

    try:

        audio_data = base64.b64decode(
            audio_base64
        )

    except Exception:

        return jsonify({

            "ok": False,

            "error":
                "Base64 de áudio inválido."

        }), 400

    if not audio_data:

        return jsonify({

            "ok": False,

            "error":
                "Chunk de áudio vazio."

        }), 400

    with audio_sessions_lock:

        job = audio_sessions.get(
            job_id
        )

    if not job:

        job = create_audio_session(
            job_id,
            target_language
        )

    with job["lock"]:

        job["last_activity"] = time.time()

    try:

        job["audio_queue"].put(
            audio_data,
            timeout=2
        )

    except queue.Full:

        return jsonify({

            "ok": False,

            "error":
                "Fila de áudio cheia."

        }), 503

    return jsonify({

        "ok": True,

        "jobId":
            job_id,

        "status":
            job["status"],

        "receivedBytes":
            len(audio_data),

    })


# ============================================================
# STATUS
# ============================================================

@app.route(
    "/api/audio/status/<job_id>",
    methods=["GET"]
)
def audio_status(job_id):

    with audio_sessions_lock:

        job = audio_sessions.get(
            job_id
        )

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Job não encontrado."

        }), 404

    with job["lock"]:

        return jsonify({

            "ok": True,

            "jobId":
                job["job_id"],

            "status":
                job["status"],

            "sourceText":
                job["source_text"],

            "translatedText":
                job["translated_text"],

            "interimText":
                job["interim_text"],

            "audioUrl":
                job["audio_url"],

            "audioId":
                job["audio_id"],

            "error":
                job["error"],

            "targetLanguage":
                job["target_language"],

        })


# ============================================================
# DOWNLOAD / STREAM DO WAV
# ============================================================

@app.route(
    "/api/audio/file/<filename>",
    methods=["GET"]
)
def audio_file(filename):

    filename = os.path.basename(
        filename
    )

    return send_from_directory(
        AUDIO_DIR,
        filename,
        mimetype="audio/wav",
        as_attachment=False
    )


# ============================================================
# PARAR JOB
# ============================================================

@app.route(
    "/api/audio/stop/<job_id>",
    methods=["POST"]
)
def stop_audio(job_id):

    with audio_sessions_lock:

        job = audio_sessions.get(
            job_id
        )

    if not job:

        return jsonify({

            "ok": False,

            "error":
                "Job não encontrado."

        }), 404

    job["stop_event"].set()

    try:

        job["audio_queue"].put_nowait(
            None
        )

    except Exception:

        pass

    with job["lock"]:

        job["status"] = "stopped"

    return jsonify({

        "ok": True,

        "jobId":
            job_id,

        "status":
            "stopped"

    })


# ============================================================
# DIAGNÓSTICO
# ============================================================

@app.route(
    "/api/audio/diagnostic",
    methods=["POST"]
)
def diagnostic():

    data = request.get_json(
        silent=True
    ) or {}

    print(
        "[DIAGNOSTIC]",
        json.dumps(
            data,
            ensure_ascii=False
        ),
        flush=True
    )

    return jsonify({

        "ok": True,

        "received":
            True

    })


# ============================================================
# YOUTUBE LIVE — COMPATIBILIDADE
# ============================================================

youtube_sessions = {}

youtube_lock = threading.Lock()


@app.route(
    "/api/youtube-live",
    methods=["POST"]
)
def youtube_live():

    data = request.get_json(
        silent=True
    ) or {}

    live_id = uuid.uuid4().hex

    url = str(
        data.get(
            "url",
            ""
        )
    ).strip()

    target_language = normalize_language(
        data.get(
            "targetLanguage",
            DEFAULT_TARGET_LANGUAGE
        )
    )

    session = {

        "live_id":
            live_id,

        "url":
            url,

        "target_language":
            target_language,

        "status":
            "created",

        "translation":
            "",

        "source_translation":
            "",

        "error":
            None,

        "created_at":
            time.time(),

    }

    with youtube_lock:

        youtube_sessions[
            live_id
        ] = session

    return jsonify({

        "ok": True,

        **session

    })


@app.route(
    "/api/youtube-live/<live_id>",
    methods=["GET"]
)
def get_youtube_live(live_id):

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

    if not session:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    return jsonify({

        "ok": True,

        **session

    })


@app.route(
    "/api/youtube-live/<live_id>/translation",
    methods=["POST"]
)
def youtube_translation_start(
    live_id
):

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

        if not session:

            return jsonify({

                "ok": False,

                "error":
                    "Live não encontrada."

            }), 404

        session["status"] = "translating"

    return jsonify({

        "ok": True,

        "status":
            "translating"

    })


@app.route(
    "/api/youtube-live/<live_id>/translation",
    methods=["PUT"]
)
def youtube_translation_update(
    live_id
):

    data = request.get_json(
        silent=True
    ) or {}

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

        if not session:

            return jsonify({

                "ok": False,

                "error":
                    "Live não encontrada."

            }), 404

        session["translation"] = data.get(
            "translation",
            ""
        )

        session["source_translation"] = data.get(
            "source_translation",
            ""
        )

        session["status"] = "ready"

    return jsonify({

        "ok": True,

        **session

    })


@app.route(
    "/api/youtube-live/<live_id>/audio",
    methods=["POST"]
)
def youtube_audio_post(
    live_id
):

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

        if not session:

            return jsonify({

                "ok": False,

                "error":
                    "Live não encontrada."

            }), 404

        session["status"] = "translating"

    return jsonify({

        "ok": True,

        "message":
            "Áudio recebido."

    })


@app.route(
    "/api/youtube-live/<live_id>/audio",
    methods=["GET"]
)
def youtube_audio_get(
    live_id
):

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

    if not session:

        return jsonify({

            "ok": False,

            "error":
                "Live não encontrada."

        }), 404

    return jsonify({

        "ok": True,

        "status":
            session["status"],

        "translation":
            session["translation"],

    })


@app.route(
    "/api/youtube-live/<live_id>/stop",
    methods=["POST"]
)
def youtube_stop(
    live_id
):

    with youtube_lock:

        session = youtube_sessions.get(
            live_id
        )

        if not session:

            return jsonify({

                "ok": False,

                "error":
                    "Live não encontrada."

            }), 404

        session["status"] = "stopped"

    return jsonify({

        "ok": True,

        "status":
            "stopped"

    })


# ============================================================
# LIMPEZA
# ============================================================

def cleanup_worker():

    while True:

        time.sleep(
            300
        )

        now = time.time()

        # Limpar sessões antigas
        with audio_sessions_lock:

            old_jobs = []

            for job_id, job in audio_sessions.items():

                if (
                    now -
                    job["created_at"]
                    > 3600
                ):

                    old_jobs.append(
                        job_id
                    )

            for job_id in old_jobs:

                job = audio_sessions.pop(
                    job_id,
                    None
                )

                if job:

                    job["stop_event"].set()

        # Limpar arquivos antigos
        try:

            for path in AUDIO_DIR.iterdir():

                if not path.is_file():

                    continue

                try:

                    age = (
                        now -
                        path.stat().st_mtime
                    )

                    if age > 3600:

                        path.unlink(
                            missing_ok=True
                        )

                except Exception:

                    pass

        except Exception:

            pass


cleanup_thread = threading.Thread(
    target=cleanup_worker,
    daemon=True
)

cleanup_thread.start()


# ============================================================
# INICIALIZAÇÃO
# ============================================================

if __name__ == "__main__":

    print(
        "==========================================",
        flush=True
    )

    print(
        " SI — TRADUTOR LIVE",
        flush=True
    )

    print(
        " Deepgram + DeepL + Piper",
        flush=True
    )

    print(
        " OpenAI: DESATIVADO",
        flush=True
    )

    print(
        " ElevenLabs: DESATIVADO",
        flush=True
    )

    print(
        " DeepL Voice: DESATIVADO",
        flush=True
    )

    print(
        " Piper TTS: ATIVO",
        flush=True
    )

    print(
        "==========================================",
        flush=True
    )

    print(
        "PORT:",
        PORT,
        flush=True
    )

    print(
        "Deepgram configurado:",
        bool(DEEPGRAM_API_KEY),
        flush=True
    )

    print(
        "DeepL configurado:",
        bool(DEEPL_API_KEY),
        flush=True
    )

    print(
        "Piper directory:",
        PIPER_DATA_DIR,
        flush=True
    )

    app.run(
        host="0.0.0.0",
        port=PORT,
        debug=False,
        threaded=True
    )

import os
import re
import uuid
import time
import threading
from urllib.parse import urlparse, parse_qs

import requests
from flask import Flask, jsonify, request
from flask_cors import CORS


# =========================================================
# SI — TRADUTOR LIVE
# Backend Flask + DeepL Voice
# =========================================================

app = Flask(__name__)

CORS(
    app,
    resources={r"/*": {"origins": "*"}},
    supports_credentials=False
)


# =========================================================
# CONFIGURAÇÃO
# =========================================================

PORT = int(os.environ.get("PORT", "10000"))

DEEPL_API_KEY = os.environ.get("DEEPL_API_KEY", "").strip()

DEEPL_SESSION_URL = "https://api.deepl.com/v3/voice/realtime"


# Idiomas disponíveis no aplicativo
ALLOWED_LANGUAGES = {
    "pt": "pt-BR",
    "en": "en",
    "es": "es",
    "fr": "fr",
    "de": "de",
    "it": "it",
    "ja": "ja",
    "ko": "ko",
    "zh": "zh",
    "ar": "ar",
}


# =========================================================
# MEMÓRIA TEMPORÁRIA
# =========================================================

live_sessions = {}

lock = threading.Lock()


# =========================================================
# FUNÇÕES AUXILIARES
# =========================================================

def get_youtube_id(url):
    """
    Tenta obter o ID de um vídeo/live do YouTube.
    Aceita:
    https://youtu.be/VIDEO_ID
    https://www.youtube.com/watch?v=VIDEO_ID
    https://www.youtube.com/live/VIDEO_ID
    """

    if not url:
        return None

    url = url.strip()

    try:
        parsed = urlparse(url)
        host = parsed.netloc.lower()

        # youtu.be/ID
        if "youtu.be" in host:
            video_id = parsed.path.strip("/").split("/")[0]
            if video_id:
                return video_id

        # youtube.com/watch?v=ID
        if "youtube.com" in host:
            query = parse_qs(parsed.query)

            if "v" in query and query["v"]:
                return query["v"][0]

            # youtube.com/live/ID
            parts = parsed.path.strip("/").split("/")

            if len(parts) >= 2 and parts[0] in ["live", "embed", "shorts"]:
                return parts[1]

    except Exception:
        pass

    # Fallback
    match = re.search(
        r"(?:v=|youtu\.be/|youtube\.com/live/)([A-Za-z0-9_-]{6,})",
        url
    )

    if match:
        return match.group(1)

    return None


def normalize_language(language):
    """
    Converte o valor enviado pelo frontend
    para um idioma aceito pela DeepL Voice.
    """

    language = (language or "pt").strip()

    return ALLOWED_LANGUAGES.get(
        language,
        language
    )


def deepl_headers():
    return {
        "Authorization": f"DeepL-Auth-Key {DEEPL_API_KEY}",
        "Content-Type": "application/json",
    }


# =========================================================
# ROTAS BÁSICAS
# =========================================================

@app.route("/", methods=["GET"])
def home():
    return jsonify({
        "ok": True,
        "app": "SI Tradutor Live",
        "message": "Backend do SI funcionando",
        "version": "4.0",
        "provider": "DeepL Voice"
    })


@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({
        "ok": True,
        "server": "online",
        "provider": "DeepL Voice",
        "deepl_configured": bool(DEEPL_API_KEY)
    })


# =========================================================
# CRIAR LIVE
# =========================================================

@app.route("/api/youtube-live", methods=["POST"])
def create_live():

    try:
        data = request.get_json(silent=True) or {}

        url = (
            data.get("url")
            or data.get("youtubeUrl")
            or data.get("link")
            or ""
        ).strip()

        target_language = normalize_language(
            data.get("targetLang")
            or data.get("targetLanguage")
            or "pt"
        )

        if not url:
            return jsonify({
                "ok": False,
                "error": "Informe o link do YouTube."
            }), 400

        youtube_id = get_youtube_id(url)

        if not youtube_id:
            return jsonify({
                "ok": False,
                "error": "Não consegui identificar o vídeo/live do YouTube."
            }), 400

        live_id = str(uuid.uuid4())

        with lock:
            live_sessions[live_id] = {
                "live_id": live_id,
                "url": url,
                "youtube_id": youtube_id,
                "target_language": target_language,
                "status": "created",
                "created_at": time.time(),
                "translation": "",
                "source_translation": "",
                "error": None,
            }

        return jsonify({
            "ok": True,
            "live_id": live_id,
            "youtube_id": youtube_id,
            "target_language": target_language,
            "status": "created"
        })

    except Exception as e:
        return jsonify({
            "ok": False,
            "error": f"Erro ao criar live: {str(e)}"
        }), 500


# =========================================================
# STATUS DA LIVE
# =========================================================

@app.route("/api/youtube-live/<live_id>", methods=["GET"])
def get_live(live_id):

    with lock:
        session = live_sessions.get(live_id)

    if not session:
        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404

    return jsonify({
        "ok": True,
        **session
    })


# =========================================================
# SESSÃO DEEP L VOICE
# =========================================================

@app.route("/api/deepl/voice-session", methods=["POST"])
def create_deepl_voice_session():

    if not DEEPL_API_KEY:
        return jsonify({
            "ok": False,
            "error": "DEEPL_API_KEY não está configurada no Render."
        }), 500

    try:
        data = request.get_json(silent=True) or {}

        target_language = normalize_language(
            data.get("targetLang")
            or data.get("targetLanguage")
            or "pt"
        )

        # A captura do navegador será:
        # WebM + Opus
        source_content_type = "audio/webm;codecs=opus"

        payload = {
            "source_media_content_type": source_content_type,

            # Tradução de texto
            "target_languages": [
                target_language
            ],

            # Síntese da voz traduzida
            "target_media_languages": [
                target_language
            ],

            # Voz semelhante à original quando suportado
            "target_media_voice": "match",

            # WebM/Opus para a voz devolvida
            "target_media_content_type": "audio/webm;codecs=opus",

            # JSON é o formato usado pelo navegador
            "message_format": "json"
        }

        response = requests.post(
            DEEPL_SESSION_URL,
            headers=deepl_headers(),
            json=payload,
            timeout=30
        )

        try:
            result = response.json()
        except Exception:
            result = {
                "message": response.text
            }

        if response.status_code >= 400:
            return jsonify({
                "ok": False,
                "error": (
                    result.get("message")
                    or result.get("error")
                    or f"DeepL retornou HTTP {response.status_code}"
                ),
                "deepl_status": response.status_code,
                "deepl_response": result
            }), response.status_code

        if not result.get("streaming_url"):
            return jsonify({
                "ok": False,
                "error": "A DeepL não retornou streaming_url.",
                "deepl_response": result
            }), 502

        if not result.get("token"):
            return jsonify({
                "ok": False,
                "error": "A DeepL não retornou o token da sessão.",
                "deepl_response": result
            }), 502

        return jsonify({
            "ok": True,
            "streaming_url": result["streaming_url"],
            "token": result["token"],
            "session_id": result.get("session_id"),
            "target_language": target_language,
            "source_media_content_type": source_content_type,
            "target_media_content_type": "audio/webm;codecs=opus"
        })

    except requests.exceptions.Timeout:
        return jsonify({
            "ok": False,
            "error": "A DeepL demorou muito para responder."
        }), 504

    except requests.exceptions.RequestException as e:
        return jsonify({
            "ok": False,
            "error": f"Erro de conexão com a DeepL: {str(e)}"
        }), 502

    except Exception as e:
        return jsonify({
            "ok": False,
            "error": f"Erro interno: {str(e)}"
        }), 500


# =========================================================
# MARCAR TRADUÇÃO COMO ATIVA
# =========================================================

@app.route("/api/youtube-live/<live_id>/translation", methods=["POST"])
def start_translation(live_id):

    with lock:
        session = live_sessions.get(live_id)

        if not session:
            return jsonify({
                "ok": False,
                "error": "Live não encontrada."
            }), 404

        session["status"] = "translating"

    return jsonify({
        "ok": True,
        "status": "translating"
    })


# =========================================================
# RECEBER ATUALIZAÇÃO DE TEXTO
# =========================================================

@app.route("/api/youtube-live/<live_id>/translation", methods=["PUT"])
def update_translation(live_id):

    data = request.get_json(silent=True) or {}

    text = data.get("text", "")
    source_text = data.get("source_text", "")

    with lock:
        session = live_sessions.get(live_id)

        if not session:
            return jsonify({
                "ok": False,
                "error": "Live não encontrada."
            }), 404

        session["translation"] = text
        session["source_translation"] = source_text

    return jsonify({
        "ok": True
    })


# =========================================================
# ÁUDIO — COMPATIBILIDADE
# =========================================================

@app.route("/api/youtube-live/<live_id>/audio", methods=["POST"])
def audio_received(live_id):

    with lock:
        session = live_sessions.get(live_id)

        if not session:
            return jsonify({
                "ok": False,
                "error": "Live não encontrada."
            }), 404

        session["status"] = "translating"

    return jsonify({
        "ok": True,
        "message": "Áudio recebido."
    })


@app.route("/api/youtube-live/<live_id>/audio", methods=["GET"])
def audio_status(live_id):

    with lock:
        session = live_sessions.get(live_id)

    if not session:
        return jsonify({
            "ok": False,
            "error": "Live não encontrada."
        }), 404

    return jsonify({
        "ok": True,
        "status": session.get("status")
    })


# =========================================================
# PARAR
# =========================================================

@app.route("/api/youtube-live/<live_id>/stop", methods=["POST"])
def stop_live(live_id):

    with lock:
        session = live_sessions.get(live_id)

        if not session:
            return jsonify({
                "ok": False,
                "error": "Live não encontrada."
            }), 404

        session["status"] = "stopped"

    return jsonify({
        "ok": True,
        "status": "stopped"
    })


# =========================================================
# LIMPEZA DE SESSÕES ANTIGAS
# =========================================================

def cleanup_sessions():

    while True:

        try:
            now = time.time()

            with lock:

                old_ids = []

                for live_id, session in live_sessions.items():

                    created = session.get("created_at", now)

                    if now - created > 3600:
                        old_ids.append(live_id)

                for live_id in old_ids:
                    del live_sessions[live_id]

        except Exception:
            pass

        time.sleep(300)


threading.Thread(
    target=cleanup_sessions,
    daemon=True
).start()


# =========================================================
# START
# =========================================================

if __name__ == "__main__":

    print("=" * 50)
    print("SI TRADUTOR LIVE")
    print("DeepL Voice")
    print("=" * 50)

    if DEEPL_API_KEY:
        print("DEEPL_API_KEY: configurada")
    else:
        print("DEEPL_API_KEY: NÃO CONFIGURADA")

    print(f"Porta: {PORT}")

    app.run(
        host="0.0.0.0",
        port=PORT,
        debug=False
    )

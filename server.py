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
ELEVENLABS_AGENT_ID = os.getenv(
    "ELEVENLABS_AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
)

ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1"

jobs = {}

MAX_FILE_SIZE = 500 * 1024 * 1024


# =========================================================
# ELEVENLABS
# =========================================================

def eleven_headers():
    return {
        "xi-api-key": ELEVENLABS_API_KEY
    }


def get_agent_signed_url():
    """
    Gera uma URL temporária para o navegador
    conectar ao Agent sem expor a API Key.
    """

    if not ELEVENLABS_API_KEY:
        raise Exception(
            "ELEVENLABS_API_KEY não configurada."
        )

    if not ELEVENLABS_AGENT_ID:
        raise Exception(
            "ELEVENLABS_AGENT_ID não configurado."
        )

    url = (
        f"{ELEVENLABS_BASE_URL}"
        f"/convai/conversation/get-signed-url"
    )

    response = requests.get(
        url,
        headers=eleven_headers(),
        params={
            "agent_id": ELEVENLABS_AGENT_ID
        },
        timeout=30
    )

    if not response.ok:
        raise Exception(
            f"ElevenLabs Agent erro "
            f"{response.status_code}: {response.text}"
        )

    data = response.json()

    signed_url = data.get("signed_url")

    if not signed_url:
        raise Exception(
            "ElevenLabs não retornou signed_url."
        )

    return signed_url


# =========================================================
# DUBLAGEM DE VÍDEO
# =========================================================

def create_dubbing(file_path, target_language):

    url = f"{ELEVENLABS_BASE_URL}/dubbing/project"

    with open(file_path, "rb") as video_file:

        files = {
            "file": (
                os.path.basename(file_path),
                video_file,
                "application/octet-stream"
            )
        }

        data = {
            "model_id": "dubbing_v2",
            "target_language": target_language,
            "reference": "SI Tradutor Dublagem IA"
        }

        response = requests.post(
            url,
            headers=eleven_headers(),
            files=files,
            data=data,
            timeout=300
        )

    if not response.ok:
        raise Exception(
            f"ElevenLabs erro "
            f"{response.status_code}: {response.text}"
        )

    return response.json()


def get_language_target(project_id, language_id):

    url = (
        f"{ELEVENLABS_BASE_URL}/dubbing/project/"
        f"{project_id}/language/{language_id}"
    )

    response = requests.get(
        url,
        headers=eleven_headers(),
        timeout=60
    )

    if not response.ok:
        raise Exception(
            f"Erro consultando ElevenLabs: "
            f"{response.status_code}: {response.text}"
        )

    return response.json()


def process_job(job_id, file_path, target_language):

    try:

        jobs[job_id]["status"] = "processing"
        jobs[job_id]["message"] = (
            "Enviando vídeo para ElevenLabs..."
        )

        result = create_dubbing(
            file_path,
            target_language
        )

        project_id = result.get("project_id")

        if not project_id:
            raise Exception(
                "ElevenLabs não retornou project_id."
            )

        jobs[job_id]["project_id"] = project_id
        jobs[job_id]["status"] = "waiting"
        jobs[job_id]["message"] = (
            "Vídeo recebido. "
            "Aguardando processamento..."
        )

        language_id = None

        for _ in range(180):

            project_url = (
                f"{ELEVENLABS_BASE_URL}/dubbing/project/"
                f"{project_id}"
            )

            response = requests.get(
                project_url,
                headers=eleven_headers(),
                timeout=60
            )

            if not response.ok:
                raise Exception(
                    f"Erro consultando projeto: "
                    f"{response.status_code}: "
                    f"{response.text}"
                )

            project = response.json()

            project_status = project.get("status")

            jobs[job_id]["message"] = (
                f"Processando vídeo: {project_status}"
            )

            language_ids = project.get(
                "language_ids",
                []
            )

            if language_ids:
                language_id = language_ids[0]

            if project_status == "failed":
                raise Exception(
                    "A ElevenLabs informou que "
                    "o projeto falhou."
                )

            if project_status == "ready":
                break

            time.sleep(5)

        if not language_id:

            languages_url = (
                f"{ELEVENLABS_BASE_URL}/dubbing/project/"
                f"{project_id}/language"
            )

            response = requests.get(
                languages_url,
                headers=eleven_headers(),
                timeout=60
            )

            if response.ok:

                languages_data = response.json()

                languages = languages_data.get(
                    "languages",
                    []
                )

                for language in languages:

                    if language.get(
                        "target_language"
                    ) == target_language:

                        language_id = language.get(
                            "language_id"
                        )

                        break

        if not language_id:

            language_url = (
                f"{ELEVENLABS_BASE_URL}/dubbing/project/"
                f"{project_id}/language"
            )

            response = requests.post(
                language_url,
                headers={
                    **eleven_headers(),
                    "Content-Type": "application/json"
                },
                json={
                    "target_language": target_language
                },
                timeout=60
            )

            if not response.ok:
                raise Exception(
                    f"Erro criando idioma: "
                    f"{response.status_code}: "
                    f"{response.text}"
                )

            language_data = response.json()

            language_id = language_data.get(
                "language_id"
            )

        if not language_id:
            raise Exception(
                "Não foi possível encontrar "
                "o language_id."
            )

        jobs[job_id]["language_id"] = language_id
        jobs[job_id]["status"] = "dubbing"
        jobs[job_id]["message"] = (
            "Gerando a dublagem..."
        )

        for _ in range(360):

            language = get_language_target(
                project_id,
                language_id
            )

            status = language.get("status")

            jobs[job_id]["message"] = (
                f"Dublagem: {status}"
            )

            if status == "completed":

                outputs = language.get(
                    "outputs"
                ) or {}

                audio_url = outputs.get(
                    "lossless_audio"
                )

                if not audio_url:
                    raise Exception(
                        "A ElevenLabs terminou, "
                        "mas não retornou o áudio."
                    )

                jobs[job_id]["status"] = "completed"
                jobs[job_id]["message"] = (
                    "Dublagem concluída!"
                )
                jobs[job_id]["output_url"] = (
                    audio_url
                )

                break

            if status == "failed":

                error = language.get("error")

                raise Exception(
                    f"Dublagem falhou: {error}"
                )

            time.sleep(5)

        else:

            raise Exception(
                "Tempo limite excedido."
            )

    except Exception as error:

        jobs[job_id]["status"] = "error"
        jobs[job_id]["message"] = str(error)

    finally:

        try:
            if os.path.exists(file_path):
                os.remove(file_path)
        except Exception:
            pass


# =========================================================
# ROTAS
# =========================================================

@app.get("/")
def home():

    return jsonify({
        "ok": True,
        "service": "SI Tradutor & Dublagem IA",
        "provider": "ElevenLabs",
        "agent_id": ELEVENLABS_AGENT_ID,
        "message": "Servidor funcionando."
    })


@app.get("/api/health")
def health():

    if not ELEVENLABS_API_KEY:
        return jsonify({
            "ok": False,
            "message": (
                "ELEVENLABS_API_KEY "
                "não configurada."
            )
        }), 500

    return jsonify({
        "ok": True,
        "message": "Servidor conectado à ElevenLabs.",
        "agent_id": ELEVENLABS_AGENT_ID
    })


# =========================================================
# AGENT - URL SEGURA
# =========================================================

@app.get("/api/agent/signed-url")
def agent_signed_url():

    try:

        signed_url = get_agent_signed_url()

        return jsonify({
            "ok": True,
            "signedUrl": signed_url
        })

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": str(error)
        }), 500


# =========================================================
# YOUTUBE LIVE
# =========================================================

@app.post("/api/youtube-live")
def youtube_live():

    data = request.get_json(
        silent=True
    ) or {}

    youtube_url = str(
        data.get("url", "")
    ).strip()

    target_language = str(
        data.get("targetLang", "pt")
    ).strip()

    if not youtube_url:

        return jsonify({
            "ok": False,
            "error": "Cole o link da YouTube Live."
        }), 400

    if (
        "youtube.com" not in youtube_url
        and "youtu.be" not in youtube_url
    ):

        return jsonify({
            "ok": False,
            "error": (
                "Informe um link válido "
                "do YouTube."
            )
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
            "error": "Idioma não suportado."
        }), 400

    # Neste primeiro estágio guardamos a URL.
    # A captura real do áudio será ligada
    # na próxima etapa.

    live_id = str(uuid.uuid4())

    jobs[live_id] = {
        "type": "youtube_live",
        "status": "ready",
        "message": (
            "Link recebido. "
            "Aguardando conexão de áudio."
        ),
        "youtube_url": youtube_url,
        "target_language": target_language
    }

    return jsonify({
        "ok": True,
        "liveId": live_id,
        "status": "ready",
        "message": (
            "Link da YouTube Live recebido."
        )
    })


# =========================================================
# UPLOAD DE VÍDEO
# =========================================================

@app.post("/api/test-upload")
def test_upload():

    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "error": (
                "ELEVENLABS_API_KEY "
                "não está configurada."
            )
        }), 500

    if "video" not in request.files:

        return jsonify({
            "ok": False,
            "error": "Nenhum vídeo foi enviado."
        }), 400

    video = request.files["video"]

    if not video.filename:

        return jsonify({
            "ok": False,
            "error": "Nome do arquivo inválido."
        }), 400

    target_language = request.form.get(
        "targetLang",
        "pt"
    ).strip()

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
            "error": "Idioma não suportado."
        }), 400

    job_id = str(uuid.uuid4())

    upload_dir = "/tmp/si_uploads"

    os.makedirs(
        upload_dir,
        exist_ok=True
    )

    file_path = os.path.join(
        upload_dir,
        f"{job_id}_{video.filename}"
    )

    try:

        video.save(file_path)

        file_size = os.path.getsize(
            file_path
        )

        if file_size > MAX_FILE_SIZE:

            os.remove(file_path)

            return jsonify({
                "ok": False,
                "error": (
                    "O vídeo é maior que 500 MB."
                )
            }), 400

    except Exception as error:

        return jsonify({
            "ok": False,
            "error": (
                f"Erro salvando vídeo: {error}"
            )
        }), 500

    jobs[job_id] = {
        "status": "queued",
        "message": "Vídeo recebido.",
        "target_language": target_language,
        "output_url": None
    }

    thread = threading.Thread(
        target=process_job,
        args=(
            job_id,
            file_path,
            target_language
        ),
        daemon=True
    )

    thread.start()

    return jsonify({
        "ok": True,
        "jobId": job_id,
        "status": "queued",
        "message": (
            "Vídeo enviado para processamento."
        )
    })


# =========================================================
# STATUS
# =========================================================

@app.get("/api/status/<job_id>")
def job_status(job_id):

    job = jobs.get(job_id)

    if not job:

        return jsonify({
            "ok": False,
            "error": "Job não encontrado."
        }), 404

    return jsonify({
        "ok": True,
        "jobId": job_id,
        **job
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

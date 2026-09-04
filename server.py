import os
import uuid
import threading
import time
from pathlib import Path

import requests
from flask import Flask, request, jsonify, send_from_directory
from flask_cors import CORS
from werkzeug.utils import secure_filename


# =========================================================
# CONFIGURAÇÃO
# =========================================================

app = Flask(__name__)
CORS(app)

# Render fornece a porta automaticamente
PORT = int(os.environ.get("PORT", 10000))

# Chave da ElevenLabs
ELEVENLABS_API_KEY = os.environ.get("ELEVENLABS_API_KEY")

# Endereço da API ElevenLabs
ELEVENLABS_API = "https://api.elevenlabs.io/v1"

# Pasta para arquivos temporários/resultados
BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "uploads"
OUTPUT_DIR = BASE_DIR / "outputs"

UPLOAD_DIR.mkdir(exist_ok=True)
OUTPUT_DIR.mkdir(exist_ok=True)

# Tamanho máximo do vídeo: 500 MB
app.config["MAX_CONTENT_LENGTH"] = 500 * 1024 * 1024

ALLOWED_EXTENSIONS = {
    "mp4",
    "mov",
    "mkv",
    "avi",
    "webm",
    "mp3",
    "wav",
    "m4a",
    "aac",
    "flac",
}

# Trabalhos em andamento
jobs = {}


# =========================================================
# FUNÇÕES AUXILIARES
# =========================================================

def allowed_file(filename):
    if not filename:
        return False

    extension = filename.rsplit(".", 1)[-1].lower()

    return extension in ALLOWED_EXTENSIONS


def extension_from_filename(filename):
    if "." in filename:
        return "." + filename.rsplit(".", 1)[-1].lower()

    return ".mp4"


def elevenlabs_headers():
    return {
        "xi-api-key": ELEVENLABS_API_KEY
    }


# =========================================================
# PÁGINA PRINCIPAL
# =========================================================

@app.route("/")
def home():
    return jsonify({
        "ok": True,
        "service": "SI - Tradutor e Dublagem IA",
        "message": "Servidor funcionando.",
        "provider": "ElevenLabs"
    })


# =========================================================
# HEALTH CHECK
# =========================================================

@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({
        "ok": True,
        "service": "SI Tradutor IA",
        "elevenlabs_configured": bool(ELEVENLABS_API_KEY)
    })


# =========================================================
# INFORMAÇÕES
# =========================================================

@app.route("/api", methods=["GET"])
def api_info():
    return jsonify({
        "ok": True,
        "message": "API SI Tradutor IA funcionando.",
        "endpoints": [
            "/api/health",
            "/api/test-upload",
            "/api/status/<job_id>",
            "/api/download/<filename>"
        ]
    })


# =========================================================
# UPLOAD E DUBLAGEM
# =========================================================

@app.route("/api/test-upload", methods=["POST"])
def test_upload():

    if not ELEVENLABS_API_KEY:
        return jsonify({
            "ok": False,
            "error": "ELEVENLABS_API_KEY não foi configurada no Render."
        }), 500

    if "video" not in request.files:
        return jsonify({
            "ok": False,
            "error": "Nenhum arquivo foi enviado. O campo deve ser 'video'."
        }), 400

    uploaded_file = request.files["video"]

    if not uploaded_file.filename:
        return jsonify({
            "ok": False,
            "error": "O arquivo não possui nome."
        }), 400

    if not allowed_file(uploaded_file.filename):
        return jsonify({
            "ok": False,
            "error": "Formato não suportado."
        }), 400

    target_lang = request.form.get("targetLang", "pt").strip().lower()

    # Idiomas aceitos pela interface.
    # A ElevenLabs suporta muitos outros idiomas também.
    supported_languages = {
        "pt",
        "en",
        "es",
        "fr",
        "de",
        "it",
        "ja",
        "ko",
        "zh",
        "hi",
        "ar",
        "ru",
        "tr",
        "nl",
        "pl",
        "sv",
        "da",
        "no",
        "fi",
        "el",
        "cs",
        "ro",
        "uk",
        "id",
        "vi"
    }

    if target_lang not in supported_languages:
        return jsonify({
            "ok": False,
            "error": "Idioma de destino não suportado pela interface."
        }), 400

    job_id = str(uuid.uuid4())

    original_name = secure_filename(uploaded_file.filename)
    extension = extension_from_filename(original_name)

    input_filename = f"{job_id}{extension}"
    input_path = UPLOAD_DIR / input_filename

    uploaded_file.save(input_path)

    jobs[job_id] = {
        "id": job_id,
        "status": "uploaded",
        "progress": 5,
        "message": "Arquivo recebido.",
        "target_language": target_lang,
        "input_file": str(input_path),
        "output_file": None,
        "error": None,
        "created_at": time.time()
    }

    # Inicia a dublagem em segundo plano
    worker = threading.Thread(
        target=dub_video,
        args=(job_id, target_lang),
        daemon=True
    )

    worker.start()

    return jsonify({
        "ok": True,
        "jobId": job_id,
        "status": "processing",
        "message": "Vídeo enviado para dublagem."
    })


# =========================================================
# PROCESSAMENTO ELEVENLABS
# =========================================================

def dub_video(job_id, target_lang):

    job = jobs.get(job_id)

    if not job:
        return

    input_path = Path(job["input_file"])

    try:

        job["status"] = "processing"
        job["progress"] = 10
        job["message"] = "Enviando vídeo para ElevenLabs..."

        with open(input_path, "rb") as media_file:

            files = {
                "file": (
                    input_path.name,
                    media_file,
                    "application/octet-stream"
                )
            }

            data = {
                "target_lang": target_lang,
                "source_lang": "auto",
                "name": f"SI Tradutor {job_id}"
            }

            response = requests.post(
                f"{ELEVENLABS_API}/dubbing",
                headers=elevenlabs_headers(),
                files=files,
                data=data,
                timeout=120
            )

        if response.status_code not in (200, 201):

            try:
                error_data = response.json()
            except Exception:
                error_data = response.text

            raise Exception(
                f"ElevenLabs retornou HTTP {response.status_code}: "
                f"{error_data}"
            )

        result = response.json()

        dubbing_id = result.get("dubbing_id")

        if not dubbing_id:
            raise Exception(
                "ElevenLabs não retornou o dubbing_id."
            )

        job["dubbing_id"] = dubbing_id
        job["status"] = "processing"
        job["progress"] = 25
        job["message"] = "ElevenLabs está processando o vídeo."

        # =================================================
        # ESPERA A DUBLAGEM TERMINAR
        # =================================================

        max_attempts = 180

        for attempt in range(max_attempts):

            time.sleep(5)

            status_response = requests.get(
                f"{ELEVENLABS_API}/dubbing/{dubbing_id}",
                headers=elevenlabs_headers(),
                timeout=60
            )

            if status_response.status_code != 200:

                raise Exception(
                    f"Erro ao consultar dublagem: "
                    f"HTTP {status_response.status_code}"
                )

            status_data = status_response.json()

            status = str(
                status_data.get("status", "")
            ).lower()

            job["elevenlabs_status"] = status

            # Atualiza progresso aproximado
            progress = min(
                85,
                25 + int((attempt / max_attempts) * 60)
            )

            job["progress"] = progress
            job["message"] = (
                f"Processando dublagem... {status or 'aguardando'}"
            )

            if status in {
                "dubbed",
                "completed",
                "complete",
                "finished"
            }:
                break

            if status in {
                "failed",
                "error",
                "cancelled",
                "canceled"
            }:
                raise Exception(
                    f"A ElevenLabs informou falha: {status}"
                )

        else:

            raise Exception(
                "A dublagem demorou mais que o tempo máximo de espera."
            )

        # =================================================
        # BAIXAR VÍDEO DUBLADO
        # =================================================

        job["progress"] = 90
        job["message"] = "Baixando vídeo dublado..."

        output_filename = f"{job_id}_dubbed.mp4"
        output_path = OUTPUT_DIR / output_filename

        audio_response = requests.get(
            f"{ELEVENLABS_API}/dubbing/{dubbing_id}/audio/{target_lang}",
            headers=elevenlabs_headers(),
            timeout=180
        )

        if audio_response.status_code != 200:

            raise Exception(
                f"Erro ao baixar resultado da ElevenLabs: "
                f"HTTP {audio_response.status_code}"
            )

        with open(output_path, "wb") as output_file:
            output_file.write(audio_response.content)

        job["output_file"] = output_filename
        job["status"] = "completed"
        job["progress"] = 100
        job["message"] = "Dublagem concluída."
        job["download_url"] = (
            f"/api/download/{output_filename}"
        )

    except Exception as error:

        job["status"] = "error"
        job["progress"] = 0
        job["message"] = "Erro durante a dublagem."
        job["error"] = str(error)

    finally:

        # Remove arquivo enviado depois do processamento
        try:
            if input_path.exists():
                input_path.unlink()
        except Exception:
            pass


# =========================================================
# CONSULTAR STATUS
# =========================================================

@app.route("/api/status/<job_id>", methods=["GET"])
def get_status(job_id):

    job = jobs.get(job_id)

    if not job:
        return jsonify({
            "ok": False,
            "error": "Trabalho não encontrado."
        }), 404

    response = {
        "ok": True,
        "jobId": job_id,
        "status": job.get("status"),
        "progress": job.get("progress", 0),
        "message": job.get("message"),
        "error": job.get("error")
    }

    if job.get("status") == "completed":
        response["download_url"] = job.get(
            "download_url"
        )

    return jsonify(response)


# =========================================================
# DOWNLOAD DO RESULTADO
# =========================================================

@app.route("/api/download/<filename>", methods=["GET"])
def download_file(filename):

    safe_name = secure_filename(filename)

    file_path = OUTPUT_DIR / safe_name

    if not file_path.exists():
        return jsonify({
            "ok": False,
            "error": "Arquivo não encontrado."
        }), 404

    return send_from_directory(
        OUTPUT_DIR,
        safe_name,
        as_attachment=False,
        mimetype="video/mp4"
    )


# =========================================================
# ERRO DE ARQUIVO GRANDE
# =========================================================

@app.errorhandler(413)
def file_too_large(error):

    return jsonify({
        "ok": False,
        "error": "O arquivo é muito grande. Limite: 500 MB."
    }), 413


# =========================================================
# INICIAR SERVIDOR
# =========================================================

if __name__ == "__main__":

    print("=" * 50)
    print("SI - Tradutor e Dublagem IA")
    print("ElevenLabs: somente")
    print(f"Porta: {PORT}")
    print(
        "API configurada:",
        bool(ELEVENLABS_API_KEY)
    )
    print("=" * 50)

    app.run(
        host="0.0.0.0",
        port=PORT,
        debug=False
    )

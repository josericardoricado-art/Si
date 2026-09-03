import os
import uuid
import subprocess
import threading
import time
from pathlib import Path

from flask import Flask, request, jsonify, send_file
from flask_cors import CORS
from werkzeug.utils import secure_filename


# =========================================================
# CONFIGURAÇÃO
# =========================================================

app = Flask(__name__)

CORS(
    app,
    resources={r"/api/*": {"origins": "*"}},
    supports_credentials=False
)

BASE_DIR = Path(__file__).resolve().parent

UPLOAD_DIR = BASE_DIR / "uploads"
OUTPUT_DIR = BASE_DIR / "outputs"

UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# Limite de upload: 100 MB
app.config["MAX_CONTENT_LENGTH"] = 100 * 1024 * 1024

# Jobs em memória
jobs = {}

# Extensões permitidas
ALLOWED_EXTENSIONS = {
    ".mp4",
    ".mov",
    ".mkv",
    ".webm",
    ".avi",
    ".m4v"
}


# =========================================================
# FUNÇÕES AUXILIARES
# =========================================================

def log(message):
    print(f"[SERVER] {message}", flush=True)


def allowed_file(filename):
    if not filename:
        return False

    extension = Path(filename).suffix.lower()

    return extension in ALLOWED_EXTENSIONS


def update_job(job_id, **values):
    if job_id not in jobs:
        return

    jobs[job_id].update(values)

    log(
        f"JOB {job_id}: "
        f"{values}"
    )


# =========================================================
# PROCESSAMENTO DO VÍDEO
# =========================================================

def process_video(job_id, input_file, output_file, target_lang):

    try:

        update_job(
            job_id,
            status="processing",
            stage="Iniciando processamento",
            progress=1
        )

        log("========================================")
        log("NOVO PROCESSAMENTO")
        log("========================================")
        log(f"Job: {job_id}")
        log(f"Entrada: {input_file}")
        log(f"Saída: {output_file}")
        log(f"Idioma: {target_lang}")

        # -------------------------------------------------
        # Verificação dos arquivos
        # -------------------------------------------------

        if not input_file.exists():

            raise Exception(
                "Arquivo de entrada não encontrado."
            )

        # -------------------------------------------------
        # Verificar pipeline.py
        # -------------------------------------------------

        pipeline_file = BASE_DIR / "pipeline.py"

        if not pipeline_file.exists():

            raise Exception(
                "pipeline.py não encontrado na raiz do projeto."
            )

        # -------------------------------------------------
        # Atualizar status
        # -------------------------------------------------

        update_job(
            job_id,
            stage="Enviando vídeo para processamento",
            progress=3
        )

        # -------------------------------------------------
        # Comando do pipeline
        # -------------------------------------------------

        command = [
            "python3",
            str(pipeline_file),

            "--input",
            str(input_file),

            "--output",
            str(output_file),

            "--target-lang",
            target_lang
        ]

        log("Executando pipeline:")
        log(" ".join(command))

        update_job(
            job_id,
            stage="Processando vídeo",
            progress=5
        )

        # -------------------------------------------------
        # Executar pipeline
        # -------------------------------------------------

        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )

        last_progress = 5

        # -------------------------------------------------
        # Ler logs do pipeline
        # -------------------------------------------------

        for line in process.stdout:

            line = line.strip()

            if not line:
                continue

            log(f"PIPELINE: {line}")

            # ---------------------------------------------
            # STAGE
            # ---------------------------------------------

            if line.startswith("STAGE:"):

                stage = line.replace(
                    "STAGE:",
                    "",
                    1
                ).strip()

                update_job(
                    job_id,
                    stage=stage
                )

            # ---------------------------------------------
            # PROGRESS
            # ---------------------------------------------

            if line.startswith("PROGRESS:"):

                try:

                    value = int(
                        line.replace(
                            "PROGRESS:",
                            "",
                            1
                        ).strip()
                    )

                    value = max(
                        0,
                        min(100, value)
                    )

                    last_progress = value

                    update_job(
                        job_id,
                        progress=value
                    )

                except Exception:

                    pass

        # -------------------------------------------------
        # Esperar processo
        # -------------------------------------------------

        return_code = process.wait()

        log(
            f"Pipeline finalizado com código: "
            f"{return_code}"
        )

        # -------------------------------------------------
        # Erro no pipeline
        # -------------------------------------------------

        if return_code != 0:

            raise Exception(
                f"O pipeline terminou com código {return_code}."
            )

        # -------------------------------------------------
        # Verificar vídeo final
        # -------------------------------------------------

        if not output_file.exists():

            raise Exception(
                "O pipeline terminou, mas o vídeo final "
                "não foi criado."
            )

        if output_file.stat().st_size <= 0:

            raise Exception(
                "O vídeo final foi criado vazio."
            )

        # -------------------------------------------------
        # SUCESSO
        # -------------------------------------------------

        update_job(
            job_id,
            status="completed",
            stage="Vídeo dublado pronto",
            progress=100,
            output_url=f"/api/video/{job_id}",
            finished_at=time.time()
        )

        log(
            f"JOB {job_id} concluído com sucesso."
        )

    except Exception as e:

        error_message = str(e)

        log(
            f"ERRO JOB {job_id}: "
            f"{error_message}"
        )

        update_job(
            job_id,
            status="error",
            stage="Erro no processamento",
            progress=0,
            error=error_message,
            finished_at=time.time()
        )


# =========================================================
# HEALTH CHECK
# =========================================================

@app.route("/api/health", methods=["GET"])
def health():

    return jsonify({
        "ok": True,
        "service": "SI - Tradutor Universal",
        "status": "online",
        "whisper": "tiny",
        "translation": "Argos Translate",
        "dubbing": "ElevenLabs"
    })


# =========================================================
# PÁGINA PRINCIPAL
# =========================================================

@app.route("/", methods=["GET"])
def home():

    return jsonify({
        "ok": True,
        "message": "SI - Tradutor Universal",
        "status": "online",
        "api": True
    })


# =========================================================
# UPLOAD E INÍCIO DO PROCESSAMENTO
# =========================================================

@app.route("/api/test-upload", methods=["POST"])
def test_upload():

    try:

        log("Recebendo upload...")

        # ---------------------------------------------
        # Verificar arquivo
        # ---------------------------------------------

        if "video" not in request.files:

            return jsonify({
                "ok": False,
                "error": "Nenhum vídeo foi enviado."
            }), 400

        video = request.files["video"]

        if not video or not video.filename:

            return jsonify({
                "ok": False,
                "error": "Arquivo de vídeo inválido."
            }), 400

        # ---------------------------------------------
        # Verificar extensão
        # ---------------------------------------------

        if not allowed_file(video.filename):

            return jsonify({
                "ok": False,
                "error": (
                    "Formato de vídeo não permitido. "
                    "Use MP4, MOV, MKV, WEBM, AVI ou M4V."
                )
            }), 400

        # ---------------------------------------------
        # Idioma
        # ---------------------------------------------

        target_lang = request.form.get(
            "targetLang",
            "en"
        ).lower().strip()

        # ---------------------------------------------
        # Idiomas permitidos
        # ---------------------------------------------

        allowed_languages = {
            "pt",
            "en",
            "es"
        }

        if target_lang not in allowed_languages:

            return jsonify({
                "ok": False,
                "error": (
                    "Idioma não permitido. "
                    "Use pt, en ou es."
                )
            }), 400

        # ---------------------------------------------
        # Criar ID
        # ---------------------------------------------

        job_id = str(uuid.uuid4())

        # ---------------------------------------------
        # Nome seguro
        # ---------------------------------------------

        original_name = secure_filename(
            video.filename
        )

        extension = Path(
            original_name
        ).suffix.lower()

        if not extension:
            extension = ".mp4"

        # ---------------------------------------------
        # Arquivo de entrada
        # ---------------------------------------------

        input_file = (
            UPLOAD_DIR /
            f"{job_id}{extension}"
        )

        output_file = (
            OUTPUT_DIR /
            f"{job_id}.mp4"
        )

        # ---------------------------------------------
        # Salvar upload
        # ---------------------------------------------

        log(
            f"Salvando vídeo em: {input_file}"
        )

        video.save(str(input_file))

        file_size = input_file.stat().st_size

        log(
            f"Vídeo recebido: "
            f"{file_size} bytes"
        )

        # ---------------------------------------------
        # Criar job
        # ---------------------------------------------

        jobs[job_id] = {

            "job_id": job_id,

            "status": "queued",

            "stage": "Vídeo recebido",

            "progress": 1,

            "target_lang": target_lang,

            "filename": original_name,

            "error": None,

            "output_url": None,

            "created_at": time.time(),

            "finished_at": None
        }

        # ---------------------------------------------
        # Iniciar processamento
        # ---------------------------------------------

        worker = threading.Thread(
            target=process_video,
            args=(
                job_id,
                input_file,
                output_file,
                target_lang
            ),
            daemon=True
        )

        worker.start()

        log(
            f"Processamento iniciado: "
            f"{job_id}"
        )

        # ---------------------------------------------
        # Resposta para o frontend
        # ---------------------------------------------

        return jsonify({

            "ok": True,

            "jobId": job_id,

            "status": "queued",

            "stage": "Vídeo recebido",

            "progress": 1

        }), 202

    except Exception as e:

        log(
            f"Erro no upload: {str(e)}"
        )

        return jsonify({

            "ok": False,

            "error": str(e)

        }), 500


# =========================================================
# STATUS DO JOB
# =========================================================

@app.route("/api/status/<job_id>", methods=["GET"])
def job_status(job_id):

    job = jobs.get(job_id)

    if not job:

        return jsonify({

            "ok": False,

            "error": "Job não encontrado."

        }), 404

    response = {

        "ok": True,

        "jobId": job_id,

        "status": job.get(
            "status",
            "unknown"
        ),

        "stage": job.get(
            "stage",
            ""
        ),

        "progress": job.get(
            "progress",
            0
        ),

        "targetLang": job.get(
            "target_lang"
        ),

        "filename": job.get(
            "filename"
        )
    }

    # ---------------------------------------------
    # Se terminou
    # ---------------------------------------------

    if job.get("status") == "completed":

        response["outputUrl"] = (
            job.get("output_url")
        )

        response["videoUrl"] = (
            f"/api/video/{job_id}"
        )

    # ---------------------------------------------
    # Se deu erro
    # ---------------------------------------------

    if job.get("status") == "error":

        response["error"] = (
            job.get("error")
        )

    return jsonify(response)


# =========================================================
# SERVIR VÍDEO FINAL
# =========================================================

@app.route("/api/video/<job_id>", methods=["GET"])
def get_video(job_id):

    job = jobs.get(job_id)

    if not job:

        return jsonify({

            "ok": False,

            "error": "Job não encontrado."

        }), 404

    if job.get("status") != "completed":

        return jsonify({

            "ok": False,

            "error": "O vídeo ainda não está pronto."

        }), 409

    output_file = (
        OUTPUT_DIR /
        f"{job_id}.mp4"
    )

    if not output_file.exists():

        return jsonify({

            "ok": False,

            "error": "Vídeo final não encontrado."

        }), 404

    return send_file(

        str(output_file),

        mimetype="video/mp4",

        as_attachment=False,

        download_name="video-dublado.mp4"
    )


# =========================================================
# TRATAMENTO DE ARQUIVO GRANDE
# =========================================================

@app.errorhandler(413)
def request_too_large(error):

    return jsonify({

        "ok": False,

        "error": (
            "O vídeo é muito grande. "
            "O limite é 100 MB."
        )

    }), 413


# =========================================================
# ERROS GERAIS
# =========================================================

@app.errorhandler(Exception)
def handle_exception(error):

    log(
        f"Erro interno: {str(error)}"
    )

    return jsonify({

        "ok": False,

        "error": str(error)

    }), 500


# =========================================================
# LIMPEZA DE JOBS ANTIGOS
# =========================================================

def cleanup_old_jobs():

    while True:

        try:

            now = time.time()

            expired = []

            for job_id, job in list(
                jobs.items()
            ):

                created = job.get(
                    "created_at",
                    now
                )

                # Remove jobs com mais de 2 horas
                if now - created > 7200:

                    expired.append(job_id)

            for job_id in expired:

                job = jobs.pop(
                    job_id,
                    None
                )

                if not job:
                    continue

                # Apagar entrada
                for extension in ALLOWED_EXTENSIONS:

                    file_path = (
                        UPLOAD_DIR /
                        f"{job_id}{extension}"
                    )

                    try:

                        if file_path.exists():
                            file_path.unlink()

                    except Exception:
                        pass

                # Apagar saída
                output_file = (
                    OUTPUT_DIR /
                    f"{job_id}.mp4"
                )

                try:

                    if output_file.exists():
                        output_file.unlink()

                except Exception:
                    pass

                log(
                    f"Job antigo removido: "
                    f"{job_id}"
                )

        except Exception as e:

            log(
                f"Erro na limpeza: {e}"
            )

        time.sleep(600)


# =========================================================
# INICIAR LIMPEZA
# =========================================================

cleanup_thread = threading.Thread(
    target=cleanup_old_jobs,
    daemon=True
)

cleanup_thread.start()


# =========================================================
# START LOCAL
# =========================================================

if __name__ == "__main__":

    port = int(
        os.environ.get(
            "PORT",
            "10000"
        )
    )

    log("========================================")
    log("SI - TRADUTOR UNIVERSAL")
    log("========================================")
    log(f"Servidor rodando na porta {port}")
    log("Modo vídeo: ATIVO")
    log("Polling de status: ATIVO")
    log("Whisper: tiny")
    log("Tradução: Argos Translate")
    log("Dublagem: ElevenLabs")
    log("========================================")

    app.run(
        host="0.0.0.0",
        port=port,
        threaded=True
  )

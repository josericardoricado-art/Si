import os
import uuid
import requests

from flask import Flask, request, jsonify
from flask_cors import CORS


# =========================================================
# CONFIGURAÇÃO
# =========================================================

app = Flask(__name__)
CORS(app)

PORT = int(os.environ.get("PORT", 10000))

ELEVENLABS_API_KEY = os.environ.get("ELEVENLABS_API_KEY", "")

ELEVENLABS_URL = "https://api.elevenlabs.io/v1"

UPLOAD_FOLDER = "uploads"

os.makedirs(UPLOAD_FOLDER, exist_ok=True)


# =========================================================
# IDIOMAS
# =========================================================

LANGUAGES = {
    "pt": "Português",
    "en": "Inglês",
    "es": "Espanhol",
    "fr": "Francês",
    "de": "Alemão",
    "it": "Italiano",
    "ja": "Japonês",
    "ko": "Coreano",
    "zh": "Chinês",
    "hi": "Hindi",
    "ar": "Árabe"
}


# =========================================================
# VERIFICAÇÃO DA API
# =========================================================

def verificar_api():

    if not ELEVENLABS_API_KEY:

        return False, "ELEVENLABS_API_KEY não configurada."

    return True, None


# =========================================================
# ROTA PRINCIPAL
# =========================================================

@app.route("/", methods=["GET"])
def home():

    return jsonify({
        "ok": True,
        "service": "SI - Tradutor & Dublagem IA",
        "message": "Servidor online",
        "elevenlabs": bool(ELEVENLABS_API_KEY)
    })


# =========================================================
# HEALTH
# =========================================================

@app.route("/api/health", methods=["GET"])
def health():

    return jsonify({
        "ok": True,
        "status": "online",
        "service": "SI",
        "elevenlabs_configured": bool(ELEVENLABS_API_KEY)
    })


# =========================================================
# IDIOMAS
# =========================================================

@app.route("/api/languages", methods=["GET"])
def languages():

    return jsonify({
        "ok": True,
        "languages": [
            {
                "code": code,
                "name": name
            }
            for code, name in LANGUAGES.items()
        ]
    })


# =========================================================
# CRIAR DUBLAGEM
# =========================================================

@app.route("/api/test-upload", methods=["POST"])
def test_upload():

    try:

        # -----------------------------------------------
        # Verificar API
        # -----------------------------------------------

        ok, erro = verificar_api()

        if not ok:

            return jsonify({
                "ok": False,
                "error": erro
            }), 500


        # -----------------------------------------------
        # Verificar vídeo
        # -----------------------------------------------

        if "video" not in request.files:

            return jsonify({
                "ok": False,
                "error": "Nenhum vídeo foi enviado."
            }), 400


        video = request.files["video"]


        if video.filename == "":

            return jsonify({
                "ok": False,
                "error": "Nenhum arquivo foi selecionado."
            }), 400


        # -----------------------------------------------
        # Idioma de destino
        # -----------------------------------------------

        target_language = request.form.get(
            "targetLang",
            request.form.get(
                "language",
                "pt"
            )
        ).strip().lower()


        if target_language not in LANGUAGES:

            return jsonify({
                "ok": False,
                "error": "Idioma de destino não suportado.",
                "allowed_languages": list(LANGUAGES.keys())
            }), 400


        # -----------------------------------------------
        # Salvar arquivo temporariamente
        # -----------------------------------------------

        extension = os.path.splitext(
            video.filename
        )[1].lower()

        if not extension:

            extension = ".mp4"


        filename = (
            str(uuid.uuid4())
            + extension
        )


        filepath = os.path.join(
            UPLOAD_FOLDER,
            filename
        )


        video.save(filepath)


        # -----------------------------------------------
        # Criar projeto na ElevenLabs
        # -----------------------------------------------

        url = (
            ELEVENLABS_URL
            + "/dubbing/project"
        )


        headers = {
            "xi-api-key": ELEVENLABS_API_KEY
        }


        try:

            with open(
                filepath,
                "rb"
            ) as arquivo:

                files = {
                    "file": (
                        video.filename,
                        arquivo,
                        video.mimetype or "video/mp4"
                    )
                }

                data = {
                    "model_id": "dubbing_v2",
                    "target_language": target_language,
                    "reference": "SI Tradutor IA"
                }


                resposta = requests.post(
                    url,
                    headers=headers,
                    files=files,
                    data=data,
                    timeout=300
                )


        finally:

            # Apagar arquivo temporário
            try:
                os.remove(filepath)
            except Exception:
                pass


        # -----------------------------------------------
        # Erro da ElevenLabs
        # -----------------------------------------------

        if resposta.status_code >= 400:

            try:
                detalhe = resposta.json()
            except Exception:
                detalhe = resposta.text


            return jsonify({
                "ok": False,
                "error": "Erro na ElevenLabs.",
                "details": detalhe
            }), resposta.status_code


        # -----------------------------------------------
        # Resposta
        # -----------------------------------------------

        projeto = resposta.json()


        project_id = projeto.get(
            "project_id"
        )


        language_ids = projeto.get(
            "language_ids",
            []
        )


        return jsonify({

            "ok": True,

            "jobId": project_id,

            "projectId": project_id,

            "languageIds": language_ids,

            "status": projeto.get(
                "status",
                "queued"
            ),

            "targetLang": target_language,

            "targetLanguage": LANGUAGES.get(
                target_language,
                target_language
            ),

            "message": (
                "Vídeo enviado para a ElevenLabs. "
                "A dublagem foi iniciada."
            )

        })


    except requests.exceptions.Timeout:

        return jsonify({
            "ok": False,
            "error": (
                "A ElevenLabs demorou muito "
                "para responder."
            )
        }), 504


    except Exception as erro:

        return jsonify({
            "ok": False,
            "error": str(erro)
        }), 500


# =========================================================
# STATUS DA DUBLAGEM
# =========================================================

@app.route("/api/status/<project_id>", methods=["GET"])
def status(project_id):

    try:

        ok, erro = verificar_api()

        if not ok:

            return jsonify({
                "ok": False,
                "error": erro
            }), 500


        # -----------------------------------------------
        # Buscar projeto
        # -----------------------------------------------

        project_url = (
            ELEVENLABS_URL
            + "/dubbing/project/"
            + project_id
        )


        headers = {
            "xi-api-key": ELEVENLABS_API_KEY
        }


        project_response = requests.get(
            project_url,
            headers=headers,
            timeout=60
        )


        if project_response.status_code >= 400:

            try:
                detalhe = project_response.json()
            except Exception:
                detalhe = project_response.text


            return jsonify({
                "ok": False,
                "error": "Erro ao consultar projeto.",
                "details": detalhe
            }), project_response.status_code


        projeto = project_response.json()


        project_status = projeto.get(
            "status",
            "unknown"
        )


        # -----------------------------------------------
        # Procurar idiomas do projeto
        # -----------------------------------------------

        language_url = (
            ELEVENLABS_URL
            + "/dubbing/project/"
            + project_id
            + "/language"
        )


        language_response = requests.get(
            language_url,
            headers=headers,
            params={
                "page_size": 100
            },
            timeout=60
        )


        idiomas = []


        if language_response.status_code < 400:

            language_data = language_response.json()

            idiomas = language_data.get(
                "languages",
                []
            )


        # -----------------------------------------------
        # Encontrar resultado concluído
        # -----------------------------------------------

        completed_language = None


        for idioma in idiomas:

            if idioma.get("status") == "completed":

                completed_language = idioma

                break


        # -----------------------------------------------
        # Dublagem concluída
        # -----------------------------------------------

        if completed_language:

            outputs = completed_language.get(
                "outputs"
            ) or {}


            return jsonify({

                "ok": True,

                "jobId": project_id,

                "projectId": project_id,

                "status": "completed",

                "message": (
                    "Dublagem concluída!"
                ),

                "targetLang": completed_language.get(
                    "target_language"
                ),

                "languageId": completed_language.get(
                    "language_id"
                ),

                "output": outputs,

                "outputs": outputs

            })


        # -----------------------------------------------
        # Algum idioma falhou
        # -----------------------------------------------

        for idioma in idiomas:

            if idioma.get("status") == "failed":

                return jsonify({

                    "ok": False,

                    "jobId": project_id,

                    "projectId": project_id,

                    "status": "failed",

                    "message": (
                        "A ElevenLabs informou "
                        "que a dublagem falhou."
                    ),

                    "error": idioma.get(
                        "error"
                    )

                })


        # -----------------------------------------------
        # Projeto falhou
        # -----------------------------------------------

        if project_status == "failed":

            return jsonify({

                "ok": False,

                "jobId": project_id,

                "projectId": project_id,

                "status": "failed",

                "message": (
                    "O projeto de dublagem falhou."
                ),

                "error": projeto.get(
                    "error"
                )

            })


        # -----------------------------------------------
        # Ainda processando
        # -----------------------------------------------

        return jsonify({

            "ok": True,

            "jobId": project_id,

            "projectId": project_id,

            "status": project_status,

            "message": (
                "A ElevenLabs ainda está "
                "processando o vídeo."
            ),

            "languages": idiomas

        })


    except requests.exceptions.Timeout:

        return jsonify({

            "ok": False,

            "error": (
                "Tempo limite ao consultar "
                "a ElevenLabs."
            )

        }), 504


    except Exception as erro:

        return jsonify({

            "ok": False,

            "error": str(erro)

        }), 500


# =========================================================
# STATUS DE UM IDIOMA ESPECÍFICO
# =========================================================

@app.route(
    "/api/status/<project_id>/<language_id>",
    methods=["GET"]
)
def status_language(
    project_id,
    language_id
):

    try:

        ok, erro = verificar_api()

        if not ok:

            return jsonify({
                "ok": False,
                "error": erro
            }), 500


        url = (
            ELEVENLABS_URL
            + "/dubbing/project/"
            + project_id
            + "/language/"
            + language_id
        )


        headers = {
            "xi-api-key": ELEVENLABS_API_KEY
        }


        resposta = requests.get(
            url,
            headers=headers,
            timeout=60
        )


        try:
            dados = resposta.json()
        except Exception:
            dados = {
                "error": resposta.text
            }


        if resposta.status_code >= 400:

            return jsonify({
                "ok": False,
                "details": dados
            }), resposta.status_code


        return jsonify({

            "ok": True,

            "jobId": project_id,

            "projectId": project_id,

            "languageId": language_id,

            "status": dados.get(
                "status"
            ),

            "targetLang": dados.get(
                "target_language"
            ),

            "outputs": dados.get(
                "outputs"
            ),

            "error": dados.get(
                "error"
            )

        })


    except Exception as erro:

        return jsonify({
            "ok": False,
            "error": str(erro)
        }), 500


# =========================================================
# TRATAMENTO DE 404
# =========================================================

@app.errorhandler(404)
def not_found(error):

    return jsonify({

        "ok": False,

        "error": "Endpoint não encontrado."

    }), 404


# =========================================================
# INICIALIZAÇÃO
# =========================================================

if __name__ == "__main__":

    print(
        "========================================"
    )

    print(
        "SI - Tradutor & Dublagem IA"
    )

    print(
        "ElevenLabs Dubbing API"
    )

    print(
        f"Porta: {PORT}"
    )

    print(
        f"ElevenLabs configurada: "
        f"{bool(ELEVENLABS_API_KEY)}"
    )

    print(
        "========================================"
    )


    app.run(

        host="0.0.0.0",

        port=PORT,

        debug=False

            )

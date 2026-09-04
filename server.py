import os
import uuid
import tempfile
import threading
import subprocess
from datetime import datetime

from flask import Flask, request, jsonify
from flask_cors import CORS


# ============================================================
# CONFIGURAÇÃO
# ============================================================

app = Flask(__name__)

CORS(
    app,
    resources={
        r"/api/*": {
            "origins": "*"
        }
    }
)


PORT = int(
    os.environ.get(
        "PORT",
        "10000"
    )
)


# Guarda os trabalhos enquanto o servidor estiver rodando.
# Observação: no plano gratuito do Render, isso pode ser perdido
# quando a instância reinicia.
JOBS = {}


# ============================================================
# PÁGINA PRINCIPAL
# ============================================================

@app.route("/")
def home():

    return jsonify({
        "ok": True,
        "service": "si-tradutor-backend",
        "message": "Backend do SI funcionando",
        "version": "4.0"
    })


# ============================================================
# HEALTH CHECK
# ============================================================

@app.route("/api/health", methods=["GET"])
def health():

    return jsonify({
        "ok": True,
        "service": "si-tradutor-backend",
        "status": "online",
        "version": "4.0"
    })


# ============================================================
# CHAT
# ============================================================

@app.route("/api/chat", methods=["POST"])
def chat():

    try:

        data = request.get_json(
            silent=True
        ) or {}


        message = str(
            data.get(
                "message",
                ""
            )
        ).strip()


        if not message:

            return jsonify({
                "ok": False,
                "error": "Mensagem vazia."
            }), 400


        # ----------------------------------------------------
        # Respostas básicas.
        #
        # Se OPENAI_API_KEY estiver configurada no Render,
        # o servidor poderá usar a API da OpenAI.
        # ----------------------------------------------------

        api_key = os.environ.get(
            "OPENAI_API_KEY"
        )


        if api_key:

            try:

                resposta = chamar_openai(
                    message,
                    api_key
                )

                return jsonify({
                    "ok": True,
                    "reply": resposta
                })

            except Exception as erro:

                print(
                    "Erro OpenAI:",
                    erro
                )


        # ----------------------------------------------------
        # Resposta local caso não exista API key.
        # ----------------------------------------------------

        resposta = resposta_local(
            message
        )


        return jsonify({
            "ok": True,
            "reply": resposta
        })


    except Exception as erro:

        print(
            "Erro /api/chat:",
            erro
        )

        return jsonify({
            "ok": False,
            "error": "Erro interno no servidor."
        }), 500


# ============================================================
# RESPOSTA LOCAL
# ============================================================

def resposta_local(message):

    texto = message.lower().strip()


    if texto in [
        "oi",
        "olá",
        "ola",
        "hello",
        "hi"
    ]:

        return (
            "Olá! 👋 "
            "Eu sou o SI. "
            "Como posso ajudar você?"
        )


    if "tudo bem" in texto:

        return (
            "Tudo bem por aqui! 😊 "
            "Pode mandar sua mensagem."
        )


    if "quem é você" in texto or \
       "quem e voce" in texto:

        return (
            "Eu sou o SI, seu assistente "
            "do Tradutor Universal."
        )


    if "obrigado" in texto or \
       "obrigada" in texto:

        return (
            "Por nada! 😊 "
            "Estou aqui para ajudar."
        )


    if "vídeo" in texto or \
       "video" in texto:

        return (
            "Você pode enviar um vídeo "
            "pela área de upload. "
            "O arquivo será enviado para o backend."
        )


    return (
        "Recebi sua mensagem: "
        f"\"{message}\"\n\n"
        "O servidor está funcionando. "
        "Para respostas inteligentes completas, "
        "você pode configurar OPENAI_API_KEY "
        "nas variáveis de ambiente do Render."
    )


# ============================================================
# OPENAI
# ============================================================

def chamar_openai(message, api_key):

    """
    Faz uma chamada HTTP usando somente a biblioteca padrão
    do Python. Assim não precisamos instalar o pacote openai.
    """

    import json
    import urllib.request
    import urllib.error


    url = "https://api.openai.com/v1/responses"


    payload = {
        "model": os.environ.get(
            "OPENAI_MODEL",
            "gpt-5-mini"
        ),

        "input": [
            {
                "role": "system",
                "content": (
                    "Você é o SI, um assistente "
                    "útil e amigável. "
                    "Responda em português do Brasil "
                    "quando o usuário escrever em português."
                )
            },
            {
                "role": "user",
                "content": message
            }
        ]
    }


    body = json.dumps(
        payload
    ).encode("utf-8")


    requisicao =
        urllib.request.Request(
            url,
            data=body,
            method="POST"
        )


    requisicao.add_header(
        "Content-Type",
        "application/json"
    )

    requisicao.add_header(
        "Authorization",
        "Bearer " + api_key
    )


    with urllib.request.urlopen(
        requisicao,
        timeout=60
    ) as resposta:

        dados = json.loads(
            resposta.read().decode(
                "utf-8"
            )
        )


    # A API Responses retorna o texto em output.
    texto = extrair_texto_openai(
        dados
    )


    if not texto:

        raise RuntimeError(
            "A OpenAI não retornou texto."
        )


    return texto


# ============================================================
# EXTRAIR RESPOSTA DA OPENAI
# ============================================================

def extrair_texto_openai(data):

    partes = []


    output = data.get(
        "output",
        []
    )


    for item in output:

        if not isinstance(
            item,
            dict
        ):
            continue


        content = item.get(
            "content",
            []
        )


        for bloco in content:

            if not isinstance(
                bloco,
                dict
            ):
                continue


            texto = bloco.get(
                "text"
            )


            if texto:

                partes.append(
                    texto
                )


    return "\n".join(
        partes
    ).strip()


# ============================================================
# UPLOAD DE VÍDEO
# ============================================================

@app.route(
    "/api/test-upload",
    methods=["POST"]
)
def test_upload():

    try:

        if "video" not in request.files:

            return jsonify({
                "ok": False,
                "error": "Nenhum vídeo foi enviado."
            }), 400


        arquivo = request.files[
            "video"
        ]


        if not arquivo.filename:

            return jsonify({
                "ok": False,
                "error": "Arquivo sem nome."
            }), 400


        target_lang = request.form.get(
            "targetLang",
            "pt"
        )


        idiomas_permitidos = [
            "pt",
            "en",
            "es"
        ]


        if target_lang not in idiomas_permitidos:

            target_lang = "pt"


        job_id = str(
            uuid.uuid4()
        )


        extensao = os.path.splitext(
            arquivo.filename
        )[1].lower()


        if not extensao:

            extensao = ".mp4"


        pasta = tempfile.gettempdir()


        caminho = os.path.join(
            pasta,
            f"si_{job_id}{extensao}"
        )


        arquivo.save(
            caminho
        )


        JOBS[job_id] = {
            "id": job_id,
            "status": "received",
            "targetLang": target_lang,
            "filename": arquivo.filename,
            "createdAt": datetime.utcnow().isoformat(),
            "file": caminho
        }


        # Processamento em segundo plano.
        thread = threading.Thread(
            target=processar_video,
            args=(
                job_id,
                caminho,
                target_lang
            ),
            daemon=True
        )

        thread.start()


        return jsonify({
            "ok": True,
            "message": "Vídeo recebido.",
            "jobId": job_id,
            "status": "received",
            "targetLang": target_lang
        })


    except Exception as erro:

        print(
            "Erro upload:",
            erro
        )

        return jsonify({
            "ok": False,
            "error": "Erro ao receber o vídeo."
        }), 500


# ============================================================
# STATUS
# ============================================================

@app.route(
    "/api/status/<job_id>",
    methods=["GET"]
)
def status(job_id):

    job = JOBS.get(
        job_id
    )


    if not job:

        return jsonify({
            "ok": False,
            "error": "Job não encontrado."
        }), 404


    return jsonify({
        "ok": True,
        "jobId": job_id,
        "status": job.get(
            "status",
            "unknown"
        ),
        "targetLang": job.get(
            "targetLang"
        ),
        "filename": job.get(
            "filename"
        ),
        "message": job.get(
            "message"
        )
    })


# ============================================================
# PROCESSAMENTO DO VÍDEO
# ============================================================

def processar_video(
    job_id,
    caminho,
    target_lang
):

    try:

        JOBS[job_id]["status"] = \
            "processing"


        JOBS[job_id]["message"] = \
            "Vídeo recebido e processamento iniciado."


        # ----------------------------------------------------
        # Aqui fazemos apenas uma verificação básica do vídeo.
        # Isso evita que o servidor quebre caso Whisper/Argos
        # não estejam instalados.
        # ----------------------------------------------------

        resultado = verificar_video(
            caminho
        )


        if resultado:

            JOBS[job_id]["status"] = \
                "completed"

            JOBS[job_id]["message"] = \
                (
                    "Vídeo recebido corretamente. "
                    "A etapa de tradução/dublagem "
                    "pode ser adicionada ao pipeline."
                )

        else:

            JOBS[job_id]["status"] = \
                "failed"

            JOBS[job_id]["message"] = \
                "Não foi possível verificar o vídeo."


    except Exception as erro:

        print(
            "Erro processamento:",
            erro
        )

        JOBS[job_id]["status"] = \
            "failed"

        JOBS[job_id]["message"] = \
            str(erro)


    finally:

        # Remove o arquivo temporário depois do processamento.
        try:

            if os.path.exists(
                caminho
            ):

                os.remove(
                    caminho
                )

        except Exception:
            pass


# ============================================================
# VERIFICAR VÍDEO COM FFMPEG
# ============================================================

def verificar_video(caminho):

    if not os.path.exists(
        caminho
    ):

        return False


    try:

        comando = [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            caminho,
            "-f",
            "null",
            "-"
        ]


        resultado = subprocess.run(
            comando,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=120
        )


        if resultado.returncode == 0:

            return True


        print(
            "FFmpeg:",
            resultado.stderr.decode(
                "utf-8",
                errors="ignore"
            )
        )


        return False


    except Exception as erro:

        print(
            "FFmpeg erro:",
            erro
        )

        return False


# ============================================================
# EXECUTAR
# ============================================================

if __name__ == "__main__":

    print(
        "===================================="
    )

    print(
        " SI — Tradutor Universal"
    )

    print(
        " Backend iniciado"
    )

    print(
        f" Porta: {PORT}"
    )

    print(
        "===================================="
    )


    app.run(
        host="0.0.0.0",
        port=PORT,
        debug=False
        )

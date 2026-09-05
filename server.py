import os
import re
import json
import uuid
import base64
import queue
import threading
import subprocess
import time

import requests
import websocket

from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# =========================================================
# CONFIGURAÇÃO
# =========================================================

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY")

AGENT_ID = os.getenv(
    "ELEVENLABS_AGENT_ID",
    "agent_1601m1q929bhf2zvts65479fyzdw"
)

PORT = int(os.getenv("PORT", "10000"))

# =========================================================
# JOBS
# =========================================================

live_jobs = {}

# =========================================================
# FUNÇÕES
# =========================================================


def eleven_headers():
    return {
        "xi-api-key": ELEVENLABS_API_KEY
    }


def get_youtube_id(url):
    if not url:
        return None

    patterns = [
        r"youtube\.com/live/([^?&#/]+)",
        r"youtube\.com/watch\?v=([^&#]+)",
        r"youtu\.be/([^?&#/]+)",
        r"youtube\.com/embed/([^?&#/]+)"
    ]

    for pattern in patterns:
        match = re.search(
            pattern,
            url,
            re.IGNORECASE
        )

        if match:
            return match.group(1)

    return None


def get_signed_url():
    """
    Cria uma URL temporária para o Agent.
    A API key nunca vai para o navegador.
    """

    url = (
        "https://api.elevenlabs.io/v1/"
        "convai/conversation/get-signed-url"
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
            "Erro criando Signed URL: "
            f"{response.status_code} "
            f"{response.text}"
        )

    data = response.json()

    signed_url = data.get("signed_url")

    if not signed_url:
        raise Exception(
            "ElevenLabs não retornou signed_url."
        )

    return signed_url


def find_ffmpeg():
    """
    O imageio-ffmpeg instala um FFmpeg portátil.
    """

    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()

    except Exception as error:

        raise Exception(
            "FFmpeg não encontrado. "
            f"Erro: {error}"
        )


def get_youtube_stream_url(youtube_url):
    """
    Usa yt-dlp para descobrir o endereço direto
    do áudio da transmissão.
    """

    command = [
        "yt-dlp",
        "--no-warnings",
        "--quiet",
        "--no-playlist",
        "-f",
        "bestaudio/best",
        "-g",
        youtube_url
    ]

    process = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=60
    )

    if process.returncode != 0:

        raise Exception(
            "Não foi possível obter o áudio da "
            "YouTube Live: "
            + process.stderr.strip()
        )

    stream_url = process.stdout.strip().splitlines()

    if not stream_url:
        raise Exception(
            "YouTube não retornou um stream de áudio."
        )

    return stream_url[0]


# =========================================================
# ÁUDIO DO AGENT
# =========================================================


def add_audio_to_browser(job, audio_base64):

    try:

        job["audio_queue"].put({
            "audio": audio_base64
        })

    except Exception:
        pass


# =========================================================
# ELEVENLABS WEBSOCKET
# =========================================================


def start_elevenlabs_agent(job):
    """
    Abre a conexão WebSocket com o Agent.
    """

    signed_url = get_signed_url()

    ws = websocket.create_connection(
        signed_url,
        timeout=30
    )

    job["eleven_ws"] = ws

    # Inicialização
    init_message = {
        "type":
            "conversation_initiation_client_data"
    }

    ws.send(
        json.dumps(init_message)
    )

    job["agent_connected"] = True

    while job["status"] == "running":

        try:

            message = ws.recv()

            if not message:
                continue

            data = json.loads(message)

            event_type = data.get("type")

            # -----------------------------------------
            # PING
            # -----------------------------------------

            if event_type == "ping":

                ping_event = data.get(
                    "ping_event",
                    {}
                )

                event_id = ping_event.get(
                    "event_id"
                )

                ws.send(
                    json.dumps({
                        "type": "pong",
                        "event_id": event_id
                    })
                )

            # -----------------------------------------
            # TRANSCRIÇÃO
            # -----------------------------------------

            elif event_type == "user_transcript":

                transcript_event = data.get(
                    "user_transcription_event",
                    {}
                )

                transcript = transcript_event.get(
                    "user_transcript",
                    ""
                )

                if transcript:

                    job["last_transcript"] = transcript

                    job["transcripts"].append(
                        transcript
                    )

            # -----------------------------------------
            # RESPOSTA DO AGENT
            # -----------------------------------------

            elif event_type == "agent_response":

                response_event = data.get(
                    "agent_response_event",
                    {}
                )

                text = response_event.get(
                    "agent_response",
                    ""
                )

                if text:

                    job["last_translation"] = text

                    job["translations"].append(
                        text
                    )

            # -----------------------------------------
            # ÁUDIO TRADUZIDO
            # -----------------------------------------

            elif event_type == "audio":

                audio_event = data.get(
                    "audio_event",
                    {}
                )

                audio_base64 = audio_event.get(
                    "audio_base_64"
                )

                if audio_base64:

                    # Ignora o áudio inicial
                    # de saudação do Agent.
                    if not job["received_transcript"]:

                        continue

                    add_audio_to_browser(
                        job,
                        audio_base64
                    )

            # -----------------------------------------
            # ERRO
            # -----------------------------------------

            elif event_type in (
                "error",
                "client_error"
            ):

                job["error"] = str(data)

        except websocket.WebSocketTimeoutException:

            continue

        except Exception as error:

            if job["status"] == "running":

                job["error"] = str(error)

            break

    try:
        ws.close()
    except Exception:
        pass


# =========================================================
# ENVIA ÁUDIO PARA O AGENT
# =========================================================


def send_audio_to_agent(job, audio_chunk):

    ws = job.get("eleven_ws")

    if not ws:
        return

    if not job.get("agent_connected"):
        return

    encoded = base64.b64encode(
        audio_chunk
    ).decode("ascii")

    message = {
        "user_audio_chunk": encoded
    }

    try:

        ws.send(
            json.dumps(message)
        )

        job["received_audio"] = True

    except Exception as error:

        job["error"] = (
            "Erro enviando áudio para ElevenLabs: "
            + str(error)
        )


# =========================================================
# CAPTURA YOUTUBE + FFMPEG
# =========================================================


def capture_youtube_audio(job):

    youtube_url = job["youtube_url"]

    stream_url = get_youtube_stream_url(
        youtube_url
    )

    ffmpeg = find_ffmpeg()

    job["audio_capture"] = "running"

    command = [
        ffmpeg,

        "-loglevel",
        "error",

        "-reconnect",
        "1",

        "-reconnect_streamed",
        "1",

        "-reconnect_delay_max",
        "5",

        "-i",
        stream_url,

        "-vn",

        "-ac",
        "1",

        "-ar",
        "16000",

        "-f",
        "s16le",

        "pipe:1"
    ]

    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )

    job["ffmpeg_process"] = process

    chunk_size = 3200

    while job["status"] == "running":

        audio = process.stdout.read(
            chunk_size
        )

        if not audio:

            break

        send_audio_to_agent(
            job,
            audio
        )

    try:

        process.kill()

    except Exception:
        pass

    job["audio_capture"] = "stopped"


# =========================================================
# PROCESSAMENTO DA LIVE
# =========================================================


def process_live(job):

    try:

        job["status"] = "starting"

        # ---------------------------------------------
        # Agent
        # ---------------------------------------------

        agent_thread = threading.Thread(
            target=start_elevenlabs_agent,
            args=(job,),
            daemon=True
        )

        agent_thread.start()

        # Espera a conexão
        # ficar disponível.

        for _ in range(100):

            if job.get("agent_connected"):
                break

            if job.get("error"):
                raise Exception(
                    job["error"]
                )

            time.sleep(0.1)

        if not job.get("agent_connected"):

            raise Exception(
                "Não foi possível conectar ao Agent."
            )

        # ---------------------------------------------
        # Captura do YouTube
        # ---------------------------------------------

        job["status"] = "running"

        capture_youtube_audio(
            job
        )

    except Exception as error:

        job["status"] = "error"

        job["error"] = str(error)

        job["message"] = str(error)

    finally:

        if job["status"] == "running":

            job["status"] = "stopped"


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
            "message":
                "ELEVENLABS_API_KEY não configurada."
        }), 500

    return jsonify({
        "ok": True,
        "provider": "ElevenLabs",
        "agent_id": AGENT_ID,
        "message":
            "Servidor conectado à ElevenLabs."
    })


# =========================================================
# INICIAR YOUTUBE LIVE
# =========================================================


@app.post("/api/youtube-live")
def youtube_live():

    if not ELEVENLABS_API_KEY:

        return jsonify({
            "ok": False,
            "error":
                "ELEVENLABS_API_KEY não configurada."
        }), 500

    data = request.get_json(
        silent=True
    ) or {}

    youtube_url = str(
        data.get("url", "")
    ).strip()

    if not youtube_url:

        return jsonify({
            "ok": False,
            "error":
                "Cole o link da YouTube Live."
        }), 400

    video_id = get_youtube_id(
        youtube_url
    )

    if not video_id:

        return jsonify({
            "ok": False,
            "error":
                "Link do YouTube inválido."
        }), 400

    live_id = str(
        uuid.uuid4()
    )

    job = {

        "live_id":
            live_id,

        "youtube_url":
            youtube_url,

        "video_id":
            video_id,

        "status":
            "queued",

        "message":
            "Iniciando tradução...",

        "agent_id":
            AGENT_ID,

        "agent_connected":
            False,

        "received_audio":
            False,

        "received_transcript":
            False,

        "audio_capture":
            "waiting",

        "last_transcript":
            "",

        "last_translation":
            "",

        "transcripts":
            [],

        "translations":
            [],

        "audio_queue":
            queue.Queue(),

        "error":
            None
    }

    live_jobs[live_id] = job

    thread = threading.Thread(
        target=process_live,
        args=(job,),
        daemon=True
    )

    thread.start()

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "videoId":
            video_id,

        "status":
            "queued",

        "message":
            "Live iniciada."
    })


# =========================================================
# STATUS
# =========================================================


@app.get("/api/youtube-live/<live_id>")
def live_status(live_id):

    job = live_jobs.get(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error":
                "Live não encontrada."
        }), 404

    return jsonify({

        "ok": True,

        "liveId":
            live_id,

        "status":
            job["status"],

        "message":
            job.get("message"),

        "audioCapture":
            job.get("audio_capture"),

        "agentConnected":
            job.get("agent_connected"),

        "lastTranscript":
            job.get("last_transcript"),

        "lastTranslation":
            job.get("last_translation"),

        "error":
            job.get("error")
    })


# =========================================================
# ÁUDIO TRADUZIDO
# =========================================================


@app.get("/api/youtube-live/<live_id>/audio")
def live_audio(live_id):

    job = live_jobs.get(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error":
                "Live não encontrada."
        }), 404

    def generate():

        while True:

            if (
                job["status"] in (
                    "error",
                    "stopped"
                )
                and job["audio_queue"].empty()
            ):
                break

            try:

                item = job["audio_queue"].get(
                    timeout=2
                )

                yield (
                    "data: "
                    + json.dumps(item)
                    + "\n\n"
                )

            except queue.Empty:

                yield ": heartbeat\n\n"

    return Response(
        stream_with_context(
            generate()
        ),
        mimetype="text/event-stream",
        headers={
            "Cache-Control":
                "no-cache",
            "X-Accel-Buffering":
                "no"
        }
    )


# =========================================================
# PARAR LIVE
# =========================================================


@app.post("/api/youtube-live/<live_id>/stop")
def stop_live(live_id):

    job = live_jobs.get(
        live_id
    )

    if not job:

        return jsonify({
            "ok": False,
            "error":
                "Live não encontrada."
        }), 404

    job["status"] = "stopped"

    process = job.get(
        "ffmpeg_process"
    )

    if process:

        try:
            process.kill()
        except Exception:
            pass

    ws = job.get(
        "eleven_ws"
    )

    if ws:

        try:
            ws.close()
        except Exception:
            pass

    return jsonify({
        "ok": True,
        "message":
            "Live parada."
    })


# =========================================================
# EXECUÇÃO
# =========================================================


if __name__ == "__main__":

    app.run(
        host="0.0.0.0",
        port=PORT
    )

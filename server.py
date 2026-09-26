import os
import re
import json
import time
import uuid
import base64
import wave
import queue
import threading
import subprocess
import sys
from pathlib import Path

import requests
import websocket
from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS
from piper import PiperVoice

# SI â€” TRADUTOR LIVE
# Deepgram = voz -> texto
# DeepL Text = traduÃ§Ã£o
# Piper = texto -> voz
# Sem OpenAI, ElevenLabs ou DeepL Voice.

app = Flask(__name__)
CORS(app, resources={r"/*": {"origins": "*"}}, supports_credentials=False)

PORT = int(os.environ.get("PORT", "10000"))
DEEPGRAM_API_KEY = os.environ.get("DEEPGRAM_API_KEY", "").strip()
DEEPL_API_KEY = os.environ.get("DEEPL_API_KEY", "").strip()
DEEPL_API_URL = os.environ.get("DEEPL_API_URL", "https://api-free.deepl.com/v2/translate").strip()
DEFAULT_TARGET_LANGUAGE = os.environ.get("DEFAULT_TARGET_LANGUAGE", "pt").strip().lower()
DEEPGRAM_SOURCE_LANGUAGE = os.environ.get("DEEPGRAM_SOURCE_LANGUAGE", "pt-BR").strip()
PIPER_DATA_DIR = Path(os.environ.get("PIPER_DATA_DIR", "/app/voices"))
AUDIO_DIR = Path(os.environ.get("AUDIO_DIR", "/tmp/si_audio"))
PIPER_DATA_DIR.mkdir(parents=True, exist_ok=True)
AUDIO_DIR.mkdir(parents=True, exist_ok=True)

PIPER_VOICES = {
    "pt": "pt_BR-faber-medium",
    "en": "en_US-lessac-medium",
    "es": "es_ES-davefx-medium",
    "fr": "fr_FR-siwis-medium",
    "de": "de_DE-thorsten-medium",
    "it": "it_IT-riccardo-x_low",
}

piper_voices = {}
piper_locks = {}
piper_global_lock = threading.Lock()
audio_sessions = {}
audio_sessions_lock = threading.Lock()


def normalize_language(language):
    language = str(language or DEFAULT_TARGET_LANGUAGE).strip().lower()
    aliases = {
        "pt-br": "pt", "pt_br": "pt", "portuguese": "pt", "portugues": "pt", "portuguÃªs": "pt",
        "en-us": "en", "en_us": "en", "english": "en", "ingles": "en", "inglÃªs": "en",
        "es-es": "es", "es_es": "es", "spanish": "es", "espanhol": "es", "espanol": "es",
        "fr-fr": "fr", "fr_fr": "fr", "french": "fr", "frances": "fr", "francÃªs": "fr",
        "de-de": "de", "de_de": "de", "german": "de", "alemao": "de", "alemÃ£o": "de",
        "it-it": "it", "it_it": "it", "italian": "it", "italiano": "it",
    }
    language = aliases.get(language, language)
    return language if language in PIPER_VOICES else "pt"


def deepl_language(language):
    return {"pt": "PT-BR", "en": "EN", "es": "ES", "fr": "FR", "de": "DE", "it": "IT"}.get(normalize_language(language), "PT-BR")


def clean_text(text):
    if not text:
        return ""
    return re.sub(r"\s+", " ", str(text)).strip()[:1500]


def find_piper_model(model_name):
    for path in (
        PIPER_DATA_DIR / f"{model_name}.onnx",
        Path("/app/voices") / f"{model_name}.onnx",
        Path("/tmp/piper") / f"{model_name}.onnx",
    ):
        if path.exists():
            return path
    return None


def download_piper_voice(model_name):
    model = find_piper_model(model_name)
    if model:
        return model
    print(f"[PIPER] Baixando voz: {model_name}", flush=True)
    result = subprocess.run(
        [sys.executable, "-m", "piper.download_voices", model_name, "--data-dir", str(PIPER_DATA_DIR)],
        capture_output=True, text=True
    )
    if result.stdout:
        print(result.stdout, flush=True)
    if result.stderr:
        print(result.stderr, flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"Falha ao baixar voz Piper {model_name}: {result.stderr[-500:]}")
    model = find_piper_model(model_name)
    if not model:
        raise RuntimeError(f"Modelo Piper nÃ£o encontrado: {model_name}")
    return model


def get_piper_voice(language):
    language = normalize_language(language)
    with piper_global_lock:
        if language in piper_voices:
            return piper_voices[language]
        lock = piper_locks.setdefault(language, threading.Lock())
    with lock:
        if language in piper_voices:
            return piper_voices[language]
        model_path = download_piper_voice(PIPER_VOICES[language])
        print(f"[PIPER] Carregando {model_path}", flush=True)
        voice = PiperVoice.load(str(model_path))
        with piper_global_lock:
            piper_voices[language] = voice
        print(f"[PIPER] Voz pronta: {language}", flush=True)
        return voice


def generate_piper_audio(text, language):
    text = clean_text(text)
    if not text:
        return None
    voice = get_piper_voice(language)
    filename = f"{uuid.uuid4().hex}.wav"
    output = AUDIO_DIR / filename
    print(f"[PIPER] Gerando Ã¡udio: {text}", flush=True)
    with wave.open(str(output), "wb") as wav_file:
        voice.synthesize_wav(text, wav_file)
    return filename


def translate_with_deepl(text, target_language):
    text = clean_text(text)
    if not text:
        return ""
    if not DEEPL_API_KEY:
        raise RuntimeError("DEEPL_API_KEY nÃ£o configurada no Render.")
    response = requests.post(
        DEEPL_API_URL,
        headers={"Authorization": f"DeepL-Auth-Key {DEEPL_API_KEY}", "Content-Type": "application/json"},
        json={"text": [text], "target_lang": deepl_language(target_language)},
        timeout=20,
    )
    if response.status_code != 200:
        raise RuntimeError(f"DeepL HTTP {response.status_code}: {response.text[:500]}")
    translations = response.json().get("translations", [])
    if not translations:
        raise RuntimeError("DeepL nÃ£o retornou traduÃ§Ã£o.")
    return clean_text(translations[0].get("text", ""))


def deepgram_url():
    params = [
        "encoding=linear16", "sample_rate=16000", "channels=1", "model=nova-3",
        f"language={DEEPGRAM_SOURCE_LANGUAGE}", "interim_results=true", "punctuate=true",
        "smart_format=true", "endpointing=300", "utterance_end_ms=1000",
    ]
    return "wss://api.deepgram.com/v1/listen?" + "&".join(params)


def process_transcript(job, transcript):
    transcript = clean_text(transcript)
    if not transcript:
        return
    with job["lock"]:
        if transcript == job["last_source"]:
            return
        job["last_source"] = transcript
        job["source_text"] = transcript
        job["status"] = "translating"
        job["error"] = None
    print("[SI] Texto:", transcript, flush=True)
    try:
        translated = translate_with_deepl(transcript, job["target_language"])
        with job["lock"]:
            job["translated_text"] = translated
            job["status"] = "generating_voice"
        print("[SI] TraduÃ§Ã£o:", translated, flush=True)
        filename = generate_piper_audio(translated, job["target_language"])
        if not filename:
            return
        audio_id = uuid.uuid4().hex
        item = {"audioId": audio_id, "audioUrl": "/api/audio/file/" + filename}
        with job["lock"]:
            job["audio_queue"].append(item)
            job["audio_id"] = audio_id
            job["audio_url"] = item["audioUrl"]
            job["status"] = "ready"
        print("[SI] Ãudio Piper pronto:", audio_id, flush=True)
    except Exception as error:
        print("[SI] Erro:", repr(error), flush=True)
        with job["lock"]:
            job["error"] = str(error)
            job["status"] = "error"


def translation_worker(job):
    while not job["stop_event"].is_set():
        try:
            transcript = job["translation_queue"].get(timeout=0.5)
        except queue.Empty:
            continue
        if transcript is None:
            break
        process_transcript(job, transcript)


def deepgram_worker(job):
    ws = None
    try:
        if not DEEPGRAM_API_KEY:
            raise RuntimeError("DEEPGRAM_API_KEY nÃ£o configurada.")
        print("[DEEPGRAM] Conectando...", flush=True)
        ws = websocket.create_connection(
            deepgram_url(),
            header=["Authorization: Token " + DEEPGRAM_API_KEY],
            timeout=10,
        )
        ws.settimeout(0.5)
        with job["lock"]:
            job["status"] = "listening"
            job["connected"] = True
        print("[DEEPGRAM] Conectado.", flush=True)
        while not job["stop_event"].is_set():
            try:
                audio_data = job["audio_queue_input"].get(timeout=0.2)
            except queue.Empty:
                continue
            if audio_data is None:
                break
            try:
                ws.send(audio_data, opcode=websocket.ABNF.OPCODE_BINARY)
            except Exception as error:
                print("[DEEPGRAM] Erro enviando Ã¡udio:", repr(error), flush=True)
                break
            while True:
                try:
                    message = ws.recv()
                except websocket.WebSocketTimeoutException:
                    break
                except Exception:
                    break
                if not message or isinstance(message, bytes):
                    continue
                try:
                    data = json.loads(message)
                except Exception:
                    continue
                channel = data.get("channel", {})
                alternatives = channel.get("alternatives", [])
                if not alternatives:
                    continue
                transcript = clean_text(alternatives[0].get("transcript", ""))
                if not transcript:
                    continue
                with job["lock"]:
                    job["interim_text"] = transcript
                if bool(data.get("is_final", False)):
                    try:
                        job["translation_queue"].put(transcript, timeout=2)
                    except queue.Full:
                        print("[SI] Fila de traduÃ§Ã£o cheia.", flush=True)
    except Exception as error:
        print("[DEEPGRAM] Erro:", repr(error), flush=True)
        with job["lock"]:
            job["error"] = str(error)
            job["status"] = "error"
    finally:
        with job["lock"]:
            job["connected"] = False
        try:
            if ws:
                ws.close()
        except Exception:
            pass


def create_audio_session(job_id, target_language):
    target_language = normalize_language(target_language)
    job = {
        "job_id": job_id, "target_language": target_language, "status": "connecting",
        "source_text": "", "translated_text": "", "interim_text": "", "audio_url": None,
        "audio_id": None, "audio_queue": [], "audio_queue_input": queue.Queue(maxsize=200),
        "translation_queue": queue.Queue(maxsize=100), "error": None, "connected": False,
        "created_at": time.time(), "last_activity": time.time(), "last_source": "",
        "stop_event": threading.Event(), "lock": threading.Lock(),
    }
    with audio_sessions_lock:
        audio_sessions[job_id] = job
    threading.Thread(target=translation_worker, args=(job,), daemon=True).start()
    threading.Thread(target=deepgram_worker, args=(job,), daemon=True).start()
    return job


@app.route("/", methods=["GET"])
def home():
    return jsonify({"ok": True, "service": "SI Tradutor Live", "version": "17.1-Piper", "providers": {"stt": "Deepgram", "translation": "DeepL Text", "tts": "Piper"}, "openai": False, "elevenlabs": False, "deepl_voice": False})


@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({"ok": True, "service": "SI Tradutor Live", "version": "17.1-Piper", "deepgram_configured": bool(DEEPGRAM_API_KEY), "deepl_configured": bool(DEEPL_API_KEY), "piper": True, "piper_voices_loaded": list(piper_voices.keys()), "openai": False, "elevenlabs": False, "deepl_voice": False})


@app.route("/api/audio/start", methods=["POST"])
def audio_start():
    try:
        data = request.get_json(silent=True) or {}
        target_language = normalize_language(data.get("targetLang", data.get("targetLanguage", DEFAULT_TARGET_LANGUAGE)))
        job_id = uuid.uuid4().hex
        job = create_audio_session(job_id, target_language)
        return jsonify({"ok": True, "jobId": job_id, "status": job["status"], "targetLanguage": target_language})
    except Exception as error:
        return jsonify({"ok": False, "error": str(error)}), 500


@app.route("/api/test-piper", methods=["GET", "POST"])
def test_piper():
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        text = data.get("text", "OlÃ¡. Este Ã© um teste de voz do SI.")
        language = data.get("language", "pt")
    else:
        text = request.args.get("text", "OlÃ¡. Este Ã© um teste de voz do SI.")
        language = request.args.get("language", "pt")
    try:
        filename = generate_piper_audio(text, language)
        return jsonify({"ok": True, "provider": "Piper", "language": normalize_language(language), "text": clean_text(text), "audioUrl": "/api/audio/file/" + filename})
    except Exception as error:
        return jsonify({"ok": False, "provider": "Piper", "error": str(error)}), 500


@app.route("/api/audio/chunk", methods=["POST"])
def audio_chunk():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"ok": False, "error": "JSON nÃ£o recebido."}), 400
    job_id = str(data.get("jobId", "")).strip()
    if not job_id:
        return jsonify({"ok": False, "error": "jobId nÃ£o informado."}), 400
    encoded = data.get("audio", "")
    if not encoded:
        return jsonify({"ok": False, "error": "Ãudio nÃ£o informado."}), 400
    target_language = normalize_language(data.get("targetLanguage", data.get("targetLang", DEFAULT_TARGET_LANGUAGE)))
    try:
        audio_data = base64.b64decode(encoded, validate=True)
    except Exception:
        return jsonify({"ok": False, "error": "Base64 invÃ¡lido."}), 400
    if not audio_data:
        return jsonify({"ok": False, "error": "Chunk vazio."}), 400
    with audio_sessions_lock:
        job = audio_sessions.get(job_id)
    if not job:
        job = create_audio_session(job_id, target_language)
    with job["lock"]:
        job["last_activity"] = time.time()
    try:
        job["audio_queue_input"].put(audio_data, timeout=2)
    except queue.Full:
        return jsonify({"ok": False, "error": "Fila de Ã¡udio cheia."}), 503
    return jsonify({"ok": True, "jobId": job_id, "status": job["status"], "receivedBytes": len(audio_data)})


@app.route("/api/audio/status/<job_id>", methods=["GET"])
def audio_status(job_id):
    with audio_sessions_lock:
        job = audio_sessions.get(job_id)
    if not job:
        return jsonify({"ok": False, "error": "Job nÃ£o encontrado."}), 404
    with job["lock"]:
        next_audio = job["audio_queue"][0] if job["audio_queue"] else None
        return jsonify({"ok": True, "jobId": job["job_id"], "status": job["status"], "sourceText": job["source_text"], "translatedText": job["translated_text"], "interimText": job["interim_text"], "audioUrl": next_audio["audioUrl"] if next_audio else None, "audioId": next_audio["audioId"] if next_audio else None, "audioQueueSize": len(job["audio_queue"]), "error": job["error"], "targetLanguage": job["target_language"], "deepgramConnected": job["connected"]})


@app.route("/api/audio/ack/<job_id>/<audio_id>", methods=["POST"])
def audio_ack(job_id, audio_id):
    with audio_sessions_lock:
        job = audio_sessions.get(job_id)
    if not job:
        return jsonify({"ok": False, "error": "Job nÃ£o encontrado."}), 404
    with job["lock"]:
        if job["audio_queue"] and job["audio_queue"][0]["audioId"] == audio_id:
            job["audio_queue"].pop(0)
    return jsonify({"ok": True, "audioId": audio_id})


@app.route("/api/audio/file/<filename>", methods=["GET"])
def audio_file(filename):
    return send_from_directory(AUDIO_DIR, os.path.basename(filename), mimetype="audio/wav", as_attachment=False)


@app.route("/api/audio/stop/<job_id>", methods=["POST"])
def stop_audio(job_id):
    with audio_sessions_lock:
        job = audio_sessions.get(job_id)
    if not job:
        return jsonify({"ok": False, "error": "Job nÃ£o encontrado."}), 404
    job["stop_event"].set()
    for q in (job["audio_queue_input"], job["translation_queue"]):
        try:
            q.put_nowait(None)
        except Exception:
            pass
    with job["lock"]:
        job["status"] = "stopped"
    return jsonify({"ok": True, "jobId": job_id, "status": "stopped"})


@app.route("/api/audio/diagnostic", methods=["POST"])
def diagnostic():
    data = request.get_json(silent=True) or {}
    print("[DIAGNOSTIC]", json.dumps(data, ensure_ascii=False), flush=True)
    return jsonify({"ok": True, "received": True})


def cleanup_worker():
    while True:
        time.sleep(300)
        now = time.time()
        with audio_sessions_lock:
            old_jobs = [job_id for job_id, job in audio_sessions.items() if now - job["created_at"] > 3600]
            for job_id in old_jobs:
                job = audio_sessions.pop(job_id, None)
                if job:
                    job["stop_event"].set()
        try:
            for path in AUDIO_DIR.iterdir():
                if path.is_file() and now - path.stat().st_mtime > 3600:
                    path.unlink(missing_ok=True)
        except Exception:
            pass


threading.Thread(target=cleanup_worker, daemon=True).start()


if __name__ == "__main__":
    print("====================================", flush=True)
    print(" SI â€” TRADUTOR LIVE", flush=True)
    print(" Deepgram + DeepL + Piper", flush=True)
    print(" OpenAI: DESATIVADO", flush=True)
    print(" ElevenLabs: DESATIVADO", flush=True)
    print(" DeepL Voice: DESATIVADO", flush=True)
    print(" Piper: ATIVO", flush=True)
    print("====================================", flush=True)
    app.run(host="0.0.0.0", port=PORT, debug=False, threaded=True)

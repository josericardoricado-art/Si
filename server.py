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


# ============================================================
# SI — TRADUTOR LIVE
#
# Android
#    ↓
# Áudio PCM 16 kHz
#    ↓
# Deepgram = voz → texto
#    ↓
# DeepL = texto → tradução
#    ↓
# Piper = tradução → voz WAV
#    ↓
# Render
#    ↓
# Android
#    ↓
# Alto-falante
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

PORT = int(
    os.environ.get("PORT", "10000")
)


# ============================================================
# CONFIGURAÇÕES
# ============================================================

DEEPGRAM_API_KEY = os.environ.get(
    "DEEPGRAM_API_KEY",
    ""
).strip()

DEEPL_API_KEY = os.environ.get(
    "DEEPL_API_KEY",
    ""
).strip()

DEEPL_API_URL = os.environ.get(
    "DEEPL_API_URL",
    "https://api-free.deepl.com/v2/translate"
).strip()

DEFAULT_TARGET_LANGUAGE = os.environ.get(
    "DEFAULT_TARGET_LANGUAGE",
    "pt"
).strip().lower()

DEEPGRAM_SOURCE_LANGUAGE = os.environ.get(
    "DEEPGRAM_SOURCE_LANGUAGE",
    "pt-BR"
).strip()


# ============================================================
# DIRETÓRIOS
# ============================================================

PIPER_DATA_DIR = Path(
    os.environ.get(
        "PIPER_DATA_DIR",
        "/app/voices"
    )
)

AUDIO_DIR = Path(
    os.environ.get(
        "AUDIO_DIR",
        "/tmp/si_audio"
    )
)

PIPER_DATA_DIR.mkdir(
    parents=True,
    exist_ok=True
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
}


piper_voices = {}
piper_locks = {}
piper_global_lock = threading.Lock()


# ============================================================
# SESSÕES
# ============================================================

audio_sessions = {}
audio_sessions_lock = threading.Lock()


# ============================================================
# IDIOMAS
# ============================================================

def normalize_language(language):

    if not language:
        language = DEFAULT_TARGET_LANGUAGE

    language = str(
        language
    ).strip().lower()

    aliases = {

        "pt-br": "pt",
        "pt_br": "pt",
        "portuguese": "pt",
        "portugues": "pt",
        "português": "pt",

        "en-us": "en",
        "en_us": "en",
        "english": "en",
        "ingles": "en",
        "inglês": "en",

        "es-es": "es",
        "es_es": "es",
        "spanish": "es",
        "espanhol": "es",
        "espanol": "es",

        "fr-fr": "fr",
        "fr_fr": "fr",
        "french": "fr",
        "frances": "fr",
        "francês": "fr",

        "de-de": "de",
        "de_de": "de",
        "german": "de",
        "alemao": "de",
        "alemão": "de",

        "it-it": "it",
        "it_it": "it",
        "italian": "it",
        "italiano": "it",
    }

    language = aliases.get(
        language,
        language
    )

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
    }

    return mapping.get(
        normalize_language(language),
        "PT-BR"
    )


def clean_text(text):

    if not text:
        return ""

    text = re.sub(
        r"\s+",
        " ",
        str(text)
    ).strip()

    return text[:1500]


# ============================================================
# PIPER — LOCALIZAR MODELO
# ============================================================

def find_piper_model(model_name):

    possible_paths = [

        PIPER_DATA_DIR /
        f"{model_name}.onnx",

        Path("/app/voices") /
        f"{model_name}.onnx",

        Path("/tmp/piper") /
        f"{model_name}.onnx",
    ]

    for path in possible_paths:

        if path.exists():

            print(
                f"[PIPER] Modelo encontrado: {path}",
                flush=True
            )

            return path

    return None


# ============================================================
# PIPER — BAIXAR VOZ
# ============================================================

def download_piper_voice(model_name):

    model = find_piper_model(
        model_name
    )

    if model:
        return model

    print(
        f"[PIPER] Modelo não encontrado.",
        flush=True
    )

    print(
        f"[PIPER] Baixando voz: {model_name}",
        flush=True
    )

    command = [

        sys.executable,

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

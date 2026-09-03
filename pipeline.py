#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
SI - TRADUTOR UNIVERSAL
Pipeline de tradução e dublagem de vídeo

Compatível com Render Free:
- Python
- FFmpeg
- Whisper tiny
- OpenAI para tradução, quando configurado
- Argos Translate como fallback de tradução
- ElevenLabs para geração de voz
- FFmpeg para substituir o áudio do vídeo
- Logs de progresso para o server.js
"""

import os
import sys
import json
import uuid
import shutil
import signal
import argparse
import tempfile
import subprocess
from pathlib import Path

# ============================================================
# CONFIGURAÇÃO
# ============================================================

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "tiny")

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "").strip()

ELEVENLABS_VOICE_ID = os.getenv(
    "ELEVENLABS_VOICE_ID",
    ""
).strip()

# Modelo ElevenLabs
ELEVENLABS_MODEL = os.getenv(
    "ELEVENLABS_MODEL",
    "eleven_multilingual_v2"
)

# Limite de tamanho de texto enviado para TTS por bloco
TTS_MAX_CHARS = int(
    os.getenv("TTS_MAX_CHARS", "2500")
)

# Idiomas permitidos
SUPPORTED_LANGUAGES = {
    "pt": "Português",
    "en": "Inglês",
    "es": "Espanhol"
}

# ============================================================
# IMPORTS OPCIONAIS
# ============================================================

whisper = None
openai_client = None
argos_translate = None
requests = None

# ============================================================
# CONTROLE DE ENCERRAMENTO
# ============================================================

STOP_REQUESTED = False


def handle_signal(signum, frame):
    global STOP_REQUESTED

    STOP_REQUESTED = True

    print(
        f"[PIPELINE] Sinal recebido: {signum}",
        flush=True
    )


signal.signal(signal.SIGTERM, handle_signal)
signal.signal(signal.SIGINT, handle_signal)


# ============================================================
# LOG
# ============================================================

def log(message):
    print(
        f"[PIPELINE] {message}",
        flush=True
    )


def stage(message, progress=None):
    print(
        f"STAGE:{message}",
        flush=True
    )

    if progress is not None:
        print(
            f"PROGRESS:{progress}",
            flush=True
        )


# ============================================================
# EXECUTAR COMANDO
# ============================================================

def run_command(
    command,
    description="comando",
    timeout=None
):
    log(f"Executando: {description}")

    log(
        "Comando: " +
        " ".join(str(x) for x in command)
    )

    try:
        process = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout
        )

    except subprocess.TimeoutExpired:
        raise RuntimeError(
            f"Tempo limite excedido: {description}"
        )

    if process.stdout:
        for line in process.stdout.splitlines():
            print(
                f"[PIPELINE] {line}",
                flush=True
            )

    if process.stderr:
        for line in process.stderr.splitlines():
            print(
                f"[PIPELINE ERROR] {line}",
                flush=True
            )

    if process.returncode != 0:
        raise RuntimeError(
            f"{description} falhou "
            f"(código {process.returncode})"
        )

    return process.stdout


# ============================================================
# VERIFICAR FFmpeg
# ============================================================

def check_ffmpeg():

    stage(
        "Verificando FFmpeg"
    )

    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")

    if not ffmpeg:
        raise RuntimeError(
            "FFmpeg não encontrado no sistema."
        )

    if not ffprobe:
        raise RuntimeError(
            "FFprobe não encontrado no sistema."
        )

    log(f"ffmpeg: {ffmpeg}")
    log(f"ffprobe: {ffprobe}")

    return ffmpeg, ffprobe


# ============================================================
# VERIFICAR ARQUIVO
# ============================================================

def check_input_file(input_file):

    if not os.path.exists(input_file):
        raise FileNotFoundError(
            f"Vídeo não encontrado: {input_file}"
        )

    size = os.path.getsize(input_file)

    if size <= 0:
        raise RuntimeError(
            "O vídeo recebido está vazio."
        )

    log(
        f"Vídeo de entrada: {size} bytes"
    )


# ============================================================
# ANALISAR ÁUDIO
# ============================================================

def check_audio_stream(
    ffprobe,
    input_file
):

    log("Executando: análise do áudio")

    command = [
        ffprobe,
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=codec_name",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        input_file
    ]

    output = run_command(
        command,
        "análise do áudio"
    )

    codec = output.strip()

    if not codec:
        raise RuntimeError(
            "O vídeo não possui faixa de áudio."
        )

    log(
        f"Codec de áudio: {codec}"
    )

    return codec


# ============================================================
# EXTRAIR ÁUDIO
# ============================================================

def extract_audio(
    ffmpeg,
    input_file,
    output_wav
):

    stage(
        "Extraindo áudio do vídeo",
        5
    )

    command = [
        ffmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        input_file,
        "-map",
        "0:a:0",
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "pcm_s16le",
        output_wav
    ]

    run_command(
        command,
        "extração do áudio"
    )

    if not os.path.exists(output_wav):
        raise RuntimeError(
            "O áudio WAV não foi criado."
        )

    size = os.path.getsize(output_wav)

    if size <= 0:
        raise RuntimeError(
            "O áudio extraído está vazio."
        )

    log(
        f"Áudio original: {size} bytes"
    )


# ============================================================
# CARREGAR WHISPER
# ============================================================

def load_whisper():

    global whisper

    stage(
        "Transcrevendo áudio",
        20
    )

    log(
        f"Modelo Whisper: {WHISPER_MODEL}"
    )

    log(
        "Modo CPU ativado."
    )

    try:
        import whisper as whisper_module

        whisper = whisper_module

    except Exception as e:
        raise RuntimeError(
            "Não foi possível importar Whisper: "
            + str(e)
        )

    try:

        # Diretório temporário/cached do Render
        cache_dir = os.getenv(
            "XDG_CACHE_HOME",
            "/tmp"
        )

        os.makedirs(
            cache_dir,
            exist_ok=True
        )

        model = whisper.load_model(
            WHISPER_MODEL,
            device="cpu",
            download_root=cache_dir
        )

        log(
            "Whisper carregado com sucesso."
        )

        return model

    except Exception as e:

        raise RuntimeError(
            "Erro ao carregar Whisper: "
            + str(e)
        )


# ============================================================
# TRANSCRIÇÃO
# ============================================================

def transcribe_audio(
    model,
    audio_file
):

    stage(
        "Transcrevendo áudio",
        25
    )

    log(
        "Iniciando transcrição com Whisper..."
    )

    try:

        result = model.transcribe(
            audio_file,
            fp16=False,
            verbose=False,
            temperature=0,
            condition_on_previous_text=False
        )

    except Exception as e:

        raise RuntimeError(
            "Erro na transcrição Whisper: "
            + str(e)
        )

    text = (
        result.get("text", "")
        .strip()
    )

    language = (
        result.get("language", "")
        .strip()
        .lower()
    )

    log(
        f"Idioma detectado: {language}"
    )

    log(
        f"Caracteres transcritos: {len(text)}"
    )

    if not text:

        raise RuntimeError(
            "Whisper não encontrou fala no vídeo."
        )

    return result, text, language


# ============================================================
# CARREGAR OPENAI
# ============================================================

def load_openai():

    global openai_client

    if not OPENAI_API_KEY:
        log(
            "OpenAI não configurado."
        )
        return None

    try:

        from openai import OpenAI

        openai_client = OpenAI(
            api_key=OPENAI_API_KEY
        )

        log(
            "OpenAI configurado."
        )

        return openai_client

    except Exception as e:

        log(
            "Não foi possível carregar OpenAI: "
            + str(e)
        )

        return None


# ============================================================
# TRADUÇÃO COM OPENAI
# ============================================================

def translate_with_openai(
    client,
    text,
    source_lang,
    target_lang
):

    if not client:
        return None

    target_name = SUPPORTED_LANGUAGES.get(
        target_lang,
        target_lang
    )

    source_name = SUPPORTED_LANGUAGES.get(
        source_lang,
        source_lang
    )

    prompt = f"""
Traduza o texto abaixo de {source_name}
para {target_name}.

Regras:
- mantenha o significado original;
- não explique nada;
- não coloque aspas;
- não acrescente comentários;
- preserve nomes próprios;
- produza somente a tradução.

Texto:

{text}
"""

    try:

        response = client.chat.completions.create(
            model=os.getenv(
                "OPENAI_TRANSLATION_MODEL",
                "gpt-4o-mini"
            ),
            messages=[
                {
                    "role": "system",
                    "content":
                    "Você é um tradutor profissional."
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            temperature=0
        )

        translated = (
            response.choices[0]
            .message.content
            .strip()
        )

        return translated

    except Exception as e:

        log(
            "Erro na tradução OpenAI: "
            + str(e)
        )

        return None


# ============================================================
# TRADUÇÃO COM ARGOS
# ============================================================

def translate_with_argos(
    text,
    source_lang,
    target_lang
):

    try:

        import argostranslate.translate

        translated = (
            argostranslate.translate.translate(
                text,
                source_lang,
                target_lang
            )
        )

        if translated:
            return translated.strip()

    except Exception as e:

        log(
            "Argos não conseguiu traduzir: "
            + str(e)
        )

    return None


# ============================================================
# TRADUÇÃO
# ============================================================

def translate_text(
    text,
    source_lang,
    target_lang,
    client
):

    stage(
        "Traduzindo áudio",
        45
    )

    if not target_lang:
        raise RuntimeError(
            "Idioma de destino não informado."
        )

    if target_lang not in SUPPORTED_LANGUAGES:
        raise RuntimeError(
            f"Idioma não suportado: {target_lang}"
        )

    # Se já estiver no idioma desejado
    if source_lang == target_lang:

        log(
            "Idioma original já é o idioma desejado."
        )

        return text

    # Primeiro tenta OpenAI
    if client:

        log(
            "Tentando tradução com OpenAI..."
        )

        translated = translate_with_openai(
            client,
            text,
            source_lang,
            target_lang
        )

        if translated:

            log(
                "Tradução realizada com OpenAI."
            )

            return translated

    # Depois tenta Argos
    log(
        "Tentando tradução com Argos Translate..."
    )

    translated = translate_with_argos(
        text,
        source_lang,
        target_lang
    )

    if translated:

        log(
            "Tradução realizada com Argos."
        )

        return translated

    raise RuntimeError(
        "Não foi possível traduzir o texto. "
        "Configure OPENAI_API_KEY ou instale "
        "o pacote de idioma correspondente "
        "no Argos Translate."
    )


# ============================================================
# DIVIDIR TEXTO PARA ELEVENLABS
# ============================================================

def split_text(text, max_chars):

    words = text.split()

    chunks = []
    current = []

    current_length = 0

    for word in words:

        new_length = (
            current_length +
            len(word) +
            1
        )

        if (
            current
            and new_length > max_chars
        ):

            chunks.append(
                " ".join(current)
            )

            current = [word]
            current_length = len(word)

        else:

            current.append(word)
            current_length = new_length

    if current:
        chunks.append(
            " ".join(current)
        )

    return chunks


# ============================================================
# ELEVENLABS
# ============================================================

def generate_elevenlabs_audio(
    text,
    output_audio
):

    stage(
        "Gerando voz com ElevenLabs",
        60
    )

    if not ELEVENLABS_API_KEY:

        raise RuntimeError(
            "ELEVENLABS_API_KEY não configurada."
        )

    if not ELEVENLABS_VOICE_ID:

        raise RuntimeError(
            "ELEVENLABS_VOICE_ID não configurada."
        )

    try:

        import requests as requests_module

        requests = requests_module

    except Exception as e:

        raise RuntimeError(
            "Biblioteca requests não disponível: "
            + str(e)
        )

    chunks = split_text(
        text,
        TTS_MAX_CHARS
    )

    if not chunks:

        raise RuntimeError(
            "Texto vazio para ElevenLabs."
        )

    temporary_files = []

    try:

        for index, chunk in enumerate(chunks):

            if STOP_REQUESTED:

                raise RuntimeError(
                    "Processamento interrompido."
                )

            log(
                f"ElevenLabs: bloco "
                f"{index + 1}/{len(chunks)}"
            )

            url = (
                "https://api.elevenlabs.io/v1/text-to-speech/"
                + ELEVENLABS_VOICE_ID
            )

            headers = {
                "xi-api-key":
                ELEVENLABS_API_KEY,
                "Content-Type":
                "application/json",
                "Accept":
                "audio/mpeg"
            }

            payload = {
                "text": chunk,
                "model_id":
                ELEVENLABS_MODEL,
                "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75
                }
            }

            response = requests.post(
                url,
                headers=headers,
                json=payload,
                timeout=180
            )

            if response.status_code != 200:

                error_text = response.text[:1000]

                raise RuntimeError(
                    "ElevenLabs retornou "
                    f"HTTP {response.status_code}: "
                    f"{error_text}"
                )

            temp_mp3 = os.path.join(
                os.path.dirname(output_audio),
                f"voice_{uuid.uuid4().hex}.mp3"
            )

            with open(
                temp_mp3,
                "wb"
            ) as file:

                file.write(
                    response.content
                )

            temporary_files.append(
                temp_mp3
            )

        # Se só houver um bloco
        if len(temporary_files) == 1:

            shutil.copyfile(
                temporary_files[0],
                output_audio
            )

            return

        # Criar lista para concatenação
        concat_file = os.path.join(
            os.path.dirname(output_audio),
            f"concat_{uuid.uuid4().hex}.txt"
        )

        with open(
            concat_file,
            "w",
            encoding="utf-8"
        ) as file:

            for temp_file in temporary_files:

                safe_path = temp_file.replace(
                    "'",
                    "'\\''"
                )

                file.write(
                    f"file '{safe_path}'\n"
                )

        ffmpeg = shutil.which("ffmpeg")

        if not ffmpeg:
            raise RuntimeError(
                "FFmpeg não encontrado."
            )

        run_command(
            [
                ffmpeg,
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                concat_file,
                "-c",
                "copy",
                output_audio
            ],
            "união dos áudios ElevenLabs"
        )

        try:
            os.remove(concat_file)
        except Exception:
            pass

    finally:

        for temp_file in temporary_files:

            try:
                os.remove(temp_file)
            except Exception:
                pass


# ============================================================
# COMBINAR ÁUDIO + VÍDEO
# ============================================================

def create_final_video(
    ffmpeg,
    input_video,
    dubbed_audio,
    output_video
):

    stage(
        "Criando vídeo final",
        80
    )

    os.makedirs(
        os.path.dirname(output_video),
        exist_ok=True
    )

    command = [
        ffmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",

        "-i",
        input_video,

        "-i",
        dubbed_audio,

        "-map",
        "0:v:0",

        "-map",
        "1:a:0",

        "-c:v",
        "copy",

        "-c:a",
        "aac",

        "-b:a",
        "128k",

        "-shortest",

        "-movflags",
        "+faststart",

        output_video
    ]

    run_command(
        command,
        "criação do vídeo final"
    )

    if not os.path.exists(output_video):

        raise RuntimeError(
            "O vídeo final não foi criado."
        )

    size = os.path.getsize(
        output_video
    )

    if size <= 0:

        raise RuntimeError(
            "O vídeo final está vazio."
        )

    log(
        f"Vídeo final criado: {size} bytes"
    )


# ============================================================
# SALVAR TEXTO
# ============================================================

def save_transcription(
    directory,
    original_text,
    translated_text,
    source_lang,
    target_lang
):

    data = {
        "source_language": source_lang,
        "target_language": target_lang,
        "original_text": original_text,
        "translated_text": translated_text
    }

    path = os.path.join(
        directory,
        "transcription.json"
    )

    try:

        with open(
            path,
            "w",
            encoding="utf-8"
        ) as file:

            json.dump(
                data,
                file,
                ensure_ascii=False,
                indent=2
            )

    except Exception as e:

        log(
            "Aviso: não foi possível salvar "
            f"transcription.json: {e}"
        )


# ============================================================
# PIPELINE PRINCIPAL
# ============================================================

def process_video(
    input_file,
    output_file,
    target_lang
):

    log("========================================")
    log("SI - TRADUTOR UNIVERSAL")
    log("PIPELINE OTIMIZADO PARA RENDER")
    log("========================================")

    log(
        f"Entrada: {input_file}"
    )

    log(
        f"Saída: {output_file}"
    )

    log(
        f"Idioma: {target_lang}"
    )

    log(
        f"Whisper padrão: {WHISPER_MODEL}"
    )

    log(
        "Dublagem: ElevenLabs"
    )

    if OPENAI_API_KEY:
        log(
            "Tradução: OpenAI"
        )
    else:
        log(
            "Tradução: Argos/OpenAI opcional"
        )

    log("========================================")

    # --------------------------------------------------------
    # VERIFICAÇÕES
    # --------------------------------------------------------

    check_input_file(
        input_file
    )

    ffmpeg, ffprobe = check_ffmpeg()

    check_audio_stream(
        ffprobe,
        input_file
    )

    # --------------------------------------------------------
    # TEMP
    # --------------------------------------------------------

    temp_dir = tempfile.mkdtemp(
        prefix="si_pipeline_"
    )

    log(
        f"Diretório temporário: {temp_dir}"
    )

    try:

        # ----------------------------------------------------
        # ARQUIVOS
        # ----------------------------------------------------

        original_audio = os.path.join(
            temp_dir,
            "original.wav"
        )

        dubbed_audio = os.path.join(
            temp_dir,
            "dubbed.mp3"
        )

        # ----------------------------------------------------
        # EXTRAIR ÁUDIO
        # ----------------------------------------------------

        extract_audio(
            ffmpeg,
            input_file,
            original_audio
        )

        # ----------------------------------------------------
        # WHISPER
        # ----------------------------------------------------

        model = load_whisper()

        result, original_text, source_lang = (
            transcribe_audio(
                model,
                original_audio
            )
        )

        # ----------------------------------------------------
        # NORMALIZAR IDIOMA
        # ----------------------------------------------------

        if source_lang not in SUPPORTED_LANGUAGES:

            log(
                f"Idioma detectado '{source_lang}' "
                "não está na lista principal."
            )

        # ----------------------------------------------------
        # OPENAI
        # ----------------------------------------------------

        client = load_openai()

        # ----------------------------------------------------
        # TRADUÇÃO
        # ----------------------------------------------------

        translated_text = translate_text(
            original_text,
            source_lang,
            target_lang,
            client
        )

        log(
            "Texto traduzido com sucesso."
        )

        save_transcription(
            temp_dir,
            original_text,
            translated_text,
            source_lang,
            target_lang
        )

        # ----------------------------------------------------
        # ELEVENLABS
        # ----------------------------------------------------

        generate_elevenlabs_audio(
            translated_text,
            dubbed_audio
        )

        # ----------------------------------------------------
        # VÍDEO FINAL
        # ----------------------------------------------------

        create_final_video(
            ffmpeg,
            input_file,
            dubbed_audio,
            output_file
        )

        # ----------------------------------------------------
        # SUCESSO
        # ----------------------------------------------------

        stage(
            "Processamento concluído",
            100
        )

        log(
            "========================================"
        )

        log(
            "PROCESSAMENTO CONCLUÍDO COM SUCESSO"
        )

        log(
            f"Arquivo final: {output_file}"
        )

        log(
            "========================================"
        )

        return True

    except Exception as e:

        log(
            "========================================"
        )

        log(
            "ERRO NO PIPELINE"
        )

        log(
            str(e)
        )

        log(
            "========================================"
        )

        raise

    finally:

        # ----------------------------------------------------
        # LIMPEZA
        # ----------------------------------------------------

        try:

            shutil.rmtree(
                temp_dir,
                ignore_errors=True
            )

            log(
                "Arquivos temporários removidos."
            )

        except Exception as e:

            log(
                "Aviso ao limpar temporários: "
                + str(e)
            )


# ============================================================
# ARGUMENTOS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description=
        "SI - Tradutor Universal"
    )

    parser.add_argument(
        "--input",
        required=True,
        help="Vídeo de entrada"
    )

    parser.add_argument(
        "--output",
        required=True,
        help="Vídeo de saída"
    )

    parser.add_argument(
        "--target-lang",
        required=True,
        choices=[
            "pt",
            "en",
            "es"
        ],
        help="Idioma da dublagem"
    )

    return parser.parse_args()


# ============================================================
# MAIN
# ============================================================

def main():

    args = parse_arguments()

    try:

        process_video(
            input_file=args.input,
            output_file=args.output,
            target_lang=args.target_lang
        )

        print(
            "PIPELINE_SUCCESS",
            flush=True
        )

        sys.exit(0)

    except KeyboardInterrupt:

        log(
            "Processamento interrompido pelo usuário."
        )

        sys.exit(130)

    except Exception as e:

        log(
            f"PIPELINE_FAILED: {e}"
        )

        print(
            "PIPELINE_FAILED",
            flush=True
        )

        sys.exit(1)


if __name__ == "__main__":
    main()

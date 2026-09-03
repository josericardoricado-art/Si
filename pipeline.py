#!/usr/bin/env python3

# ============================================================
# SI - TRADUTOR UNIVERSAL
# PIPELINE RENDER FREE
#
# Whisper tiny
# Argos Translate
# ElevenLabs
# FFmpeg
#
# SEM OPENAI
# ============================================================

import argparse
import os
import sys
import subprocess
import tempfile
import shutil
import traceback
from pathlib import Path


# ============================================================
# LOG
# ============================================================

def log_stage(message):
    print(f"STAGE:{message}", flush=True)


def log_progress(value):
    try:
        value = int(value)
    except Exception:
        value = 0

    value = max(0, min(100, value))

    print(f"PROGRESS:{value}", flush=True)


def log_info(message):
    print(f"[PIPELINE] {message}", flush=True)


def log_error(message):
    print(
        f"[PIPELINE ERROR] {message}",
        file=sys.stderr,
        flush=True
    )


# ============================================================
# IDIOMAS
# ============================================================

LANGUAGES = {
    "pt": "português",
    "en": "inglês",
    "es": "espanhol"
}


# ============================================================
# WHISPER
# ============================================================

_whisper_model = None


def get_whisper_model():

    global _whisper_model

    if _whisper_model is not None:
        return _whisper_model

    log_stage(
        "Carregando Whisper tiny"
    )

    try:
        import whisper
    except ImportError as exc:

        raise RuntimeError(
            "openai-whisper não está instalado."
        ) from exc

    model_name = os.getenv(
        "WHISPER_MODEL",
        "tiny"
    ).strip()

    if not model_name:
        model_name = "tiny"

    # No Render Free usamos CPU
    os.environ["CUDA_VISIBLE_DEVICES"] = ""

    log_info(
        f"Modelo Whisper: {model_name}"
    )

    log_info(
        "Modo CPU ativado."
    )

    try:

        _whisper_model = whisper.load_model(
            model_name,
            device="cpu"
        )

    except Exception as exc:

        raise RuntimeError(
            "Não foi possível carregar o Whisper "
            f"'{model_name}': {exc}"
        ) from exc

    log_info(
        "Whisper carregado com sucesso."
    )

    return _whisper_model


# ============================================================
# FFmpeg
# ============================================================

def check_ffmpeg():

    log_stage(
        "Verificando FFmpeg"
    )

    ffmpeg_path = shutil.which(
        "ffmpeg"
    )

    ffprobe_path = shutil.which(
        "ffprobe"
    )

    if not ffmpeg_path:

        raise RuntimeError(
            "FFmpeg não foi encontrado no Render."
        )

    if not ffprobe_path:

        raise RuntimeError(
            "FFprobe não foi encontrado no Render."
        )

    log_info(
        f"ffmpeg: {ffmpeg_path}"
    )

    log_info(
        f"ffprobe: {ffprobe_path}"
    )


# ============================================================
# VERIFICAR ARQUIVO
# ============================================================

def verify_file(
    file_path,
    description
):

    path = Path(
        file_path
    )

    if not path.exists():

        raise RuntimeError(
            f"{description} não foi criado: {file_path}"
        )

    size = path.stat().st_size

    if size <= 0:

        raise RuntimeError(
            f"{description} está vazio."
        )

    log_info(
        f"{description}: {size} bytes"
    )


# ============================================================
# EXTRAIR ÁUDIO
# ============================================================

def extract_audio(
    video_path,
    audio_path
):

    log_stage(
        "Extraindo áudio do vídeo"
    )

    log_progress(5)

    log_info(
        "Executando: análise do áudio"
    )

    probe_command = [
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=codec_name",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        video_path
    ]

    log_info(
        "Comando: " +
        " ".join(probe_command)
    )

    probe = subprocess.run(
        probe_command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    if probe.returncode != 0:

        raise RuntimeError(
            "FFprobe não conseguiu analisar o vídeo:\n"
            + probe.stderr[-5000:]
        )

    codec = probe.stdout.strip()

    if not codec:

        raise RuntimeError(
            "Este vídeo não possui faixa de áudio."
        )

    log_info(
        f"Codec de áudio: {codec}"
    )

    log_info(
        "Executando: extração do áudio"
    )

    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        video_path,
        "-map",
        "0:a:0",
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "pcm_s16le",
        audio_path
    ]

    log_info(
        "Comando: " +
        " ".join(command)
    )

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    if result.returncode != 0:

        raise RuntimeError(
            "FFmpeg não conseguiu extrair o áudio:\n"
            + result.stderr[-5000:]
        )

    verify_file(
        audio_path,
        "Áudio original"
    )


# ============================================================
# TRANSCRIÇÃO
# ============================================================

def transcribe_audio(
    audio_path
):

    log_stage(
        "Transcrevendo áudio"
    )

    log_progress(20)

    model = get_whisper_model()

    log_info(
        "Iniciando transcrição..."
    )

    try:

        result = model.transcribe(
            audio_path,
            fp16=False,
            verbose=False
        )

    except Exception as exc:

        raise RuntimeError(
            "Erro durante a transcrição Whisper: "
            + str(exc)
        ) from exc

    text = (
        result.get("text")
        or ""
    ).strip()

    detected_language = (
        result.get("language")
        or "unknown"
    ).lower().strip()

    if not text:

        raise RuntimeError(
            "O Whisper não encontrou fala no vídeo."
        )

    log_info(
        f"Idioma detectado: {detected_language}"
    )

    log_info(
        f"Texto detectado: {len(text)} caracteres"
    )

    log_info(
        f"Transcrição: {text[:500]}"
    )

    log_progress(40)

    return text, detected_language


# ============================================================
# ARGOS
# ============================================================

def get_argos_languages():

    try:

        import argostranslate.translate

    except ImportError as exc:

        raise RuntimeError(
            "Argos Translate não está instalado. "
            "Adicione argostranslate ao requirements.txt."
        ) from exc

    try:

        installed_languages = (
            argostranslate.translate.get_installed_languages()
        )

    except Exception as exc:

        raise RuntimeError(
            "Não foi possível consultar os idiomas "
            f"instalados no Argos: {exc}"
        ) from exc

    return installed_languages


# ============================================================
# LISTAR IDIOMAS ARGOS
# ============================================================

def log_argos_languages():

    try:

        languages = get_argos_languages()

        names = []

        for language in languages:

            code = getattr(
                language,
                "code",
                "?"
            )

            name = getattr(
                language,
                "name",
                "?"
            )

            names.append(
                f"{code} ({name})"
            )

        log_info(
            "Idiomas Argos instalados: " +
            ", ".join(names)
        )

    except Exception as exc:

        log_error(
            f"Não foi possível listar Argos: {exc}"
        )


# ============================================================
# ENCONTRAR IDIOMA ARGOS
# ============================================================

def find_argos_language(
    languages,
    code
):

    for language in languages:

        language_code = getattr(
            language,
            "code",
            ""
        )

        if (
            language_code.lower()
            == code.lower()
        ):

            return language

    return None


# ============================================================
# TRADUÇÃO ARGOS
# ============================================================

def translate_with_argos(
    text,
    source_lang,
    target_lang
):

    log_stage(
        "Traduzindo com Argos Translate"
    )

    log_progress(50)

    source_lang = (
        source_lang
        or ""
    ).lower().strip()

    target_lang = (
        target_lang
        or ""
    ).lower().strip()

    # --------------------------------------------------------
    # Se já estiver no idioma escolhido
    # --------------------------------------------------------

    if source_lang == target_lang:

        log_info(
            "O áudio já está no idioma de destino."
        )

        log_progress(60)

        return text

    if source_lang not in LANGUAGES:

        log_info(
            "Idioma detectado pelo Whisper "
            f"'{source_lang}' não está configurado."
        )

        raise RuntimeError(
            "O Whisper detectou o idioma "
            f"'{source_lang}', mas o Argos deste projeto "
            "está configurado para pt, en e es."
        )

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma de destino não suportado: {target_lang}"
        )

    log_info(
        f"Tradução: {source_lang} -> {target_lang}"
    )

    try:

        import argostranslate.translate

    except ImportError as exc:

        raise RuntimeError(
            "Argos Translate não está instalado."
        ) from exc

    installed_languages = (
        get_argos_languages()
    )

    source = find_argos_language(
        installed_languages,
        source_lang
    )

    target = find_argos_language(
        installed_languages,
        target_lang
    )

    if source is None:

        raise RuntimeError(
            f"Idioma de origem '{source_lang}' "
            "não está instalado no Argos."
        )

    if target is None:

        raise RuntimeError(
            f"Idioma de destino '{target_lang}' "
            "não está instalado no Argos."
        )

    log_info(
        "Procurando pacote de tradução Argos..."
    )

    translation = None

    try:

        translation = (
            argostranslate.translate.translate(
                text,
                source_lang,
                target_lang
            )
        )

    except Exception as first_error:

        log_error(
            "Tentativa direta do Argos falhou: "
            + str(first_error)
        )

        # ----------------------------------------------------
        # Tenta encontrar tradução instalada manualmente
        # ----------------------------------------------------

        try:

            translation = (
                argostranslate.translate.translate(
                    text,
                    source_lang,
                    target_lang
                )
            )

        except Exception as second_error:

            raise RuntimeError(
                "O Argos não conseguiu traduzir "
                f"{source_lang} -> {target_lang}.\n"
                "Verifique se o modelo de idioma correspondente "
                "está instalado no Render.\n"
                f"Erro: {second_error}"
            ) from second_error

    translation = (
        translation
        or ""
    ).strip()

    if not translation:

        raise RuntimeError(
            "O Argos retornou uma tradução vazia."
        )

    log_info(
        f"Tradução criada: {len(translation)} caracteres"
    )

    log_info(
        f"Tradução: {translation[:500]}"
    )

    log_progress(60)

    return translation


# ============================================================
# ELEVENLABS
# ============================================================

def generate_elevenlabs_voice(
    text,
    target_lang,
    output_path
):

    log_stage(
        "Gerando voz com ElevenLabs"
    )

    log_progress(70)

    api_key = os.getenv(
        "ELEVENLABS_API_KEY"
    )

    if not api_key:

        raise RuntimeError(
            "ELEVENLABS_API_KEY não está configurada no Render."
        )

    try:

        from elevenlabs.client import ElevenLabs

    except ImportError as exc:

        raise RuntimeError(
            "A biblioteca elevenlabs não está instalada. "
            "Adicione elevenlabs ao requirements.txt."
        ) from exc

    client = ElevenLabs(
        api_key=api_key
    )

    # ========================================================
    # VOICE ID
    # ========================================================

    configured_voice_id = os.getenv(
        "ELEVENLABS_VOICE_ID",
        ""
    ).strip()

    selected_voice = None

    # --------------------------------------------------------
    # Se houver VOICE ID configurado, usa ele
    # --------------------------------------------------------

    if configured_voice_id:

        log_info(
            "Usando ELEVENLABS_VOICE_ID configurado."
        )

        voice_id = configured_voice_id

        voice_name = "voz configurada"

    else:

        # ----------------------------------------------------
        # Procurar voz automaticamente
        # ----------------------------------------------------

        log_info(
            "ELEVENLABS_VOICE_ID não configurado."
        )

        log_info(
            "Buscando uma voz disponível..."
        )

        try:

            voices = client.voices.get_all()

            voice_list = getattr(
                voices,
                "voices",
                []
            )

        except Exception as exc:

            raise RuntimeError(
                "Não foi possível consultar as vozes "
                f"da ElevenLabs: {exc}"
            ) from exc

        if not voice_list:

            raise RuntimeError(
                "Nenhuma voz disponível foi encontrada "
                "na conta ElevenLabs."
            )

        preferred_names = [
            "Rachel",
            "Bella",
            "Antoni",
            "Adam",
            "Domi",
            "Josh",
            "Elli"
        ]

        for preferred in preferred_names:

            for voice in voice_list:

                name = getattr(
                    voice,
                    "name",
                    ""
                )

                if (
                    name
                    and
                    name.lower()
                    == preferred.lower()
                ):

                    selected_voice = voice
                    break

            if selected_voice:
                break

        if selected_voice is None:

            selected_voice = voice_list[0]

        voice_id = getattr(
            selected_voice,
            "voice_id",
            None
        )

        voice_name = getattr(
            selected_voice,
            "name",
            "voz"
        )

        if not voice_id:

            raise RuntimeError(
                "A ElevenLabs retornou uma voz sem voice_id."
            )

    log_info(
        f"Voz selecionada: {voice_name}"
    )

    log_info(
        f"Voice ID: {voice_id}"
    )

    model_id = os.getenv(
        "ELEVENLABS_MODEL",
        "eleven_multilingual_v2"
    ).strip()

    if not model_id:

        model_id = "eleven_multilingual_v2"

    log_info(
        f"Modelo ElevenLabs: {model_id}"
    )

    # ========================================================
    # GERAR ÁUDIO
    # ========================================================

    try:

        audio_stream = (
            client
            .text_to_speech
            .convert(
                text=text,
                voice_id=voice_id,
                model_id=model_id,
                output_format="mp3_44100_128"
            )
        )

    except Exception as exc:

        raise RuntimeError(
            "Erro ao solicitar voz à ElevenLabs: "
            + str(exc)
        ) from exc

    try:

        with open(
            output_path,
            "wb"
        ) as output_file:

            for chunk in audio_stream:

                if chunk:

                    output_file.write(
                        chunk
                    )

    except Exception as exc:

        raise RuntimeError(
            "Erro salvando áudio ElevenLabs: "
            + str(exc)
        ) from exc

    verify_file(
        output_path,
        "Áudio ElevenLabs"
    )

    log_info(
        "Áudio da dublagem criado."
    )

    log_progress(80)


# ============================================================
# NORMALIZAR ÁUDIO
# ============================================================

def normalize_audio(
    input_audio,
    output_audio
):

    log_stage(
        "Preparando áudio da dublagem"
    )

    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        input_audio,
        "-vn",
        "-ac",
        "2",
        "-ar",
        "48000",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        output_audio
    ]

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    if result.returncode != 0:

        raise RuntimeError(
            "Não foi possível preparar o áudio:\n"
            + result.stderr[-5000:]
        )

    verify_file(
        output_audio,
        "Áudio normalizado"
    )


# ============================================================
# CRIAR VÍDEO FINAL
# ============================================================

def create_dubbed_video(
    original_video,
    dubbed_audio,
    output_video
):

    log_stage(
        "Criando vídeo final"
    )

    log_progress(88)

    output_path = Path(
        output_video
    )

    output_path.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    temp_output = (
        str(output_path)
        + ".tmp.mp4"
    )

    if os.path.exists(
        temp_output
    ):

        os.remove(
            temp_output
        )

    # ========================================================
    # IMPORTANTE
    #
    # Mantém o vídeo original sem recodificar.
    # Troca somente a faixa de áudio.
    # ========================================================

    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",

        "-i",
        original_video,

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
        "192k",

        "-shortest",

        "-movflags",
        "+faststart",

        temp_output
    ]

    log_info(
        "Criando MP4 final..."
    )

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    if result.returncode != 0:

        raise RuntimeError(
            "FFmpeg não conseguiu criar o MP4:\n"
            + result.stderr[-10000:]
        )

    verify_file(
        temp_output,
        "Vídeo temporário"
    )

    os.replace(
        temp_output,
        output_video
    )

    verify_file(
        output_video,
        "Vídeo final"
    )

    log_progress(98)

    log_info(
        "MP4 final criado com sucesso."
    )


# ============================================================
# MAIN
# ============================================================

def main():

    parser = argparse.ArgumentParser(
        description=
        "SI Tradutor Universal - Whisper + Argos + ElevenLabs"
    )

    parser.add_argument(
        "--input",
        required=True
    )

    parser.add_argument(
        "--output",
        required=True
    )

    parser.add_argument(
        "--target-lang",
        default="pt"
    )

    args = parser.parse_args()

    input_video = os.path.abspath(
        args.input
    )

    output_video = os.path.abspath(
        args.output
    )

    target_lang = (
        args.target_lang
        or "pt"
    ).lower().strip()

    # ========================================================
    # CABEÇALHO
    # ========================================================

    log_info(
        "========================================"
    )

    log_info(
        "SI - TRADUTOR UNIVERSAL"
    )

    log_info(
        "PIPELINE RENDER FREE"
    )

    log_info(
        "========================================"
    )

    log_info(
        f"Entrada: {input_video}"
    )

    log_info(
        f"Saída: {output_video}"
    )

    log_info(
        f"Idioma: {target_lang}"
    )

    log_info(
        "Whisper: tiny"
    )

    log_info(
        "Tradução: Argos Translate"
    )

    log_info(
        "Dublagem: ElevenLabs"
    )

    log_info(
        "OpenAI: DESATIVADA"
    )

    log_info(
        "========================================"
    )

    # ========================================================
    # VERIFICAÇÕES
    # ========================================================

    check_ffmpeg()

    if not os.path.exists(
        input_video
    ):

        raise RuntimeError(
            f"Vídeo não encontrado: {input_video}"
        )

    verify_file(
        input_video,
        "Vídeo de entrada"
    )

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma não suportado: {target_lang}. "
            "Use pt, en ou es."
        )

    # ========================================================
    # DIRETÓRIO TEMPORÁRIO
    # ========================================================

    temp_dir = tempfile.mkdtemp(
        prefix="si_pipeline_"
    )

    log_info(
        f"Diretório temporário: {temp_dir}"
    )

    original_audio = os.path.join(
        temp_dir,
        "original.wav"
    )

    eleven_audio = os.path.join(
        temp_dir,
        "eleven.mp3"
    )

    normalized_audio = os.path.join(
        temp_dir,
        "dub.m4a"
    )

    try:

        # ====================================================
        # 1. EXTRAIR ÁUDIO
        # ====================================================

        extract_audio(
            input_video,
            original_audio
        )

        # ====================================================
        # 2. TRANSCRIÇÃO
        # ====================================================

        original_text, detected_language = (
            transcribe_audio(
                original_audio
            )
        )

        # ====================================================
        # 3. TRADUÇÃO ARGOS
        # ====================================================

        translated_text = translate_with_argos(
            original_text,
            detected_language,
            target_lang
        )

        # ====================================================
        # 4. ELEVENLABS
        # ====================================================

        generate_elevenlabs_voice(
            translated_text,
            target_lang,
            eleven_audio
        )

        # ====================================================
        # 5. NORMALIZAR ÁUDIO
        # ====================================================

        normalize_audio(
            eleven_audio,
            normalized_audio
        )

        # ====================================================
        # 6. CRIAR VÍDEO
        # ====================================================

        create_dubbed_video(
            input_video,
            normalized_audio,
            output_video
        )

        # ====================================================
        # VERIFICAÇÃO FINAL
        # ====================================================

        verify_file(
            output_video,
            "Arquivo final"
        )

        log_stage(
            "Concluído"
        )

        log_progress(100)

        log_info(
            "========================================"
        )

        log_info(
            "PROCESSAMENTO CONCLUÍDO"
        )

        log_info(
            f"Arquivo: {output_video}"
        )

        log_info(
            "Tamanho: "
            f"{os.path.getsize(output_video)} bytes"
        )

        log_info(
            "========================================"
        )

        return 0

    except Exception as exc:

        log_error(
            "========================================"
        )

        log_error(
            "PROCESSAMENTO FALHOU"
        )

        log_error(
            str(exc)
        )

        traceback.print_exc(
            file=sys.stderr
        )

        log_error(
            "========================================"
        )

        return 1

    finally:

        shutil.rmtree(
            temp_dir,
            ignore_errors=True
        )


# ============================================================
# EXECUÇÃO
# ============================================================

if __name__ == "__main__":

    try:

        sys.exit(
            main()
        )

    except Exception as exc:

        log_error(
            str(exc)
        )

        traceback.print_exc(
            file=sys.stderr
        )

        sys.exit(1)

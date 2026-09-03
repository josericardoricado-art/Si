#!/usr/bin/env python3

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
    value = max(0, min(100, int(value)))
    print(f"PROGRESS:{value}", flush=True)


def log_info(message):
    print(f"[PIPELINE] {message}", flush=True)


def log_error(message):
    print(f"[PIPELINE ERROR] {message}", file=sys.stderr, flush=True)


# ============================================================
# IDIOMAS
# ============================================================

LANGUAGES = {
    "pt": "português",
    "en": "inglês",
    "es": "espanhol",
    "fr": "francês",
    "de": "alemão",
    "it": "italiano",
    "ja": "japonês",
    "ko": "coreano",
    "zh": "chinês",
    "ru": "russo",
    "ar": "árabe",
    "hi": "hindi",
    "tr": "turco",
    "nl": "holandês",
    "pl": "polonês",
    "sv": "sueco",
    "da": "dinamarquês",
    "no": "norueguês",
    "fi": "finlandês",
    "cs": "tcheco",
    "el": "grego",
    "he": "hebraico",
    "id": "indonésio",
    "vi": "vietnamita",
    "th": "tailandês",
}


# ============================================================
# WHISPER
# ============================================================

_whisper_model = None


def get_whisper_model():

    global _whisper_model

    if _whisper_model is not None:
        return _whisper_model

    try:
        import whisper
    except ImportError as exc:
        raise RuntimeError(
            "openai-whisper não está instalado."
        ) from exc

    model_name = os.getenv(
        "WHISPER_MODEL",
        "base"
    ).strip()

    if not model_name:
        model_name = "base"

    log_info(
        f"Carregando modelo Whisper: {model_name}"
    )

    try:

        # O Whisper usa stderr para mostrar a barra
        # de download. Isso NÃO significa que ocorreu erro.
        _whisper_model = whisper.load_model(
            model_name
        )

    except Exception as exc:

        raise RuntimeError(
            "Não foi possível carregar o modelo Whisper "
            f"'{model_name}': {exc}"
        ) from exc

    log_info(
        f"Modelo Whisper '{model_name}' carregado com sucesso."
    )

    return _whisper_model


# ============================================================
# OPENAI
# ============================================================

def get_openai_client():

    api_key = os.getenv(
        "OPENAI_API_KEY"
    )

    if not api_key:
        raise RuntimeError(
            "OPENAI_API_KEY não configurada no Render."
        )

    try:
        from openai import OpenAI
    except ImportError as exc:
        raise RuntimeError(
            "A biblioteca openai não está instalada."
        ) from exc

    return OpenAI(
        api_key=api_key
    )


# ============================================================
# EXECUTAR COMANDO
# ============================================================

def run_command(
    command,
    description,
    error_limit=10000
):

    log_info(
        f"Executando: {description}"
    )

    try:

        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

    except FileNotFoundError as exc:

        raise RuntimeError(
            f"Programa necessário não encontrado: "
            f"{command[0]}"
        ) from exc

    if result.returncode != 0:

        stderr = (
            result.stderr
            or ""
        )

        if len(stderr) > error_limit:
            stderr = stderr[-error_limit:]

        raise RuntimeError(
            f"{description} falhou:\n{stderr}"
        )

    return result


# ============================================================
# VERIFICAR ARQUIVO
# ============================================================

def verify_file(
    file_path,
    description
):

    path = Path(file_path)

    if not path.exists():

        raise RuntimeError(
            f"{description} não foi criado: {file_path}"
        )

    try:

        size = path.stat().st_size

    except OSError as exc:

        raise RuntimeError(
            f"Não foi possível verificar {description}: {exc}"
        ) from exc

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

    probe = run_command(
        [
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
        ],
        "análise do vídeo com FFprobe",
        error_limit=5000
    )

    codec = (
        probe.stdout
        or ""
    ).strip()

    if not codec:

        raise RuntimeError(
            "Este vídeo não possui faixa de áudio."
        )

    log_info(
        f"Codec de áudio: {codec}"
    )

    result = run_command(
        [
            "ffmpeg",
            "-y",
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
        ],
        "extração do áudio com FFmpeg",
        error_limit=5000
    )

    verify_file(
        audio_path,
        "Áudio original"
    )

    log_progress(10)


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
        "Iniciando reconhecimento de fala..."
    )

    try:

        result = model.transcribe(
            audio_path,
            fp16=False
        )

    except Exception as exc:

        raise RuntimeError(
            f"Erro durante a transcrição com Whisper: {exc}"
        ) from exc

    text = (
        result.get("text")
        or ""
    ).strip()

    language = (
        result.get("language")
        or "unknown"
    )

    if not text:

        raise RuntimeError(
            "O Whisper não encontrou fala no vídeo."
        )

    log_info(
        f"Idioma detectado: {language}"
    )

    log_info(
        f"Texto detectado: {len(text)} caracteres"
    )

    log_progress(40)

    return text, language


# ============================================================
# TRADUÇÃO
# ============================================================

def translate_text(
    text,
    target_lang
):

    log_stage(
        "Traduzindo texto"
    )

    log_progress(50)

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma não suportado: {target_lang}"
        )

    client = get_openai_client()

    target_name = LANGUAGES[target_lang]

    prompt = f"""
Traduza o texto abaixo para {target_name}.

Regras:
- Retorne somente a tradução.
- Não explique.
- Não coloque aspas.
- Preserve nomes próprios.
- Preserve números.
- Faça uma tradução natural para dublagem.
- Não acrescente informações.
- Mantenha o sentido original.
- Não escreva observações.

Texto:

{text}
""".strip()

    model_name = os.getenv(
        "TRANSLATION_MODEL",
        "gpt-4o-mini"
    ).strip()

    if not model_name:
        model_name = "gpt-4o-mini"

    log_info(
        f"Modelo de tradução: {model_name}"
    )

    try:

        response = client.responses.create(
            model=model_name,
            input=prompt
        )

    except Exception as exc:

        raise RuntimeError(
            f"Erro na tradução OpenAI: {exc}"
        ) from exc

    translated = (
        response.output_text
        or ""
    ).strip()

    if not translated:

        raise RuntimeError(
            "A tradução retornou vazia."
        )

    log_info(
        f"Tradução criada: {len(translated)} caracteres"
    )

    log_progress(60)

    return translated


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
    # BUSCAR VOZ
    # ========================================================

    voice_id_env = os.getenv(
        "ELEVENLABS_VOICE_ID",
        ""
    ).strip()

    selected_voice = None

    if voice_id_env:

        log_info(
            "Usando ELEVENLABS_VOICE_ID configurado."
        )

        try:

            voices = client.voices.get_all()

            voice_list = getattr(
                voices,
                "voices",
                []
            )

            for voice in voice_list:

                current_id = getattr(
                    voice,
                    "voice_id",
                    ""
                )

                if current_id == voice_id_env:

                    selected_voice = voice
                    break

        except Exception as exc:

            log_info(
                "Não foi possível consultar a voz configurada. "
                f"Será feita uma nova tentativa: {exc}"
            )

    if selected_voice is None:

        log_info(
            "Buscando uma voz disponível na conta..."
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
                    name and
                    name.lower() ==
                    preferred.lower()
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
            f"Erro salvando áudio da ElevenLabs: {exc}"
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

    result = run_command(
        [
            "ffmpeg",
            "-y",
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
        ],
        "normalização do áudio com FFmpeg",
        error_limit=5000
    )

    verify_file(
        output_audio,
        "Áudio normalizado"
    )

    log_progress(85)


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

    temp_output = str(
        output_path
    ) + ".tmp.mp4"

    if os.path.exists(
        temp_output
    ):

        try:
            os.remove(
                temp_output
            )
        except OSError:
            pass

    command = [
        "ffmpeg",
        "-y",

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

    try:

        run_command(
            command,
            "criação do vídeo final com FFmpeg",
            error_limit=10000
        )

    except Exception:

        if os.path.exists(
            temp_output
        ):

            try:
                os.remove(
                    temp_output
                )
            except OSError:
                pass

        raise

    verify_file(
        temp_output,
        "Vídeo temporário"
    )

    try:

        os.replace(
            temp_output,
            output_video
        )

    except Exception as exc:

        raise RuntimeError(
            f"Não foi possível mover o vídeo final: {exc}"
        ) from exc

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
        description="SI - Tradutor Universal"
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

    log_info(
        "========================================"
    )

    log_info(
        "SI - TRADUTOR UNIVERSAL"
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
        "Dublagem: ElevenLabs"
    )

    log_info(
        "========================================"
    )

    # ========================================================
    # VERIFICAÇÕES
    # ========================================================

    if not os.path.exists(
        input_video
    ):

        raise RuntimeError(
            f"Vídeo não encontrado: {input_video}"
        )

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma não suportado: {target_lang}"
        )

    # Verificar FFmpeg

    try:

        subprocess.run(
            [
                "ffmpeg",
                "-version"
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True
        )

    except Exception as exc:

        raise RuntimeError(
            "FFmpeg não está instalado ou não está disponível."
        ) from exc

    # Verificar FFprobe

    try:

        subprocess.run(
            [
                "ffprobe",
                "-version"
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True
        )

    except Exception as exc:

        raise RuntimeError(
            "FFprobe não está instalado ou não está disponível."
        ) from exc

    temp_dir = tempfile.mkdtemp(
        prefix="si_pipeline_"
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
        # 2. TRANSCRIBIR
        # ====================================================

        original_text, detected_language = (
            transcribe_audio(
                original_audio
            )
        )

        log_info(
            f"Idioma original detectado: {detected_language}"
        )

        # ====================================================
        # 3. TRADUZIR
        # ====================================================

        translated_text = translate_text(
            original_text,
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
        # 6. CRIAR MP4
        # ====================================================

        create_dubbed_video(
            input_video,
            normalized_audio,
            output_video
        )

        # ====================================================
        # 7. VERIFICAÇÃO FINAL
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
            f"Tamanho: {os.path.getsize(output_video)} bytes"
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

    except KeyboardInterrupt:

        log_error(
            "Processamento interrompido."
        )

        sys.exit(130)

    except Exception as exc:

        log_error(
            str(exc)
        )

        traceback.print_exc(
            file=sys.stderr
        )

        sys.exit(1)

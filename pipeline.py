#!/usr/bin/env python3

# ============================================================
# SI - TRADUTOR UNIVERSAL
# Pipeline de transcrição, tradução e dublagem
#
# VERSÃO OTIMIZADA PARA RENDER FREE
#
# Fluxo:
# vídeo
#   ↓
# FFmpeg
#   ↓
# áudio mono 16 kHz
#   ↓
# Whisper Tiny
#   ↓
# OpenAI
#   ↓
# ElevenLabs
#   ↓
# FFmpeg
#   ↓
# vídeo final dublado
# ============================================================

import argparse
import gc
import os
import shutil
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path


# ============================================================
# CONFIGURAÇÕES
# ============================================================

DEFAULT_WHISPER_MODEL = "tiny"

DEFAULT_TRANSLATION_MODEL = "gpt-4o-mini"

DEFAULT_ELEVEN_MODEL = "eleven_multilingual_v2"


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

    print(
        f"PROGRESS:{value}",
        flush=True
    )


def log_info(message):
    print(
        f"[PIPELINE] {message}",
        flush=True
    )


def log_error(message):
    print(
        f"[PIPELINE ERROR] {message}",
        file=sys.stderr,
        flush=True
    )


# ============================================================
# MEMÓRIA
# ============================================================

def free_memory():
    """
    Tenta liberar memória Python/PyTorch.
    """

    try:
        gc.collect()
    except Exception:
        pass

    try:
        import torch

        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    except Exception:
        pass


# ============================================================
# CPU
# ============================================================

def configure_cpu():

    # Render Free tem CPU muito limitada.
    # Evitamos que bibliotecas matemáticas criem
    # dezenas de threads.

    cpu_threads = os.getenv(
        "OMP_NUM_THREADS",
        "1"
    )

    os.environ["OMP_NUM_THREADS"] = cpu_threads
    os.environ["MKL_NUM_THREADS"] = cpu_threads
    os.environ["OPENBLAS_NUM_THREADS"] = cpu_threads
    os.environ["NUMEXPR_NUM_THREADS"] = cpu_threads

    try:

        import torch

        torch.set_num_threads(
            int(cpu_threads)
        )

        try:
            torch.set_num_interop_threads(1)
        except Exception:
            pass

    except Exception:
        pass


# ============================================================
# EXECUTAR COMANDO
# ============================================================

def run_command(
    command,
    description,
    timeout=None
):

    log_info(
        f"Executando: {description}"
    )

    log_info(
        "Comando: " +
        " ".join(
            str(x)
            for x in command
        )
    )

    try:

        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout
        )

    except subprocess.TimeoutExpired as exc:

        raise RuntimeError(
            f"{description} excedeu o tempo limite."
        ) from exc

    except FileNotFoundError as exc:

        raise RuntimeError(
            f"Programa não encontrado durante {description}: "
            f"{command[0]}"
        ) from exc

    if result.returncode != 0:

        error_text = (
            result.stderr
            or result.stdout
            or ""
        )

        raise RuntimeError(
            f"{description} falhou.\n"
            f"Código: {result.returncode}\n"
            f"{error_text[-10000:]}"
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
            f"{description} não foi criado: "
            f"{file_path}"
        )

    try:

        size = path.stat().st_size

    except Exception as exc:

        raise RuntimeError(
            f"Não foi possível verificar "
            f"{description}: {file_path}"
        ) from exc

    if size <= 0:

        raise RuntimeError(
            f"{description} está vazio."
        )

    log_info(
        f"{description}: {size} bytes"
    )

    return size


# ============================================================
# VERIFICAR FFMPEG
# ============================================================

def check_ffmpeg():

    log_stage(
        "Verificando FFmpeg"
    )

    for program in [
        "ffmpeg",
        "ffprobe"
    ]:

        result = shutil.which(
            program
        )

        if not result:

            raise RuntimeError(
                f"{program} não está instalado "
                "no servidor."
            )

        log_info(
            f"{program}: {result}"
        )


# ============================================================
# WHISPER
# ============================================================

_whisper_model = None


def get_whisper_model():

    global _whisper_model

    if _whisper_model is not None:

        return _whisper_model

    configure_cpu()

    try:

        import whisper

    except ImportError as exc:

        raise RuntimeError(
            "openai-whisper não está instalado."
        ) from exc

    # --------------------------------------------------------
    # Para Render Free, tiny é o padrão.
    # --------------------------------------------------------

    model_name = os.getenv(
        "WHISPER_MODEL",
        DEFAULT_WHISPER_MODEL
    ).strip().lower()

    if not model_name:

        model_name = DEFAULT_WHISPER_MODEL

    allowed_models = {
        "tiny",
        "tiny.en",
        "base",
        "base.en",
        "small",
        "small.en",
        "medium",
        "medium.en",
        "large",
        "turbo",
    }

    if model_name not in allowed_models:

        log_info(
            f"Modelo Whisper inválido: {model_name}"
        )

        log_info(
            "Usando tiny."
        )

        model_name = "tiny"

    # --------------------------------------------------------
    # Se estiver usando Render Free, recomendamos tiny.
    #
    # Caso WHISPER_MODEL=base esteja configurado,
    # mostramos aviso no log.
    # --------------------------------------------------------

    if model_name not in [
        "tiny",
        "tiny.en"
    ]:

        log_info(
            "AVISO: o modelo Whisper configurado é "
            f"{model_name}."
        )

        log_info(
            "Para Render Free, recomendamos "
            "WHISPER_MODEL=tiny."
        )

    log_stage(
        f"Carregando Whisper {model_name}"
    )

    log_info(
        f"Modelo Whisper: {model_name}"
    )

    log_info(
        "Modo CPU ativado."
    )

    free_memory()

    try:

        _whisper_model = whisper.load_model(
            model_name,
            device="cpu"
        )

    except Exception as exc:

        raise RuntimeError(
            "Não foi possível carregar o Whisper "
            f"({model_name}). "
            "No Render Free use WHISPER_MODEL=tiny. "
            f"Erro original: {exc}"
        ) from exc

    log_info(
        "Whisper carregado."
    )

    return _whisper_model


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

    video_path = str(video_path)
    audio_path = str(audio_path)

    # --------------------------------------------------------
    # Primeiro verifica se existe faixa de áudio.
    # --------------------------------------------------------

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
            video_path,
        ],
        "análise do áudio"
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

    # --------------------------------------------------------
    # Converte diretamente para WAV mono 16 kHz.
    #
    # Isso reduz bastante o tamanho do áudio que será
    # utilizado pelo Whisper.
    # --------------------------------------------------------

    run_command(
        [
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

            audio_path,
        ],
        "extração do áudio"
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
        "Iniciando transcrição em CPU..."
    )

    free_memory()

    try:

        result = model.transcribe(
            str(audio_path),

            fp16=False,

            language=None,

            task="transcribe",

            temperature=0,

            verbose=False,

            condition_on_previous_text=False,

            no_speech_threshold=0.6,

            compression_ratio_threshold=2.4,

            logprob_threshold=-1.0,

            beam_size=1,

            best_of=1,
        )

    except TypeError:

        # Compatibilidade com versões diferentes
        # do Whisper.

        result = model.transcribe(
            str(audio_path),
            fp16=False,
            verbose=False,
            condition_on_previous_text=False,
        )

    except Exception as exc:

        raise RuntimeError(
            "Erro durante a transcrição do Whisper: "
            + str(exc)
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

    # --------------------------------------------------------
    # Evita guardar objetos grandes desnecessariamente.
    # --------------------------------------------------------

    try:

        del result

    except Exception:
        pass

    free_memory()

    log_progress(40)

    return text, language


# ============================================================
# OPENAI
# ============================================================

def get_openai_client():

    api_key = os.getenv(
        "OPENAI_API_KEY"
    )

    if not api_key:

        raise RuntimeError(
            "OPENAI_API_KEY não configurada "
            "no Render."
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
# DIVIDIR TEXTO
# ============================================================

def split_text(
    text,
    max_chars=5000
):

    text = (
        text
        or ""
    ).strip()

    if not text:

        return []

    if len(text) <= max_chars:

        return [text]

    words = text.split()

    chunks = []
    current = []

    current_length = 0

    for word in words:

        word_length = len(word) + 1

        if (
            current
            and
            current_length + word_length > max_chars
        ):

            chunks.append(
                " ".join(current)
            )

            current = []
            current_length = 0

        current.append(word)

        current_length += word_length

    if current:

        chunks.append(
            " ".join(current)
        )

    return chunks


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

    target_lang = (
        target_lang
        or "pt"
    ).lower().strip()

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma não suportado: {target_lang}"
        )

    client = get_openai_client()

    target_name = LANGUAGES[
        target_lang
    ]

    model_name = os.getenv(
        "TRANSLATION_MODEL",
        DEFAULT_TRANSLATION_MODEL
    )

    chunks = split_text(
        text,
        max_chars=5000
    )

    if not chunks:

        raise RuntimeError(
            "Não existe texto para traduzir."
        )

    log_info(
        f"Texto dividido em {len(chunks)} parte(s)."
    )

    translated_chunks = []

    total = len(chunks)

    for index, chunk in enumerate(
        chunks,
        start=1
    ):

        log_info(
            f"Traduzindo parte {index}/{total}..."
        )

        prompt = f"""
Traduza o texto abaixo para {target_name}.

Regras:
- Retorne somente a tradução.
- Não explique.
- Não coloque aspas.
- Preserve nomes próprios.
- Preserve números.
- Preserve o significado.
- Faça uma tradução natural para dublagem.
- Não acrescente informações.
- Não resuma.
- Não remova frases.

Texto:
{chunk}
""".strip()

        try:

            response = client.responses.create(
                model=model_name,
                input=prompt
            )

        except Exception as exc:

            raise RuntimeError(
                "Erro na tradução pela OpenAI: "
                + str(exc)
            ) from exc

        translated = (
            response.output_text
            or ""
        ).strip()

        if not translated:

            raise RuntimeError(
                "A OpenAI retornou uma tradução vazia."
            )

        translated_chunks.append(
            translated
        )

        progress = (
            50
            +
            int(
                (index / total) * 10
            )
        )

        log_progress(
            min(progress, 60)
        )

        free_memory()

    translated_text = "\n".join(
        translated_chunks
    ).strip()

    if not translated_text:

        raise RuntimeError(
            "A tradução final ficou vazia."
        )

    log_info(
        f"Tradução criada: "
        f"{len(translated_text)} caracteres"
    )

    log_progress(60)

    return translated_text


# ============================================================
# ELEVENLABS
# ============================================================

def get_elevenlabs_client():

    api_key = os.getenv(
        "ELEVENLABS_API_KEY"
    )

    if not api_key:

        raise RuntimeError(
            "ELEVENLABS_API_KEY não está "
            "configurada no Render."
        )

    try:

        from elevenlabs.client import ElevenLabs

    except ImportError as exc:

        raise RuntimeError(
            "A biblioteca elevenlabs não está instalada."
        ) from exc

    return ElevenLabs(
        api_key=api_key
    )


# ============================================================
# ENCONTRAR VOZ
# ============================================================

def select_elevenlabs_voice(
    client
):

    log_info(
        "Buscando voz disponível na ElevenLabs..."
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

    # --------------------------------------------------------
    # Se o usuário configurar manualmente:
    #
    # ELEVENLABS_VOICE_ID
    #
    # usamos essa voz.
    # --------------------------------------------------------

    configured_voice_id = os.getenv(
        "ELEVENLABS_VOICE_ID"
    )

    if configured_voice_id:

        configured_voice_id = (
            configured_voice_id.strip()
        )

        for voice in voice_list:

            voice_id = getattr(
                voice,
                "voice_id",
                None
            )

            if voice_id == configured_voice_id:

                log_info(
                    "Usando ELEVENLABS_VOICE_ID "
                    "configurado."
                )

                return voice

        log_info(
            "ELEVENLABS_VOICE_ID configurado "
            "não foi encontrado."
        )

    # --------------------------------------------------------
    # Vozes preferidas.
    # --------------------------------------------------------

    preferred_names = [
        "Rachel",
        "Bella",
        "Antoni",
        "Adam",
        "Domi",
        "Josh",
        "Elli",
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
                name.lower() ==
                preferred.lower()
            ):

                return voice

    # --------------------------------------------------------
    # Se não encontrar, primeira voz.
    # --------------------------------------------------------

    return voice_list[0]


# ============================================================
# GERAR VOZ
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

    client = get_elevenlabs_client()

    voice = select_elevenlabs_voice(
        client
    )

    voice_id = getattr(
        voice,
        "voice_id",
        None
    )

    voice_name = getattr(
        voice,
        "name",
        "voz"
    )

    if not voice_id:

        raise RuntimeError(
            "A ElevenLabs retornou uma voz "
            "sem voice_id."
        )

    log_info(
        f"Voz selecionada: {voice_name}"
    )

    model_id = os.getenv(
        "ELEVENLABS_MODEL",
        DEFAULT_ELEVEN_MODEL
    )

    log_info(
        f"Modelo ElevenLabs: {model_id}"
    )

    # --------------------------------------------------------
    # Para textos muito grandes, dividimos.
    # Isso evita mandar um texto enorme de uma vez.
    # --------------------------------------------------------

    chunks = split_text(
        text,
        max_chars=4500
    )

    log_info(
        f"Texto para ElevenLabs: "
        f"{len(chunks)} parte(s)"
    )

    temp_dir = tempfile.mkdtemp(
        prefix="si_eleven_"
    )

    generated_files = []

    try:

        for index, chunk in enumerate(
            chunks,
            start=1
        ):

            chunk_path = os.path.join(
                temp_dir,
                f"part_{index}.mp3"
            )

            log_info(
                f"Gerando voz "
                f"{index}/{len(chunks)}..."
            )

            try:

                audio_stream = (
                    client
                    .text_to_speech
                    .convert(
                        text=chunk,
                        voice_id=voice_id,
                        model_id=model_id,
                        output_format="mp3_44100_128",
                    )
                )

            except Exception as exc:

                raise RuntimeError(
                    "Erro ao solicitar voz à ElevenLabs: "
                    + str(exc)
                ) from exc

            with open(
                chunk_path,
                "wb"
            ) as output_file:

                for data in audio_stream:

                    if data:

                        output_file.write(
                            data
                        )

            verify_file(
                chunk_path,
                f"Áudio ElevenLabs parte {index}"
            )

            generated_files.append(
                chunk_path
            )

            log_progress(
                70
                +
                int(
                    (index / len(chunks))
                    * 10
                )
            )

            free_memory()

        if len(generated_files) == 1:

            shutil.copyfile(
                generated_files[0],
                output_path
            )

        else:

            # ------------------------------------------------
            # Cria lista FFmpeg para concatenar os áudios.
            # ------------------------------------------------

            concat_file = os.path.join(
                temp_dir,
                "concat.txt"
            )

            with open(
                concat_file,
                "w",
                encoding="utf-8"
            ) as file:

                for audio_file in generated_files:

                    safe_path = (
                        audio_file
                        .replace(
                            "'",
                            "'\\''"
                        )
                    )

                    file.write(
                        f"file '{safe_path}'\n"
                    )

            run_command(
                [
                    "ffmpeg",
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

                    "-c:a",
                    "copy",

                    output_path,
                ],
                "junção dos áudios da ElevenLabs"
            )

        verify_file(
            output_path,
            "Áudio ElevenLabs"
        )

        log_info(
            "Áudio da dublagem criado."
        )

        log_progress(80)

    finally:

        shutil.rmtree(
            temp_dir,
            ignore_errors=True
        )

        free_memory()


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

    run_command(
        [
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
            "128k",

            output_audio,
        ],
        "normalização do áudio"
    )

    verify_file(
        output_audio,
        "Áudio normalizado"
    )

    free_memory()


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
        +
        ".tmp.mp4"
    )

    try:

        if os.path.exists(
            temp_output
        ):

            os.remove(
                temp_output
            )

    except Exception:
        pass

    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",

        "-i",
        str(original_video),

        "-i",
        str(dubbed_audio),

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

        temp_output,
    ]

    try:

        run_command(
            command,
            "criação do vídeo final"
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

    finally:

        if os.path.exists(
            temp_output
        ):

            try:

                os.remove(
                    temp_output
                )

            except Exception:
                pass

    log_progress(98)

    log_info(
        "MP4 final criado com sucesso."
    )


# ============================================================
# LIMPAR MODELO WHISPER
# ============================================================

def unload_whisper():

    global _whisper_model

    if _whisper_model is None:

        return

    try:

        del _whisper_model

    except Exception:
        pass

    _whisper_model = None

    free_memory()

    log_info(
        "Memória do Whisper liberada."
    )


# ============================================================
# VALIDAR ENTRADA
# ============================================================

def validate_input(
    input_video,
    output_video,
    target_lang
):

    if not os.path.exists(
        input_video
    ):

        raise RuntimeError(
            f"Vídeo não encontrado: "
            f"{input_video}"
        )

    verify_file(
        input_video,
        "Vídeo de entrada"
    )

    if target_lang not in LANGUAGES:

        raise RuntimeError(
            f"Idioma não suportado: "
            f"{target_lang}"
        )

    output_parent = Path(
        output_video
    ).parent

    output_parent.mkdir(
        parents=True,
        exist_ok=True
    )


# ============================================================
# MAIN
# ============================================================

def main():

    parser = argparse.ArgumentParser(
        description=(
            "SI - Tradutor Universal "
            "para Render"
        )
    )

    parser.add_argument(
        "--input",
        required=True,
        help="Vídeo de entrada"
    )

    parser.add_argument(
        "--output",
        required=True,
        help="Vídeo final"
    )

    parser.add_argument(
        "--target-lang",
        default="pt",
        help="Idioma da dublagem"
    )

    args = parser.parse_args()

    configure_cpu()

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
        "PIPELINE OTIMIZADO PARA RENDER"
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
        "Whisper padrão: tiny"
    )

    log_info(
        "Dublagem: ElevenLabs"
    )

    log_info(
        "Tradução: OpenAI"
    )

    log_info(
        "========================================"
    )

    temp_dir = None

    try:

        # ----------------------------------------------------
        # 1. Validar
        # ----------------------------------------------------

        check_ffmpeg()

        validate_input(
            input_video,
            output_video,
            target_lang
        )

        # ----------------------------------------------------
        # 2. Criar diretório temporário
        # ----------------------------------------------------

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

        # ----------------------------------------------------
        # 3. Extrair áudio
        # ----------------------------------------------------

        extract_audio(
            input_video,
            original_audio
        )

        # ----------------------------------------------------
        # 4. Whisper
        # ----------------------------------------------------

        original_text, detected_language = (
            transcribe_audio(
                original_audio
            )
        )

        log_info(
            f"Idioma detectado: "
            f"{detected_language}"
        )

        # ----------------------------------------------------
        # 5. Depois da transcrição, liberamos o Whisper
        # antes de chamar OpenAI/ElevenLabs.
        #
        # Isso é importante no Render Free.
        # ----------------------------------------------------

        unload_whisper()

        # ----------------------------------------------------
        # 6. Tradução
        # ----------------------------------------------------

        translated_text = translate_text(
            original_text,
            target_lang
        )

        # ----------------------------------------------------
        # O texto original já não é mais necessário.
        # ----------------------------------------------------

        original_text = None

        free_memory()

        # ----------------------------------------------------
        # 7. ElevenLabs
        # ----------------------------------------------------

        generate_elevenlabs_voice(
            translated_text,
            target_lang,
            eleven_audio
        )

        translated_text = None

        free_memory()

        # ----------------------------------------------------
        # 8. Normalizar áudio
        # ----------------------------------------------------

        normalize_audio(
            eleven_audio,
            normalized_audio
        )

        # ----------------------------------------------------
        # 9. Criar vídeo
        # ----------------------------------------------------

        create_dubbed_video(
            input_video,
            normalized_audio,
            output_video
        )

        # ----------------------------------------------------
        # 10. Verificação final
        # ----------------------------------------------------

        final_size = verify_file(
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
            f"Tamanho final: {final_size} bytes"
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

        unload_whisper()

        free_memory()

        if temp_dir:

            shutil.rmtree(
                temp_dir,
                ignore_errors=True
            )

        free_memory()


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

        sys.exit(1)

    except Exception as exc:

        log_error(
            str(exc)
        )

        traceback.print_exc(
            file=sys.stderr
        )

        sys.exit(1)

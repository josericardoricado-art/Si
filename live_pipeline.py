#!/usr/bin/env python3

"""
live_pipeline.py

Processamento de áudio para o modo de tradução ao vivo do Si.

Fluxo:

Áudio WebM
   ↓
FFmpeg
   ↓
Whisper
   ↓
Texto original
   ↓
Argos Translate
   ↓
Texto traduzido
   ↓
TTS
   ↓
Áudio dublado

O programa recebe:

--input       arquivo de áudio
--target-lang idioma de destino

E imprime:

TEXT: texto reconhecido
TRANSLATION: texto traduzido
AUDIO: caminho do áudio gerado
PROGRESS: porcentagem
STAGE: etapa atual
"""

import argparse
import os
import subprocess
import sys
import tempfile


# ==========================================================
# LOG
# ==========================================================

def log(message):
    print(message, flush=True)


def stage(message):
    log(f"STAGE:{message}")


def progress(value):
    value = max(0, min(100, int(value)))
    log(f"PROGRESS:{value}")


# ==========================================================
# FFMPEG
# ==========================================================

def convert_audio(input_path, output_path):

    stage("convertendo áudio")

    progress(5)

    command = [
        "ffmpeg",
        "-y",
        "-i",
        input_path,
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-acodec",
        "pcm_s16le",
        output_path
    ]

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )

    if result.returncode != 0:

        error = result.stderr.decode(
            "utf-8",
            errors="ignore"
        )

        raise RuntimeError(
            "FFmpeg falhou: " + error[-3000:]
        )


# ==========================================================
# WHISPER
# ==========================================================

def transcribe(audio_path):

    stage("reconhecendo fala")

    progress(20)

    try:

        import whisper

    except ImportError:

        raise RuntimeError(
            "Whisper não está instalado."
        )


    model_name = os.environ.get(
        "WHISPER_MODEL",
        "tiny"
    )


    log(
        f"INFO: carregando Whisper {model_name}"
    )


    model = whisper.load_model(
        model_name
    )


    result = model.transcribe(
        audio_path,
        fp16=False
    )


    text = (
        result
        .get("text", "")
        .strip()
    )


    detected_language = (
        result
        .get("language", "en")
    )


    if not text:

        return "", detected_language


    log(
        "TEXT:" + text
    )


    log(
        "LANG:" + detected_language
    )


    return text, detected_language


# ==========================================================
# ARGOS TRANSLATE
# ==========================================================

def translate_text(
    text,
    source_lang,
    target_lang
):

    stage(
        f"traduzindo {source_lang} -> {target_lang}"
    )

    progress(45)


    if not text.strip():

        return ""


    try:

        import argostranslate.translate

    except ImportError:

        raise RuntimeError(
            "Argos Translate não está instalado."
        )


    installed_languages = (
        argostranslate.translate
        .get_installed_languages()
    )


    from_lang = None
    to_lang = None


    for language in installed_languages:

        if language.code == source_lang:

            from_lang = language

        if language.code == target_lang:

            to_lang = language


    if not from_lang:

        raise RuntimeError(
            f"Idioma de origem não instalado: {source_lang}"
        )


    if not to_lang:

        raise RuntimeError(
            f"Idioma de destino não instalado: {target_lang}"
        )


    translation = (
        from_lang
        .get_translation(to_lang)
    )


    if not translation:

        raise RuntimeError(
            f"Par de tradução não instalado: "
            f"{source_lang}->{target_lang}"
        )


    translated = (
        translation
        .translate(text)
        .strip()
    )


    log(
        "TRANSLATION:" + translated
    )


    return translated


# ==========================================================
# TTS
# ==========================================================

def synthesize(
    text,
    target_lang,
    output_path
):

    stage(
        "gerando voz dublada"
    )

    progress(70)


    if not text.strip():

        raise RuntimeError(
            "Texto vazio para TTS."
        )


    try:

        from TTS.api import TTS

    except ImportError:

        raise RuntimeError(
            "Coqui TTS não está instalado."
        )


    model_name = os.environ.get(
        "TTS_MODEL",
        "tts_models/multilingual/multi-dataset/xtts_v2"
    )


    log(
        "INFO: carregando modelo TTS"
    )


    tts = TTS(
        model_name=model_name,
        progress_bar=False
    )


    speakers = []

    try:

        speakers = (
            tts
            .speakers
            or []
        )

    except Exception:

        speakers = []


    kwargs = {

        "text": text,

        "file_path": output_path

    }


    # ======================================================
    # XTTS V2
    # ======================================================

    if "xtts" in model_name.lower():

        if target_lang:

            kwargs[
                "language"
            ] = target_lang


        if speakers:

            # Primeira voz disponível
            kwargs[
                "speaker"
            ] = speakers[0]

        else:

            # Alguns ambientes/modelos
            # exigem speaker_wav.
            #
            # Sem uma voz de referência,
            # deixamos o modelo tentar
            # utilizar a configuração disponível.

            pass


    tts.tts_to_file(
        **kwargs
    )


    if not os.path.exists(
        output_path
    ):

        raise RuntimeError(
            "O TTS não gerou o arquivo de áudio."
        )


    progress(90)


# ==========================================================
# PROCESSAMENTO COMPLETO
# ==========================================================

def process(
    input_path,
    target_lang,
    output_audio
):

    with tempfile.TemporaryDirectory() as tmp:

        wav_path = os.path.join(
            tmp,
            "input.wav"
        )


        # --------------------------------------------------
        # 1. CONVERTER
        # --------------------------------------------------

        convert_audio(
            input_path,
            wav_path
        )


        # --------------------------------------------------
        # 2. WHISPER
        # --------------------------------------------------

        text, detected_language = (
            transcribe(
                wav_path
            )
        )


        if not text:

            stage(
                "nenhuma fala detectada"
            )

            progress(100)

            return {
                "text": "",
                "translation": "",
                "audio": None
            }


        # --------------------------------------------------
        # 3. EVITAR TRADUÇÃO DESNECESSÁRIA
        # --------------------------------------------------

        if (
            detected_language ==
            target_lang
        ):

            translated = text

        else:

            translated = translate_text(
                text,
                detected_language,
                target_lang
            )


        if not translated:

            raise RuntimeError(
                "A tradução retornou vazia."
            )


        # --------------------------------------------------
        # 4. TTS
        # --------------------------------------------------

        synthesize(
            translated,
            target_lang,
            output_audio
        )


        # --------------------------------------------------
        # FINAL
        # --------------------------------------------------

        progress(100)

        stage(
            "bloco concluído"
        )


        return {

            "text":
                text,

            "translation":
                translated,

            "audio":
                output_audio

        }


# ==========================================================
# MAIN
# ==========================================================

def main():

    parser = argparse.ArgumentParser()


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


    try:

        result = process(
            args.input,
            args.target_lang,
            args.output
        )


        if result["text"]:

            log(
                "RESULT_TEXT:" +
                result["text"]
            )


        if result["translation"]:

            log(
                "RESULT_TRANSLATION:" +
                result["translation"]
            )


        if result["audio"]:

            log(
                "AUDIO:" +
                result["audio"]
            )


        sys.exit(0)


    except Exception as error:

        stage("erro")

        print(
            "ERROR:" +
            str(error),
            file=sys.stderr,
            flush=True
        )

        sys.exit(1)


# ==========================================================
# EXECUTAR
# ==========================================================

if __name__ == "__main__":

    main()

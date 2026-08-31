#!/usr/bin/env python3

import argparse
import subprocess
import sys
import os
import tempfile
import traceback


def log_stage(msg):
    print(f"STAGE:{msg}", flush=True)


def log_progress(pct):
    print(f"PROGRESS:{pct}", flush=True)


def extract_audio(video_path, audio_path):

    log_stage("Extraindo áudio do vídeo")
    log_progress(5)

    try:

        result = subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i",
                video_path,
                "-vn",
                "-acodec",
                "pcm_s16le",
                "-ar",
                "16000",
                "-ac",
                "1",
                audio_path
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        if result.returncode != 0:

            raise RuntimeError(
                "FFmpeg falhou:\n" +
                result.stderr[-5000:]
            )

    except Exception:

        raise


def transcribe(audio_path):

    log_stage("Carregando Whisper")
    log_progress(15)

    try:

        import whisper
        import torch

        model_name = os.getenv(
            "WHISPER_MODEL",
            "base"
        )

        log_stage(
            f"Carregando modelo Whisper: {model_name}"
        )

        print(
            f"[WHISPER] Modelo: {model_name}",
            flush=True
        )

        print(
            f"[WHISPER] Torch: {torch.__version__}",
            flush=True
        )

        print(
            f"[WHISPER] CPU disponível: {os.cpu_count()}",
            flush=True
        )

        model = whisper.load_model(
            model_name,
            device="cpu"
        )

        log_stage(
            "Transcrevendo áudio"
        )

        log_progress(25)

        result = model.transcribe(
            audio_path,
            language=None,
            fp16=False,
            verbose=False
        )

        text = result.get(
            "text",
            ""
        ).strip()

        detected_language = result.get(
            "language",
            "en"
        )

        print(
            f"[WHISPER] Idioma detectado: {detected_language}",
            flush=True
        )

        print(
            f"[WHISPER] Texto: {text[:500]}",
            flush=True
        )

        if not text:

            raise RuntimeError(
                "Whisper não conseguiu reconhecer fala no áudio."
            )

        log_progress(40)

        return text, detected_language

    except Exception as error:

        print(
            "========================================",
            file=sys.stderr
        )

        print(
            "[WHISPER ERROR]",
            file=sys.stderr
        )

        print(
            str(error),
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        print(
            "========================================",
            file=sys.stderr
        )

        raise


def translate(
    text,
    source_lang,
    target_lang
):

    log_stage(
        f"Traduzindo texto ({source_lang} -> {target_lang})"
    )

    log_progress(50)

    try:

        import argostranslate.translate

        installed_languages = (
            argostranslate.translate
            .get_installed_languages()
        )

        from_lang = next(
            (
                language
                for language in installed_languages
                if language.code == source_lang
            ),
            None
        )

        to_lang = next(
            (
                language
                for language in installed_languages
                if language.code == target_lang
            ),
            None
        )

        if not from_lang:

            raise RuntimeError(
                f"Idioma de origem não instalado: {source_lang}"
            )

        if not to_lang:

            raise RuntimeError(
                f"Idioma de destino não instalado: {target_lang}"
            )

        translation = from_lang.get_translation(
            to_lang
        )

        if not translation:

            raise RuntimeError(
                f"Par de tradução não instalado: "
                f"{source_lang} -> {target_lang}"
            )

        translated = translation.translate(
            text
        )

        if not translated.strip():

            raise RuntimeError(
                "A tradução retornou texto vazio."
            )

        print(
            f"[ARGOS] Tradução: {translated[:500]}",
            flush=True
        )

        log_progress(60)

        return translated

    except Exception as error:

        print(
            "[TRANSLATION ERROR]",
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        raise


def synthesize_speech(
    text,
    target_lang,
    out_audio_path
):

    log_stage(
        "Gerando voz dublada"
    )

    log_progress(70)

    try:

        from TTS.api import TTS

        model_name = (
            "tts_models/"
            "multilingual/"
            "multi-dataset/"
            "xtts_v2"
        )

        print(
            f"[TTS] Carregando: {model_name}",
            flush=True
        )

        tts = TTS(
            model_name=model_name,
            progress_bar=False
        )

        tts.tts_to_file(
            text=text,
            language=target_lang,
            file_path=out_audio_path,
            speaker="Claribel Dervla"
        )

        if not os.path.exists(
            out_audio_path
        ):

            raise RuntimeError(
                "O TTS não criou o arquivo de áudio."
            )

        log_progress(85)

    except Exception:

        print(
            "[TTS ERROR]",
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        raise


def merge_audio_video(
    video_path,
    new_audio_path,
    output_path
):

    log_stage(
        "Remontando vídeo com áudio dublado"
    )

    log_progress(90)

    try:

        result = subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i",
                video_path,
                "-i",
                new_audio_path,

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

                output_path
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        if result.returncode != 0:

            raise RuntimeError(
                "FFmpeg falhou ao juntar áudio e vídeo:\n"
                + result.stderr[-5000:]
            )

        if not os.path.exists(
            output_path
        ):

            raise RuntimeError(
                "O vídeo final não foi criado."
            )

        log_progress(95)

    except Exception:

        print(
            "[FFMPEG MERGE ERROR]",
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        raise


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

    print(
        "========================================",
        flush=True
    )

    print(
        "SI — PIPELINE DE DUBLAGEM",
        flush=True
    )

    print(
        "========================================",
        flush=True
    )

    print(
        f"Entrada: {args.input}",
        flush=True
    )

    print(
        f"Saída: {args.output}",
        flush=True
    )

    print(
        f"Idioma alvo: {args.target_lang}",
        flush=True
    )

    with tempfile.TemporaryDirectory() as tmp:

        audio_in = os.path.join(
            tmp,
            "audio_original.wav"
        )

        audio_out = os.path.join(
            tmp,
            "audio_dublado.wav"
        )

        try:

            extract_audio(
                args.input,
                audio_in
            )

            text, detected_lang = transcribe(
                audio_in
            )

            if detected_lang == args.target_lang:

                print(
                    "[INFO] O idioma original já é o idioma de destino.",
                    flush=True
                )

                translated_text = text

            else:

                translated_text = translate(
                    text,
                    detected_lang,
                    args.target_lang
                )

            synthesize_speech(
                translated_text,
                args.target_lang,
                audio_out
            )

            merge_audio_video(
                args.input,
                audio_out,
                args.output
            )

            log_progress(100)

            log_stage(
                "Concluído"
            )

            print(
                "PROCESSAMENTO CONCLUÍDO",
                flush=True
            )

            sys.exit(0)

        except Exception as error:

            print(
                "========================================",
                file=sys.stderr
            )

            print(
                "[PIPELINE ERROR]",
                file=sys.stderr
            )

            print(
                str(error),
                file=sys.stderr
            )

            traceback.print_exc(
                file=sys.stderr
            )

            print(
                "========================================",
                file=sys.stderr
            )

            sys.exit(1)


if __name__ == "__main__":
    main()

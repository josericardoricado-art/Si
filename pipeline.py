#!/usr/bin/env python3
"""
pipeline.py
Pipeline de dublagem automatica, 100% com ferramentas open-source (sem custo de API):

  1. Extrai o audio do video (ffmpeg)
  2. Transcreve o audio original (Whisper)
  3. Traduz o texto para o idioma alvo (Argos Translate)
  4. Gera a nova narracao em audio (Coqui TTS)
  5. Junta a nova trilha de audio com o video original (ffmpeg)

Uso:
    python3 pipeline.py --input video.mp4 --output video_dublado.mp4 --target-lang pt

Imprime no stdout linhas "STAGE:..." e "PROGRESS:..." para o backend Node
acompanhar o andamento em tempo real.
"""

import argparse
import subprocess
import sys
import os
import tempfile


def log_stage(msg):
    print(f"STAGE:{msg}", flush=True)


def log_progress(pct):
    print(f"PROGRESS:{pct}", flush=True)


def extract_audio(video_path, audio_path):
    log_stage("extraindo audio do video")
    log_progress(5)
    subprocess.run(
        ["ffmpeg", "-y", "-i", video_path, "-vn", "-acodec", "pcm_s16le",
         "-ar", "16000", "-ac", "1", audio_path],
        check=True,
        capture_output=True,
    )


def transcribe(audio_path):
    log_stage("transcrevendo audio (Whisper)")
    log_progress(20)
    import whisper

    # "small" e um bom equilibrio custo/qualidade. Para mais qualidade,
    # troque para "medium" ou "large" (exige mais VRAM/tempo).
    model = whisper.load_model("small")
    result = model.transcribe(audio_path)
    return result["text"], result.get("language", "en")


def translate(text, source_lang, target_lang):
    log_stage(f"traduzindo texto ({source_lang} -> {target_lang})")
    log_progress(45)
    import argostranslate.package
    import argostranslate.translate

    # Assume que o pacote de idiomas ja foi instalado previamente
    # (veja install_languages.py). Baixar pacotes exige internet,
    # mas so precisa ser feito uma vez, na configuracao do servidor.
    installed_languages = argostranslate.translate.get_installed_languages()
    from_lang = next((l for l in installed_languages if l.code == source_lang), None)
    to_lang = next((l for l in installed_languages if l.code == target_lang), None)

    if not from_lang or not to_lang:
        raise RuntimeError(
            f"Par de idiomas {source_lang}->{target_lang} nao instalado. "
            f"Rode install_languages.py primeiro."
        )

    translation = from_lang.get_translation(to_lang)
    return translation.translate(text)


def synthesize_speech(text, target_lang, out_audio_path):
    log_stage("gerando a voz dublada (TTS)")
    log_progress(70)
    from TTS.api import TTS

    # Modelo multilingue da Coqui TTS. Troque pelo modelo que preferir;
    # alguns suportam clonagem de voz a partir de uma amostra de audio.
    tts = TTS(model_name="tts_models/multilingual/multi-dataset/xtts_v2", progress_bar=False)
    tts.tts_to_file(
        text=text,
        language=target_lang,
        file_path=out_audio_path,
        speaker="Claribel Dervla",  # voz padrao do modelo; pode trocar
    )


def merge_audio_video(video_path, new_audio_path, output_path):
    log_stage("remontando o video com o audio dublado")
    log_progress(90)
    subprocess.run(
        [
            "ffmpeg", "-y",
            "-i", video_path,
            "-i", new_audio_path,
            "-c:v", "copy",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest",
            output_path,
        ],
        check=True,
        capture_output=True,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Caminho do video original")
    parser.add_argument("--output", required=True, help="Caminho do video dublado de saida")
    parser.add_argument("--target-lang", default="pt", help="Idioma de destino (ex: pt, en, es)")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        audio_in = os.path.join(tmp, "audio_original.wav")
        audio_out = os.path.join(tmp, "audio_dublado.wav")

        try:
            extract_audio(args.input, audio_in)

            text, detected_lang = transcribe(audio_in)
            if not text.strip():
                raise RuntimeError("Nao foi possivel extrair texto do audio (silencio ou fala nao reconhecida).")

            translated_text = translate(text, detected_lang, args.target_lang)

            synthesize_speech(translated_text, args.target_lang, audio_out)

            merge_audio_video(args.input, audio_out, args.output)

            log_progress(100)
            log_stage("concluido")
        except subprocess.CalledProcessError as e:
            sys.stderr.write(f"Erro no ffmpeg: {e.stderr.decode(errors='ignore') if e.stderr else e}\n")
            sys.exit(1)
        except Exception as e:
            sys.stderr.write(f"Erro no pipeline: {e}\n")
            sys.exit(1)


if __name__ == "__main__":
    main()

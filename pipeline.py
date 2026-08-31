def extract_audio(video_path, audio_path):

    log_stage("Extraindo áudio do vídeo")
    log_progress(5)

    try:
        # Primeiro verifica se existe uma faixa de áudio
        probe = subprocess.run(
            [
                "ffprobe",
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video_path
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        if probe.returncode != 0:
            raise RuntimeError(
                "Não foi possível analisar o vídeo com FFprobe."
            )

        audio_codec = probe.stdout.strip()

        if not audio_codec:
            raise RuntimeError(
                "Este vídeo não possui uma faixa de áudio. "
                "Envie um vídeo que tenha som."
            )

        print(
            f"[FFMPEG] Codec de áudio detectado: {audio_codec}",
            flush=True
        )

        # Extrai o áudio para WAV compatível com Whisper
        result = subprocess.run(
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
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        if result.returncode != 0:
            raise RuntimeError(
                "FFmpeg não conseguiu extrair o áudio:\n"
                + result.stderr[-5000:]
            )

        if not os.path.exists(audio_path):
            raise RuntimeError(
                "O arquivo de áudio não foi criado."
            )

        if os.path.getsize(audio_path) == 0:
            raise RuntimeError(
                "O arquivo de áudio criado está vazio."
            )

        print(
            "[FFMPEG] Áudio extraído com sucesso.",
            flush=True
        )

    except Exception:

        print(
            "[AUDIO ERROR]",
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        raise

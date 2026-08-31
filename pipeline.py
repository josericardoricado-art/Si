def transcribe(audio_path):

    log_stage("Carregando Whisper")
    log_progress(15)

    try:
        import whisper
        import torch

        model_name = os.getenv("WHISPER_MODEL", "base")

        log_stage(
            f"Carregando modelo Whisper: {model_name}"
        )

        print(
            f"[WHISPER] Modelo: {model_name}",
            flush=True
        )

        model = whisper.load_model(
            model_name,
            device="cpu"
        )

        log_stage("Transcrevendo áudio")
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

        if not text:
            raise RuntimeError(
                "Whisper não conseguiu reconhecer fala no áudio."
            )

        log_progress(40)

        return text, detected_language

    except Exception as error:

        print(
            "[WHISPER ERROR]",
            file=sys.stderr
        )

        traceback.print_exc(
            file=sys.stderr
        )

        raise

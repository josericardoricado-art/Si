#!/usr/bin/env python3

import sys
import traceback


# ============================================================
# INSTALAÇÃO DOS MODELOS ARGOS
# ============================================================

LANGUAGE_PAIRS = [
    ("pt", "en"),
    ("en", "pt"),

    ("pt", "es"),
    ("es", "pt"),

    ("en", "es"),
    ("es", "en"),
]


def log(message):
    print(
        f"[ARGOS INSTALL] {message}",
        flush=True
    )


def main():

    log("========================================")
    log("INSTALAÇÃO DOS MODELOS ARGOS")
    log("========================================")

    try:
        import argostranslate.package
        import argostranslate.translate

    except ImportError as exc:

        log(
            "ERRO: argostranslate não está instalado."
        )

        traceback.print_exc()

        return 1

    # ========================================================
    # ATUALIZAR ÍNDICE DE PACOTES
    # ========================================================

    log(
        "Atualizando lista de pacotes Argos..."
    )

    try:

        argostranslate.package.update_package_index()

    except Exception as exc:

        log(
            "Não foi possível atualizar o índice:"
        )

        log(
            str(exc)
        )

        return 1

    # ========================================================
    # PACOTES DISPONÍVEIS
    # ========================================================

    try:

        available_packages = (
            argostranslate.package.get_available_packages()
        )

    except Exception as exc:

        log(
            "Erro obtendo pacotes disponíveis:"
        )

        log(
            str(exc)
        )

        return 1

    # ========================================================
    # INSTALAR CADA DIREÇÃO
    # ========================================================

    for source_lang, target_lang in LANGUAGE_PAIRS:

        log(
            "----------------------------------------"
        )

        log(
            f"Procurando: {source_lang} -> {target_lang}"
        )

        # ----------------------------------------------------
        # Verificar se já existe
        # ----------------------------------------------------

        try:

            installed_languages = (
                argostranslate.translate
                .get_installed_languages()
            )

            source = next(
                (
                    language
                    for language
                    in installed_languages
                    if language.code == source_lang
                ),
                None
            )

            target = next(
                (
                    language
                    for language
                    in installed_languages
                    if language.code == target_lang
                ),
                None
            )

            if source and target:

                try:

                    translation = (
                        source.get_translation(
                            target
                        )
                    )

                    if translation:

                        log(
                            f"Já instalado: "
                            f"{source_lang} -> {target_lang}"
                        )

                        continue

                except Exception:
                    pass

        except Exception:
            pass

        # ----------------------------------------------------
        # Procurar pacote
        # ----------------------------------------------------

        package = None

        for candidate in available_packages:

            candidate_from = getattr(
                candidate,
                "from_code",
                None
            )

            candidate_to = getattr(
                candidate,
                "to_code",
                None
            )

            if (
                candidate_from == source_lang
                and
                candidate_to == target_lang
            ):

                package = candidate

                break

        if package is None:

            log(
                f"AVISO: pacote não encontrado "
                f"{source_lang} -> {target_lang}"
            )

            continue

        # ----------------------------------------------------
        # Instalar
        # ----------------------------------------------------

        try:

            log(
                f"Instalando "
                f"{source_lang} -> {target_lang}..."
            )

            download_path = (
                package.download()
            )

            argostranslate.package.install_from_path(
                download_path
            )

            log(
                f"OK: {source_lang} -> {target_lang}"
            )

        except Exception as exc:

            log(
                f"ERRO instalando "
                f"{source_lang} -> {target_lang}:"
            )

            log(
                str(exc)
            )

    # ========================================================
    # VERIFICAÇÃO FINAL
    # ========================================================

    log(
        "========================================"
    )

    log(
        "VERIFICANDO IDIOMAS INSTALADOS"
    )

    log(
        "========================================"
    )

    try:

        installed_languages = (
            argostranslate.translate
            .get_installed_languages()
        )

        for language in installed_languages:

            log(
                f"Idioma: {language.code} "
                f"({language.name})"
            )

    except Exception as exc:

        log(
            "Erro verificando idiomas:"
        )

        log(
            str(exc)
        )

    # ========================================================
    # VERIFICAR DIREÇÕES
    # ========================================================

    log(
        "========================================"
    )

    log(
        "VERIFICANDO TRADUÇÕES"
    )

    log(
        "========================================"
    )

    try:

        installed_languages = (
            argostranslate.translate
            .get_installed_languages()
        )

        language_map = {
            language.code: language
            for language in installed_languages
        }

        for source_lang, target_lang in LANGUAGE_PAIRS:

            source = language_map.get(
                source_lang
            )

            target = language_map.get(
                target_lang
            )

            if not source or not target:

                log(
                    f"FALTANDO: "
                    f"{source_lang} -> {target_lang}"
                )

                continue

            try:

                translation = (
                    source.get_translation(
                        target
                    )
                )

                if translation:

                    log(
                        f"OK: "
                        f"{source_lang} -> {target_lang}"
                    )

                else:

                    log(
                        f"FALTANDO: "
                        f"{source_lang} -> {target_lang}"
                    )

            except Exception:

                log(
                    f"FALTANDO: "
                    f"{source_lang} -> {target_lang}"
                )

    except Exception as exc:

        log(
            "Erro na verificação:"
        )

        log(
            str(exc)
        )

    log(
        "========================================"
    )

    log(
        "INSTALAÇÃO DO ARGOS FINALIZADA"
    )

    log(
        "========================================"
    )

    return 0


if __name__ == "__main__":

    try:

        sys.exit(
            main()
        )

    except Exception as exc:

        log(
            "ERRO FATAL:"
        )

        log(
            str(exc)
        )

        traceback.print_exc()

        sys.exit(1)

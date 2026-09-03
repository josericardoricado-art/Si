import os
import sys
import traceback

import argostranslate.package
import argostranslate.translate


LANGUAGES = {
    "pt": "Português",
    "en": "English",
    "es": "Español",
}

REQUIRED_PAIRS = [
    ("pt", "en"),
    ("en", "pt"),

    ("pt", "es"),
    ("es", "pt"),

    ("en", "es"),
    ("es", "en"),
]


def log(message):
    print(f"[ARGOS] {message}", flush=True)


def get_installed_pairs():
    installed = set()

    try:
        languages = argostranslate.translate.get_installed_languages()

        for language in languages:
            for translation in language.translations_from:
                installed.add(
                    (
                        translation.from_lang.code,
                        translation.to_lang.code,
                    )
                )

    except Exception as e:
        log(f"Erro verificando idiomas instalados: {e}")

    return installed


def install_pair(from_code, to_code, available_packages):
    log(
        f"Procurando pacote "
        f"{from_code} -> {to_code}"
    )

    package = None

    for item in available_packages:
        if (
            item.from_code == from_code
            and item.to_code == to_code
        ):
            package = item
            break

    if package is None:
        raise RuntimeError(
            f"Pacote Argos não encontrado: "
            f"{from_code} -> {to_code}"
        )

    log(
        f"Baixando pacote "
        f"{from_code} -> {to_code}"
    )

    package_path = package.download()

    if not package_path:
        raise RuntimeError(
            f"Download falhou: "
            f"{from_code} -> {to_code}"
        )

    log(
        f"Instalando pacote "
        f"{from_code} -> {to_code}"
    )

    argostranslate.package.install_from_path(
        package_path
    )

    log(
        f"OK: "
        f"{from_code} -> {to_code}"
    )


def main():
    log("========================================")
    log("INSTALAÇÃO DOS IDIOMAS ARGOS")
    log("========================================")

    log("Atualizando índice de pacotes...")

    argostranslate.package.update_package_index()

    available_packages = (
        argostranslate.package.get_available_packages()
    )

    log(
        f"Pacotes disponíveis: "
        f"{len(available_packages)}"
    )

    installed_pairs = get_installed_pairs()

    log(
        f"Pacotes de tradução já instalados: "
        f"{len(installed_pairs)}"
    )

    for from_code, to_code in REQUIRED_PAIRS:

        pair = (from_code, to_code)

        if pair in installed_pairs:
            log(
                f"Já instalado: "
                f"{from_code} -> {to_code}"
            )
            continue

        try:
            install_pair(
                from_code,
                to_code,
                available_packages,
            )

        except Exception as e:
            log(
                f"ERRO instalando "
                f"{from_code} -> {to_code}: {e}"
            )

            traceback.print_exc()

            # Não escondemos o erro.
            # O build precisa avisar caso o pacote
            # realmente não possa ser instalado.
            raise

    log("")
    log("========================================")
    log("VERIFICANDO INSTALAÇÃO")
    log("========================================")

    final_pairs = get_installed_pairs()

    for from_code, to_code in REQUIRED_PAIRS:
        if (from_code, to_code) in final_pairs:
            log(
                f"OK {from_code} -> {to_code}"
            )
        else:
            log(
                f"FALTA {from_code} -> {to_code}"
            )

    log("")
    log("========================================")
    log("ARGOS CONFIGURADO")
    log("========================================")


if __name__ == "__main__":
    main()

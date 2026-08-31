#!/usr/bin/env python3

"""
install_languages.py

Instala os pacotes de idiomas necessários
para o Argos Translate.

Idiomas utilizados pelo Si:

pt = Português
en = Inglês
es = Espanhol

O script pode ser executado durante a configuração
do servidor ou manualmente:

    python3 install_languages.py
"""

import sys

import argostranslate.package
import argostranslate.translate


# ==========================================================
# IDIOMAS DO SI
# ==========================================================

LANGUAGES = [
    "en",
    "pt",
    "es"
]


# ==========================================================
# MOSTRAR IDIOMAS INSTALADOS
# ==========================================================

def get_installed_codes():

    installed = (
        argostranslate.translate
        .get_installed_languages()
    )

    return {
        language.code
        for language in installed
    }


# ==========================================================
# BAIXAR LISTA DE PACOTES
# ==========================================================

def update_package_index():

    print(
        "Atualizando lista de pacotes do Argos...",
        flush=True
    )

    argostranslate.package.update_package_index()

    print(
        "Lista de pacotes atualizada.",
        flush=True
    )


# ==========================================================
# ENCONTRAR PACOTE DE TRADUÇÃO
# ==========================================================

def find_package(
    from_code,
    to_code
):

    packages = (
        argostranslate.package
        .get_available_packages()
    )

    for package in packages:

        if (
            package.from_code == from_code
            and
            package.to_code == to_code
        ):

            return package

    return None


# ==========================================================
# INSTALAR UM PAR
# ==========================================================

def install_pair(
    from_code,
    to_code
):

    print(
        f"Procurando tradução: "
        f"{from_code} -> {to_code}",
        flush=True
    )

    package = find_package(
        from_code,
        to_code
    )

    if package is None:

        print(
            f"Pacote não encontrado: "
            f"{from_code}->{to_code}",
            flush=True
        )

        return False


    print(
        f"Baixando pacote "
        f"{from_code}->{to_code}...",
        flush=True
    )


    package_path = (
        package.download()
    )


    print(
        f"Instalando pacote "
        f"{from_code}->{to_code}...",
        flush=True
    )


    argostranslate.package.install_from_path(
        package_path
    )


    print(
        f"Instalado: "
        f"{from_code}->{to_code}",
        flush=True
    )


    return True


# ==========================================================
# INSTALAR OS PARES
# ==========================================================

def install_languages():

    print(
        "==========================================",
        flush=True
    )

    print(
        "     SI - INSTALADOR DE IDIOMAS",
        flush=True
    )

    print(
        "==========================================",
        flush=True
    )


    update_package_index()


    installed_codes =
        get_installed_codes()


    print(
        "Idiomas atualmente instalados:",
        sorted(installed_codes),
        flush=True
    )


    # ======================================================
    # PARES NECESSÁRIOS
    # ======================================================

    pairs = [

        ("en", "pt"),
        ("pt", "en"),

        ("es", "pt"),
        ("pt", "es"),

        ("en", "es"),
        ("es", "en")

    ]


    success = 0
    failed = 0


    for (
        from_code,
        to_code
    ) in pairs:

        try:

            result = install_pair(
                from_code,
                to_code
            )


            if result:

                success += 1

            else:

                failed += 1


        except Exception as error:

            failed += 1

            print(
                f"Erro instalando "
                f"{from_code}->{to_code}: "
                f"{error}",
                file=sys.stderr,
                flush=True
            )


    # ======================================================
    # RESULTADO
    # ======================================================

    print(
        "==========================================",
        flush=True
    )

    print(
        "INSTALAÇÃO FINALIZADA",
        flush=True
    )

    print(
        f"Pares instalados: {success}",
        flush=True
    )

    print(
        f"Pares com erro: {failed}",
        flush=True
    )

    print(
        "==========================================",
        flush=True
    )


    # Mostrar idiomas novamente

    installed_codes =
        get_installed_codes()


    print(
        "Idiomas disponíveis:",
        sorted(installed_codes),
        flush=True
    )


    if failed > 0:

        print(
            "ATENÇÃO: alguns pares de idiomas "
            "não puderam ser instalados.",
            flush=True
        )


# ==========================================================
# MAIN
# ==========================================================

def main():

    try:

        install_languages()

    except Exception as error:

        print(
            "ERRO:",
            error,
            file=sys.stderr,
            flush=True
        )

        sys.exit(1)


if __name__ == "__main__":

    main()

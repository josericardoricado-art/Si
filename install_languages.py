#!/usr/bin/env python3
"""
install_languages.py
Baixa e instala os pacotes de traducao do Argos Translate.
Rode isso UMA VEZ na configuracao do servidor (precisa de internet nesse momento,
mas depois o pipeline roda 100% offline).

Uso:
    python3 install_languages.py
"""

import argostranslate.package

PARES_DE_IDIOMA = [
    ("en", "pt"),
    ("es", "pt"),
    ("pt", "en"),
    # adicione outros pares conforme sua necessidade, ex: ("fr", "pt")
]

def main():
    print("Atualizando indice de pacotes do Argos Translate...")
    argostranslate.package.update_package_index()
    available_packages = argostranslate.package.get_available_packages()

    for from_code, to_code in PARES_DE_IDIOMA:
        pkg = next(
            (p for p in available_packages if p.from_code == from_code and p.to_code == to_code),
            None,
        )
        if pkg is None:
            print(f"  [aviso] pacote {from_code}->{to_code} nao encontrado, pulando.")
            continue
        print(f"  instalando {from_code} -> {to_code} ...")
        argostranslate.package.install_from_path(pkg.download())

    print("Pronto! Idiomas instalados.")

if __name__ == "__main__":
    main()

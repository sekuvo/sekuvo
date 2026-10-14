#!/usr/bin/env python3
"""iOS kodunda çağrılan her metin anahtarı katalogda var mı?

    python3 ios/araclar/anahtar_denetle.py

Eksik anahtar derlemede hata vermez, ekranda anahtarın kendisi ("info_x")
görünür. Bu betik onu derlemeden önce yakalar; eksik varsa 1 ile çıkar.
"""
import json
import pathlib
import re
import sys

KOK = pathlib.Path(__file__).resolve().parents[1]
katalog = json.loads((KOK / "Sekuvo/Localizable.xcstrings").read_text(encoding="utf-8"))["strings"]
desen = re.compile(r'\bLp?\("([a-z][a-z0-9_]*)"')
eksik = {}
for dosya in sorted((KOK / "Sekuvo").rglob("*.swift")):
    for no, satir in enumerate(dosya.read_text(encoding="utf-8").splitlines(), 1):
        for anahtar in desen.findall(satir):
            if anahtar not in katalog:
                eksik.setdefault(anahtar, []).append(f"{dosya.relative_to(KOK)}:{no}")
for anahtar, yerler in sorted(eksik.items()):
    print(f"EKSİK {anahtar}  ←  {', '.join(yerler)}")
print(f"{'eksik anahtar yok' if not eksik else f'{len(eksik)} eksik anahtar'}")
sys.exit(1 if eksik else 0)

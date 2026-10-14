#!/usr/bin/env python3
"""Android çevirilerini iOS String Catalog'una aktarır.

    python3 ios/araclar/ceviri_aktar.py

Kaynak: app/src/main/res/values*/strings.xml (16 dil) + ios/Ceviriler/yalniz-ios.json
Hedef : ios/Sekuvo/Localizable.xcstrings, ios/Sekuvo/InfoPlist.xcstrings

iOS uygulaması metinlerini Android'in anahtarlarıyla çağırır
(`L("home_search_hint")`). Böylece bir metin tek yerde, Android'de çevrilir ve
bu betikle iOS'a geçer. Yalnız iOS'ta anlamlı olan metinler (Face ID, iOS
izin açıklamaları) `yalniz-ios.json`'da durur; anahtar çakışırsa betik durur.

Dönüşümler:
  %1$s → %1$@      %1$d → %1$lld      (sıralı belirteçler korunur)
  \\' \\" \\n \\t \\@ \\? \\\\ ve \\uXXXX çözülür; çevreleyen tırnak atılır
  <plurals> → plural varyasyonları (one / other …)
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

KOK = pathlib.Path(__file__).resolve().parents[2]
RES = KOK / "app/src/main/res"
HEDEF = KOK / "ios/Sekuvo/Localizable.xcstrings"
IOS_YALNIZ = KOK / "ios/Ceviriler/yalniz-ios.json"

# Android klasörü → iOS dil kodu. Android'deki pt Brezilya Portekizcesidir.
DILLER = {
    "": "en", "tr": "tr", "es": "es", "hi": "hi", "ar": "ar", "zh": "zh-Hans", "fr": "fr",
    "bn": "bn", "pt": "pt-BR", "ru": "ru", "ur": "ur", "id": "id", "de": "de", "ja": "ja",
    "pcm": "pcm", "vi": "vi",
}


def coz(ham):
    """Android kaynak kaçışlarını çözer."""
    s = ham.strip()
    if len(s) >= 2 and s[0] == s[-1] == '"':
        s = s[1:-1]
    cikti, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", s[i + 2:i + 6] or ""):
                cikti.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            cikti.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
            continue
        cikti.append(c)
        i += 1
    return "".join(cikti)


def ios_bicim(s):
    """Android biçim belirteçlerini Foundation'ınkine çevirir."""
    s = re.sub(r"%(\d+)\$s", r"%\1$@", s)
    s = re.sub(r"%(\d+)\$d", r"%\1$lld", s)
    s = re.sub(r"%s", "%@", s)
    s = re.sub(r"%d", "%lld", s)
    return s


def oku(klasor):
    yol = RES / ("values" if not klasor else f"values-{klasor}") / "strings.xml"
    kok = ET.parse(yol).getroot()
    tekil, cogul = {}, {}
    for el in kok:
        ad = el.get("name")
        if el.get("translatable") == "false":
            continue
        if el.tag == "string":
            tekil[ad] = ios_bicim(coz("".join(el.itertext())))
        elif el.tag == "plurals":
            cogul[ad] = {it.get("quantity"): ios_bicim(coz("".join(it.itertext()))) for it in el}
    return tekil, cogul


def birim(deger):
    return {"stringUnit": {"state": "translated", "value": deger}}


def main():
    katalog = {}
    for klasor, dil in DILLER.items():
        tekil, cogul = oku(klasor)
        for ad, deger in tekil.items():
            katalog.setdefault(ad, {"extractionState": "manual", "localizations": {}})
            katalog[ad]["localizations"][dil] = birim(deger)
        for ad, bicimler in cogul.items():
            katalog.setdefault(ad, {"extractionState": "manual", "localizations": {}})
            katalog[ad]["localizations"][dil] = {
                "variations": {"plural": {q: birim(v) for q, v in bicimler.items()}}
            }

    android_sayisi = len(katalog)
    yalniz = json.loads(IOS_YALNIZ.read_text(encoding="utf-8")) if IOS_YALNIZ.exists() else {}
    for ad, ceviriler in yalniz.get("metinler", {}).items():
        if ad in katalog:
            sys.exit(f"çakışma: '{ad}' hem Android'de hem yalniz-ios.json'da")
        katalog[ad] = {"extractionState": "manual", "localizations": {
            d: ({"variations": {"plural": {q: birim(x) for q, x in v.items()}}} if isinstance(v, dict) else birim(v))
            for d, v in ceviriler.items()}}

    HEDEF.parent.mkdir(parents=True, exist_ok=True)
    # Info.plist açıklamaları (kamera, Face ID) ayrı katalogda durur.
    plist = {ad: {"extractionState": "manual", "localizations": {d: birim(v) for d, v in c.items()}}
             for ad, c in yalniz.get("infoplist", {}).items()}
    (HEDEF.parent / "InfoPlist.xcstrings").write_text(
        json.dumps({"sourceLanguage": "en", "strings": dict(sorted(plist.items())), "version": "1.0"},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    HEDEF.parent.mkdir(parents=True, exist_ok=True)
    HEDEF.write_text(json.dumps({"sourceLanguage": "en", "strings": dict(sorted(katalog.items())),
                                 "version": "1.0"}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    eksik = {dil: sum(1 for k in katalog.values() if dil not in k["localizations"]) for dil in DILLER.values()}
    print(f"{HEDEF.relative_to(KOK)}: {android_sayisi} Android + {len(katalog) - android_sayisi} iOS metni, "
          f"{len(DILLER)} dil" + ("" if not any(eksik.values()) else f" · eksik: { {d: n for d, n in eksik.items() if n} }"))


if __name__ == "__main__":
    main()

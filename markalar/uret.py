#!/usr/bin/env python3
"""Kurum logoları: katalog.json'dan iki platformun dosyalarını üretir.

Kullanım:
    python3 markalar/uret.py --si <simple-icons paket klasörü>   # logoları tazele + üret
    python3 markalar/uret.py                                     # yalnız üret (svg/ hazırsa)

--si: `npm pack simple-icons@<sürüm>` ile indirilip açılmış paketin `package/`
klasörü. Logoların yol verisi oradan `markalar/svg/`ye kopyalanır; sürüm ve
her ikonun lisans bilgisi `markalar/kaynak.json`a yazılır. Uygulamanın internet
izni yok: logolar uygulamanın içine gömülür, hiçbir zaman indirilmez.

Üretilenler (elle düzenlenmez, her koşumda baştan yazılır):
    app/src/main/res/drawable/marka_<id>.xml           Android vektör çizimleri
    app/src/main/java/com/afgover/vault/core/BrandCatalog.kt
    app/src/main/java/com/afgover/vault/ui/BrandLogos.kt
    ios/Sekuvo/Assets.xcassets/Markalar/marka-<id>.imageset/
    ios/SekuvoCore/Sources/SekuvoCore/BrandCatalog.swift
"""
import argparse
import json
import os
import re
import shutil
import sys

KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BURA = os.path.join(KOK, "markalar")
SVG = os.path.join(BURA, "svg")
DRAWABLE = os.path.join(KOK, "app/src/main/res/drawable")
KT_KATALOG = os.path.join(KOK, "app/src/main/java/com/afgover/vault/core/BrandCatalog.kt")
KT_LOGO = os.path.join(KOK, "app/src/main/java/com/afgover/vault/ui/BrandLogos.kt")
IOS_ASSET = os.path.join(KOK, "ios/Sekuvo/Assets.xcassets/Markalar")
SW_KATALOG = os.path.join(KOK, "ios/SekuvoCore/Sources/SekuvoCore/BrandCatalog.swift")

UYARI = "Bu dosya markalar/uret.py tarafından üretildi; elle düzenleme. Kaynak: markalar/katalog.json"

# ── SVG yol verisi: sıkıştırılmış yazımı açık yazıma çevir ────────────────
# Simple Icons yolları en kısa yazımı kullanır: yay komutunda bayraklar
# ayırıcısız gelir ("a1 1 0 01.5.5" = rx 1, ry 1, dönüş 0, bayraklar 0 ve 1,
# x .5, y .5). Bazı ayrıştırıcılar bunu yanlış okur; her parametre boşlukla
# ayrılmış biçimde yeniden yazılır. Geometri değişmez.
SAYI = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
PARAM = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def yolu_ac(d):
    i, n, cikti, komut = 0, len(d), [], None
    def bosluk():
        nonlocal i
        while i < n and (d[i].isspace() or d[i] == ","):
            i += 1
    def sayi():
        nonlocal i
        bosluk()
        m = SAYI.match(d, i)
        if not m:
            raise ValueError(f"sayı bekleniyordu: {d[i:i + 20]!r}")
        i = m.end()
        return m.group(0)
    def bayrak():
        nonlocal i
        bosluk()
        if i >= n or d[i] not in "01":
            raise ValueError(f"yay bayrağı bekleniyordu: {d[i:i + 20]!r}")
        i += 1
        return d[i - 1]
    while True:
        bosluk()
        if i >= n:
            break
        if d[i].isalpha():
            komut = d[i]
            i += 1
            if komut.upper() == "Z":
                cikti.append(komut)
                continue
        elif komut is None:
            raise ValueError("yol komutla başlamıyor")
        k = komut.upper()
        if k == "A":
            p = [sayi(), sayi(), sayi(), bayrak(), bayrak(), sayi(), sayi()]
        else:
            p = [sayi() for _ in range(PARAM[k])]
        cikti.append(komut + " " + " ".join(p))
        # M'den sonraki örtük çiftler L/l sayılır
        if komut == "M":
            komut = "L"
        elif komut == "m":
            komut = "l"
    return " ".join(cikti)


def svg_yolu(metin):
    yollar = re.findall(r'<path[^>]*\sd="([^"]+)"', metin)
    if len(yollar) != 1:
        raise ValueError(f"tek <path> bekleniyordu, {len(yollar)} bulundu")
    return yollar[0]


def renk(hexa):
    h = hexa.lstrip("#").upper()
    if not re.fullmatch(r"[0-9A-F]{6}", h):
        raise ValueError(f"geçersiz renk: {hexa}")
    return h


def kotlin_metin(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def swift_metin(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--si", help="simple-icons paketinin package/ klasörü")
    args = ap.parse_args()

    katalog = json.load(open(os.path.join(BURA, "katalog.json"), encoding="utf-8"))["markalar"]
    os.makedirs(SVG, exist_ok=True)

    if args.si:
        veri = json.load(open(os.path.join(args.si, "data", "simple-icons.json"), encoding="utf-8"))
        surum = json.load(open(os.path.join(args.si, "package.json"), encoding="utf-8"))["version"]
        sozluk = {ic["slug"]: ic for ic in veri}
        kaynak = {"simple-icons": surum, "lisans": "CC0-1.0 (paket); ikon başına aşağıda", "ikonlar": {}}
        for f in os.listdir(SVG):
            os.remove(os.path.join(SVG, f))
        for m in katalog:
            if not m["si"]:
                continue
            ic = sozluk.get(m["si"])
            if ic is None:
                sys.exit(f"{m['id']}: Simple Icons'ta '{m['si']}' yok")
            yol = svg_yolu(open(os.path.join(args.si, "icons", m["si"] + ".svg"), encoding="utf-8").read())
            open(os.path.join(SVG, m["id"] + ".svg"), "w", encoding="utf-8").write(
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="{yol}"/></svg>\n')
            kaynak["ikonlar"][m["id"]] = {
                "slug": m["si"], "baslik": ic["title"], "renk": ic["hex"],
                "kaynak": ic.get("source", ""), "lisans": (ic.get("license") or {}).get("type", "belirtilmemiş"),
            }
        json.dump(kaynak, open(os.path.join(BURA, "kaynak.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    kaynak = json.load(open(os.path.join(BURA, "kaynak.json"), encoding="utf-8"))
    markalar = []
    for m in katalog:
        logo = None
        if m["si"]:
            yol_dosya = os.path.join(SVG, m["id"] + ".svg")
            if not os.path.exists(yol_dosya):
                sys.exit(f"{m['id']}: svg/{m['id']}.svg yok — önce --si ile koş")
            logo = yolu_ac(svg_yolu(open(yol_dosya, encoding="utf-8").read()))
            hexa = m["renk"] or kaynak["ikonlar"][m["id"]]["renk"]
            rozet = ""
        else:
            hexa = m["renk"]
            rozet = m["rozet"]
            if not rozet or len(rozet) > 3:
                sys.exit(f"{m['id']}: rozet 1–3 karakter olmalı")
        if not re.fullmatch(r"[a-z0-9]+", m["id"]):
            sys.exit(f"{m['id']}: kimlik yalnız a-z ve rakam olmalı")
        markalar.append(dict(id=m["id"], ad=m["ad"], renk=renk(hexa), rozet=rozet, logo=logo,
                             alanlar=m["alanlar"], anahtarlar=m["anahtarlar"]))

    # ── Android ──
    for f in os.listdir(DRAWABLE):
        if f.startswith("marka_") and f.endswith(".xml"):
            os.remove(os.path.join(DRAWABLE, f))
    for m in markalar:
        if m["logo"]:
            open(os.path.join(DRAWABLE, f"marka_{m['id']}.xml"), "w", encoding="utf-8").write(
                f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {UYARI} -->\n'
                f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                f'    android:width="24dp" android:height="24dp"\n'
                f'    android:viewportWidth="24" android:viewportHeight="24">\n'
                f'    <path android:fillColor="#FFFFFFFF" android:pathData="{m["logo"]}" />\n</vector>\n')
    satirlar = []
    for m in markalar:
        satirlar.append(
            f'    Brand({kotlin_metin(m["id"])}, {kotlin_metin(m["ad"])}, 0xFF{m["renk"]}.toInt(), '
            f'{kotlin_metin(m["rozet"])}, {"true" if m["logo"] else "false"},\n'
            f'        listOf({", ".join(kotlin_metin(a) for a in m["alanlar"])}),\n'
            f'        listOf({", ".join(kotlin_metin(a) for a in m["anahtarlar"])})),')
    open(KT_KATALOG, "w", encoding="utf-8").write(
        f"// {UYARI}\npackage com.afgover.vault.core\n\n"
        f"/** Kurum logoları kataloğu ({len(markalar)} marka). Eşleşme kuralı: [Brands]. */\n"
        f"internal val BRAND_CATALOG: List<Brand> = listOf(\n" + "\n".join(satirlar) + "\n)\n")
    dallar = "\n".join(f'    "{m["id"]}" -> R.drawable.marka_{m["id"]}' for m in markalar if m["logo"])
    open(KT_LOGO, "w", encoding="utf-8").write(
        f"// {UYARI}\npackage com.afgover.vault.ui\n\nimport com.afgover.vault.R\n\n"
        f"/** Markanın logo çizimi; logosu yoksa (rozetli marka) 0. */\n"
        f"fun brandLogoRes(id: String): Int = when (id) {{\n{dallar}\n    else -> 0\n}}\n")

    # ── iOS ──
    if os.path.isdir(IOS_ASSET):
        shutil.rmtree(IOS_ASSET)
    os.makedirs(IOS_ASSET)
    json.dump({"info": {"author": "xcode", "version": 1}, "properties": {"provides-namespace": True}},
              open(os.path.join(IOS_ASSET, "Contents.json"), "w"), indent=2)
    for m in markalar:
        if not m["logo"]:
            continue
        kume = os.path.join(IOS_ASSET, f"marka-{m['id']}.imageset")
        os.makedirs(kume)
        open(os.path.join(kume, f"marka-{m['id']}.svg"), "w", encoding="utf-8").write(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">'
            f'<path fill="#000" d="{m["logo"]}"/></svg>\n')
        json.dump({"images": [{"filename": f"marka-{m['id']}.svg", "idiom": "universal"}],
                   "info": {"author": "xcode", "version": 1},
                   "properties": {"preserves-vector-representation": True, "template-rendering-intent": "template"}},
                  open(os.path.join(kume, "Contents.json"), "w"), indent=2)
    satirlar = []
    for m in markalar:
        satirlar.append(
            f'        Brand(id: {swift_metin(m["id"])}, name: {swift_metin(m["ad"])}, color: 0x{m["renk"]}, '
            f'badge: {swift_metin(m["rozet"])}, hasLogo: {"true" if m["logo"] else "false"},\n'
            f'              domains: [{", ".join(swift_metin(a) for a in m["alanlar"])}],\n'
            f'              keywords: [{", ".join(swift_metin(a) for a in m["anahtarlar"])}]),')
    open(SW_KATALOG, "w", encoding="utf-8").write(
        f"// {UYARI}\n\nextension Brands {{\n"
        f"    /// Kurum logoları kataloğu ({len(markalar)} marka). Eşleşme kuralı: `Brands.match`.\n"
        f"    public static let catalog: [Brand] = [\n" + "\n".join(satirlar) + "\n    ]\n}\n")

    print(f"{len(markalar)} marka: {sum(1 for m in markalar if m['logo'])} logo, "
          f"{sum(1 for m in markalar if not m['logo'])} rozet")


if __name__ == "__main__":
    main()

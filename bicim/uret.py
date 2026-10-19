#!/usr/bin/env python3
"""Sekuvo'nun ortak biçim test değerlerini üretir → bicim/vektorler/*.json

Android (JVM testi `OrtakBicimTest`) ve iOS (`SekuvoCore` testleri) AYNI
dosyaları okur. Bir platformun yazdığı `.vaultbak` ya da QR zarfının diğerinde
bayt bayt açılmasının güvencesi budur: iki uygulama da bu değerlerle sınanır,
üçüncü bir uygulama (burada Python `cryptography`) da onları üretir.

Tuzlar ve IV'ler sabittir; çıktı her çalıştırmada birebir aynıdır. git'te bir
fark görünüyorsa biçim değişmiş demektir — bilerek değilse dur.

    pip install cryptography
    python3 bicim/uret.py
"""
import base64
import hashlib
import json
import pathlib
import zlib

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

KOK = pathlib.Path(__file__).parent
HEDEF = KOK / "vektorler"
TUR = 310_000          # 1.3'ten önceki yazıcıların ve araçların tur sayısı
YENI_TUR = 600_000     # uygulamaların 1.3'ten beri yazdığı tur sayısı
QR_ONEK = "VLT1"
QR_PARCA = 1200        # uygulamadaki QrTransfer.CHUNK, aktar.html ve vault-clip ile aynı


def b64(b):
    return base64.b64encode(b).decode("ascii")


def sira(bas, n):
    """Sabit, okunabilir test baytları: bas, bas+1, …"""
    return bytes((bas + i) % 256 for i in range(n))


def kdf(parola, tuz, tur):
    # Parola UTF-8 baytlarıyla türetilir. ASCII dışı parolada Android
    # çalışma zamanının dönüşümü cihazda ölçülene kadar değerler
    # yalnız ASCII parola taşır.
    return hashlib.pbkdf2_hmac("sha256", parola.encode("utf-8"), tuz, tur, 32)


def muhurle(anahtar, iv, acik):
    """iv(12) || şifreli metin || etiket(16) — uygulamanın Crypto.encrypt çıktısı."""
    return iv + AESGCM(anahtar).encrypt(iv, acik, None)


def zarf(parola, icerik_metni, tuz, iv, tur=TUR):
    anahtar = kdf(parola, tuz, tur)
    return {
        "app": "vault",
        "version": 1,
        "kdf": {"algo": "PBKDF2WithHmacSHA256", "iterations": tur, "salt": b64(tuz)},
        "cipher": "AES-256-GCM",
        "data": b64(muhurle(anahtar, iv, icerik_metni.encode("utf-8"))),
    }


def qr_kareleri(zarf_metni):
    """tools/vault-clip.py ve aktar.html'deki üretimin aynısı."""
    ham = zarf_metni.encode("utf-8")
    s = zlib.compressobj(9, zlib.DEFLATED, -15)
    sikisik = s.compress(ham) + s.flush()
    if len(sikisik) < len(ham):
        bayrak, veri = "Z", b64(sikisik)
    else:
        bayrak, veri = "P", b64(ham)
    parcalar = [veri[i:i + QR_PARCA] for i in range(0, len(veri), QR_PARCA)] or [""]
    n = len(parcalar)
    return [f"{QR_ONEK}|{i + 1}/{n}|{bayrak}|{p}" for i, p in enumerate(parcalar)]


def yaz(ad, veri):
    HEDEF.mkdir(exist_ok=True)
    (HEDEF / ad).write_text(json.dumps(veri, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"bicim/vektorler/{ad}")


# ── 1. Anahtar türetme ───────────────────────────────────────────────────────

KDF = [
    {"parola": "correct horse battery staple", "tuz": sira(0, 16), "tur": TUR},
    {"parola": "yedek-parolasi-123", "tuz": sira(16, 16), "tur": TUR},
    {"parola": "Kasa!2026", "tuz": sira(32, 16), "tur": 10_000},
    {"parola": "Uzun bir yedek cumlesi 2026", "tuz": sira(48, 16), "tur": YENI_TUR},
]

# ── 2. AES-256-GCM ───────────────────────────────────────────────────────────

GCM = [
    {"aciklama": "kısa ASCII", "acik": "Sekuvo"},
    {"aciklama": "boş içerik — yalnız etiket", "acik": ""},
    {"aciklama": "UTF-8 ve emoji", "acik": "Şifre: çok-gizli 🔐 · ğüşıöç"},
]

# ── 3. Yedek içeriği: her tür, her alan, bilinmeyen değerler ────────────────

UZUN_ANAHTAR = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "\n".join(
    b64(sira(i * 7, 52))[:70] for i in range(40)
) + "\n-----END OPENSSH PRIVATE KEY-----"

TAM_ICERIK = {
    "entries": [
        {
            "type": "LOGIN", "title": "GitHub",
            "createdAt": 1760000000000, "updatedAt": 1760400000000,
            "quick": True, "sortIndex": 2,
            "data": {
                "username": "deniz@example.com",
                "password": "g7#Tq9!vLm2@xPz4",
                "url": "github.com",
                "custom": [
                    {"label": "Kurtarma PIN", "value": "482915", "type": "password"},
                    {"label": "Not alanı", "value": "düz metin, türsüz"},
                    {"label": "Gelecek tür", "value": "x", "type": "hologram"},
                ],
                "passwordChangedAt": 1760300000000,
                "passwordHistory": [
                    {"value": "eski-parola-2", "changedAt": 1760300000000},
                    {"value": "eski-parola-1", "changedAt": 1755000000000},
                ],
            },
            "tags": ["iş"],
        },
        {
            "type": "EVERYDAY", "title": "Kimlik ve adres",
            "createdAt": 1750000000000, "updatedAt": 1750000000000,
            "quick": False, "sortIndex": 0,
            "data": {
                "fullName": "Deniz Yılmaz",
                "phone": "+1 202 555 0147",
                "email": "deniz@example.com",
                "address": "Demo Sok. No: 7\nKadıköy / İstanbul",
            },
        },
        {
            "type": "CARD", "title": "Visa · günlük",
            "createdAt": 1751000000000, "updatedAt": 1752000000000,
            "quick": False, "sortIndex": 1,
            "data": {
                "cardholder": "DENIZ YILMAZ",
                "cardNumber": "4242424242424242",
                "expiry": "0829",
                "cvv": "123",
                "iban": "DE89370400440532013000",
            },
            "tags": ["finans"],
        },
        {
            "type": "NOTE", "title": "Deploy anahtarı (ed25519)", "noteKind": "ANAHTAR",
            "createdAt": 1753000000000, "updatedAt": 1753000000000,
            "quick": False, "sortIndex": 0,
            "data": {
                "notes": UZUN_ANAHTAR,
                "custom": [{"label": "fingerprint", "value": "SHA256:hcm3lXKROlj0"}],
            },
            "tags": ["iş", "finans"],
        },
        {
            "type": "NOTE", "title": "aktar.html", "noteKind": "PARMAK_IZI", "anchor": True,
            "createdAt": 1754000000000, "updatedAt": 1754000000000,
            "quick": False, "sortIndex": 0,
            "data": {"notes": "85ee1729fda96072b36303074f21a170d5361c0577a920ef631bd6884d5b653d"},
        },
        {
            "type": "NOTE", "title": "Bilinmeyen not türü", "noteKind": "YENI_TUR",
            "createdAt": 1754100000000, "updatedAt": 1754100000000,
            "data": {"notes": "eski bir sürüm bu türü tanımaz"},
        },
        {
            "type": "PASSKEY", "title": "Bilinmeyen kayıt türü",
            "createdAt": 1754200000000, "updatedAt": 1754200000000,
            "data": {"notes": "Android bilinmeyen türü Güvenli Not okur"},
        },
    ],
    "tagDefs": [
        {"name": "iş", "color": -11549705, "icon": "work"},       # 0xFF4FC3F7
        {"name": "finans", "color": -18611},                       # 0xFFFFB74D, ikonsuz
        {"name": "ileri", "color": -1739917, "icon": "hologram"},  # bilinmeyen ikon → ""
    ],
}


def beklenen(e):
    """İçe aktarmanın ürettiği kayıt: varsayılanlar doldurulmuş, bilinmeyenler düşürülmüş."""
    tur = e["type"] if e["type"] in ("LOGIN", "EVERYDAY", "CARD", "NOTE") else "NOTE"
    not_turu = e.get("noteKind", "GENEL")
    if not_turu not in ("GENEL", "BETIK", "ANAHTAR", "PARMAK_IZI", "KURTARMA", "YAPILANDIRMA"):
        not_turu = "GENEL"
    d = e.get("data", {})
    alanlar = {k: d.get(k, "") for k in (
        "username", "password", "url", "cardholder", "cardNumber", "expiry", "cvv", "iban",
        "notes", "fullName", "phone", "email", "address")}
    tipler = ("text", "password", "email", "phone", "url", "number", "multiline")
    ozel = [{"label": c["label"], "value": c.get("value", ""),
             "type": c.get("type", "text") if c.get("type", "text") in tipler else "text"}
            for c in d.get("custom", []) if c.get("label")]
    gecmis = [{"value": p["value"], "changedAt": p.get("changedAt", 0)}
              for p in d.get("passwordHistory", []) if p.get("value")]
    return {
        "type": tur, "title": e["title"], "noteKind": not_turu,
        "createdAt": e["createdAt"], "updatedAt": e["updatedAt"],
        "quick": e.get("quick", False), "sortIndex": e.get("sortIndex", 0),
        "anchor": e.get("anchor", False), "tags": e.get("tags", []),
        "data": {**alanlar, "custom": ozel,
                 "passwordChangedAt": d.get("passwordChangedAt", 0), "passwordHistory": gecmis},
    }


IKONLAR = {"work", "home", "person", "group", "bank", "card", "shopping", "mail", "cloud",
           "server", "code", "key", "shield", "wifi", "game", "media", "music", "school",
           "health", "travel"}

# aktar.html / docs/kendi-zarfini-uret.md ile üretilen tek kayıtlık zarfın içeriği
AKTAR_ICERIK = {"entries": [{
    "type": "NOTE", "title": "Server SSH",
    "createdAt": 1760500000000, "updatedAt": 1760500000000, "quick": False,
    "data": {"notes": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA== deploy@example"},
}]}


def main():
    yaz("kdf.json", {
        "aciklama": "PBKDF2-HMAC-SHA256, 32 bayt anahtar; parola UTF-8 baytları.",
        "vektorler": [
            {"parola": v["parola"], "tuz": b64(v["tuz"]), "tur": v["tur"],
             "anahtar_hex": kdf(v["parola"], v["tuz"], v["tur"]).hex()}
            for v in KDF
        ],
    })

    anahtar = sira(0, 32)
    gcm = []
    for i, v in enumerate(GCM):
        iv = sira(100 + i * 12, 12)
        gcm.append({"aciklama": v["aciklama"], "anahtar_hex": anahtar.hex(), "acik": v["acik"],
                    "blob": b64(muhurle(anahtar, iv, v["acik"].encode("utf-8")))})
    bozuk = bytearray(base64.b64decode(gcm[0]["blob"]))
    bozuk[-1] ^= 0x01
    yaz("aesgcm.json", {
        "aciklama": "AES-256-GCM; blob = iv(12) || şifreli metin || etiket(16), base64. AAD yok.",
        "vektorler": gcm,
        "bozuk": {"aciklama": "etiketin son biti çevrildi — çözme başarısız olmalı",
                  "anahtar_hex": anahtar.hex(), "blob": b64(bytes(bozuk))},
    })

    tam_metin = json.dumps(TAM_ICERIK, ensure_ascii=False, separators=(",", ":"))
    tam_zarf = zarf("yedek-parolasi-123", tam_metin, sira(48, 16), sira(64, 12))
    aktar_metin = json.dumps(AKTAR_ICERIK, ensure_ascii=False, separators=(",", ":"))
    aktar_zarf = zarf("TransferPass2026", aktar_metin, sira(80, 16), sira(96, 12))
    tam_zarf_metni = json.dumps(tam_zarf, indent=2)
    aktar_zarf_metni = json.dumps(aktar_zarf)

    etiketler = [{"name": t["name"], "color": t["color"],
                  "icon": t.get("icon", "") if t.get("icon", "") in IKONLAR else ""}
                 for t in TAM_ICERIK["tagDefs"]]

    yaz("zarf.json", {
        "aciklama": "Yedek (.vaultbak) ve aktarım zarfı. 'metin' dosyanın/QR'ın taşıdığı metnin kendisi.",
        "gecerli": [
            {"ad": "tam-yedek", "parola": "yedek-parolasi-123", "metin": tam_zarf_metni,
             "beklenen_kayitlar": [beklenen(e) for e in TAM_ICERIK["entries"]],
             "beklenen_etiketler": etiketler},
            {"ad": "aktar-tek-not", "parola": "TransferPass2026", "metin": aktar_zarf_metni,
             "beklenen_kayitlar": [beklenen(e) for e in AKTAR_ICERIK["entries"]],
             "beklenen_etiketler": []},
            # Android'in org.json'u "/" karakterini "\\/" yazar (base64'te sık geçer)
            # ve iki boşluklu girinti kullanır: gerçek bir .vaultbak dosyasının görünümü.
            {"ad": "android-kacisli", "parola": "yedek-parolasi-123",
             "metin": json.dumps(tam_zarf, indent=2).replace("/", "\\/"),
             "beklenen_kayitlar": [beklenen(e) for e in TAM_ICERIK["entries"]],
             "beklenen_etiketler": etiketler},
            {"ad": "bom-ve-gorunmez", "parola": "TransferPass2026",
             "metin": "﻿​  " + aktar_zarf_metni + "\n‎\r\n",
             "beklenen_kayitlar": [beklenen(e) for e in AKTAR_ICERIK["entries"]],
             "beklenen_etiketler": []},
        ],
        "yanlis_parola": {"metin": aktar_zarf_metni, "parola": "TransferPass2025"},
        "gecersiz": [
            {"ad": "uygulama-adi-yanlis", "metin": json.dumps({**aktar_zarf, "app": "other"})},
            {"ad": "tur-cok-az", "metin": json.dumps({**aktar_zarf, "kdf": {**aktar_zarf["kdf"], "iterations": 9_999}})},
            {"ad": "tur-cok-fazla", "metin": json.dumps({**aktar_zarf, "kdf": {**aktar_zarf["kdf"], "iterations": 1_000_001}})},
            {"ad": "json-degil", "metin": "bu bir zarf değil"},
            {"ad": "base64-bozuk", "metin": json.dumps({**aktar_zarf, "data": "@@@"})},
        ],
    })

    cok = qr_kareleri(tam_zarf_metni)
    tek = qr_kareleri(aktar_zarf_metni)
    duz_metin = "kısa"  # sıkıştırma kazandırmaz → P bayrağı
    duz = qr_kareleri(duz_metin)
    assert duz[0].split("|")[2] == "P" and tek[0].split("|")[2] == "Z" and len(cok) >= 3
    yaz("qr.json", {
        "aciklama": "QR kareleri: 'VLT1|i/n|F|veri'; F=Z ham deflate+base64, F=P base64. Kareler herhangi bir sırayla gelebilir.",
        "parca": QR_PARCA,
        "gecerli": [
            {"ad": "cok-kare-Z", "kareler": cok, "zarf_metni": tam_zarf_metni},
            {"ad": "tek-kare-Z", "kareler": tek, "zarf_metni": aktar_zarf_metni},
            {"ad": "tek-kare-P", "kareler": duz, "zarf_metni": duz_metin},
        ],
        "ayristirilamaz": [
            "VLT2|1/1|P|AAAA", "VLT1|0/1|P|AAAA", "VLT1|2/1|P|AAAA", "VLT1|1/513|P|AAAA",
            "VLT1|1/1|X|AAAA", "VLT1|1-1|P|AAAA", "VLT1|1/1|P", "merhaba",
        ],
        "birlesmez": [
            {"ad": "eksik-kare", "kareler": cok[:-1]},
            {"ad": "toplam-celisiyor", "kareler": [cok[0]] + [k.replace(f"/{len(cok)}|", f"/{len(cok) + 1}|", 1) for k in cok[1:]]},
            {"ad": "bayrak-celisiyor", "kareler": [cok[0]] + [k.replace("|Z|", "|P|", 1) for k in cok[1:]]},
        ],
    })


if __name__ == "__main__":
    main()

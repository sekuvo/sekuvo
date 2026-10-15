#!/usr/bin/env python3
# -B: bytecode önbelleği yazma/okuma — planted .pyc atlatmasını kapatır (denetim).
import sys
if not sys.flags.dont_write_bytecode:
    import os
    os.environ['PYTHONDONTWRITEBYTECODE'] = '1'
"""
Mac panosundaki metni Vault'a aktarılabilir şifreli yedeğe (.vaultbak) çevirir.

Neden bu yol: uygulamanın ağ izni yok, cihazın panosuna dışarıdan yazılamıyor
(`cmd clipboard` Samsung'da yok) ve `adb shell input text` özel karakterlerde
güvenilmez. Yedek biçimi ise cihazdan bağımsız ve her karakteri taşıyor.

Akış:
    1. Bu betik panodakini alır, tek kayıtlık şifreli yedek üretir.
    2. --push ile dosyayı telefonun Download klasörüne kopyalar.
    3. Telefonda: Ayarlar → Yedekten geri yükle → dosyayı seç → yedek parolası
       → "Mevcuta ekle".

Biçim, app/src/main/java/com/afgover/vault/backup/BackupManager.kt ile birebir:
    {"app":"vault","version":1,
     "kdf":{"algo":"PBKDF2WithHmacSHA256","iterations":310000,"salt":b64},
     "cipher":"AES-256-GCM",
     "data":b64( iv(12) || şifreli+tag )}

Panodaki değer diske hiç düz metin olarak yazılmaz; yalnızca şifreli dosya
oluşur. Yedek parolası sorulur, hiçbir yerde saklanmaz.

Örnekler:
    ./vault-clip.py "Ev adresi" --tur gundelik --push
    ./vault-clip.py "Banka" --tur hesap --alan sifre --push
    ./vault-clip.py "Wi-Fi" --tur not --alan not --korumali
Göç (başka yöneticiden): dışa aktarılmış CSV'yi tek .vaultbak'a çevirir.
    ./vault-clip.py --csv bitwarden_export.csv            # kaynak başlıktan anlaşılır
    ./vault-clip.py --csv "Chrome Passwords.csv" --push
    ./vault-clip.py --csv keepassxc.csv --kaynak keepass
CSV düz metindir: içe aktardıktan sonra silinir (betik hatırlatır).
"""

import argparse
import base64
import csv
import getpass
import hashlib
import json
import os
import subprocess
import sys
import zlib
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor"))

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

KDF_ITERATIONS = 310_000
RELAY = "https://vault.gover.us"
RELAY_KEYCHAIN_SERVICE = "vault.gover.us-relay"
KEY_BYTES = 32
IV_BYTES = 12
PUSH_DIR = "/sdcard/Download"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

# --tur değeri -> uygulamadaki EntryType
TYPES = {
    "hesap": "LOGIN",
    "gundelik": "EVERYDAY",
    "kart": "CARD",
    "not": "NOTE",
}

# --alan değeri -> EntryData alanı (app/src/main/java/.../data/EntryData.kt)
FIELDS = {
    "sifre": "password",
    "kullanici": "username",
    "site": "url",
    "not": "notes",
    "ad": "fullName",
    "telefon": "phone",
    "eposta": "email",
    "adres": "address",
    "kart-no": "cardNumber",
    "iban": "iban",
}

# Tür başına, --alan verilmezse kullanılacak alan
DEFAULT_FIELD = {
    "hesap": "sifre",
    "gundelik": "adres",
    "kart": "kart-no",
    "not": "not",
}


def die(message):
    print(f"hata: {message}", file=sys.stderr)
    sys.exit(1)


def clipboard_text():
    try:
        result = subprocess.run(["pbpaste"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        die(f"pano okunamadı: {exc}")
    text = result.stdout
    if not text.strip():
        die("pano boş (ya da metin değil). Önce kopyala, sonra çalıştır.")
    return text


MAX_PLAIN_BYTES = 256 * 1024  # uygulamadaki kayıt sınırıyla aynı (B-040)


def file_value(path):
    """
    Dosyayı okur: UTF-8 metinse olduğu gibi, ikiliyse base64 döner.
    İkincisi için geri dönüştürme komutu not olarak eklenir (SEC-018).
    Dönen: (değer, not_veya_None)
    """
    if not os.path.isfile(path):
        die(f"dosya bulunamadı: {path}")
    raw = open(path, "rb").read()
    if len(raw) > MAX_PLAIN_BYTES:
        die(
            f"dosya {len(raw)//1024} KB — kayıt sınırı 256 KB. Büyük dosyayı "
            "şifreli olarak diskte/Drive'da tut; kasaya yalnız PAROLASINI koy "
            "(SEC-018: dosya kasaya girmez, anahtarı girer)."
        )
    try:
        text = raw.decode("utf-8")
        if "\x00" not in text:
            return text, None
    except UnicodeDecodeError:
        pass
    name = os.path.basename(path)
    note = (
        "İkili dosya base64 olarak saklandı. Geri döndür: "
        f"base64 -d giris.txt > {name}  (macOS: base64 -D -i giris.txt -o {name})"
    )
    return base64.b64encode(raw).decode("ascii"), note


def ask_password():
    """
    Yedek parolası. Yalnızca ASCII: Android tarafındaki PBKDF2'nin char->bayt
    dönüşümü ASCII dışında sağlayıcıya göre değişebiliyor; ASCII'de her
    uygulamada aynı sonucu verir, yani dosya kesin açılır.
    """
    while True:
        pw = getpass.getpass("Yedek parolası (uygulamada bunu gireceksin): ")
        if len(pw) < 8:
            print("En az 8 karakter olmalı.")
            continue
        if not pw.isascii():
            print("Türkçe/özel harf kullanma — yalnızca ASCII (a-z, 0-9, noktalama).")
            continue
        if pw != getpass.getpass("Parolayı tekrar gir: "):
            print("Parolalar eşleşmedi.")
            continue
        return pw


def parola_zayif(pw):
    """aktar.html'deki sezgiyle aynı: 16'dan kısa ya da tek karakter sınıfı."""
    if len(pw) < 16:
        return True
    siniflar = sum(1 for test in (str.islower, str.isupper, str.isdigit) if any(test(c) for c in pw))
    if any(not c.isalnum() for c in pw):
        siniflar += 1
    return siniflar < 2


# ---- Göç: Bitwarden / Chrome / KeePass CSV → .vaultbak ----

def _ilk_satir(metin):
    return (metin or "").strip().splitlines()[0].strip() if (metin or "").strip() else ""


def _ek_alanlar(metin):
    """Bitwarden 'fields' sütunu: her satır 'ad: değer'. Adsız satır notlara düşer."""
    ek, kalan = [], []
    for satir in (metin or "").splitlines():
        if ": " in satir:
            ad, deger = satir.split(": ", 1)
            if ad.strip():
                ek.append({"label": ad.strip(), "value": deger})
                continue
        if satir.strip():
            kalan.append(satir)
    return ek, "\n".join(kalan)


def csv_kaynak_sez(basliklar):
    b = {h.strip().lower() for h in basliklar if h}
    if {"login_uri", "login_username", "login_password"} <= b:
        return "bitwarden"
    if {"name", "url", "username", "password"} <= b:
        return "chrome"
    if {"title", "username", "password"} <= b and ("group" in b or "url" in b):
        return "keepass"
    return None


def csv_kayitlari(path, kaynak):
    """CSV satırlarını uygulamanın yedek kayıtlarına çevirir.
    Dönüş: (kayıtlar, atlanan açıklamaları)."""
    with open(path, newline="", encoding="utf-8-sig") as f:
        okuyucu = csv.DictReader(f)
        basliklar = okuyucu.fieldnames or []
        satirlar = list(okuyucu)
    if kaynak == "auto":
        kaynak = csv_kaynak_sez(basliklar)
        if not kaynak:
            die("CSV başlığı tanınmadı; --kaynak bitwarden|chrome|keepass ver. Başlık: " + ", ".join(basliklar))
    # Sütun adları kaynağa göre; büyük/küçük harf gözetmeden okunur.
    def al(satir, *adlar):
        for ad in adlar:
            for k, v in satir.items():
                if k and k.strip().lower() == ad.lower() and v:
                    return v
        return ""
    now = int(time.time() * 1000)
    kayitlar, atlanan = [], []
    for i, s in enumerate(satirlar, 2):
        etiketler = []
        if kaynak == "bitwarden":
            tur = al(s, "type").lower()
            ad = al(s, "name") or _ilk_satir(al(s, "login_uri")) or f"Kayıt {i}"
            ek, notlar = _ek_alanlar(al(s, "fields"))
            notlar = "\n\n".join(x for x in (al(s, "notes"), notlar) if x)
            if al(s, "login_totp"):
                ek.append({"label": "TOTP", "value": al(s, "login_totp"), "type": "password"})
            if al(s, "folder"):
                etiketler = [al(s, "folder").split("/")[-1].strip()]
            if tur == "login":
                data = {"username": al(s, "login_username"), "password": al(s, "login_password"),
                        "url": _ilk_satir(al(s, "login_uri")), "notes": notlar}
                kayit = {"type": "LOGIN", "title": ad, "data": data}
            elif tur in ("note", "securenote"):
                kayit = {"type": "NOTE", "title": ad, "data": {"notes": notlar}}
            else:
                atlanan.append(f"satır {i}: Bitwarden türü '{tur}' (kart/kimlik CSV'de taşınmaz)")
                continue
            if ek:
                kayit["data"]["custom"] = ek
        elif kaynak == "chrome":
            ad = al(s, "name") or _ilk_satir(al(s, "url")) or f"Kayıt {i}"
            kayit = {"type": "LOGIN", "title": ad, "data": {
                "username": al(s, "username"), "password": al(s, "password"),
                "url": al(s, "url"), "notes": al(s, "note")}}
        else:  # keepass (KeePassXC CSV)
            ad = al(s, "title") or _ilk_satir(al(s, "url")) or f"Kayıt {i}"
            grup = al(s, "group").replace("\\", "/").split("/")
            grup = [g for g in grup if g and g.lower() != "root"]
            if grup:
                etiketler = [grup[-1].strip()]
            ek = []
            if al(s, "totp"):
                ek.append({"label": "TOTP", "value": al(s, "totp"), "type": "password"})
            if al(s, "username") or al(s, "password") or al(s, "url"):
                kayit = {"type": "LOGIN", "title": ad, "data": {
                    "username": al(s, "username"), "password": al(s, "password"),
                    "url": al(s, "url"), "notes": al(s, "notes")}}
            else:
                kayit = {"type": "NOTE", "title": ad, "data": {"notes": al(s, "notes")}}
            if ek:
                kayit["data"]["custom"] = ek
        kayit["data"] = {k: v for k, v in kayit["data"].items() if v}
        kayit.update({"createdAt": now, "updatedAt": now, "quick": False})
        if kayit["data"].get("password"):
            kayit["data"]["passwordChangedAt"] = now
        if etiketler:
            kayit["tags"] = etiketler
        kayitlar.append(kayit)
    return kaynak, kayitlar, atlanan


def goc(args):
    kaynak, kayitlar, atlanan = csv_kayitlari(args.csv, args.kaynak)
    if not kayitlar:
        die("CSV'den hiç kayıt çıkmadı")
    buyuk = [k["title"] for k in kayitlar
             if len(json.dumps(k, ensure_ascii=False).encode("utf-8")) > MAX_PLAIN_BYTES]
    if buyuk:
        die("256 KB sınırını aşan kayıt(lar): " + ", ".join(buyuk))
    turler = {}
    for k in kayitlar:
        turler[k["type"]] = turler.get(k["type"], 0) + 1
    print(f"Kaynak: {kaynak} · {len(kayitlar)} kayıt · "
          + ", ".join(f"{t.lower()} {n}" for t, n in sorted(turler.items())))
    for a in atlanan:
        print("  atlandı — " + a)
    password = ask_password()
    payload = json.dumps({"entries": kayitlar}, ensure_ascii=False)
    envelope = encrypt(payload, password)
    path = args.cikti or os.path.expanduser(
        f"~/Desktop/vault-goc-{kaynak}-{time.strftime('%Y%m%d-%H%M%S')}.vaultbak")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(envelope, f, indent=2, ensure_ascii=False)
    os.chmod(path, 0o600)
    print(f"\nŞifreli dosya: {path}")
    remote = None
    if args.push:
        remote = push(path)
        print(f"Telefona kopyalandı: {remote}")
    print(
        "\nTelefonda: Ayarlar → Yedekten geri yükle → dosyayı seç → yedek parolası\n"
        "→ \"Mevcuta ekle\" (mevcut kayıtların korunur).\n"
        "Klasörler/gruplar etiket olarak gelir; TOTP sırları ek alan olarak\n"
        "taşınır (uygulama kod üretmez).\n"
        "CSV DÜZ METİNDİR: içe aktardıktan sonra ikisini de sil:\n"
        f"  rm \"{args.csv}\" {path}"
        + (f"\n  {ADB} shell rm {remote}" if remote else "")
    )


def build_entry(args, value, note=None):
    now = int(time.time() * 1000)
    data = {}
    if args.ek:
        data["custom"] = [{"label": args.ek, "value": value}]
    else:
        data[FIELDS[args.alan]] = value
    if args.kullanici:
        data["username"] = args.kullanici
    if note:
        data["notes"] = note
    return {
        "type": TYPES[args.tur],
        "title": args.baslik,
        "createdAt": now,
        "updatedAt": now,
        "quick": args.hizli,
        "data": data,
    }


def encrypt(payload, password):
    salt = os.urandom(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, KDF_ITERATIONS, KEY_BYTES)
    iv = os.urandom(IV_BYTES)
    blob = iv + AESGCM(key).encrypt(iv, payload.encode("utf-8"), None)
    return {
        "app": "vault",
        "version": 1,
        "kdf": {
            "algo": "PBKDF2WithHmacSHA256",
            "iterations": KDF_ITERATIONS,
            "salt": base64.b64encode(salt).decode("ascii"),
        },
        "cipher": "AES-256-GCM",
        "data": base64.b64encode(blob).decode("ascii"),
    }


def relay_token():
    """Yükleme token'ı yalnız Keychain'de durur; dosyaya/argümana yazılmaz."""
    result = subprocess.run(
        ["security", "find-generic-password", "-s", RELAY_KEYCHAIN_SERVICE, "-a", "upload", "-w"],
        capture_output=True, text=True,
    )
    if result.returncode != 0 or not result.stdout.strip():
        die(f"relay token'ı Keychain'de bulunamadı (servis: {RELAY_KEYCHAIN_SERVICE})")
    return result.stdout.strip()


def publish(envelope_text, ttl):
    """
    Zarfı relay'e yükler, tek kullanımlık URL döner. Token, ps çıktısında
    görünmesin diye komut satırından değil geçici bir header dosyasından verilir.
    HTTP'yi curl yapar: bu Mac'teki Python'un kök sertifika deposu eksik.
    """
    import tempfile
    token = relay_token()
    with tempfile.NamedTemporaryFile("w", suffix=".hdr", delete=False) as hf:
        os.chmod(hf.name, 0o600)
        hf.write(f"Authorization: Bearer {token}\n")
        header_file = hf.name
    try:
        result = subprocess.run(
            ["curl", "-sS", "--fail-with-body", "-X", "POST",
             "-H", f"@{header_file}", "--data-binary", "@-",
             f"{RELAY}/api/b?ttl={ttl}"],
            input=envelope_text, capture_output=True, text=True,
        )
    finally:
        os.unlink(header_file)
    if result.returncode != 0:
        die(f"yayınlama başarısız: {result.stderr.strip() or result.stdout.strip()}")
    try:
        return json.loads(result.stdout)["url"]
    except (ValueError, KeyError):
        die(f"relay beklenmedik cevap verdi: {result.stdout[:200]}")


QR_PREFIX = "VLT1"
QR_CHUNK = 1200  # kare başına base64 uzunluğu (uygulamadaki QrTransfer.CHUNK ile aynı)


def qr_kareleri(zarf_metni):
    """
    Zarfı QR karelerine böler: `VLT1|i/n|F|veri`.
    Sıkıştırma (ham deflate) çoğu zarfı tek kareye indiriyor; kazanç yoksa
    düz base64'e düşülür ve bayrak buna göre yazılır (uygulama ikisini de
    tanır — vault_takip B-035).
    """
    ham = zarf_metni.encode("utf-8")
    sikistirici = zlib.compressobj(9, zlib.DEFLATED, -15)
    sikisik = sikistirici.compress(ham) + sikistirici.flush()
    if len(sikisik) < len(ham):
        bayrak, veri = "Z", base64.b64encode(sikisik).decode("ascii")
    else:
        bayrak, veri = "P", base64.b64encode(ham).decode("ascii")
    parcalar = [veri[i:i + QR_CHUNK] for i in range(0, len(veri), QR_CHUNK)] or [""]
    n = len(parcalar)
    return [f"{QR_PREFIX}|{i + 1}/{n}|{bayrak}|{p}" for i, p in enumerate(parcalar)]


QR_VERSION = 25  # 1200 karakterlik kare + başlık bu sürüme sığar (ölçüldü)


def show_qr(veri, sabit_boy=False):
    """
    QR'ı terminale çizer. Çok kareli aktarımda `sabit_boy` şarttır: QR
    sürümü veri uzunluğuna göre seçilirse son (kısa) kare küçük çıkar,
    kareler dönerken boy değişir ve telefonu hizalamak imkânsızlaşır.
    Sürümü sabitlemek bütün kareleri aynı ölçüde tutar.
    """
    try:
        import segno
    except ImportError:
        print("(QR çizilemedi: tools/vendor/segno yok)")
        return
    # Hata düzeltme "l": ekran-kamera temiz bir kanal, düşük seviye daha çok
    # veri sığdırır ve kare sayısını azaltır.
    segno.make(veri, error="l", version=QR_VERSION if sabit_boy else None) \
        .terminal(compact=True, border=2)


def push(path):
    if not os.path.exists(ADB):
        die(f"adb bulunamadı: {ADB}")
    result = subprocess.run([ADB, "push", path, PUSH_DIR + "/"], capture_output=True, text=True)
    if result.returncode != 0:
        die(f"adb push başarısız: {result.stderr.strip() or result.stdout.strip()}")
    return f"{PUSH_DIR}/{os.path.basename(path)}"


def main():
    parser = argparse.ArgumentParser(
        description="Mac panosundaki metni Vault yedeği (.vaultbak) olarak paketler.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("baslik", nargs="?", help="Kaydın başlığı (uygulamada listede görünen ad)")
    parser.add_argument("--csv", metavar="DOSYA",
                        help="Göç: Bitwarden / Chrome / KeePass(XC) CSV dışa aktarımını tek .vaultbak'a çevir")
    parser.add_argument("--kaynak", choices=["auto", "bitwarden", "chrome", "keepass"], default="auto",
                        help="--csv kaynağı (varsayılan: başlıktan sezilir)")
    parser.add_argument("--tur", choices=sorted(TYPES), default="hesap", help="Kayıt türü (varsayılan: hesap)")
    parser.add_argument("--alan", choices=sorted(FIELDS), help="Panodakinin yazılacağı alan")
    parser.add_argument("--ek", metavar="ETIKET", help="Hazır alan yerine bu adla bir ek alan oluştur")
    parser.add_argument("--kullanici", metavar="AD", help="Kullanıcı adını da doldur")
    hizli = parser.add_mutually_exclusive_group()
    hizli.add_argument("--hizli", action="store_true", default=None,
                       help="Klavyede parolasız kullanılabilir olarak işaretle")
    hizli.add_argument("--korumali", dest="hizli", action="store_false",
                       help="Kilit açmadan görünmesin (varsayılan)")
    parser.add_argument("--cikti", metavar="DOSYA", help="Çıktı dosyası yolu")
    parser.add_argument("--push", action="store_true", help="Dosyayı telefonun Download klasörüne kopyala")
    parser.add_argument("--dosya", metavar="YOL",
                        help="Panodan değil dosyadan oku; metin olduğu gibi, ikili base64 + geri dönüştürme notu")
    parser.add_argument("--qr", action="store_true",
                        help="Zarfın KENDİSİNİ terminalde QR olarak göster (ağ yok, relay yok)")
    parser.add_argument("--yayinla", action="store_true",
                        help=f"Zarfı {RELAY} üzerinden tek kullanımlık URL olarak yayınla ve QR göster")
    parser.add_argument("--ttl", type=int, default=86_400, metavar="SANIYE",
                        help="--yayinla süresi: blob en geç bu kadar saniye sonra silinir (60-86400, varsayılan 24 saat)")
    args = parser.parse_args()

    if args.csv:
        if args.baslik or args.qr or args.yayinla:
            parser.error("--csv ile başlık, --qr ve --yayinla kullanılmaz")
        goc(args)
        return
    if not args.baslik:
        parser.error("başlık gerekli (ya da --csv)")

    if args.alan and args.ek:
        die("--alan ile --ek birlikte kullanılamaz")
    if not args.alan:
        args.alan = DEFAULT_FIELD[args.tur]
    if args.hizli is None:
        # Uygulamadaki kuralın aynısı: Gündelik kayıtlar hızlı erişimle başlar.
        args.hizli = args.tur == "gundelik"

    note = None
    if args.dosya:
        value, note = file_value(args.dosya)
        print(f"Dosyadan alınan: {os.path.basename(args.dosya)} · {len(value)} karakter"
              + (" · ikili (base64)" if note else " · metin"))
    else:
        value = clipboard_text()
    # Önizleme bilinçli bir takas: kısa bir baş kısmı ekrana yazmak, yanlış
    # içeriği şifreleyip aktarmayı (yaşandı: komutun kendisi aktarıldı)
    # önler; bedeli, o baş kısmın terminal geçmişinde görünmesidir.
    ilk = value.strip().splitlines()[0] if value.strip() else ""
    onizleme = ilk[:32] + ("…" if len(ilk) > 32 or "\n" in value.strip() else "")
    if not args.dosya:
        print(f"Panodan alınan: {len(value)} karakter · başı: \u201c{onizleme}\u201d")
    if "vault-clip" in value:
        print(
            "\nUYARI: Panodaki metin bu betiğin KOMUTU gibi görünüyor.\n"
            "Komutu panoya kopyalamak, aktarmak istediğin asıl metni siler.\n"
            "Doğrusu: önce aktarılacak metni kopyala, komutu elle yaz ya da\n"
            "terminalin geçmişinden çağır."
        )
        try:
            cevap = input("Yine de bunu aktarayım mı? [e/H] ")
        except EOFError:
            die("panoda komut var; onay alınamadı, iptal edildi")
        if cevap.strip().lower() != "e":
            die("iptal edildi — asıl metni kopyalayıp yeniden dene")
    print(f"Kayıt: \"{args.baslik}\" · {args.tur} · alan: {args.ek or args.alan} · "
          f"{'klavyede parolasız' if args.hizli else 'korumalı'}")

    password = ask_password()
    payload = json.dumps({"entries": [build_entry(args, value, note)]}, ensure_ascii=False)
    if len(payload.encode("utf-8")) > MAX_PLAIN_BYTES:
        die("kayıt 256 KB sınırını aşıyor — içerik kısaltılmalı (B-040 uygulamada da reddeder)")
    envelope = encrypt(payload, password)

    if args.qr:
        kareler = qr_kareleri(json.dumps(envelope, separators=(",", ":"), ensure_ascii=False))
        sure = len(kareler) * 1.2
        print(f"\n{len(kareler)} kare"
              + (f" · tur başına ~{sure:.0f} sn" if len(kareler) > 1 else "")
              + " · telefonda: Vault → + → \"QR ile aktar\"")
        if len(kareler) > 1:
            print("Kareler sırayla gösterilecek; okuyucu sırasız toplar, "
                  "hepsi okunana kadar bekle. Çıkmak için Ctrl-C.\n")
        try:
            tur = 0
            while True:
                for i, kare in enumerate(kareler, 1):
                    if len(kareler) > 1:
                        sys.stdout.write("\033[2J\033[H")  # ekranı temizle
                    print(f"— kare {i}/{len(kareler)} —")
                    show_qr(kare, sabit_boy=len(kareler) > 1)
                    if len(kareler) == 1:
                        break
                    time.sleep(1.2)
                tur += 1
                if len(kareler) == 1:
                    break
        except KeyboardInterrupt:
            print("\nQR gösterimi durduruldu.")
        if not args.cikti and not args.push and not args.yayinla:
            return

    if args.yayinla:
        # Relay herkese açık bir blobdur: insan uydurması parola konmasın
        # (aktar.html'deki uyarının aynısı; QR ve dosya yolunda sorulmaz).
        if parola_zayif(password):
            try:
                cevap = input("Parola kısa ya da tek tip görünüyor; relay'e yine de yüklensin mi? [e/H] ")
            except EOFError:
                cevap = ""
            if cevap.strip().lower() != "e":
                die("iptal edildi — üretilmiş uzun bir parola kullan (docs/kendi-zarfini-uret.md)")
        zarf_metni = json.dumps(envelope, separators=(",", ":"), ensure_ascii=False)
        if len(zarf_metni) > 64 * 1024:
            die(
                f"şifreli zarf {len(zarf_metni)//1024} KB — relay sınırı 64 KB. "
                "Bu boyut için --cikti/--push (dosya yolu) kullan."
            )
        url = publish(zarf_metni, args.ttl)
        hours = args.ttl / 3600
        print(f"\nTek kullanımlık bağlantı ({hours:.1f} saat geçerli, ilk açılışta yanar):")
        print(f"  {url}\n")
        show_qr(url)
        print(
            "\nTelefonda: kamerayla QR'ı okut → açılan düz metni kopyala →\n"
            "Vault → + → \"Metinden içe aktar\" → Panodan al → yedek parolası\n"
            "→ \"Mevcuta ekle\". Bağlantı bir kez açılır; yanlışlıkla kapattıysan\n"
            "komutu yeniden çalıştır. Dosya olarak indirmek istersen URL'nin\n"
            "sonuna ?indir=1 ekle."
        )
        if not args.cikti and not args.push:
            return

    path = args.cikti or os.path.expanduser(
        f"~/Desktop/vault-{time.strftime('%Y%m%d-%H%M%S')}.vaultbak"
    )
    with open(path, "w", encoding="utf-8") as f:
        json.dump(envelope, f, indent=2, ensure_ascii=False)
    os.chmod(path, 0o600)
    print(f"\nŞifreli dosya: {path}")

    if args.push:
        remote = push(path)
        print(f"Telefona kopyalandı: {remote}")

    print(
        "\nTelefonda: Ayarlar → Yedekten geri yükle → dosyayı seç → yedek parolası\n"
        "→ \"Mevcuta ekle\" (mevcut kayıtların korunur).\n"
        "İçe aktardıktan sonra dosyayı sil:\n"
        f"  rm {path}"
        + (f"\n  {ADB} shell rm {remote}" if args.push else "")
    )


if __name__ == "__main__":
    main()

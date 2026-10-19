# Kendi zarfını üret — terminalden Sekuvo'ya şifreli aktarım (B yöntemi)

Bu yönerge, bilgisayardaki bir sırrı **hiçbir hazır araca körü körüne
güvenmeden** Sekuvo'ya taşımanın yolu. Şifreleme kendi makinende, senin
gördüğün komutlarla yapılır; `vault.gover.us`'a yalnız **şifreli** metin
gider. Sitenin ele geçirilmesi ihtimalinde bile içerik çözülemez — tek
şartla: **parolan üretilmiş ve güçlü olmalı** (aşağıda).

## Güvenlik modelinin özeti

- Zarf, telefondaki uygulamanın açtığı `.vaultbak` biçimidir:
  PBKDF2-HMAC-SHA256 (600.000 tur) + AES-256-GCM, `iv || şifreli+tag`.
- Yanlış parola "rastgele çözme" üretmez: GCM etiketi tutmaz, içe aktarma
  "parola yanlış" der (2⁻¹²⁸ hata payı).
- Relay tek görüntülemelik ve süreli: bağlantı ilk açılışta yanar, en geç
  24 saatte kendiliğinden silinir.
- **Parola kuralı:** insan uydurması parola BU modelde yeterli değildir —
  herkese açık blob'a karşı saldırganın sınırsız zamanı olur. Aşağıdaki
  komutla üret (harf+rakam, ~119 bit) ve telefonda girene kadar sakla:

```
python3 -c "import secrets,string; print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(20)))"
```

## Yol 1 — Hazır araç (önerilen)

Depodaki `tools/vault-clip.py` her adımı yapar:

```
# panodaki sırrı yayınla:
tools/vault-clip.py "Sunucu SSH" --tur not --yayinla --ttl 3600

# ya da dosyayı:
tools/vault-clip.py "Deploy anahtarı" --tur not --dosya ~/.ssh/id_ed25519 --yayinla
```

Parolayı sorar, zarfı yükler, URL'nin QR'ını terminale çizer. Telefonda:
kamera → açılan **düz metni** tümüyle kopyala → Sekuvo → + → **Metinden içe
aktar** → Panodan al → parola → Mevcuta ekle. (Bağlantı bir sayfa değil düz
metin döner; markalı sayfa Güvenli Tarama uyarısı verdiği için kaldırıldı.)

## Yol 2 — Çıplak betik (denetlemek isteyene)

Aracımıza değil, kendi okuduğun 30 satıra güvenmek istersen: aşağıdaki
betik zarfı üretir. Tek bağımlılık `cryptography` (`pip install cryptography`).

```python
#!/usr/bin/env python3
# zarf-uret.py — stdin'den sırrı alır, .vaultbak zarfını stdout'a yazar.
# Kullanım: python3 zarf-uret.py "Başlık" > zarf.json  (sırrı YAPIŞTIR, Ctrl-D)
# Komut satırına sır yazma (printf '...'): ~/.zsh_history'e düz metin kalır.
import sys, os, json, time, base64, hashlib, getpass
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

baslik = sys.argv[1] if len(sys.argv) > 1 else "Aktarım"
sir = sys.stdin.read()
parola = getpass.getpass("Zarf parolası (ASCII, üretilmiş): ")
assert parola.isascii() and len(parola) >= 8, "ASCII ve en az 8 karakter"

now = int(time.time() * 1000)
payload = json.dumps({"entries": [{
    "type": "NOTE", "title": baslik,
    "createdAt": now, "updatedAt": now, "quick": False,
    "data": {"notes": sir},
}]}, ensure_ascii=False)

salt = os.urandom(16)
key = hashlib.pbkdf2_hmac("sha256", parola.encode(), salt, 600_000, 32)
iv = os.urandom(12)
blob = iv + AESGCM(key).encrypt(iv, payload.encode(), None)
print(json.dumps({
    "app": "vault", "version": 1,
    "kdf": {"algo": "PBKDF2WithHmacSHA256", "iterations": 600_000,
            "salt": base64.b64encode(salt).decode()},
    "cipher": "AES-256-GCM",
    "data": base64.b64encode(blob).decode(),
}))
```

Yayınlamak için (token Mac Keychain'inde durur; hiçbir dosyaya yazma):

```
curl -sS -X POST \
  -H "Authorization: Bearer $(security find-generic-password -s vault.gover.us-relay -a upload -w)" \
  --data-binary @zarf.json "https://vault.gover.us/api/b?ttl=3600"
```

Dönen `url`'yi telefonda aç (QR istersen: `tools/vendor` içindeki segno ile
`python3 -c "import sys; sys.path.insert(0,'tools/vendor'); import segno; segno.make('URL', error='m').terminal(compact=True)"`).
İş bitince `rm zarf.json` — zarf şifreli ama masada bırakmaya gerek yok.

## Sınırlar

- Zarf ≤ 64 KB (ham içerik ~45 KB) — aşarsa dosya yolu (`--cikti`/`--push`).
- Kayıt başına 256 KB; büyük dosyanın kendisi değil PAROLASI kasaya girer.
- Yükleme token gerektirir (tek kullanıcılık kapı); token yalnız yazmayı
  açar, okuma tek kullanımlık bağlantının kendisiyle korunur.

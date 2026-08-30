# Play Store başvurusu — Sekuvo

Bu dosya, market başvurusunun **teknik tarafının bittiğini** ve konsolda
hangi cevapların verileceğini kayda geçirir. Denetim: 2026-08-29.

## Yüklenecek çıktı

```
app/build/outputs/bundle/release/app-release.aab
```

`./gradlew clean bundleRelease` ile üretilir. Play yalnız **AAB** kabul eder;
APK yerel test içindir.

| Alan | Değer |
|---|---|
| Paket kimliği | `com.sekuvo.app` — ilk yüklemeden sonra **asla değişemez** |
| versionCode / versionName | `1` / `1.0` |
| minSdk / targetSdk | 26 / 36 |
| İmza | 4096-bit RSA, SHA384withRSA, **2054'e kadar geçerli** |
| Keystore | `~/keystores/vault-release.jks` — repo dışında, `local.properties` ile okunur |

> **Keystore'u kaybetmek geri dönüşü olmayan bir hatadır:** aynı anahtar
> olmadan uygulamaya bir daha güncelleme yayımlanamaz. Play App Signing'e
> kaydolmak (yükleme sırasında önerilir) bu riski Google'a devreder; yine de
> `.jks` dosyasının ve parolalarının ayrı bir yerde yedeği bulunmalı.

## Mağaza varlıkları

| Varlık | Durum | Yer |
|---|---|---|
| Uygulama ikonu 512×512 | ✅ | `sekuvo-site/img/icon-512.png` |
| Öne çıkan görsel 1024×500 | ✅ | `store/feature-graphic-1024x500.png` |
| Telefon ekran görüntüleri (en az 2) | ✅ 8 adet, 1080×1920 (9:16) | `store/screenshots/` |
| Liste metinleri (16 dil) | ✅ | `store/listing/*.txt` |
| Gizlilik politikası URL'si | ✅ | https://sekuvo.com/privacy/ |

Ekran görüntüleri Play'in alt sınırını (320 piksel) geçiyor ama düşük
çözünürlüklü. Uygulama pencereleri `FLAG_SECURE` taşıdığı için cihazdan
doğrudan yakalanamaz; daha keskin görsel gerekirse bu bayrağı geçici olarak
kapatan bir hata ayıklama derlemesiyle alınmalı.

## Konsolda verilecek cevaplar

**Veri güvenliği (Data safety)**
- Uygulama kullanıcı verisi topluyor mu / paylaşıyor mu → **Hayır, ikisi de.**
  Gerekçe: `INTERNET` izni yok; veri cihazdan çıkamaz.
- Veriler aktarımda şifreleniyor mu → *ilgisiz* (aktarım yok)
- Kullanıcı silme talebi yolu → *ilgisiz* (hesap yok; veri yalnız cihazda)
- Analitik / reklam / izleme kütüphanesi → **yok**

**İçerik ve sınıflandırma**
- Kategori: **Araçlar** (Tools)
- İçerik derecelendirmesi: şiddet/cinsellik/kumar yok → **Herkes**
- Hedef kitle: **18 yaş ve üzeri** (Families politikası kapsamı dışında kalır)
- Reklam içeriyor mu → **Hayır**
- Uygulama içi satın alma → **Yok**
- Finansal özellikler → **Yok.** Kart bilgisi *saklamak* finansal hizmet
  sunmak değildir; bu beyan ödeme/kredi/yatırım hizmetleri içindir.
- Devlet uygulaması → **Hayır**

**Uygulama erişimi (App access)**
- **Tüm işlevler özel erişim olmadan kullanılabilir.** İnceleyen kendi ana
  parolasını belirleyip kasayı açar; sunucu hesabı yoktur, bu yüzden
  Google'a verilecek bir test hesabı da yoktur.

**İzinler** — hiçbiri Play'in ayrı beyan formunu gerektirmiyor:
`CAMERA` (yalnız QR), `USE_BIOMETRIC`, `BLUETOOTH_CONNECT`, `VIBRATE`.
Hassas izin ailelerinden (SMS, arama kaydı, konum, tüm dosyalar,
`QUERY_ALL_PACKAGES`) hiçbiri yok.

## Yayın öncesi son adımlar (konsolda)

1. Geliştirici hesabı (tek seferlik 25 USD) ve kimlik doğrulaması.
2. Uygulamayı oluştur → paket kimliği `com.sekuvo.app`, varsayılan dil.
3. Yukarıdaki formlar + 16 dilin liste metinleri.
4. AAB'yi **kapalı test** (closed testing) kanalına yükle, kendi cihazında
   Play üzerinden kur ve **dil seçicisini** doğrula — dil bölmesi kapatıldı
   ama bunu Play'in gerçek dağıtımında bir kez görmek doğru olur.
5. Sorun yoksa üretime yükselt.

Kapalı testten geçirmenin sebebi somut: dil bölmesi kusuru yalnız Play
dağıtımında ortaya çıkıyordu, yerel APK kurulumunda hiç görünmüyordu.
Aynı sınıftan başka bir sürprizin üretimde değil test kanalında çıkması
tercih edilir.

## Durum — 2026-08-30

Konsolda yapıldı ve **incelemeye gönderildi** (29 değişiklik; inceleme
genellikle ≤7 gün):

- Mağaza girişi: en-US + 14 çeviri (pcm Play'de desteklenmiyor, yalnız o eksik).
- Grafikler: ikon 512, öne çıkan görsel 1024×500, telefon/7"/10" 8'er görüntü.
  Görüntüler emülatörde demo kasayla çekildi (FLAG_SECURE debug'da geçici
  kapatıldı, commit'lenmedi); 1080×2400 → 1080×1920 tuvale yerleştirildi.
- Kapalı test (Alpha): AAB (1/1.0, çıpa özelliği dahil) yüklendi, 177 ülke,
  test listesi "CoPilot kapali test" (13 kullanıcı), geri bildirim
  contact@sekuvo.com.
- Tek uyarı: yerel hata ayıklama sembolleri (zararsız, bağımlılık kaynaklı).

İnceleme onaylanınca kapalı test yayına girer; **12+ test kullanıcısı
14 gün** katıldıktan sonra "Üretime başvur" düğmesi açılır.

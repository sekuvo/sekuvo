# Kurum logoları

Kayıt listesinde, detayda ve düzenleme ekranında bankanın, kurumun ya da
servisin logosu görünür. Logo yoksa kayıt tür ikonuyla kalır.

## Dosyalar

| Dosya | Ne |
|---|---|
| `katalog.json` | Markalar: kimlik, ad, renk, Simple Icons adı ya da rozet metni, alan adları, başlıkta aranan sözcükler. **Elle düzenlenen tek dosya.** |
| `svg/` | Simple Icons'tan kopyalanan logo yolları (yalnız `--si` ile değişir). |
| `kaynak.json` | Simple Icons sürümü ve her ikonun kaynağı ve lisansı. |
| `uret.py` | Katalogdan iki platformun dosyalarını üretir. |

Üretilenler: `app/src/main/res/drawable/marka_*.xml`,
`app/src/main/java/com/afgover/vault/core/BrandCatalog.kt`,
`app/src/main/java/com/afgover/vault/ui/BrandLogos.kt`,
`ios/Sekuvo/Assets.xcassets/Markalar/`,
`ios/SekuvoCore/Sources/SekuvoCore/BrandCatalog.swift`. Bunlar elle
düzenlenmez.

## Marka eklemek

1. `katalog.json`'a bir satır ekle. Simple Icons'ta varsa `si` alanına kısa
   adını yaz; yoksa `rozet` (1–3 karakter) ve `renk` ver.
2. Simple Icons logosu eklediysen paketi indir ve `--si` ile koş:

   ```bash
   npm pack simple-icons@16.33.0 && tar -xzf simple-icons-16.33.0.tgz
   python3 markalar/uret.py --si package
   ```

   Yalnız rozet eklediysen `python3 markalar/uret.py` yeter.
3. Eşleşmeyi değiştirdiysen `bicim/vektorler/marka-eslesme.json`'a bir durum
   ekle. Android `BrandsTest` ve iOS `BrandsTests` aynı dosyayı okur.

Yazı tabanlı (wordmark) logolar liste boyutunda okunmaz; onlar rozettir
(Pegasus, Zoom, AliExpress, American Express, Coinbase, Samsung, Wells Fargo).

## Eşleşme kuralı

Sıra, iki platformda aynı (`core/Brands.kt`, `SekuvoCore/Brands.swift`):

1. **Kullanıcının seçimi.** Kayıt verisindeki `brand` alanı: `none` → logo
   yok; katalogdaki bir kimlik → o marka. Bilinmeyen kimlik otomatiğe düşer.
2. **Site adresi.** Alan adı markanın alan adlarından biri ya da onun alt
   alan adıysa. Birden çoğu tutarsa en uzun alan adı kazanır
   (`mail.google.com` → Gmail).
3. **Başlık.** Türkçe harfler ve aksanlar katlanmış başlıkta anahtar sözcük
   **tam sözcük** olarak geçiyorsa. En uzun anahtar kazanır ("Amazon Web
   Services" → AWS). Anahtarı boş olan marka (X, Threads, Signal, Zoom, Visa,
   Mastercard) başlıkla eşleşmez; adı sıradan bir sözcük olduğu için.
4. **Kart kaydı.** Başka eşleşme yoksa kart numarasının ağı: Visa, Mastercard,
   American Express.

Adres ve seçim şifreli veride durur. Eşleşme kilit açıkken bellekte
hesaplanır, veritabanına açık yazılmaz.

## Lisans ve markalar

Logolar [Simple Icons](https://simpleicons.org) 16.33.0'dan alındı. Paket
CC0 1.0; `kaynak.json`'da hiçbir ikon ayrı bir lisans taşımıyor. Ama Simple
Icons şunu açıkça söylüyor: CC0 çizimi serbest bırakır, markayı kullanma
hakkını vermez. Logolar sahiplerinin ticari markalarıdır; uygulamada yalnız
kullanıcının kendi hesaplarını tanıması için gösterilir ve seçicide bu not
yazılıdır. Bir marka itiraz ederse o marka `si` alanı silinip rozete
çevrilir.

Rozet renkleri (Türk bankaları, kamu kurumları ve Simple Icons'ta olmayan
markalar) **yaklaşıktır**; resmî marka kılavuzlarıyla karşılaştırılmadı.

Uygulamanın internet izni yok: logolar uygulamaya gömülüdür, hiçbir zaman
indirilmez.

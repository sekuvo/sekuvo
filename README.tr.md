# Sekuvo 🔐

*Your secure vault. — Güvenli kasan.*

*Türkçe belge — [English](README.md)*

Kişisel kullanım için Android şifre / kart / hassas veri kasası.
Tamamen çevrimdışı çalışır; hiçbir veri internete gönderilmez.

## Özellikler

- **Kayıt türleri**: Hesap/Şifre, **Gündelik** (ad soyad, telefon, e-posta,
  adres), Kart (kart no, son kullanma, CVV, IBAN), Güvenli Not
- **Ek alanlar**: Hesap/Şifre ve Gündelik kayıtlarında istediğin kadar
  "alan adı + değer" çifti ekleyebilirsin; bu alanlar da şifrelenir, detay
  ekranında ve klavyede diğerleriyle birlikte listelenir
- **Güçlü şifreleme**: Tüm hassas alanlar AES-256-GCM ile şifrelenir; anahtar ana
  paroladan PBKDF2-HMAC-SHA256 (310.000 tur) ile türetilir
- **Biyometrik kilit açma** (opsiyonel, parmak izi/yüz). Kasa, uygulamanın
  bulunduğu **ekran kapandığı anda** kilitlenir — ekranı kapatmak da,
  katlanabilir cihazı **katlamak** da kilitler (katlayınca cihaz "uyanık"
  kalır ama ana ekran kapanır). Uygulamadan çıkmak ekran açıkken kilitlemez:
  klavye ve otomatik doldurma aynı oturumu kullanır
- **Şifreli yedekleme**: `.vaultbak` dosyası olarak istediğin yere (Drive, SD kart,
  USB...) kaydet; **telefon sıfırlansa veya değişse bile** dosya + yedek parolası
  ile tüm veriler geri yüklenir
- **Otomatik doldurma**: Android'in otomatik doldurma servisi olarak çalışır;
  uygulama ve tarayıcılardaki giriş/kart formlarına dokununca kayıtların
  doldurma seçeneği olarak çıkar, yeni girdiğin bilgileri kaydetmeyi teklif eder
- **Sekuvo Klavyesi**: Klavye eklentisi ile herhangi bir uygulamada şifre, kart no
  vb. bilgileri doğrudan ilgili alana yazdır (panoya kopyalamadan). Son
  kullandığın kayıtlar en üstte durur; 🔍 ile başlık, kullanıcı adı ve adres
  üzerinde arama yapılır (Türkçe harfler ASCII karşılığıyla eşleşir)
- **Hızlı erişim (klavyede parolasız kullanım)**: Kasa kilitliyken klavyede
  yalnızca "parolasız kullan" işaretli kayıtlar görünür; geri kalan her şey için
  🔓 ile parola/parmak izi istenir. İşaret, her kaydın kendi ekranından tek tek
  açılıp kapatılır ve varsayılan olarak **kapalıdır** — tek istisna **Gündelik**
  türünde açtığın yeni kayıtlardır, onlar işaretli başlar (istersen kapatırsın)
- **Bilgisayara yazma (Bluetooth klavye)**: Telefon, bilgisayara Bluetooth
  klavye olarak bağlanır ve seçtiğin şifreyi/alanı tuş basımları halinde
  doğrudan bilgisayardaki imlecin olduğu alana yazar. Bilgisayara hiçbir
  yazılım kurulmaz; Windows/Mac/Linux fark etmez (Android 9+ gerektirir).
  Uzun sırlar için yazma hızı seçilir, hız testiyle ölçülür
- **Şifre üretici**: Kriptografik rastgelelikle (SecureRandom) 8–64 karakter
  şifre üretimi; karakter sınıfı seçimi, karışan karakterleri eleme ve entropi
  (bit) göstergesi. Ana ekrandaki 🎲 simgesinden ya da kayıt düzenlerken şifre
  alanının yanından erişilir
- **Kullanım günlüğü**: Hangi kaydın hangi alanı, ne zaman, nereye gitti —
  panoya, Bluetooth ile bilgisayara (hedef cihaz adıyla) ya da klavyeden hangi
  uygulamaya. Bir bilgisayar ele geçtiğinde "oraya ne gitti, neyi değiştirmeliyim"
  sorusunun cevabı olur. Günlük kayıtlarla aynı anahtarla şifrelidir; **değerlerin
  kendisi hiçbir koşulda yazılmaz**
- **Pano koruması**: Kopyalanan değerler 45 saniye sonra panodan otomatik silinir,
  Android 13+ pano önizlemesinde gizli işaretlenir
- Ekran görüntüsü ve "son uygulamalar" önizlemesi engellenir (`FLAG_SECURE`)
- Sistem yedeklemesi (Google yedekleme / cihaz aktarımı) bilinçli olarak kapalıdır;
  yedekleme yalnızca şifreli dışa aktarma ile yapılır

## Güvenlik modeli

```
Ana parola ──PBKDF2(310k)──▶ KEK ──AES-GCM sarma──▶ dataKey (rastgele 256 bit)
                                                        │
                                                        ▼
                                          Tüm kayıtlar AES-256-GCM ile şifreli
```

- `dataKey` yalnızca kilit açıkken bellekte tutulur; **ekran kapandığı anda**
  silinir (uygulama, klavye ve otomatik doldurma aynı oturumu paylaşır).
- **Hızlı erişim** işaretli kayıtların bir de ikinci kopyası vardır: aynı içerik,
  Android Keystore'daki *kimlik doğrulama istemeyen* ayrı bir anahtarla
  şifrelenir. Klavye kasa kilitliyken yalnızca bu kopyaları okuyabilir; korumalı
  kayıtlar kilitliyken kriptografik olarak erişilemez durumdadır.
  Bu kayıtların güvenliği kasa parolasına değil **telefonun kendi ekran kilidine**
  dayanır — oraya yalnızca düşük değerli bilgiler konmalıdır.
  Kaydın aslı her zaman `dataKey` ile şifreli kaldığı için, Keystore anahtarı
  kaybolsa bile (cihaz sıfırlama vb.) veri kaybı olmaz: kopyalar kilit
  açıldığında yeniden üretilir.
- Biyometrik açma, `dataKey`'in Android Keystore'daki donanım destekli bir anahtarla
  ikinci kez sarılmasıyla çalışır. Keystore cihaz sıfırlamada kaybolur ama bu sadece
  kolaylık katmanıdır — ana parola her zaman çalışır.
- Yedek dosyası cihazdan tamamen bağımsızdır: kendi tuzu (salt) ve PBKDF2
  parametreleri dosya başlığında durur, içerik AES-256-GCM ile şifrelidir.
  Yanlış parola GCM doğrulamasında yakalanır.
- **Ana parolanı veya yedek parolanı unutursan verilerin kurtarılamaz.** Bu bir
  hata değil, tasarım gereğidir (arka kapı yok).

## Kurulum

1. Projeyi Android Studio ile aç ve "Run" ile telefonuna yükle
   (veya komut satırından `./gradlew assembleDebug` → APK:
   `app/build/outputs/apk/debug/app-debug.apk`)
2. APK'yı elle kuruyorsan bilinmeyen kaynaklara izin vermen gerekir
3. İlk açılışta ana parolanı belirle (en az 8 karakter; unutma!)

### Otomatik doldurmayı etkinleştirme

1. Sekuvo → Ayarlar → **Otomatik doldurmayı etkinleştir** → açılan sistem
   listesinden Sekuvo'yu seç (Android 8.0+). Listenin adı cihaza göre değişir:
   Samsung/Android 14+ cihazlarda *Şifreler, parolalar ve otomatik doldurma →
   **Tercih edilen servis***, saf Android'de *Otomatik doldurma servisi*
2. Herhangi bir uygulamada/tarayıcıda kullanıcı adı, şifre veya kart alanına
   dokun → klavyenin üstünde Sekuvo kayıtların çıkar → birine dokun, alanlar dolar
3. Kasa kilitliyse önce **"Doldurmak için kilidi aç"** çıkar; parola ya da
   parmak izi ile açtıktan sonra seçenekler listelenir
4. Bir sitede yeni kullanıcı adı/şifre girip gönderdiğinde Android
   "Sekuvo'ya kaydedilsin mi?" diye sorar (kasa açıkken)

> Alanlar `autofillHints` ile beyan edilmemişse alan adı/ipucu metni ve klavye
> türünden tahmin edilir (Türkçe ve İngilizce anahtar kelimeler). Kasa kilitliyken
> sisteme hiçbir kayıt verilmez — kilit açma ekranı doldurma seçeneklerini ancak
> kilit açıldıktan sonra üretir.

### Klavyeyi etkinleştirme

1. Sekuvo → Ayarlar → **Klavye ayarlarını aç** → Sekuvo Klavyesi'ni etkinleştir
2. Herhangi bir uygulamada metin alanına dokun, klavye değiştiriciden
   (genelde sağ alttaki klavye simgesi) **Sekuvo Klavyesi**'ni seç
3. Kaydı seç → hangi alanı yazmak istediğine dokun ("Şifre yaz" vb.)
4. `ABC` tuşu ile normal klavyene geri dön

> Not: Kasa kilitliyken klavyede yalnızca **hızlı erişim** işaretli kayıtlar
> listelenir. Diğerleri için klavyedeki **🔓 Kilidi aç** düğmesine dokun; parola
> ya da parmak izi sorulur ve kilit, ekran kapanana kadar açık kalır.
>
> Bir kaydı hızlı erişime almak için: kaydı aç → **Klavyede parolasız kullan**
> anahtarını aç. Ana listede bu kayıtlar `⚡ klavyede parolasız` etiketiyle
> görünür.

### Bilgisayara yazma (Bluetooth klavye)

1. Telefonu bilgisayarla Bluetooth'tan **bir kez eşleştir** (normal klavye
   eşleştirir gibi; telefon Bluetooth ayarlarından)
2. Sekuvo'ta kaydı aç → ilgili alanın yanındaki 💻 simgesine dokun
3. Listeden bilgisayarını seç → bağlanınca bilgisayarın klavye düzenini seç
   (Türkçe Q / US)
4. Bilgisayarda imleci şifre kutusuna getir → telefonda **Yaz**'a bas →
   1 saniyelik geri sayımdan sonra değer tuş tuş yazılır
5. **Uzun sırlarda hız**: değer tuş tuş gittiği için 5.000 karakterlik bir sır
   *Güvenli* temposunda dakikalar sürer. Diyalogdaki **Yazma hızı**
   (Güvenli / Hızlı / Çok hızlı) tempoyu belirler, altında tahmini süre yazar.
   Hızı artırmadan önce **⏱ Hız testi**'ne bas: bilgisayara boşlukla ayrılmış
   10 özdeş blok (`abcdefghjk0123456789`) yazılır ve ölçülen karakter/sn
   gösterilir — bloklardan biri farklıysa o tempo bu bilgisayarda güvenli
   değil, bir alt hızı seç. Yazarken ilerleme çubuğu ve **Durdur** vardır

> Değer panodan veya ağdan geçmez; Bluetooth bağlantısının kendi şifrelemesi
> içinde iletilir. Gönderim kuyruğu dolarsa rapor geri çekilip tekrar denenir,
> yine olmazsa yazma **durur ve söyler** — karakter sessizce kaybolmaz. Türkçe Q düzeninde birkaç nadir özel karakter yazılamazsa
> uygulama uyarır — o durumda US düzenini seçip bilgisayarı da geçici olarak
> İngilizce düzene almak yeterlidir.

### Kullanım günlüğü

Kayıt detayında **Son kullanımlar**, Ayarlar → **Kullanım günlüğünü aç**'ta ise
tamamı durur. Olaylar: oluşturuldu, değiştirildi, panoya kopyalandı,
bilgisayara yazıldı (hedef cihaz adıyla), klavyeden yazıldı (hedef uygulamayla),
hızlı erişim açıldı/kapatıldı.

Günlükte **hedefe göre süzme** vardır: bir bilgisayarı seçtiğinde oraya hangi
kayıtların gittiğini tek satırda listeler — rotasyon listesi budur.

Güvenlik tarafı:

- Günlük satırının **tamamı** kayıtlarla aynı anahtarla (dataKey) şifrelidir;
  satırda açık duran tek şey birincil anahtardır. Kasa kilitliyken günlük
  "kaç olay var" dışında hiçbir şey söylemez.
- Değer, değerin parçası ya da uzunluğu **hiçbir olayda** yazılmaz; yalnız alan
  adı ("Şifre") ve hedef adı ("MacBook Pro", `com.android.chrome`).
- Kasa kilitliyken klavyeden yapılan hızlı erişim kullanımları o anda dataKey
  bellekte olmadığı için Keystore anahtarıyla küçük bir tampona yazılır ve ilk
  kilit açılışında günlüğe taşınır. Tampon, hızlı erişim kopyalarıyla aynı güven
  sınıfındadır (telefonun ekran kilidi).
- Kayıt başına en fazla 50, toplamda 2.000 olay saklanır; kayıt silinince
  günlüğü de silinir. "Günlüğü sil" ile tamamı temizlenir.

> **Günlüğün göremedikleri.** Ekrandan okunan, fotoğrafı çekilen ya da elle
> yazılan bir değer iz bırakmaz. Otomatik doldurmada hangi kaydı seçtiğin de
> kaydedilemez: Android, kullanıcının seçtiği `Dataset`'i servise bildirmiyor.
> Ayrıca kasa açıkken günlük silinebilir — bu kurcalanamaz bir denetim izi
> değil, kendi kullanımını hatırlatan bir kayıttır.

## Yedekleme / Geri yükleme

- **Yedek al**: Ayarlar → *Şifreli yedek al* → konum seç → yedek parolası belirle.
  Dosyayı Google Drive'a, e-postana, SD karta — istediğin yere koyabilirsin;
  içerik şifreli olduğu için dosyanın ele geçmesi tek başına bir şey ifade etmez
  (parolan güçlüyse).
- **Geri yükle**: Ayarlar → *Yedekten geri yükle* → dosyayı seç → yedek parolasını
  gir → "Mevcuta ekle" veya "Tümünü değiştir".
- Yeni/sıfırlanmış telefonda: uygulamayı kur → yeni ana parola belirle →
  yedekten geri yükle. Hepsi bu.

💡 Öneri: Önemli bir değişiklikten sonra yeni bir yedek al ve en az iki farklı
yerde (ör. Drive + fiziksel ortam) sakla.

### Mac'ten aktarım (panodaki bilgiyi kasaya alma)

`tools/vault-clip.py` Mac panosundaki metni tek kayıtlık şifreli yedeğe çevirir
ve telefona kopyalar; sen de uygulamadan "Mevcuta ekle" ile içeri alırsın:

```
tools/vault-clip.py "Ev adresi" --tur gundelik --push
tools/vault-clip.py "Banka" --tur hesap --alan sifre --push
tools/vault-clip.py "Sunucu" --tur hesap --ek "API anahtarı" --push
```

Pano içeriği diske düz metin olarak hiç yazılmaz, yalnızca şifreli dosya oluşur;
yedek parolası sorulur ve saklanmaz.

`--yayinla` ile dosya yerine **tek kullanımlık bir bağlantı** üretilir: şifreli
zarf `vault.gover.us` relay'ine yüklenir, terminalde URL'nin QR'ı çizilir.
Telefonun kamerası QR'ı okur, sayfadaki "indir" düğmesi `.vaultbak`'ı
Download'a kaydeder, uygulamadan "Mevcuta ekle" ile içeri alınır:

```
tools/vault-clip.py "Sunucu SSH" --tur hesap --ek "private key" --yayinla
tools/vault-clip.py "Banka" --tur hesap --alan sifre --yayinla --ttl 600
```

Relay'e yalnız **şifreli** zarf gider (başlıklar dahil her şey zarfın içinde);
sayfa ilk açılışta sunucudan silinir (yak-oku) ve her blob en geç 24 saatte
kendiliğinden yok olur. Yükleme token'ı Mac Keychain'de durur
(servis: `vault.gover.us-relay`). Sunucu tarafı `relay/` klasöründedir
(Cloudflare Worker + KV). Yedek parolası **ASCII olmalı** (Android'in
PBKDF2 sağlayıcısı ASCII dışında farklı bayt dönüşümü yapabiliyor).

### Araçların bütünlüğü (`tools/dogrula.sh`)

Aktarım araçları düz metne dokunduğu için, ele geçmiş bir bilgisayarda
**değiştirilmiş olmaları** en gerçekçi sinsi risktir (vault_takip SEC-023).
Denetim üç halkalı ve her halka bir öncekini doğrular:

```
tools/dogrula.sh          # denetle (dosyalar → SHA256SUMS → GitHub → çıpa)
tools/dogrula.sh --yaz    # araçlar bilerek değiştiyse referansı yenile + commit'le
tools/dogrula.sh --cipa   # telefondaki kayıtla karşılaştırılacak tek 64 haneli değer
```

1. **Dosyalar → `tools/SHA256SUMS`** — yerel referans (git'te izlenir).
2. **Yerel → GitHub** — referansı da değiştiren bir saldırgan uzak kopyayı
   değiştiremez; `git diff origin/<dal> -- tools/` farkı gösterir.
3. **Çıpa → Sekuvo** — `SHA256SUMS`'ın kendi SHA-256'sı **telefondaki bir
   kayıtta** durur. Bilgisayarı ele geçiren bunu değiştiremez: karşılaştırma
   iki ayrı cihaz arasında yapılır.

Masaüstündeki `aktar.html` bir **bağ**dır (kopya değil): tek dosya vardır,
iki kopyanın sessizce ayrışması diye bir durum yoktur.

`tools/` içindeki bir dosya değiştiğinde `.githooks/pre-commit` commit'i
durdurur ve referansın yenilenmesini ister; yenilendiğinde de **telefondaki
çıpayı güncelle** diye yeni değeri yazar. Kanca `git config core.hooksPath
.githooks` ile etkinleşir (klonlayan bir kez çalıştırır).

Dürüst sınır: tamamen ele geçmiş bir işletim sistemi `shasum`'ın kendisi
dâhil her şey hakkında yalan söyleyebilir; kurcalanmış bir `dogrula.sh` de
kendi hakkında yalan söyler (bu yüzden betik kendi listesindedir ama
şüphede ona güvenilmez — iki komutu elle koş: `shasum -a 256 -c
tools/SHA256SUMS` ve `git fetch && git diff origin/<dal> -- tools/`).
Bu denetim, kendini denetime hazırlamamış kurcalamayı ve olay sonrası
incelemeyi hedefler — mutlak kanıt değildir.

Neden dosya yoluyla: uygulamanın ağ izni yok, `adb shell cmd clipboard` bu
cihazlarda yok ve `adb shell input text` özel karakterlerde güvenilmez.

## Teknik

- Kotlin, Jetpack Compose (Material 3), Room, androidx.biometric
- minSdk 26 (Android 8.0), targetSdk 36
- Üçüncü taraf ağ/analitik kütüphanesi yok; `INTERNET` izni bile yok

## Geliştirme notları

### Derleme JDK'sı

Kotlin 2.0.21, JDK 25 sürüm numarasını çözemiyor (`IllegalArgumentException: 25.0.2`).
**JDK 17–21 ile derle.** Android Studio kendi JBR'ını (21) kullandığı için "Run"
sorunsuz çalışır; komut satırında JDK'yı elle vermek gerekebilir:

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

SDK yolu için depoda olmayan (`.gitignore`'da) `local.properties` gerekir:
`sdk.dir=/Users/<kullanıcı>/Library/Android/sdk`

### Bağımlılık tuzağı: androidx.fragment

`androidx.biometric:1.1.0` geçişli olarak `fragment:1.2.5` getirir. O sürümdeki
`FragmentActivity`, `ActivityResultRegistry`'nin ürettiği requestCode'ları reddeder
ve dosya seçici / izin ekranı açan **her** buton `Can only use lower 16 bits for
requestCode` ile çöker. `app/build.gradle.kts` içinde fragment sürümü açıkça
pinlenmiş ve `constraints` ile alt sınır konmuştur — kaldırma.

### Commit öncesi cihaz duman testi

Bu sınıf hatalar derlemede değil, yalnızca cihazda tıklayınca ortaya çıkar.
Yeni bir özellik commit'lemeden önce:

```
adb logcat -b crash -c                 # tamponu temizle
# uygulamada sistem ekranı açan akışları dene:
# yedek al, geri yükle, otomatik doldurma ayarı, Bluetooth ile yaz
adb logcat -b crash -d                 # tampon boş kalmalı
```

## Lisans

GPLv3 — [LICENSE](LICENSE). Kodu alan herkes türevini de aynı lisansla açık
tutmak zorundadır. Üstüne GPL §7 kapsamında bir
[ek izin](LICENSE-EXCEPTION.md) var: şartları GPL ile çelişen uygulama
mağazalarından dağıtıma, kaynak GPLv3 ile herkese açık kaldığı sürece izin verir.

Katkılar DCO imzası ve yeniden lisanslama izni ister — ne olduğu ve tek
bakımcılı bir GPL projesinin bunu neden istediği [CONTRIBUTING.md](CONTRIBUTING.md)'de.

package com.afgover.vault.bt

import androidx.annotation.StringRes
import com.afgover.vault.R

/**
 * Karakter → HID klavye tuş kodu eşlemesi.
 *
 * HID kullanım kodları FİZİKSEL tuşu belirtir; hangi karakterin çıkacağı
 * bilgisayarda seçili klavye düzenine bağlıdır. Bu yüzden iki harita var:
 * bilgisayarın düzeni neyse o seçilmeli.
 */
object HidLayouts {

    const val MOD_NONE = 0x00
    const val MOD_SHIFT = 0x02
    const val MOD_ALTGR = 0x40

    data class KeyStroke(val usage: Int, val modifier: Int)

    enum class Layout(@StringRes val labelRes: Int) {
        US(R.string.bt_layout_us),
        UK(R.string.bt_layout_uk),
        TR_Q(R.string.bt_layout_tr),
        DE(R.string.bt_layout_de),
        FR(R.string.bt_layout_fr),
        ES(R.string.bt_layout_es),
        IT(R.string.bt_layout_it),
        TR_F(R.string.bt_layout_tr_f),
        PL(R.string.bt_layout_pl),
        PT(R.string.bt_layout_pt),
        SE(R.string.bt_layout_se),
        BR(R.string.bt_layout_br),
        CH(R.string.bt_layout_ch),
        NL(R.string.bt_layout_nl),
        CZ(R.string.bt_layout_cz),
        HU(R.string.bt_layout_hu),
        NO(R.string.bt_layout_no),
        BE(R.string.bt_layout_be),
        DV(R.string.bt_layout_dv),
        CK(R.string.bt_layout_ck),
        BEPO(R.string.bt_layout_bepo)
    }

    /**
     * Kullanıcının ayarlardan açtığı düzenler. Sekiz düzenin hepsi aktarım
     * ekranında birden görünmesin diye kullanıcı listeyi kendi daraltır;
     * varsayılan hepsi açıktır (kimsenin ihtiyacı olan düzen gizlenmiş
     * olmasın), ayarlardan kapatılır.
     *
     * En az bir düzen her zaman açık kalır: hiçbiri seçilmezse aktarım ekranı
     * kullanılamaz hâle gelirdi.
     */
    fun enabledLayouts(storedDisabled: String?): List<Layout> {
        // Hiç dokunulmamış kurulum: yalnız ABD İngilizcesi açık gelir
        // (kullanıcı kararı). Yirmi bir düzenin tamamını açık başlatmak, düzen
        // seçme listesini ilk kullanımda okunmaz kılıyordu; kullanıcıların
        // büyük kısmı tek bir düzen kullanıyor ve gerekeni sonradan açıyor.
        // Ayrımı `null` yapar: kullanıcı listeye bir kez dokunduğunda
        // (hepsini açsa bile) tercihi yazılır ve buraya bir daha düşmez.
        if (storedDisabled == null) return listOf(Layout.US)
        val kapali = storedDisabled.split(",").mapNotNull { ad ->
            runCatching { Layout.valueOf(ad.trim()) }.getOrNull()
        }.toSet()
        // Sıra HER ZAMAN enum sırasıdır: tercih, açma-kapama geçmişine göre
        // kayan bir liste olarak saklanırsa çipler her açılışta başka sırada
        // görünür ve geri düşüş başka bir düzene denk gelir (denetim).
        val acik = Layout.entries.filter { it !in kapali }
        return acik.ifEmpty { Layout.entries }
    }

    /**
     * KAPATILAN düzenler saklanır, açıklar değil: ileride yeni bir düzen
     * eklendiğinde mevcut kullanıcılarda kendiliğinden görünür. İzin listesi
     * saklansaydı yeni düzen herkeste gizli kalırdı (denetim).
     */
    fun storeDisabled(layouts: Collection<Layout>): String {
        val kapali = Layout.entries.filter { it !in layouts }
        return kapali.joinToString(",") { it.name }
    }

    /**
     * Saklanmış düzen tercihini okur. Eski sürümler Türkçe Q'yu `"TR"` diye
     * yazıyordu; düzen sayısı artınca ad `TR_Q` oldu. Eski tercih sessizce
     * varsayılana düşmemeli — kullanıcı bir daha seçmek zorunda kalmasın.
     */
    fun readLayout(stored: String?, enabled: List<Layout> = Layout.entries): Layout {
        val secili = when (stored) {
            // Seçilmemişse ABD İngilizcesi: açık düzenlerin varsayılanıyla
            // aynı olsun (kullanıcı kararı). Eskiden burası Türkçe Q'ydu;
            // açık liste US'e daralınca seçim zaten oraya düşüyordu, ikisini
            // ayrı bırakmak "Türkçe'yi açınca kendiliğinden seçili oluyor"
            // gibi sürprizli bir davranış üretirdi.
            null -> Layout.US
            "TR" -> Layout.TR_Q       // eski sürümlerin adı
            else -> runCatching { Layout.valueOf(stored) }.getOrDefault(Layout.US)
        }
        // Kullanıcı bu düzeni ayarlardan kapattıysa açık olanlardan birine düş;
        // yoksa aktarım ekranında görünmeyen bir düzen seçili kalırdı.
        return if (secili in enabled) secili else enabled.first()
    }

    fun map(layout: Layout): Map<Char, KeyStroke> =
        when (layout) {
            Layout.US -> US
            Layout.UK -> UK
            Layout.TR_Q -> TR
            Layout.DE -> DE
            Layout.FR -> FR
            Layout.ES -> ES
            Layout.IT -> IT
            Layout.TR_F -> TR_F
            Layout.PL -> PL
            Layout.PT -> PT
            Layout.SE -> SE
            Layout.BR -> BR
            Layout.CH -> CH
            Layout.NL -> NL
            Layout.CZ -> CZ
            Layout.HU -> HU
            Layout.NO -> NO
            Layout.BE -> BE
            Layout.DV -> DV
            Layout.CK -> CK
            Layout.BEPO -> BEPO
        }

    /**
     * Seçili düzende yazıldığında, BAŞKA bir düzende farklı tuşa düşen
     * karakterler: "düzeni yanlış seçtiysen bunlar bozuk çıkar" listesi.
     *
     * Eskiden yalnız US ile TR-Q karşılaştırılıyordu ve yorum "harfler ve
     * rakamlar iki düzende aynıdır, tehlike simgelerde" diyordu. Bu, düzen
     * sayısı ikiyken doğruydu; AZERTY'de A↔Q ve Z↔W yer değiştirir, rakamlar
     * Shift ister, QWERTZ'de Y↔Z değişir — yani artık HARFLER DE duyarlıdır.
     * Şifreler çoğunlukla harf ve rakamdan oluştuğu için yanlış düzen artık
     * birkaç simgeyi değil neredeyse her karakteri bozar.
     */
    fun layoutSensitiveChars(text: String, selected: Layout): List<Char> {
        val secili = map(selected)
        val digerleri = Layout.entries.filter { it != selected }.map { map(it) }
        return text.toSet()
            .filter { ch -> digerleri.any { it[ch] != secili[ch] } }
            .sorted()
    }

    /**
     * Bu metin, DESTEKLENEN HER DÜZENDE farklı bir tuş dizisine düşer; yani
     * bilgisayarda beklenenden farklı çıkması "yanlış düzen" demektir.
     * Harfleri de içerir: AZERTY/QWERTZ ile birlikte harfler de ayırt edici
     * oldu, yalnız simgelerden oluşan bir test artık yetmiyor.
     */
    const val LAYOUT_TEST_TEXT = "azqwym1! @ - _ ; : / ? . , ( ) ="

    /** Her düzen çifti bu metinde farklı tuş dizisi üretiyor mu (test güvencesi). */
    internal fun testTextKeystrokes(layout: Layout): List<KeyStroke?> =
        LAYOUT_TEST_TEXT.map { map(layout)[it] }

    /**
     * Hız testi bloğu ve metni: TAŞIMAyı ölçer, düzeni değil. Boşlukla
     * ayrılmış 10 özdeş blok; seçilen tempoda rapor düşerse ya da tuş tekrarı
     * olursa bloklardan biri diğerlerine benzemez, gözle anında görülür.
     *
     * DİKKAT: düzenden bağımsız DEĞİLDİR — AZERTY'de harfler, QWERTZ'de y/z
     * yer değiştirir. Bu yüzden hız testi ancak DÜZEN TESTİ geçtikten sonra
     * anlamlıdır; arayüz bu sırayı gözetir.
     */
    const val SPEED_TEST_BLOCK = "abcdefghjk0123456789"
    val SPEED_TEST_TEXT: String = List(10) { SPEED_TEST_BLOCK }.joinToString(" ")

    /**
     * ABD (ANSI) düzeni — konum tablosu. Mevcut, cihazda doğrulanmış haritadan
     * birebir türetildi; eşdeğerliği [com.afgover.vault.data.HidLayoutsTest]
     * koruyor.
     */
    private val US_TABLE = KeyTable(
        KeyTable.latinHarfler() + KeyTable.ORTAK + listOf(
            KeyCap(0x1E, '1', '!'), KeyCap(0x1F, '2', '@'), KeyCap(0x20, '3', '#'),
            KeyCap(0x21, '4', '$'), KeyCap(0x22, '5', '%'), KeyCap(0x23, '6', '^'),
            KeyCap(0x24, '7', '&'), KeyCap(0x25, '8', '*'), KeyCap(0x26, '9', '('),
            KeyCap(0x27, '0', ')'),
            KeyCap(0x2D, '-', '_'), KeyCap(0x2E, '=', '+'),
            KeyCap(0x2F, '[', '{'), KeyCap(0x30, ']', '}'), KeyCap(0x31, '\\', '|'),
            KeyCap(0x33, ';', ':'), KeyCap(0x34, '\'', '"'), KeyCap(0x35, '`', '~'),
            KeyCap(0x36, ',', '<'), KeyCap(0x37, '.', '>'), KeyCap(0x38, '/', '?')
        )
    )

    /**
     * Türkçe Q (ISO) — konum tablosu. Cihazda doğrulandı (vault_takip B-050).
     * `^` (Shift+3) ÖLÜ TUŞTUR: tek başına karakter basmaz, sonraki sesliyle
     * birleşir (â). Bu yüzden tabloda yok — yazılamayan karakter olarak
     * bildirilmesi, sessizce yanlış üretilmesinden iyidir.
     */
    private val TR_Q_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                // TR-Q'da fiziksel I tuşu 'ı', home-row'daki ek tuş 'i' üretir
                0x0C to KeyCap(0x0C, 'ı', 'I'),
                0x14 to KeyCap(0x14, 'q', 'Q', altgr = '@')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x1E, '1', '!'), KeyCap(0x1F, '2', '\''),
            KeyCap(0x20, '3', null, altgr = '#'),          // Shift+3 = ^ ölü tuş
            KeyCap(0x21, '4', '+', altgr = '$'),
            KeyCap(0x22, '5', '%'), KeyCap(0x23, '6', '&'),
            KeyCap(0x24, '7', '/', altgr = '{'), KeyCap(0x25, '8', '(', altgr = '['),
            KeyCap(0x26, '9', ')', altgr = ']'), KeyCap(0x27, '0', '=', altgr = '}'),
            KeyCap(0x2D, '*', '?', altgr = '\\'),
            KeyCap(0x2E, '-', '_'),
            KeyCap(0x2F, 'ğ', 'Ğ'), KeyCap(0x30, 'ü', 'Ü'),
            KeyCap(0x32, ',', ';'), KeyCap(0x33, 'ş', 'Ş'),
            KeyCap(0x34, 'i', 'İ'), KeyCap(0x35, '"'),
            KeyCap(0x36, 'ö', 'Ö'), KeyCap(0x37, 'ç', 'Ç'),
            KeyCap(0x38, '.', ':'),
            KeyCap(0x64, '<', '>', altgr = '|')
        )
    )

    private val US: Map<Char, KeyStroke> = US_TABLE.toMap()
    private val TR: Map<Char, KeyStroke> = TR_Q_TABLE.toMap()

    /**
     * İngiliz (UK, ISO) — konum tablosu.
     * UK ISO, US ile aynı QWERTY harf sırasını ve aynı rakam sırası tabanını kullanır; farklar noktalamada toplanır ve tam olarak şifrelerde sık geçen karak
     *
     * Hakem doğrulaması: onaylandı.
     */
    private val UK_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x08, 'e', shift = 'E'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'q', shift = 'Q'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'y', shift = 'Y'),
            KeyCap(0x1D, 'z', shift = 'Z'),
            KeyCap(0x1E, '1', shift = '!'),
            KeyCap(0x1F, '2', shift = '"'),
            KeyCap(0x20, '3', shift = '£'),
            KeyCap(0x21, '4', shift = '$', altgr = '€'),
            KeyCap(0x22, '5', shift = '%'),
            KeyCap(0x23, '6', shift = '^'),
            KeyCap(0x24, '7', shift = '&'),
            KeyCap(0x25, '8', shift = '*'),
            KeyCap(0x26, '9', shift = '('),
            KeyCap(0x27, '0', shift = ')'),
            KeyCap(0x2D, '-', shift = '_'),
            KeyCap(0x2E, '=', shift = '+'),
            KeyCap(0x2F, '[', shift = '{'),
            KeyCap(0x30, ']', shift = '}'),
            KeyCap(0x31, '#', shift = '~'),
            KeyCap(0x33, ';', shift = ':'),
            KeyCap(0x34, '\'', shift = '@'),
            KeyCap(0x35, '`', shift = '¬'),
            KeyCap(0x36, ',', shift = '<'),
            KeyCap(0x37, '.', shift = '>'),
            KeyCap(0x38, '/', shift = '?'),
            KeyCap(0x64, '\\', shift = '|')
        )
    )


    /**
     * Almanca (QWERTZ, ISO) — konum tablosu.
     * Almanca T1 (QWERTZ, ISO) düzeninin US'ten farkları, KONUM bazında: (1) 0x1C konumu (US-Y) 'z', 0x1D konumu (US-Z) 'y' üretir — Y/Z yer değiştirir. (2)
     *
     * Hakem doğrulaması: onaylandı.
     */
    private val DE_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x08, 'e', shift = 'E', altgr = '€'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'q', shift = 'Q', altgr = '@'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'z', shift = 'Z'),
            KeyCap(0x1D, 'y', shift = 'Y'),
            KeyCap(0x1E, '1', shift = '!'),
            KeyCap(0x1F, '2', shift = '"'),
            KeyCap(0x20, '3', shift = '§'),
            KeyCap(0x21, '4', shift = '$'),
            KeyCap(0x22, '5', shift = '%'),
            KeyCap(0x23, '6', shift = '&'),
            KeyCap(0x24, '7', shift = '/', altgr = '{'),
            KeyCap(0x25, '8', shift = '(', altgr = '['),
            KeyCap(0x26, '9', shift = ')', altgr = ']'),
            KeyCap(0x27, '0', shift = '=', altgr = '}'),
            KeyCap(0x2D, 'ß', shift = '?', altgr = '\\'),
            KeyCap(0x2F, 'ü', shift = 'Ü'),
            KeyCap(0x30, '+', shift = '*', altgr = '~'),
            KeyCap(0x31, '#', shift = '\''),
            KeyCap(0x33, 'ö', shift = 'Ö'),
            KeyCap(0x34, 'ä', shift = 'Ä'),
            KeyCap(0x36, ',', shift = ';'),
            KeyCap(0x37, '.', shift = ':'),
            KeyCap(0x38, '-', shift = '_'),
            KeyCap(0x64, '<', shift = '>', altgr = '|')
        )
    )


    /**
     * Fransızca (AZERTY, ISO) — konum tablosu.
     * US'e göre en kritik farklar: (1) Harf konumları kayıyor — 0x14 (US-Q) = a, 0x04 (US-A) = q, 0x1A (US-W) = z, 0x1D (US-Z) = w, ve M harfi 0x33'te (US-;
     *
     * Hakem doğrulaması: onaylandı.
     */
    private val FR_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'q', shift = 'Q'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x08, 'e', shift = 'E', altgr = '€'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, ',', shift = '?'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'a', shift = 'A'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'z', shift = 'Z'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'y', shift = 'Y'),
            KeyCap(0x1D, 'w', shift = 'W'),
            KeyCap(0x1E, '&', shift = '1'),
            KeyCap(0x1F, 'é', shift = '2'),
            KeyCap(0x20, '"', shift = '3', altgr = '#'),
            KeyCap(0x21, '\'', shift = '4', altgr = '{'),
            KeyCap(0x22, '(', shift = '5', altgr = '['),
            KeyCap(0x23, '-', shift = '6', altgr = '|'),
            KeyCap(0x24, 'è', shift = '7'),
            KeyCap(0x25, '_', shift = '8', altgr = '\\'),
            KeyCap(0x26, 'ç', shift = '9'),
            KeyCap(0x27, 'à', shift = '0', altgr = '@'),
            KeyCap(0x2D, ')', shift = '°', altgr = ']'),
            KeyCap(0x2E, '=', shift = '+', altgr = '}'),
            KeyCap(0x30, '$', shift = '£', altgr = '¤'),
            KeyCap(0x31, '*', shift = 'µ'),
            KeyCap(0x33, 'm', shift = 'M'),
            KeyCap(0x34, 'ù', shift = '%'),
            KeyCap(0x35, '²'),
            KeyCap(0x36, ';', shift = '.'),
            KeyCap(0x37, ':', shift = '/'),
            KeyCap(0x38, '!', shift = '§'),
            KeyCap(0x64, '<', shift = '>')
        )
    )


    /**
     * İspanyolca (ISO) — konum tablosu.
     * İspanyol ISO düzeninin US'ten kritik farkları: (1) Noktalama tamamen kaymış — US'te ;: olan 0x33 konumu Ñ, US'te '" olan 0x34 konumu ÖLÜ ´/¨, US'te \|
     *
     * Hakem doğrulaması: onaylandı.
     */
    private val ES_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x08, 'e', shift = 'E', altgr = '€'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'q', shift = 'Q'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'y', shift = 'Y'),
            KeyCap(0x1D, 'z', shift = 'Z'),
            KeyCap(0x1E, '1', shift = '!', altgr = '|'),
            KeyCap(0x1F, '2', shift = '"', altgr = '@'),
            KeyCap(0x20, '3', shift = '·', altgr = '#'),
            KeyCap(0x21, '4', shift = '$', altgr = '~'),
            KeyCap(0x22, '5', shift = '%'),
            KeyCap(0x23, '6', shift = '&'),
            KeyCap(0x24, '7', shift = '/'),
            KeyCap(0x25, '8', shift = '('),
            KeyCap(0x26, '9', shift = ')'),
            KeyCap(0x27, '0', shift = '='),
            KeyCap(0x2D, '\'', shift = '?'),
            KeyCap(0x2E, '¡', shift = '¿'),
            KeyCap(0x2F, null, shift = null, altgr = '['),
            KeyCap(0x30, '+', shift = '*', altgr = ']'),
            KeyCap(0x31, 'ç', shift = 'Ç', altgr = '}'),
            KeyCap(0x33, 'ñ', shift = 'Ñ'),
            KeyCap(0x34, null, shift = null, altgr = '{'),
            KeyCap(0x35, 'º', shift = 'ª', altgr = '\\'),
            KeyCap(0x36, ',', shift = ';'),
            KeyCap(0x37, '.', shift = ':'),
            KeyCap(0x38, '-', shift = '_'),
            KeyCap(0x64, '<', shift = '>')
        )
    )


    /**
     * İtalyanca (ISO) — konum tablosu.
     * İtalyan ISO düzeni tam QWERTY'dir: harflerin (0x04-0x1D) ve rakamların base değerleri US ile birebir aynıdır — tehlike tamamen simgelerdedir. US'ten f
     *
     * Hakem doğrulaması: onaylandı.
     */
    private val IT_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x08, 'e', shift = 'E', altgr = '€'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'q', shift = 'Q'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'y', shift = 'Y'),
            KeyCap(0x1D, 'z', shift = 'Z'),
            KeyCap(0x1E, '1', shift = '!'),
            KeyCap(0x1F, '2', shift = '"'),
            KeyCap(0x20, '3', shift = '£'),
            KeyCap(0x21, '4', shift = '$'),
            KeyCap(0x22, '5', shift = '%'),
            KeyCap(0x23, '6', shift = '&'),
            KeyCap(0x24, '7', shift = '/'),
            KeyCap(0x25, '8', shift = '('),
            KeyCap(0x26, '9', shift = ')'),
            KeyCap(0x27, '0', shift = '='),
            KeyCap(0x2D, '\'', shift = '?'),
            KeyCap(0x2E, 'ì', shift = '^'),
            KeyCap(0x2F, 'è', shift = 'é', altgr = '[', altgrShift = '{'),
            KeyCap(0x30, '+', shift = '*', altgr = ']', altgrShift = '}'),
            KeyCap(0x31, 'ù', shift = '§'),
            KeyCap(0x33, 'ò', shift = 'ç', altgr = '@'),
            KeyCap(0x34, 'à', shift = '°', altgr = '#'),
            KeyCap(0x35, '\\', shift = '|'),
            KeyCap(0x36, ',', shift = ';'),
            KeyCap(0x37, '.', shift = ':'),
            KeyCap(0x38, '-', shift = '_'),
            KeyCap(0x64, '<', shift = '>')
        )
    )

    private val UK: Map<Char, KeyStroke> = UK_TABLE.toMap()
    private val DE: Map<Char, KeyStroke> = DE_TABLE.toMap()
    private val FR: Map<Char, KeyStroke> = FR_TABLE.toMap()
    private val ES: Map<Char, KeyStroke> = ES_TABLE.toMap()
    /**
     * Türkçe F — konum tablosu.
     * TR-F, US ile yalnız rakam tabanlarını (1-0) ve boşluk/enter/tab'ı paylaşır; geri kalan her şey farklıdır.
     *
     * İki bağımsız hakem onayladı; ikincisi Microsoft'un resmî TR-F düzen
     * dosyasıyla (KBDTUF) satır satır karşılaştırdı.
     *
     * `|` düzende iki tuştan da çıkıyor (AltGr+0x2E ve AltGr+0x64); tabloda
     * yalnız 0x2E bırakıldı: 0x64 (ISO 102. tuş) ANSI-only ana bilgisayarlarda
     * eşlenmeyebilir, 0x2E her yerde vardır.
     */
    private val TR_F_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'u', shift = 'U'),
            KeyCap(0x05, 'ç', shift = 'Ç'),
            KeyCap(0x06, 'v', shift = 'V'),
            KeyCap(0x07, 'e', shift = 'E'),
            KeyCap(0x08, 'ğ', shift = 'Ğ'),
            KeyCap(0x09, 'a', shift = 'A'),
            KeyCap(0x0A, 'ü', shift = 'Ü'),
            KeyCap(0x0B, 't', shift = 'T'),
            KeyCap(0x0C, 'n', shift = 'N'),
            KeyCap(0x0D, 'k', shift = 'K'),
            KeyCap(0x0E, 'm', shift = 'M'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x10, 's', shift = 'S'),
            KeyCap(0x11, 'z', shift = 'Z'),
            KeyCap(0x12, 'h', shift = 'H'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x14, 'f', shift = 'F', altgr = '@'),
            KeyCap(0x15, 'ı', shift = 'I'),
            KeyCap(0x16, 'i', shift = 'İ'),
            KeyCap(0x17, 'o', shift = 'O'),
            KeyCap(0x18, 'r', shift = 'R'),
            KeyCap(0x19, 'c', shift = 'C'),
            KeyCap(0x1A, 'g', shift = 'G'),
            KeyCap(0x1B, 'ö', shift = 'Ö'),
            KeyCap(0x1C, 'd', shift = 'D'),
            KeyCap(0x1D, 'j', shift = 'J'),
            KeyCap(0x1E, '1', shift = '!'),
            KeyCap(0x1F, '2', shift = '"'),
            KeyCap(0x20, '3', shift = null, altgr = '#'),
            KeyCap(0x21, '4', shift = '$'),
            KeyCap(0x22, '5', shift = '%'),
            KeyCap(0x23, '6', shift = '&'),
            KeyCap(0x24, '7', shift = '\'', altgr = '{'),
            KeyCap(0x25, '8', shift = '(', altgr = '['),
            KeyCap(0x26, '9', shift = ')', altgr = ']'),
            KeyCap(0x27, '0', shift = '=', altgr = '}'),
            KeyCap(0x2D, '/', shift = '?', altgr = '\\'),
            KeyCap(0x2E, '-', shift = '_', altgr = '|'),
            KeyCap(0x2F, 'q', shift = 'Q'),
            KeyCap(0x30, 'w', shift = 'W'),
            KeyCap(0x31, 'x', shift = 'X'),
            KeyCap(0x33, 'y', shift = 'Y'),
            KeyCap(0x34, 'ş', shift = 'Ş'),
            KeyCap(0x35, '+', shift = '*'),
            KeyCap(0x36, 'b', shift = 'B'),
            KeyCap(0x37, '.', shift = ':'),
            KeyCap(0x38, ',', shift = ';'),
            KeyCap(0x64, '<', shift = '>')
        )
    )


    /**
     * Lehçe (Programcı, "Polish Programmers") — konum tablosu.
     *
     * Standart Polonya düzeni (214) daktilo mirası taşır ve köşeli parantez
     * gibi programlama için gerekli karakterleri taşımıştır; bu yüzden
     * Microsoft ayrı bir "Programcı" düzeni tanımladı: harfler, rakamlar ve
     * noktalama US ile BİREBİR aynı konumda, tek fark dokuz harfte AltGr ile
     * gelen Lehçe aksanlı karakterler (ą ć ę ł ń ó ś ź ż). Diğer harflerde
     * AltGr yok.
     *
     * Kaynak: Microsoft Learn resmi klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdpl1) + kbdlayout.info,
     * ikisi çakıştı.
     */
    private val PL_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x04 to KeyCap(0x04, 'a', 'A', altgr = 'ą'),
                0x06 to KeyCap(0x06, 'c', 'C', altgr = 'ć'),
                0x08 to KeyCap(0x08, 'e', 'E', altgr = 'ę'),
                0x0F to KeyCap(0x0F, 'l', 'L', altgr = 'ł'),
                0x11 to KeyCap(0x11, 'n', 'N', altgr = 'ń'),
                0x12 to KeyCap(0x12, 'o', 'O', altgr = 'ó'),
                0x16 to KeyCap(0x16, 's', 'S', altgr = 'ś'),
                0x1B to KeyCap(0x1B, 'x', 'X', altgr = 'ź'),
                0x1D to KeyCap(0x1D, 'z', 'Z', altgr = 'ż')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x1E, '1', '!'), KeyCap(0x1F, '2', '@'), KeyCap(0x20, '3', '#'),
            KeyCap(0x21, '4', '$'), KeyCap(0x22, '5', '%'), KeyCap(0x23, '6', '^'),
            KeyCap(0x24, '7', '&'), KeyCap(0x25, '8', '*'), KeyCap(0x26, '9', '('),
            KeyCap(0x27, '0', ')'),
            KeyCap(0x2D, '-', '_'), KeyCap(0x2E, '=', '+'),
            KeyCap(0x2F, '[', '{'), KeyCap(0x30, ']', '}'), KeyCap(0x31, '\\', '|'),
            KeyCap(0x33, ';', ':'), KeyCap(0x34, '\'', '"'), KeyCap(0x35, '`', '~'),
            KeyCap(0x36, ',', '<'), KeyCap(0x37, '.', '>'), KeyCap(0x38, '/', '?')
        )
    )

    private val IT: Map<Char, KeyStroke> = IT_TABLE.toMap()
    private val TR_F: Map<Char, KeyStroke> = TR_F_TABLE.toMap()

    /**
     * Portekizce (Avrupa, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdpo.html), ham Unicode
     * ad etiketleriyle (ör. "U+0021 EXCLAMATION MARK") — özetleme değil
     * birebir okuma.
     *
     * L'den sonra Ç, ondan sonra º/ª (eril/dişil sıra sayı işareti — gerçek
     * karakterdir, ölü tuş değil). P'den sonraki `+`/`*` tuşundan hemen
     * sonraki konum (ISO 0x30) akut/gravis ÖLÜ TUŞTUR, tabloda yok. Üst-sol
     * köşe (0x35, ABD'de `~) için kaynakta net bir okuma çıkmadı — tahmin
     * etmek yerine boş bırakıldı; şifrelerde bu karakter aranmıyor.
     * ISO ekstra tuşu (0x64, Z'den önce) `<`/`>` üretiyor.
     */
    private val PT_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x33 to KeyCap(0x33, 'ç', 'Ç')
            )
        ) + listOf(
            KeyCap(0x2C, ' '), KeyCap(0x28, '\n'), KeyCap(0x2B, '\t')
        ) + listOf(
            KeyCap(0x1E, '1', '!'),
            KeyCap(0x1F, '2', '"', altgr = '@'),
            KeyCap(0x20, '3', '#', altgr = '£'),
            KeyCap(0x21, '4', '$', altgr = '§'),
            KeyCap(0x22, '5', '%', altgr = '€'),
            KeyCap(0x23, '6', '&'),
            KeyCap(0x24, '7', '/', altgr = '{'),
            KeyCap(0x25, '8', '(', altgr = '['),
            KeyCap(0x26, '9', ')', altgr = ']'),
            KeyCap(0x27, '0', '=', altgr = '}'),
            KeyCap(0x2D, '\'', '?'),
            KeyCap(0x2E, '«', '»'),
            KeyCap(0x2F, '+', '*'),
            // 0x30 (P'den sonraki ikinci tuş): akut/gravis ÖLÜ TUŞ — atlanıyor
            KeyCap(0x34, 'º', 'ª'),
            // 0x35 (üst-sol köşe): kaynakta belirsiz — atlanıyor
            KeyCap(0x36, ',', ';'),
            KeyCap(0x37, '.', ':'),
            KeyCap(0x38, '-', '_'),
            KeyCap(0x64, '<', '>')
        )
    )

    private val PL: Map<Char, KeyStroke> = PL_TABLE.toMap()

    /**
     * İsveççe (Nordic, ISO) — konum tablosu. Finlandiya'da da aynı fiziksel
     * dizilim kullanılıyor (kbdfi = kbdsw ile aynı harita).
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdsw.html), ham Unicode
     * ad etiketleriyle, birebir okuma.
     *
     * L'den sonra ö, ondan sonra ä. P'den sonra å. Üst-sol köşe (0x35) § / ½
     * üretiyor (ABD'deki `~ karşılığı). Akut (´), çift nokta (¨), sirkumfleks
     * (^) ve gravis (`) — dördü de ÖLÜ TUŞ, tabloda yok.
     */
    private val SE_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(0x10 to KeyCap(0x10, 'm', 'M', altgr = 'µ'))
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x1E, '1', '!'),
            KeyCap(0x1F, '2', '"', altgr = '@'),
            KeyCap(0x20, '3', '#', altgr = '£'),
            KeyCap(0x21, '4', '¤', altgr = '$'),
            KeyCap(0x22, '5', '%', altgr = '€'),
            KeyCap(0x23, '6', '&'),
            KeyCap(0x24, '7', '/', altgr = '{'),
            KeyCap(0x25, '8', '(', altgr = '['),
            KeyCap(0x26, '9', ')', altgr = ']'),
            KeyCap(0x27, '0', '=', altgr = '}'),
            KeyCap(0x2D, '+', '?', altgr = '\\'),
            // 0x2E (0'dan sonraki ikinci tuş): akut/gravis ÖLÜ TUŞ — atlanıyor
            KeyCap(0x2F, 'å', 'Å'),
            // 0x30 (P'den sonraki ikinci tuş): ¨/^ ÖLÜ TUŞ — atlanıyor
            KeyCap(0x33, 'ö', 'Ö'),
            KeyCap(0x34, 'ä', 'Ä'),
            KeyCap(0x32, '\'', '*'),
            KeyCap(0x35, '§', '½'),
            KeyCap(0x36, ',', ';'),
            KeyCap(0x37, '.', ':'),
            KeyCap(0x38, '-', '_'),
            KeyCap(0x64, '<', '>', altgr = '|')
        )
    )

    private val PT: Map<Char, KeyStroke> = PT_TABLE.toMap()

    /**
     * Brezilya (ABNT2, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un arşivlenmiş resmî düzen belgesi
     * (learn.microsoft.com/msdn-files/resources/msdn/goglobal/keyboards/kbdbr.html),
     * ham Unicode ad etiketleriyle, birebir okuma.
     *
     * ABNT2 standart ISO'dan FAZLA tuş taşıyor (kayıtlarda "shiftler arası
     * 12 tuş" — ABD'de 10). İki ekstra konum var: L'den sonra ç, ondan
     * sonra ~ ölü tuş, ondan sonra ] (0x31 — ISO'nun "Non-US #" konumu).
     * M'den sonra ise gerçek bir "/" tuşu var (ABNT_C1, HID International1
     * = 0x87) — çoğu düzende "/" bir OEM konumundayken burada ayrı, özel bir
     * tuş. Akut (´), sirkumfleks (~) ve çift nokta (¨) ÖLÜ TUŞ, tabloda yok.
     * AltGr+C → ₢ (eski cruzeiro işareti) Wikipedia'yla da doğrulandı.
     *
     * Not: HID usage 0x87 bu kod tabanında ilk kez kullanılıyor — diğer
     * düzenlerin hepsi 0x04-0x38 ve 0x64 aralığında kalıyor.
     * `BtHidManager.stroke.usage.toByte()` her kodu aynı şekilde tek bayt
     * olarak rapora yazıyor, yani ACTARIM tarafında özel bir şey gerekmiyor
     * (doğrulandı, kod okunarak). Kalan tek soru diğer tüm düzenlerle aynı:
     * hedef işletim sisteminin bu konumu gerçekten ABNT_C1 "/" tuşu olarak
     * yorumlaması — cihazda deneme bekliyor (R-003).
     */
    private val BR_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(0x06 to KeyCap(0x06, 'c', 'C', altgr = '₢'))
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x1E, '1', '!'),
            KeyCap(0x1F, '2', '@'),
            KeyCap(0x20, '3', '#'),
            KeyCap(0x21, '4', '$'),
            KeyCap(0x22, '5', '%'),
            KeyCap(0x23, '6'), // shift: ¨ ÖLÜ TUŞ — yalnız base var
            KeyCap(0x24, '7', '&'),
            KeyCap(0x25, '8', '*'),
            KeyCap(0x26, '9', '('),
            KeyCap(0x27, '0', ')'),
            KeyCap(0x2D, '-', '_'),
            KeyCap(0x2E, '=', '+'),
            // 0x2F (P'den sonra): ´ ÖLÜ TUŞ — atlanıyor
            KeyCap(0x30, '[', '{'),
            KeyCap(0x31, ']', '}'),
            KeyCap(0x33, 'ç', 'Ç'),
            // 0x34 (Ç'den sonra): ~ ÖLÜ TUŞ — atlanıyor
            KeyCap(0x35, '\'', '"'),
            KeyCap(0x36, ',', '<'),
            KeyCap(0x37, '.', '>'),
            KeyCap(0x38, ';', ':'),
            KeyCap(0x64, '\\', '|'),
            KeyCap(0x87, '/', '?')
        )
    )

    private val SE: Map<Char, KeyStroke> = SE_TABLE.toMap()
    private val BR: Map<Char, KeyStroke> = BR_TABLE.toMap()

    /**
     * İsviçre Almancası (QWERTZ, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdsg.html), ham Unicode
     * ad etiketleriyle, birebir okuma.
     *
     * Almanca gibi Y/Z yer değiştirir. Şifre karakterleri açısından en
     * kritik fark: rakam satırının SHIFT hâli tamamen farklı simgeler
     * üretir (+ " * ç % & / ( ) = ? — Almanca'nın ! " § $ % & / ( ) = 'inden
     * apayrı), yani "hangi düzen seçili" burada özellikle önemli.
     *
     * Üst-sol köşe tuşu (0x35) base=§, shift=°; bu ikisi digit-4 ve digit-5
     * konumlarının AltGr hâlinde DE TEKRAR üretiliyor (gerçek klavyede iki
     * yoldan erişilebilir simgeler) — tabloda çakışma olmasın diye yalnız
     * 0x35'teki tanım bırakıldı, digit-4/5'in AltGr'ı atlandı (B-104'teki
     * gibi bir test kırılması değil, gerçek fiziksel fazlalık).
     *
     * Sirkumfleks (^), gravis (`) ve tilde (~) konumu (0x2E) HER ÜÇ
     * değiştiricide de ÖLÜ TUŞ — tabloda hiç yok. Buna karşın ¨ konumu
     * (0x30) yalnız BASE hâlinde ölü tuştur; shift'i ! ve AltGr'ı ] gerçek
     * karakterdir — bu satır bilinçli olarak base'siz yazıldı.
     */
    private val CH_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x08 to KeyCap(0x08, 'e', 'E', altgr = '€'),
                0x1C to KeyCap(0x1C, 'z', 'Z'),
                0x1D to KeyCap(0x1D, 'y', 'Y')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x35, '§', '°'),
            KeyCap(0x1E, '1', '+', altgr = '¦'),
            KeyCap(0x1F, '2', '"', altgr = '@'),
            KeyCap(0x20, '3', '*', altgr = '#'),
            KeyCap(0x21, '4', 'ç'), // altgr: ° — 0x35'te zaten var, çakışma olmasın diye atlandı
            KeyCap(0x22, '5', '%'), // altgr: § — 0x35'te zaten var, çakışma olmasın diye atlandı
            KeyCap(0x23, '6', '&', altgr = '¬'),
            KeyCap(0x24, '7', '/', altgr = '|'),
            KeyCap(0x25, '8', '(', altgr = '¢'),
            KeyCap(0x26, '9', ')'),
            KeyCap(0x27, '0', '='),
            KeyCap(0x2D, '\'', '?'), // altgr: ´ ÖLÜ TUŞ — atlanıyor
            // 0x2E (üst sıranın son tuşu): ^ / ` / ~ ÜÇÜ DE ÖLÜ TUŞ — atlanıyor
            KeyCap(0x2F, 'ü', 'è', altgr = '['),
            KeyCap(0x30, shift = '!', altgr = ']'), // base: ¨ ÖLÜ TUŞ
            KeyCap(0x32, '$', '£'),
            KeyCap(0x33, 'ö', 'é', altgr = '{'),
            KeyCap(0x34, 'ä', 'à', altgr = '}'),
            KeyCap(0x36, ',', ';'),
            KeyCap(0x37, '.', ':'),
            KeyCap(0x38, '-', '_'),
            KeyCap(0x64, '<', '>', altgr = '\\')
        )
    )

    private val CH: Map<Char, KeyStroke> = CH_TABLE.toMap()

    /**
     * Hollandaca (Felemenkçe, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdne.html), ham Unicode
     * ad etiketleriyle, birebir okuma.
     *
     * Harf sırası tam QWERTY (İsviçre/Almanca'nın aksine Y/Z YER
     * DEĞİŞTİRMEZ). Şifre karakterleri açısından en kritik fark yine
     * rakam satırının SHIFT hâli: ! " # $ % & _ ( ) ' — 7 üstünde `&`
     * değil `_` var, bu düzene özgü bir tuzak.
     *
     * ¨ (0x2F: base ¨, shift ^) ve ´ (0x34: base ´, shift `) konumlarının
     * HER İKİ değiştiricisi de ÖLÜ TUŞTUR — bu iki konum tabloda hiç yok.
     * Buna karşın ° konumu (0x2E) yalnız BASE'de gerçek karakter, shift'i
     * (~) ve AltGr'ı (¸) ikisi de ölü tuş — yalnız base yazıldı.
     */
    private val NL_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x06 to KeyCap(0x06, 'c', 'C', altgr = '¢'),
                0x08 to KeyCap(0x08, 'e', 'E', altgr = '€'),
                0x10 to KeyCap(0x10, 'm', 'M', altgr = 'µ'),
                0x15 to KeyCap(0x15, 'r', 'R', altgr = '¶'),
                0x16 to KeyCap(0x16, 's', 'S', altgr = 'ß'),
                0x1B to KeyCap(0x1B, 'x', 'X', altgr = '»'),
                0x1D to KeyCap(0x1D, 'z', 'Z', altgr = '«')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x35, '@', '§'),
            KeyCap(0x1E, '1', '!', altgr = '¹'),
            KeyCap(0x1F, '2', '"', altgr = '²'),
            KeyCap(0x20, '3', '#', altgr = '³'),
            KeyCap(0x21, '4', '$', altgr = '¼'),
            KeyCap(0x22, '5', '%', altgr = '½'),
            KeyCap(0x23, '6', '&', altgr = '¾'),
            KeyCap(0x24, '7', '_', altgr = '£'),
            KeyCap(0x25, '8', '(', altgr = '{'),
            KeyCap(0x26, '9', ')', altgr = '}'),
            KeyCap(0x27, '0', '\''),
            KeyCap(0x2D, '/', '?', altgr = '\\'),
            KeyCap(0x2E, '°'), // shift: ~ ölü, altgr: ¸ ölü — yalnız base var
            // 0x2F (P'den sonra): base ¨ / shift ^ İKİSİ DE ÖLÜ TUŞ — atlanıyor
            KeyCap(0x30, '*', '|'),
            KeyCap(0x32, '<', '>'),
            KeyCap(0x33, '+', '±'),
            // 0x34 (+'dan sonra): base ´ / shift ` İKİSİ DE ÖLÜ TUŞ — atlanıyor
            KeyCap(0x36, ',', ';'),
            KeyCap(0x37, '.', ':', altgr = '·'),
            KeyCap(0x38, '-', '='),
            KeyCap(0x64, ']', '[', altgr = '¦')
        )
    )

    private val NL: Map<Char, KeyStroke> = NL_TABLE.toMap()

    /**
     * Çekçe (QWERTZ, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdcz.html), ham Unicode
     * ad etiketleriyle, birebir okuma — iki ayrı geçişte doğrulandı (ikinci
     * geçiş `/` karakterinin tam konumunu netleştirmek için yapıldı, ilk
     * okumada 0x2F'in shift değeri yanlış — Ú — okunmuştu, ikinci taramada
     * gerçek değerin `/` olduğu görüldü ve düzeltildi; aynı hata 0x33'te de
     * vardı: Ů değil, `"` çıkıyor).
     *
     * En kritik ve şifreler için TEHLİKELİ fark: rakam satırının BASE hâli
     * rakam değil, aksanlı Çekçe harf üretir (+ ě š č ř ž ý á í é); rakamlar
     * yalnız SHIFT'te çıkar. Yanlış düzen seçilirse bir şifredeki HER rakam
     * bambaşka bir harfe dönüşür — bu düzenin en isabetsiz olduğu senaryo.
     *
     * AltGr satırı çoğunlukla Lehçe/Baltık harfleri BİRLEŞTİRMEK için ölü
     * tuşlardan oluşuyor (breve, ogonek, çift akut, vb.) — Çekçenin kendisi
     * bunları kullanmıyor, tabloda hiçbiri yok. Gerçek klavyede `\` iki
     * yoldan (ISO ekstra tuşu VE AltGr+Q), `|` de iki yoldan (aynı tuş VE
     * AltGr+W) erişiliyor — çakışma testine takılmasın diye yalnız ISO
     * ekstra tuşundaki (0x64) tanım bırakıldı, AltGr+Q/AltGr+W'daki
     * tekrarlar atlandı (gerçek fiziksel fazlalık, Swiss/İsviçre'deki §/°
     * örneğiyle aynı sınıf).
     */
    private val CZ_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x05 to KeyCap(0x05, 'b', 'B', altgr = '{'),
                0x06 to KeyCap(0x06, 'c', 'C', altgr = '&'),
                0x07 to KeyCap(0x07, 'd', 'D', altgr = 'Đ'),
                0x08 to KeyCap(0x08, 'e', 'E', altgr = '€'),
                0x09 to KeyCap(0x09, 'f', 'F', altgr = '['),
                0x0A to KeyCap(0x0A, 'g', 'G', altgr = ']'),
                0x0E to KeyCap(0x0E, 'k', 'K', altgr = 'ł'),
                0x0F to KeyCap(0x0F, 'l', 'L', altgr = 'Ł'),
                0x11 to KeyCap(0x11, 'n', 'N', altgr = '}'),
                0x16 to KeyCap(0x16, 's', 'S', altgr = 'đ'),
                0x19 to KeyCap(0x19, 'v', 'V', altgr = '@'),
                0x1B to KeyCap(0x1B, 'x', 'X', altgr = '#'),
                0x1C to KeyCap(0x1C, 'z', 'Z'),
                0x1D to KeyCap(0x1D, 'y', 'Y')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x35, ';'),
            KeyCap(0x1E, '+', '1', altgr = '~'),
            KeyCap(0x1F, 'ě', '2'),
            KeyCap(0x20, 'š', '3'),
            KeyCap(0x21, 'č', '4'),
            KeyCap(0x22, 'ř', '5'),
            KeyCap(0x23, 'ž', '6'),
            KeyCap(0x24, 'ý', '7'),
            KeyCap(0x25, 'á', '8'),
            KeyCap(0x26, 'í', '9'),
            KeyCap(0x27, 'é', '0'),
            KeyCap(0x2D, '=', '%'), // altgr: ¨ ÖLÜ TUŞ — atlanıyor
            // 0x2E (=/%'den sonra): base ´ / shift ˇ İKİSİ DE ÖLÜ TUŞ — atlanıyor
            KeyCap(0x2F, 'ú', '/', altgr = '÷'),
            KeyCap(0x30, ')', '(', altgr = '×'),
            // 0x31 (ANSI-tipi üçüncü tuş, ISO fiziksel klavyede karşılığı yok): atlanıyor
            KeyCap(0x33, 'ů', '"', altgr = '$'),
            KeyCap(0x34, '§', '!', altgr = 'ß'),
            KeyCap(0x36, ',', '?'),
            KeyCap(0x37, '.', ':'),
            KeyCap(0x38, '-', '_', altgr = '*'),
            KeyCap(0x64, '\\', '|') // altgr+Q/altgr+W'de de var, çakışma olmasın diye burada bırakıldı
        )
    )

    private val CZ: Map<Char, KeyStroke> = CZ_TABLE.toMap()

    /**
     * Macarca (QWERTZ, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdhu.html), ham Unicode
     * ad etiketleriyle, iki ayrı geçişte birebir okuma (ikincisi `?`
     * karakterinin konumunu netleştirmek ve AltGr+M/AltGr+. konumundaki
     * şüpheli tekrarı doğrulamak için — ikisi de aynı çıktı, gerçek).
     *
     * En göze çarpan tuzak: rakam-0 klavye konumu (9'dan sonraki tuş, 0x27)
     * `ö` üretir — `0` üst-sol köşe tuşuna (0x35) TAŞINMIŞTIR. Yanlış
     * düzende `0` yazmaya çalışan biri farkında olmadan `ö` yazar.
     *
     * Gerçek klavyede `<`/`>` iki yoldan erişiliyor (ISO ekstra tuşu VE
     * Y tuşunun AltGr'ı, AYRICA M tuşu ve nokta tuşunun AltGr'ı da aynı
     * karakterleri veriyor — kaynakta dört kez tekrarlanıyor). Çakışma
     * testine takılmasın diye yalnız ISO ekstra tuşu (`<`) ve Y tuşu (`>`)
     * bırakıldı, M ve nokta tuşundaki AltGr tekrarları atlandı.
     *
     * AltGr satırındaki ölü tuşlar (caron, sirkumfleks, breve, ogonek,
     * nokta-üstü, çift akut, çift nokta, sedil) Lehçe/Baltık/Slovak
     * birleştirmesi için var — Macarcanın kendisi kullanmıyor, tabloda yok.
     * Digit-7'nin AltGr'ı (`) — Çekçenin aksine — burada ÖLÜ TUŞ DEĞİL,
     * gerçek karakter.
     */
    private val HU_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(
                0x04 to KeyCap(0x04, 'a', 'A', altgr = 'ä'),
                0x05 to KeyCap(0x05, 'b', 'B', altgr = '{'),
                0x06 to KeyCap(0x06, 'c', 'C', altgr = '&'),
                0x07 to KeyCap(0x07, 'd', 'D', altgr = 'Đ'),
                0x08 to KeyCap(0x08, 'e', 'E', altgr = 'Ä'),
                0x09 to KeyCap(0x09, 'f', 'F', altgr = '['),
                0x0A to KeyCap(0x0A, 'g', 'G', altgr = ']'),
                0x0E to KeyCap(0x0E, 'k', 'K', altgr = 'ł'),
                0x0F to KeyCap(0x0F, 'l', 'L', altgr = 'Ł'),
                0x11 to KeyCap(0x11, 'n', 'N', altgr = '}'),
                0x14 to KeyCap(0x14, 'q', 'Q', altgr = '\\'),
                0x16 to KeyCap(0x16, 's', 'S', altgr = 'đ'),
                0x18 to KeyCap(0x18, 'u', 'U', altgr = '€'),
                0x19 to KeyCap(0x19, 'v', 'V', altgr = '@'),
                0x1A to KeyCap(0x1A, 'w', 'W', altgr = '|'),
                0x1B to KeyCap(0x1B, 'x', 'X', altgr = '#'),
                0x1C to KeyCap(0x1C, 'z', 'Z'),
                0x1D to KeyCap(0x1D, 'y', 'Y', altgr = '>')
            )
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x35, '0', '§'),
            KeyCap(0x1E, '1', '\'', altgr = '~'),
            KeyCap(0x1F, '2', '"'),
            KeyCap(0x20, '3', '+'),
            KeyCap(0x21, '4', '!'),
            KeyCap(0x22, '5', '%'),
            KeyCap(0x23, '6', '/'),
            KeyCap(0x24, '7', '=', altgr = '`'),
            KeyCap(0x25, '8', '('),
            KeyCap(0x26, '9', ')'),
            KeyCap(0x27, 'ö', 'Ö'),
            KeyCap(0x2D, 'ü', 'Ü'),
            KeyCap(0x2E, 'ó', 'Ó'),
            KeyCap(0x2F, 'ő', 'Ő', altgr = '÷'),
            KeyCap(0x30, 'ú', 'Ú', altgr = '×'),
            KeyCap(0x32, 'ű', 'Ű', altgr = '¤'),
            KeyCap(0x33, 'é', 'É', altgr = '$'),
            KeyCap(0x34, 'á', 'Á', altgr = 'ß'),
            KeyCap(0x36, ',', '?', altgr = ';'),
            KeyCap(0x37, '.', ':'), // altgr'de de > var — Y tuşuyla çakışmasın diye atlandı
            KeyCap(0x38, '-', '_', altgr = '*'),
            KeyCap(0x64, 'í', 'Í', altgr = '<')
        )
    )

    private val HU: Map<Char, KeyStroke> = HU_TABLE.toMap()

    /**
     * Norveççe (Nordic, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdno.html), ham Unicode
     * ad etiketleriyle, birebir okuma. Standart QWERTY harf sırası (Y/Z
     * yer değiştirmiyor).
     *
     * Danca (kbdda.html) İLK BAKIŞTA aynı düzenmiş gibi görünüyor ama
     * ayrı bir karşılaştırma taramasında GERÇEK farklar bulundu: üst-sol
     * köşe tuşu Norveç'te `|` / Danimarka'da `½`; P'den sonraki tuş
     * Norveç'te gerçek `\` iken Danimarka'da ÖLÜ akut ve AltGr'ı `|`;
     * L'den sonraki iki tuşun sırası TERS (Norveç: ø,æ — Danimarka: æ,ø).
     * Bu yüzden — Çekçe/Slovakça'daki gibi — yalnız Norveççe kaynaklandı,
     * Danca AYRI bir düzen olarak kuyrukta kalmaya devam ediyor.
     *
     * Gerçek klavyede `€` iki yoldan erişiliyor (AltGr+5 VE AltGr+E) —
     * çakışma olmasın diye yalnız AltGr+5 bırakıldı. `¨` konumu (0x30)
     * HER ÜÇ değiştiricide de ÖLÜ TUŞ — tabloda hiç yok.
     */
    private val NO_TABLE = KeyTable(
        KeyTable.latinHarfler(
            istisnalar = mapOf(0x10 to KeyCap(0x10, 'm', 'M', altgr = 'µ'))
        ) + KeyTable.ORTAK + listOf(
            KeyCap(0x35, '|', '§'),
            KeyCap(0x1E, '1', '!'),
            KeyCap(0x1F, '2', '"', altgr = '@'),
            KeyCap(0x20, '3', '#', altgr = '£'),
            KeyCap(0x21, '4', '¤', altgr = '$'),
            KeyCap(0x22, '5', '%', altgr = '€'),
            KeyCap(0x23, '6', '&'),
            KeyCap(0x24, '7', '/', altgr = '{'),
            KeyCap(0x25, '8', '(', altgr = '['),
            KeyCap(0x26, '9', ')', altgr = ']'),
            KeyCap(0x27, '0', '=', altgr = '}'),
            KeyCap(0x2D, '+', '?'), // altgr: ´ ÖLÜ TUŞ — atlanıyor
            KeyCap(0x2E, '\\'), // shift: ` ÖLÜ TUŞ, altgr: ´ ÖLÜ TUŞ — yalnız base var
            KeyCap(0x2F, 'å', 'Å'),
            // 0x30 (å'dan sonra): base ¨ / shift ^ / altgr ~ ÜÇÜ DE ÖLÜ TUŞ — atlanıyor
            KeyCap(0x32, '\'', '*'),
            KeyCap(0x33, 'ø', 'Ø'),
            KeyCap(0x34, 'æ', 'Æ'),
            KeyCap(0x36, ',', ';'),
            KeyCap(0x37, '.', ':'),
            KeyCap(0x38, '-', '_'),
            KeyCap(0x64, '<', '>')
        )
    )

    private val NO: Map<Char, KeyStroke> = NO_TABLE.toMap()

    /**
     * Belçika Fransızcası (AZERTY, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdbe_2.html — "Belgian
     * French", modern Windows'un varsayılanı; eski "Belgian Period"
     * (kbdbe_1) ve Felemenkçe-Belçika "Belgian Comma" (kbdbene) varyantları
     * AYRI düzenler, kaynaklanmadı), ham Unicode ad etiketleriyle, birebir
     * okuma. Fransızca (FR_TABLE) ile aynı AZERTY aile yapısı — A/Q, Z/W
     * yer değiştirir, M ";"nin yerine taşınır — ama noktalama BAMBAŞKA.
     *
     * Gerçek klavyede iki çakışma bulundu: `{` iki digit tuşunda (4 VE 9)
     * göründü, `[` hem digit-5'in hem de ^-ölü-tuşunun AltGr'ında göründü
     * — her ikisinde de yalnız İLK/digit-satırındaki tanım bırakıldı,
     * ikincisi atlandı (Macarca'daki tekrarla aynı sınıf).
     *
     * `0x2F` (^ ölü tuş, P'den sonra) HER ÜÇ değiştiricide de ölü/çakışan —
     * tabloda hiç yok. `0x31` konumu FR_TABLE'daki gibi gerçek (µ/£).
     */
    private val BE_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x14, 'a', shift = 'A'),
            KeyCap(0x1A, 'z', shift = 'Z'),
            KeyCap(0x08, 'e', shift = 'E', altgr = '€'),
            KeyCap(0x15, 'r', shift = 'R'),
            KeyCap(0x17, 't', shift = 'T'),
            KeyCap(0x1C, 'y', shift = 'Y'),
            KeyCap(0x18, 'u', shift = 'U'),
            KeyCap(0x0C, 'i', shift = 'I'),
            KeyCap(0x12, 'o', shift = 'O'),
            KeyCap(0x13, 'p', shift = 'P'),
            KeyCap(0x04, 'q', shift = 'Q'),
            KeyCap(0x16, 's', shift = 'S'),
            KeyCap(0x07, 'd', shift = 'D'),
            KeyCap(0x09, 'f', shift = 'F'),
            KeyCap(0x0A, 'g', shift = 'G'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0D, 'j', shift = 'J'),
            KeyCap(0x0E, 'k', shift = 'K'),
            KeyCap(0x0F, 'l', shift = 'L'),
            KeyCap(0x33, 'm', shift = 'M'),
            KeyCap(0x1D, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x11, 'n', shift = 'N'),
            KeyCap(0x35, '²', '³'),
            KeyCap(0x1E, '&', '1', altgr = '|'),
            KeyCap(0x1F, 'é', '2', altgr = '@'),
            KeyCap(0x20, '"', '3', altgr = '#'),
            KeyCap(0x21, '\'', '4', altgr = '{'),
            KeyCap(0x22, '(', '5', altgr = '['),
            KeyCap(0x23, '§', '6', altgr = '^'),
            KeyCap(0x24, 'è', '7'),
            KeyCap(0x25, '!', '8'),
            KeyCap(0x26, 'ç', '9'), // altgr'de de { var — digit-4'le çakışmasın diye atlandı
            KeyCap(0x27, 'à', '0', altgr = '}'),
            KeyCap(0x2D, ')', '°'),
            KeyCap(0x2E, '-', '_'),
            // 0x2F (P'den sonra): ^/¨ İKİSİ DE ÖLÜ TUŞ, altgr'deki [ de
            // digit-5'le çakışıyor — üçü de atlanıyor, konum tabloda yok
            KeyCap(0x30, '$', '*', altgr = ']'),
            KeyCap(0x31, 'µ', '£'), // altgr: ` ÖLÜ TUŞ — atlanıyor
            KeyCap(0x34, 'ù', '%'), // altgr: ´ ÖLÜ TUŞ — atlanıyor
            KeyCap(0x10, ',', '?'), // eski "m" konumu — M harfi 0x33'e taşındığı için boşta
            KeyCap(0x36, ';', '.'),
            KeyCap(0x37, ':', '/'),
            KeyCap(0x38, '=', '+'),
            KeyCap(0x64, '<', '>', altgr = '\\')
        )
    )

    private val BE: Map<Char, KeyStroke> = BE_TABLE.toMap()

    /**
     * Dvorak (Basitleştirilmiş, ABD) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbddv.html), ham Unicode
     * ad etiketleriyle, birebir okuma. AltGr YOK — beşinci sıra "Alt" tuşunu
     * iki kez gösteriyor, AltGr hiç yok (ABD tabanlı düzenlerin ortak
     * özelliği); ölü tuş da yok.
     *
     * Gerçek klavyede `\` ve `|` iki yoldan erişiliyor (Enter'ın solundaki
     * OEM tuşu VE ISO ekstra tuşu) — çakışma olmasın diye yalnız ISO ekstra
     * tuşu (0x64) bırakıldı, öbürü (0x31) tamamen atlandı (tek işlevi bu
     * ikiliydi, başka hiçbir karakter üretmiyor).
     */
    private val DV_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'x', shift = 'X'),
            KeyCap(0x06, 'j', shift = 'J'),
            KeyCap(0x07, 'e', shift = 'E'),
            KeyCap(0x08, '.', shift = '>'),
            KeyCap(0x09, 'u', shift = 'U'),
            KeyCap(0x0A, 'i', shift = 'I'),
            KeyCap(0x0B, 'd', shift = 'D'),
            KeyCap(0x0C, 'c', shift = 'C'),
            KeyCap(0x0D, 'h', shift = 'H'),
            KeyCap(0x0E, 't', shift = 'T'),
            KeyCap(0x0F, 'n', shift = 'N'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'b', shift = 'B'),
            KeyCap(0x12, 'r', shift = 'R'),
            KeyCap(0x13, 'l', shift = 'L'),
            KeyCap(0x14, '\'', shift = '"'),
            KeyCap(0x15, 'p', shift = 'P'),
            KeyCap(0x16, 'o', shift = 'O'),
            KeyCap(0x17, 'y', shift = 'Y'),
            KeyCap(0x18, 'g', shift = 'G'),
            KeyCap(0x19, 'k', shift = 'K'),
            KeyCap(0x1A, ',', shift = '<'),
            KeyCap(0x1B, 'q', shift = 'Q'),
            KeyCap(0x1C, 'f', shift = 'F'),
            KeyCap(0x1D, ';', shift = ':'),
            KeyCap(0x35, '`', '~'),
            KeyCap(0x1E, '1', '!'), KeyCap(0x1F, '2', '@'), KeyCap(0x20, '3', '#'),
            KeyCap(0x21, '4', '$'), KeyCap(0x22, '5', '%'), KeyCap(0x23, '6', '^'),
            KeyCap(0x24, '7', '&'), KeyCap(0x25, '8', '*'), KeyCap(0x26, '9', '('),
            KeyCap(0x27, '0', ')'),
            KeyCap(0x2D, '[', '{'), KeyCap(0x2E, ']', '}'),
            KeyCap(0x2F, '/', '?'), KeyCap(0x30, '=', '+'),
            // 0x31 (Enter'ın solu): \/| — 0x64'le birebir çakışıyor, atlanıyor
            KeyCap(0x33, 's', 'S'), KeyCap(0x34, '-', '_'),
            KeyCap(0x36, 'w', 'W'), KeyCap(0x37, 'v', 'V'), KeyCap(0x38, 'z', 'Z'),
            KeyCap(0x64, '\\', '|')
        )
    )

    private val DV: Map<Char, KeyStroke> = DV_TABLE.toMap()

    /**
     * Colemak (ABD tabanlı) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdcmk.html), ham
     * Unicode ad etiketleriyle, birebir okuma. Base/Shift tarafı Dvorak
     * kadar temiz — hiç çelişki yok. AltGr tarafı BAMBAŞKA bir hikâye:
     * Colemak'ın kendi "Extended" varyantı, düzinelerce dilin aksanlı
     * harfini birleştirme yoluyla (ölü tuş) yazmak için dev bir uluslararası
     * katman taşıyor (İskandinav, Baltık, Orta Avrupa, İspanyolca vb.) —
     * bunların HİÇBİRİ şifre karakter kümesinde yok ve neredeyse tamamı ölü
     * tuş, bu yüzden tabloya hiç alınmadı (gerçek bir eksiklik değil,
     * kasıtlı kapsam dışı bırakma — bu düzenin AltGr'ı zaten "aksan
     * kompozisyonu" için var, şifre yazmak için değil).
     *
     * Gerçek klavyede `-`/`_` iki yoldan erişiliyor (satır 1'deki normal
     * tire tuşu VE ISO ekstra tuşu) — çakışma olmasın diye ISO ekstra tuşu
     * (0x64) bu kez tamamen atlandı, satır 1'deki tanım (kod tabanındaki
     * her düzenle tutarlı konum) bırakıldı.
     */
    private val CK_TABLE = KeyTable(
        KeyTable.ORTAK + listOf(
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x05, 'b', shift = 'B'),
            KeyCap(0x06, 'c', shift = 'C'),
            KeyCap(0x07, 's', shift = 'S'),
            KeyCap(0x08, 'f', shift = 'F'),
            KeyCap(0x09, 't', shift = 'T'),
            KeyCap(0x0A, 'd', shift = 'D'),
            KeyCap(0x0B, 'h', shift = 'H'),
            KeyCap(0x0C, 'u', shift = 'U'),
            KeyCap(0x0D, 'n', shift = 'N'),
            KeyCap(0x0E, 'e', shift = 'E'),
            KeyCap(0x0F, 'i', shift = 'I'),
            KeyCap(0x10, 'm', shift = 'M'),
            KeyCap(0x11, 'k', shift = 'K'),
            KeyCap(0x12, 'y', shift = 'Y'),
            KeyCap(0x13, ';', shift = ':'),
            KeyCap(0x14, 'q', shift = 'Q'),
            KeyCap(0x15, 'p', shift = 'P'),
            KeyCap(0x16, 'r', shift = 'R'),
            KeyCap(0x17, 'g', shift = 'G'),
            KeyCap(0x18, 'l', shift = 'L'),
            KeyCap(0x19, 'v', shift = 'V'),
            KeyCap(0x1A, 'w', shift = 'W'),
            KeyCap(0x1B, 'x', shift = 'X'),
            KeyCap(0x1C, 'j', shift = 'J'),
            KeyCap(0x1D, 'z', shift = 'Z'),
            KeyCap(0x33, 'o', shift = 'O'),
            KeyCap(0x34, '\'', shift = '"'),
            KeyCap(0x35, '`', '~'),
            KeyCap(0x1E, '1', '!'), KeyCap(0x1F, '2', '@'), KeyCap(0x20, '3', '#'),
            KeyCap(0x21, '4', '$'), KeyCap(0x22, '5', '%'), KeyCap(0x23, '6', '^'),
            KeyCap(0x24, '7', '&'), KeyCap(0x25, '8', '*'), KeyCap(0x26, '9', '('),
            KeyCap(0x27, '0', ')'),
            KeyCap(0x2D, '-', '_'), KeyCap(0x2E, '=', '+'),
            KeyCap(0x2F, '[', '{'), KeyCap(0x30, ']', '}'), KeyCap(0x31, '\\', '|'),
            // 0x64 (ISO ekstra tuşu): -/_ — 0x2D'yle birebir çakışıyor, atlanıyor
            KeyCap(0x36, ',', '<'), KeyCap(0x37, '.', '>'), KeyCap(0x38, '/', '?')
        )
    )

    private val CK: Map<Char, KeyStroke> = CK_TABLE.toMap()

    /**
     * BÉPO (Fransızca, ISO) — konum tablosu.
     *
     * Kaynak: Microsoft'un resmî klavye düzeni belgesi
     * (learn.microsoft.com/globalization/keyboards/kbdfrnb.html), ham
     * Unicode ad etiketleriyle, iki geçişte okuma — ilk geçişte rakam
     * satırının BASE ve SHIFT hâlleri arasında sayfa üç farklı veri seti
     * gösteriyordu (CapsLock-açık bloğu rakam satırını da değiştiriyormuş
     * gibi görünüyordu); hedefli ikinci taramada satır4/satır5 ikonlarına
     * (CapsLock/Shift "enabled" etiketleri) bakılarak doğru BASE/SHIFT
     * çifti netleştirildi — CapsLock bizim modelimizde zaten önemsiz
     * (her tuş vuruşu açık Shift biti taşıyor, cihazın CapsLock durumuna
     * hiç bakmıyoruz), bu yüzden o üçüncü veri setini atladık.
     *
     * En çarpıcı tasarım: rakam satırının BASE hâli rakam değil noktalama
     * üretir ($ " « » ( ) @ + - / * = %), rakamlar yalnız SHIFT'te (1-9,0).
     * Çekçedeki gibi — yanlış düzende her rakam bambaşka bir sembole döner.
     * Virgül ve noktalı virgül ev sırasında (G tuşunun yerinde), nokta ve
     * iki nokta V tuşunun yerinde — Fransızcada bu ikisi çok sık geçtiği
     * için bilinçli bir ergonomi kararı.
     *
     * `&` `[` `]` `{` `}` `_` şifre karakter kümesinde ZORUNLU ama hiçbiri
     * base/shift'te yok — hepsi AltGr'de (P tuşu, digit-4/5, X/C tuşları,
     * hatta BOŞLUK tuşu: AltGr+Boşluk = `_`, bu yüzden `KeyTable.ORTAK`
     * kullanılmadı, boşluk satırı elle yazıldı). Kalan AltGr sembolleri
     * (ölü tuşlar, matematik işaretleri, uluslararası aksan kompozisyonu)
     * Colemak'taki gibi kapsam dışı bırakıldı — hiçbiri gerekli değil.
     */
    private val BEPO_TABLE = KeyTable(
        listOf(KeyCap(0x2C, ' ', altgr = '_'), KeyCap(0x28, '\n'), KeyCap(0x2B, '\t')) + listOf(
            KeyCap(0x14, 'b', shift = 'B'),
            KeyCap(0x1A, 'é', shift = 'É'),
            KeyCap(0x08, 'p', shift = 'P', altgr = '&'),
            KeyCap(0x15, 'o', shift = 'O'),
            KeyCap(0x17, 'è', shift = 'È'),
            KeyCap(0x1C, shift = '!'), // base: ^ ÖLÜ TUŞ
            KeyCap(0x18, 'v', shift = 'V'),
            KeyCap(0x0C, 'd', shift = 'D'),
            KeyCap(0x12, 'l', shift = 'L'),
            KeyCap(0x13, 'j', shift = 'J'),
            KeyCap(0x2F, 'z', shift = 'Z'),
            KeyCap(0x30, 'w', shift = 'W'),
            KeyCap(0x04, 'a', shift = 'A'),
            KeyCap(0x16, 'u', shift = 'U'),
            KeyCap(0x07, 'i', shift = 'I'),
            KeyCap(0x09, 'e', shift = 'E'),
            KeyCap(0x0A, ',', shift = ';'),
            KeyCap(0x0B, 'c', shift = 'C'),
            KeyCap(0x0D, 't', shift = 'T'),
            KeyCap(0x0E, 's', shift = 'S'),
            KeyCap(0x0F, 'r', shift = 'R'),
            KeyCap(0x33, 'n', shift = 'N'),
            KeyCap(0x34, 'm', shift = 'M'),
            KeyCap(0x32, 'ç', shift = 'Ç'),
            KeyCap(0x64, 'ê', shift = 'Ê'),
            KeyCap(0x1D, 'à', shift = 'À'),
            KeyCap(0x1B, 'y', shift = 'Y', altgr = '{'),
            KeyCap(0x06, 'x', shift = 'X', altgr = '}'),
            KeyCap(0x19, '.', shift = ':'),
            KeyCap(0x05, 'k', shift = 'K'),
            KeyCap(0x11, '’', shift = '?'),
            KeyCap(0x10, 'q', shift = 'Q'),
            KeyCap(0x36, 'g', shift = 'G'),
            KeyCap(0x37, 'h', shift = 'H'),
            KeyCap(0x38, 'f', shift = 'F'),
            KeyCap(0x35, '$', '#'),
            KeyCap(0x1E, '"', '1'),
            KeyCap(0x1F, '«', '2'),
            KeyCap(0x20, '»', '3'),
            KeyCap(0x21, '(', '4', altgr = '['),
            KeyCap(0x22, ')', '5', altgr = ']'),
            KeyCap(0x23, '@', '6', altgr = '^'),
            KeyCap(0x24, '+', '7'),
            KeyCap(0x25, '-', '8'),
            KeyCap(0x26, '/', '9'),
            KeyCap(0x27, '*', '0'),
            KeyCap(0x2D, '=', '°'),
            KeyCap(0x2E, '%', '`')
        )
    )

    private val BEPO: Map<Char, KeyStroke> = BEPO_TABLE.toMap()

    /** Tablolar: tutarlılık testleri buradan geçer. */
    internal val TABLES: Map<Layout, KeyTable> = mapOf(
        Layout.US to US_TABLE, Layout.UK to UK_TABLE, Layout.TR_Q to TR_Q_TABLE,
        Layout.DE to DE_TABLE, Layout.FR to FR_TABLE, Layout.ES to ES_TABLE,
        Layout.IT to IT_TABLE, Layout.TR_F to TR_F_TABLE, Layout.PL to PL_TABLE, Layout.PT to PT_TABLE, Layout.SE to SE_TABLE, Layout.BR to BR_TABLE,
        Layout.CH to CH_TABLE, Layout.NL to NL_TABLE, Layout.CZ to CZ_TABLE, Layout.HU to HU_TABLE, Layout.NO to NO_TABLE,
        Layout.BE to BE_TABLE, Layout.DV to DV_TABLE, Layout.CK to CK_TABLE, Layout.BEPO to BEPO_TABLE
    )
}

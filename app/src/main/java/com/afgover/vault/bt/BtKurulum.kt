package com.afgover.vault.bt

import androidx.annotation.StringRes
import com.afgover.vault.R

/**
 * Kurulum sihirbazında bilgisayarın sistemi: eşleştirmenin menü yolu ve
 * deneme yazısı için açılacak not uygulaması sisteme göre değişir.
 *
 * [stable] tercihlerde saklanan, dile bağlı olmayan addır.
 */
enum class PcSistemi(
    val stable: String,
    @StringRes val adRes: Int,
    /** Bilgisayarda Bluetooth aramasını başlatmanın menü yolu. */
    @StringRes val aramaRes: Int,
    @StringRes val notDefteriRes: Int
) {
    WINDOWS("windows", R.string.setup_os_windows, R.string.setup_search_windows, R.string.setup_notepad_windows),
    MACOS("macos", R.string.setup_os_macos, R.string.setup_search_macos, R.string.setup_notepad_macos),
    LINUX("linux", R.string.setup_os_linux, R.string.setup_search_linux, R.string.setup_notepad_linux),
    CHROMEOS("chromeos", R.string.setup_os_chromeos, R.string.setup_search_chromeos, R.string.setup_notepad_chromeos);

    companion object {
        fun ofStable(ad: String?): PcSistemi? = entries.firstOrNull { it.stable == ad }
    }
}

enum class BtKurulumAdimi {
    SISTEM, HAZIRLIK, ESLESTIRME, DENEME, BITTI, DESTEKLENMIYOR;

    /** İlerleme göstergesindeki sıra (1'den başlar); desteklenmiyorsa 0. */
    val sira: Int get() = if (this == DESTEKLENMIYOR) 0 else ordinal + 1

    companion object {
        /** İlerleme göstergesindeki adım sayısı. */
        const val TOPLAM = 5
    }
}

/**
 * Sihirbazın bulunduğu adım telefonun GERÇEK durumundan türetilir; elle
 * ilerletilen bir sayaç değildir. Eşleşme ve bağlantı algılandığı anda
 * sihirbaz kendiliğinden ilerler, bağlantı koparsa kendiliğinden eşleştirme
 * adımına, Bluetooth kapanırsa hazırlığa döner — ekran hiçbir zaman
 * gerçekte olmayan bir durumu "tamam" göstermez.
 */
data class BtKurulumDurumu(
    val destekleniyor: Boolean = true,
    val sistem: PcSistemi? = null,
    val izin: Boolean = false,
    val btAcik: Boolean = false,
    /** Telefon kendini bilgisayara klavye olarak duyuruyor (HID kaydı tamam). */
    val hidHazir: Boolean = false,
    val bagli: Boolean = false,
    /** Kullanıcı deneme yazısını onayladı ya da atladı. */
    val bitti: Boolean = false
) {
    val adim: BtKurulumAdimi
        get() = when {
            !destekleniyor -> BtKurulumAdimi.DESTEKLENMIYOR
            bitti -> BtKurulumAdimi.BITTI
            sistem == null -> BtKurulumAdimi.SISTEM
            !izin || !btAcik || !hidHazir -> BtKurulumAdimi.HAZIRLIK
            !bagli -> BtKurulumAdimi.ESLESTIRME
            else -> BtKurulumAdimi.DENEME
        }
}

/**
 * Deneme yazısı için ilk önerilen düzen. Kullanıcı daha önce bir düzen
 * seçtiyse o kazanır; seçmediyse telefonun dili ve ülkesinden tahmin
 * edilir. Bu yalnız bir başlangıç noktası: doğrusunu deneme yazısı söyler,
 * yanlışsa kullanıcı başka düzen seçip tekrar dener.
 */
fun duzenOnerisi(kayitli: String?, dil: String, ulke: String): HidLayouts.Layout {
    if (kayitli != null) return HidLayouts.readLayout(kayitli)
    return when (dil.lowercase()) {
        "tr" -> HidLayouts.Layout.TR_Q
        "de" -> if (ulke.equals("CH", true)) HidLayouts.Layout.CH else HidLayouts.Layout.DE
        "fr" -> when (ulke.uppercase()) {
            "BE" -> HidLayouts.Layout.BE
            "CH" -> HidLayouts.Layout.CH
            else -> HidLayouts.Layout.FR
        }
        "es" -> HidLayouts.Layout.ES
        "it" -> HidLayouts.Layout.IT
        "pt" -> if (ulke.equals("BR", true)) HidLayouts.Layout.BR else HidLayouts.Layout.PT
        "pl" -> HidLayouts.Layout.PL
        "nl" -> if (ulke.equals("BE", true)) HidLayouts.Layout.BE else HidLayouts.Layout.NL
        "cs" -> HidLayouts.Layout.CZ
        "hu" -> HidLayouts.Layout.HU
        "nb", "nn", "no" -> HidLayouts.Layout.NO
        "sv", "fi" -> HidLayouts.Layout.SE
        "en" -> if (ulke.equals("GB", true) || ulke.equals("IE", true)) HidLayouts.Layout.UK
            else HidLayouts.Layout.US
        else -> HidLayouts.Layout.US
    }
}

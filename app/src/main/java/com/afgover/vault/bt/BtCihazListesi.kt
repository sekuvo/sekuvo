package com.afgover.vault.bt

import android.bluetooth.BluetoothClass
import android.content.SharedPreferences

/** Eşleşmiş cihazın kabaca ne olduğu: ikonu ve "bilgisayar mı" sorusunu belirler. */
enum class BtCihazTuru { DIZUSTU, MASAUSTU, TELEFON, DIGER;

    val bilgisayarMi: Boolean get() = this == DIZUSTU || this == MASAUSTU

    companion object {
        /** Bluetooth cihaz sınıfından tür; sınıf yoksa [DIGER]. */
        fun sinifindan(sinif: BluetoothClass?): BtCihazTuru = when (sinif?.majorDeviceClass) {
            BluetoothClass.Device.Major.COMPUTER -> when (sinif.deviceClass) {
                BluetoothClass.Device.COMPUTER_LAPTOP,
                BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA,
                BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> DIZUSTU
                else -> MASAUSTU
            }
            BluetoothClass.Device.Major.PHONE -> TELEFON
            else -> DIGER
        }
    }
}

/** Listede gösterilen tek cihaz. [adres] kalıcı kimliktir; ad değişebilir. */
data class BtCihaz(
    val ad: String,
    val adres: String,
    val tur: BtCihazTuru,
    val yildizli: Boolean = false,
    /** Son kullanılanlar sırasındaki yeri; 0 = en son, -1 = hiç kullanılmadı. */
    val sonKullanimSirasi: Int = -1
) {
    /**
     * Varsayılan listede görünür mü. Kullanıcının kendisinin işaretlediği ya da
     * daha önce yazdığı cihaz, sınıfı ne derse desin gizlenmez: bazı
     * bilgisayarlar kendini yanlış sınıfla tanıtıyor ve gizlenmeleri
     * "cihazım kayboldu" demek olur.
     */
    val varsayilandaGorunur: Boolean get() = tur.bilgisayarMi || yildizli || sonKullanimSirasi >= 0
}

object BtCihazListesi {
    private const val YILDIZLI = "bt_starred"
    private const val SON_KULLANILAN = "bt_recent"
    private const val SON_KULLANILAN_SINIRI = 10

    /** Önce yıldızlılar, sonra son kullanılanlar (en yeni başta), sonra ada göre. */
    fun sirala(cihazlar: List<BtCihaz>): List<BtCihaz> = cihazlar.sortedWith(
        compareByDescending<BtCihaz> { it.yildizli }
            .thenBy { if (it.sonKullanimSirasi < 0) Int.MAX_VALUE else it.sonKullanimSirasi }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ad }
    )

    fun yildizlilar(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(YILDIZLI, emptySet()).orEmpty()

    fun yildizDegistir(prefs: SharedPreferences, adres: String) {
        val simdiki = yildizlilar(prefs)
        prefs.edit().putStringSet(YILDIZLI, if (adres in simdiki) simdiki - adres else simdiki + adres).apply()
    }

    /** En son kullanılan başta. */
    fun sonKullanilanlar(prefs: SharedPreferences): List<String> =
        prefs.getString(SON_KULLANILAN, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    fun kullanildi(prefs: SharedPreferences, adres: String) {
        val liste = (listOf(adres) + sonKullanilanlar(prefs).filter { it != adres })
            .take(SON_KULLANILAN_SINIRI)
        prefs.edit().putString(SON_KULLANILAN, liste.joinToString(",")).apply()
    }
}

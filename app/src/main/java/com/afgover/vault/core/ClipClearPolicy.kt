package com.afgover.vault.core

/**
 * "Panodaki duyarlı değer şimdi silinmeli mi?" kararının saf mantığı.
 *
 * Kural, Android'in ölçülmüş davranışına göre yazıldı: API 29'dan beri
 * **odakta olmayan** (ve varsayılan klavye olmayan) bir uygulama panoya
 * yazamaz, silemez, hatta pano tanımını okuyamaz. Ret **sessizdir** —
 * istisna atılmaz, çağrı hiçbir şey yapmadan döner. Eski kod 45 saniyelik
 * bir işi arka planda koşturuyordu; kullanıcı kopyalayıp uygulamadan
 * çıktığında o iş hiçbir zaman tutmadı ve boş bir `catch` yüzünden iz de
 * bırakmadı.
 *
 * Bu yüzden silme yalnız **odağın bizde olduğu** anlara bağlanır. Çıkışta
 * silme kasten yoktur: kopyalamanın amacı değeri başka bir uygulamaya
 * yapıştırmaktır, uygulamadan çıkarken silmek özelliğin kendisini bozar.
 */
object ClipClearPolicy {

    /** Kopyalanan değerin uygulama içinde panoda kalabileceği süre. */
    const val BEKLEME_MS = 45_000L

    /** Silmenin denendiği an. */
    enum class An {
        /** Uygulama ekranda dururken kurulan sayaç doldu. */
        SAYAC,

        /** Uygulama pencereye odağı geri aldı — kalıntı temizliği. */
        DONUS,

        /** Ekran kapandı. Odak bizde olmadığı için çoğu cihazda tutmaz; denenir, güvenilmez. */
        EKRAN_KAPANDI
    }

    /**
     * @param an silmenin denendiği an
     * @param bizimMi panodaki değer bizim işaretimizi taşıyor mu
     *   ([com.afgover.vault.VaultApp.CLIP_MARKER])
     * @param gecenMs değerin panoya konmasından bu yana geçen süre
     *
     * [bizimMi] yanlışsa hiçbir an silmez. Bu kontrol eskiden yalnız ekran
     * kapanma yolunda vardı, sayaç yolunda yoktu: kullanıcı parolayı
     * kopyaladıktan sonra başka bir şey kopyalarsa 45. saniyede **onun**
     * panosu siliniyordu.
     */
    fun shouldClear(an: An, bizimMi: Boolean, gecenMs: Long): Boolean {
        if (!bizimMi) return false
        return when (an) {
            An.SAYAC -> gecenMs >= BEKLEME_MS
            An.DONUS, An.EKRAN_KAPANDI -> true
        }
    }
}

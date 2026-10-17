package com.afgover.vault.core

/**
 * PIN kaba kuvvet beklemesi — saf hesap, `KeyManager` sayaçla çağırır.
 *
 * Bekleme yalnız duvar saatine bağlanmaz: cihaz ayarlarından saati 15 dakika
 * ileri almak bütün beklemeyi sıfırlıyordu. Her yanlış denemede iki damga
 * yazılır — duvar saati ve açılıştan beri geçen monoton süre. Monoton damga
 * geçerliyse (şimdiki değer kayıttakinden küçük değil, yani cihaz yeniden
 * başlamamış) **yalnız o** sayılır: saati ileri ya da geri almak hiçbir şeyi
 * değiştirmez. Yeniden başlatmada monoton saat sıfırlanır ve duvar saati
 * tek başına kalır. Yani beklemeyi atlamak için hem yeniden başlatmak hem
 * saati oynatmak gerekir; 10. yanlışta biyometrik yolun kapanması asıl
 * bekçi olmaya devam eder.
 */
object PinBackoff {

    const val MAX_FAILS = 10

    /** `fails` yanlıştan sonra beklenecek süre (ms). */
    fun waitMs(fails: Int): Long = when {
        fails < 5 -> 0L
        fails == 5 -> 30_000L
        fails == 6 -> 60_000L
        fails == 7 -> 5 * 60_000L
        else -> 15 * 60_000L
    }

    /**
     * Denemeye izin verilene kadar kalan süre (ms); 0 = serbest.
     * [failMono] 0 ise (eski kayıt) yalnız duvar saati kullanılır.
     */
    fun remainingMs(fails: Int, failWall: Long, failMono: Long, nowWall: Long, nowMono: Long): Long {
        val bekleme = waitMs(fails)
        if (bekleme == 0L) return 0L
        if (failMono > 0L && nowMono >= failMono) {
            return (failMono + bekleme - nowMono).coerceAtLeast(0L)
        }
        return (failWall + bekleme - nowWall).coerceAtLeast(0L)
    }
}

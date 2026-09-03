package com.afgover.vault.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pano temizliği kuralının sözleşmesi. Kural, ölçülmüş bir hatadan doğdu:
 * kopyalanan parola uygulamadan çıkıldığında hiç silinmiyordu, çünkü Android
 * odakta olmayan uygulamanın pano yazmasını sessizce reddediyor.
 */
class ClipClearPolicyTest {

    @Test
    fun `sayac dolmadan silinmez`() {
        assertFalse(ClipClearPolicy.shouldClear(ClipClearPolicy.An.SAYAC, bizimMi = true, gecenMs = 44_000))
    }

    @Test
    fun `sayac dolunca silinir`() {
        assertTrue(
            ClipClearPolicy.shouldClear(
                ClipClearPolicy.An.SAYAC,
                bizimMi = true,
                gecenMs = ClipClearPolicy.BEKLEME_MS
            )
        )
    }

    /**
     * Uygulamaya dönüş kalıntı temizliğidir: kullanıcı çıkarken silinemeyen
     * değer, odak geri geldiğinde süreye bakılmadan gider.
     */
    @Test
    fun `donuste sure beklenmez`() {
        assertTrue(ClipClearPolicy.shouldClear(ClipClearPolicy.An.DONUS, bizimMi = true, gecenMs = 1))
    }

    @Test
    fun `ekran kapaninca sure beklenmez`() {
        assertTrue(ClipClearPolicy.shouldClear(ClipClearPolicy.An.EKRAN_KAPANDI, bizimMi = true, gecenMs = 1))
    }

    /**
     * ASIL KORUMA: panodaki değer bizim değilse hiçbir an ona dokunulmaz.
     * Eski kodda sayaç yolu bu kontrolü yapmıyordu — kullanıcı parolayı
     * kopyaladıktan sonra başka bir şey kopyalarsa 45. saniyede onunki
     * siliniyordu.
     */
    @Test
    fun `yabanci pano hicbir anda silinmez`() {
        for (an in ClipClearPolicy.An.entries) {
            assertFalse(
                "an=$an",
                ClipClearPolicy.shouldClear(an, bizimMi = false, gecenMs = 10 * ClipClearPolicy.BEKLEME_MS)
            )
        }
    }
}

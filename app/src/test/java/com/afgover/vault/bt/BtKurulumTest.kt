package com.afgover.vault.bt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sihirbazın adımı telefonun gerçek durumundan türetilir: ilerleme de
 * gerileme de durumu izler, elle tutulan bir sayaç yok.
 */
class BtKurulumTest {

    private val hazir = BtKurulumDurumu(
        sistem = PcSistemi.WINDOWS, izin = true, btAcik = true, hidHazir = true
    )

    @Test
    fun `adimlar sirayla durumdan turetilir`() {
        assertEquals(BtKurulumAdimi.SISTEM, BtKurulumDurumu().adim)
        assertEquals(BtKurulumAdimi.HAZIRLIK, BtKurulumDurumu(sistem = PcSistemi.MACOS).adim)
        assertEquals(BtKurulumAdimi.ESLESTIRME, hazir.adim)
        assertEquals(BtKurulumAdimi.DENEME, hazir.copy(bagli = true).adim)
        assertEquals(BtKurulumAdimi.BITTI, hazir.copy(bagli = true, bitti = true).adim)
    }

    @Test
    fun `baglanti koparsa eslestirmeye, bluetooth kapanirsa hazirliga doner`() {
        val deneme = hazir.copy(bagli = true)
        assertEquals(BtKurulumAdimi.ESLESTIRME, deneme.copy(bagli = false).adim)
        assertEquals(BtKurulumAdimi.HAZIRLIK, deneme.copy(btAcik = false, hidHazir = false, bagli = false).adim)
    }

    @Test
    fun `bitti adimi baglanti kopsa da kalir, desteklenmeyen telefon her seyin onunde`() {
        assertEquals(BtKurulumAdimi.BITTI, hazir.copy(bitti = true, bagli = false).adim)
        assertEquals(BtKurulumAdimi.DESTEKLENMIYOR, hazir.copy(destekleniyor = false, bitti = true).adim)
    }

    @Test
    fun `duzen onerisi - kullanicinin secimi tahminden once gelir`() {
        assertEquals(HidLayouts.Layout.DE, duzenOnerisi("DE", "tr", "TR"))
        // Eski sürümlerin "TR" adı da okunur.
        assertEquals(HidLayouts.Layout.TR_Q, duzenOnerisi("TR", "en", "US"))
    }

    @Test
    fun `duzen onerisi - secim yoksa dil ve ulkeden`() {
        assertEquals(HidLayouts.Layout.TR_Q, duzenOnerisi(null, "tr", "TR"))
        assertEquals(HidLayouts.Layout.CH, duzenOnerisi(null, "de", "CH"))
        assertEquals(HidLayouts.Layout.BR, duzenOnerisi(null, "pt", "BR"))
        assertEquals(HidLayouts.Layout.PT, duzenOnerisi(null, "pt", "PT"))
        assertEquals(HidLayouts.Layout.UK, duzenOnerisi(null, "en", "GB"))
        assertEquals(HidLayouts.Layout.US, duzenOnerisi(null, "en", "US"))
        assertEquals(HidLayouts.Layout.US, duzenOnerisi(null, "ja", "JP"))
    }

    @Test
    fun `sistem kararli adla saklanir ve geri okunur`() {
        PcSistemi.entries.forEach { assertEquals(it, PcSistemi.ofStable(it.stable)) }
        assertEquals(null, PcSistemi.ofStable("beos"))
    }
}

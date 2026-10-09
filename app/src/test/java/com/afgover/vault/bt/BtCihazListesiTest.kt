package com.afgover.vault.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtCihazListesiTest {

    private val kulaklik = BtCihaz("AirPods", "A", BtCihazTuru.DIGER)
    private val ofis = BtCihaz("Ofis PC", "B", BtCihazTuru.MASAUSTU, sonKullanimSirasi = 1)
    private val ev = BtCihaz("Ev laptop", "C", BtCihazTuru.DIZUSTU, sonKullanimSirasi = 0)
    private val araba = BtCihaz("Araba", "D", BtCihazTuru.DIGER, yildizli = true)
    private val yedek = BtCihaz("Yedek PC", "E", BtCihazTuru.MASAUSTU)

    @Test
    fun `once yildizlilar, sonra son kullanilanlar, sonra ada gore`() {
        val sira = BtCihazListesi.sirala(listOf(yedek, kulaklik, ofis, araba, ev)).map { it.ad }
        assertEquals(listOf("Araba", "Ev laptop", "Ofis PC", "AirPods", "Yedek PC"), sira)
    }

    @Test
    fun `varsayilan listede bilgisayarlar ve kullanicinin sectikleri gorunur`() {
        assertFalse(kulaklik.varsayilandaGorunur)
        assertTrue(yedek.varsayilandaGorunur)
        // Bilgisayar değil ama yıldızlı / kullanılmış: gizlenmez.
        assertTrue(araba.varsayilandaGorunur)
        assertTrue(kulaklik.copy(sonKullanimSirasi = 3).varsayilandaGorunur)
    }
}

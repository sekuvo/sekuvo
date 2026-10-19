package com.afgover.vault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * İçe aktarmada araç çıpası: kasada en çok bir çıpa olur ve çıpa kayıt
 * sayılmaz. Yalnız çıpa taşıyan bir yedek "tümünü değiştir" ile kasayı
 * boşaltıyordu; "ekle" ikinci bir çıpa satırı açıyordu (ikinci tur denetim).
 * Depo bu ayrımla çalışır: çıpa dışında kayıt yoksa zarf boştur.
 */
class CipaIceAktarmaTest {

    private fun kayit(baslik: String, guncel: Long = 0, capa: Boolean = false) = DecryptedEntry(
        id = 0,
        type = EntryType.NOTE,
        title = baslik,
        data = EntryData(notes = baslik),
        createdAt = 0,
        updatedAt = guncel,
        anchor = capa
    )

    @Test
    fun `yalniz cipa tasiyan yedekte kayit yok`() {
        val (kayitlar, capa) = VaultRepository.capayiAyir(listOf(kayit("aktar.html", capa = true)))
        assertTrue(kayitlar.isEmpty())
        assertEquals("aktar.html", capa?.title)
    }

    @Test
    fun `birden cok cipadan en yenisi kalir`() {
        val (kayitlar, capa) = VaultRepository.capayiAyir(
            listOf(
                kayit("eski", guncel = 100, capa = true),
                kayit("not"),
                kayit("yeni", guncel = 300, capa = true),
                kayit("orta", guncel = 200, capa = true)
            )
        )
        assertEquals(listOf("not"), kayitlar.map { it.title })
        assertEquals("yeni", capa?.title)
    }

    @Test
    fun `cipasiz yedekte kayitlarin hepsi kalir`() {
        val (kayitlar, capa) = VaultRepository.capayiAyir(listOf(kayit("bir"), kayit("iki")))
        assertEquals(listOf("bir", "iki"), kayitlar.map { it.title })
        assertNull(capa)
    }
}

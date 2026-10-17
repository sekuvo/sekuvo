package com.afgover.vault.core

import com.afgover.vault.data.EntryData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kurum logosu eşleşmesi: iOS'taki BrandsTests ile aynı değerler (`bicim/vektorler/marka-eslesme.json`). */
class BrandsTest {

    @Test
    fun `ortak eslesme degerleri`() {
        val json = JSONObject(File("../bicim/vektorler/marka-eslesme.json").readText(Charsets.UTF_8))
        val durumlar = json.getJSONArray("durumlar")
        assertTrue(durumlar.length() > 20)
        for (i in 0 until durumlar.length()) {
            val d = durumlar.getJSONObject(i)
            val bulunan = Brands.match(
                title = d.getString("baslik"), url = d.getString("adres"), choice = d.getString("secim"),
                isCard = d.getBoolean("kart"), cardNumber = d.getString("kartNo")
            )
            val beklenen = if (d.isNull("beklenen")) null else d.getString("beklenen")
            assertEquals(d.getString("ad"), beklenen, bulunan?.id)
        }
    }

    @Test
    fun `katalog tutarli`() {
        val ids = Brands.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Brands.all.forEach { b ->
            assertTrue(b.id, b.hasLogo || b.badge.length in 1..3)
            assertTrue(b.id, b.domains.all { it == it.lowercase() && !it.startsWith("www.") })
        }
    }

    @Test
    fun `secim yedekte tasinir, bossa yazilmaz`() {
        assertEquals("hetzner", EntryData.fromJson(EntryData(brand = "hetzner").toJson()).brand)
        assertTrue(!EntryData().toJson().has("brand"))
    }
}

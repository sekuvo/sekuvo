package com.afgover.vault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Yerelleştirmenin iki tuzağının sözleşmesi (S-2026-08-24-ingilizce-yerellestirme):
 * maskeleme metne bakmaz, günlüğe yazılan ad dile bağlı değildir.
 */
class FieldKeyTest {

    private val dolu = EntryData(
        username = "afgover", password = "gizli", cvv = "123", cardNumber = "4444",
        notes = "not", custom = listOf(CustomField("API anahtarı", "abc"))
    )

    @Test
    fun `sifre ve cvv kimlikle maskelenir - metin karsilastirmasi yok`() {
        val alanlar = dolu.fields()
        val sifre = alanlar.first { it.key == FieldKey.PASSWORD }
        val cvv = alanlar.first { it.key == FieldKey.CVV }
        val kullanici = alanlar.first { it.key == FieldKey.USERNAME }
        assertTrue(sifre.hidden)
        assertTrue(cvv.hidden)
        assertFalse(kullanici.hidden)
    }

    @Test
    fun `gunluge yazilan ad dile bagli degil - kararli anahtar`() {
        val sifre = dolu.fields().first { it.key == FieldKey.PASSWORD }
        assertEquals("password", sifre.stableName)
        // Kullanıcının kendi alanı zaten kendi yazdığı metindir, çevrilmez.
        val ozel = dolu.fields().first { it.key == null }
        assertEquals("API anahtarı", ozel.stableName)
    }

    @Test
    fun `kararli adlar benzersiz ve geri cozulebilir`() {
        val adlar = FieldKey.entries.map { it.stable }
        assertEquals(adlar.size, adlar.toSet().size)
        assertEquals(FieldKey.PASSWORD, FieldKey.ofStable("password"))
        assertNull(FieldKey.ofStable("Şifre"))   // eski günlük satırı: çözülemez, ham gösterilir
    }

    @Test
    fun `bos alanlar listede yok`() {
        assertTrue(EntryData().fields().isEmpty())
    }

    @Test
    fun `sifre turundeki eklenen alan maskelenir, metin turundeki maskelenmez`() {
        val veri = EntryData(custom = listOf(
            CustomField("PIN", "1234", CustomFieldType.PASSWORD),
            CustomField("Şube", "Kadıköy")
        ))
        val alanlar = veri.fields()
        assertTrue(alanlar.first { it.customLabel == "PIN" }.hidden)
        assertFalse(alanlar.first { it.customLabel == "Şube" }.hidden)
    }

    @Test
    fun `alan turu JSON gidis donusunde korunur, tursuz eski kayit metin okunur`() {
        val veri = EntryData(custom = listOf(
            CustomField("PIN", "1234", CustomFieldType.PASSWORD),
            CustomField("Not", "a\nb", CustomFieldType.MULTILINE),
            CustomField("Şube", "Kadıköy")
        ))
        val json = veri.toJson()
        // Metin türü yazılmaz: eski biçimle birebir aynı kalır.
        assertFalse(json.getJSONArray("custom").getJSONObject(2).has("type"))
        assertEquals(veri.custom, EntryData.fromJson(json).custom)

        val eski = org.json.JSONObject(
            """{"custom":[{"label":"x","value":"y"},{"label":"z","value":"w","type":"gelecekte"}]}"""
        )
        assertEquals(
            listOf(CustomFieldType.TEXT, CustomFieldType.TEXT),
            EntryData.fromJson(eski).custom.map { it.type }
        )
    }
}

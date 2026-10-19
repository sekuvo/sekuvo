package com.afgover.vault.data

import com.afgover.vault.core.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cihazdaki kayıt biçimi sürüm 2'nin sözleşmesi: başlık ve etiket adı
 * şifreli, her mühür yerine bağlı; sürüm 1 satırları okunur ve kayıpsız
 * taşınır. iOS'taki karşılığı `KayitBicimiTests` aynı kuralları sınar.
 */
class KayitBicimiTest {

    private val key = Crypto.randomKey()
    private val quickKey = Crypto.randomKey()
    private val veri = EntryData(username = "fatih", password = "çok-gizli", url = "banka.example")

    /** 1.2'nin yazdığı satır: başlık açık, gövde verinin JSON'u, AAD yok. */
    private fun surum1(title: String, data: EntryData = veri, quick: Boolean = false, type: String = "LOGIN") =
        data.toJson().toString().toByteArray(Charsets.UTF_8).let { acik ->
            EntryEntity(
                id = 1, type = type, title = title, blob = Crypto.encrypt(key, acik),
                createdAt = 1, updatedAt = 2, quick = quick,
                quickBlob = if (quick) Crypto.encryptWithGeneratedIv(quickKey, acik) else null
            )
        }

    @Test
    fun `aad metinleri sabit - ios ile ayni`() {
        val uid = "00112233445566778899aabbccddeeff"
        assertEquals("sekuvo/v2/entries/$uid/blob/LOGIN/0", String(KayitBicimi.govdeAad(uid, "LOGIN", false)))
        assertEquals("sekuvo/v2/entries/$uid/blob/NOTE/1", String(KayitBicimi.govdeAad(uid, "NOTE", true)))
        assertEquals("sekuvo/v2/entries/$uid/quick/CARD", String(KayitBicimi.hizliAad(uid, "CARD")))
        assertEquals("sekuvo/v2/tags/$uid/name", String(KayitBicimi.etiketAad(uid)))
        assertEquals("sekuvo/v2/usage_log", String(KayitBicimi.GUNLUK_AAD))
        assertEquals("sekuvo/v2/usage_buffer", String(KayitBicimi.TAMPON_AAD))
        assertTrue(KayitBicimi.yeniUid().matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `surum 1 satiri tasinir - baslik govdeye girer, acik baslik silinir`() {
        val eski = surum1("Banka")
        assertEquals("Banka" to veri, KayitBicimi.govdeyiAc(eski, key))

        val yeni = KayitBicimi.tasi(eski, key, quickKey)!!
        assertTrue(yeni.uid.isNotEmpty())
        assertEquals("", yeni.title)
        assertEquals("Banka" to veri, KayitBicimi.govdeyiAc(yeni, key))
        // Yeni gövde AAD'siz açılmaz; eski gövde AAD'yle açılmaz.
        assertNull(Crypto.decrypt(key, yeni.blob))
        assertNull(KayitBicimi.govdeyiAc(eski.copy(uid = yeni.uid), key))
        // Taşınmış satır yeniden taşınmaz.
        assertNull(KayitBicimi.tasi(yeni, key, quickKey))
    }

    @Test
    fun `acilamayan surum 1 satirina dokunulmaz`() {
        assertNull(KayitBicimi.tasi(surum1("Banka").copy(blob = Crypto.randomBytes(40)), key, quickKey))
        assertNull(KayitBicimi.tasi(surum1("Banka"), Crypto.randomKey(), quickKey))
    }

    @Test
    fun `takas edilen govde acilmaz`() {
        val a = KayitBicimi.tasi(surum1("Netflix"), key, null)!!
        val b = KayitBicimi.tasi(surum1("Banka", EntryData(password = "banka-parolasi")), key, null)!!
        assertNull(KayitBicimi.govdeyiAc(a.copy(blob = b.blob), key))
        assertNull(KayitBicimi.govdeyiAc(b.copy(blob = a.blob), key))
    }

    @Test
    fun `tur ya da cipa bayragi degisirse govde acilmaz`() {
        val a = KayitBicimi.tasi(surum1("Banka"), key, null)!!
        assertNotNull(KayitBicimi.govdeyiAc(a, key))
        assertNull(KayitBicimi.govdeyiAc(a.copy(type = "NOTE"), key))
        assertNull(KayitBicimi.govdeyiAc(a.copy(anchor = true), key))
    }

    @Test
    fun `hizli erisim kopyasi basligi tasir ve satirina bagli`() {
        val a = KayitBicimi.tasi(surum1("Ev adresi", quick = true), key, quickKey)!!
        val b = KayitBicimi.tasi(surum1("Telefon", quick = true), key, quickKey)!!
        assertEquals("Ev adresi" to veri, KayitBicimi.hizliyiAc(a, quickKey))
        assertNull(KayitBicimi.hizliyiAc(a.copy(quickBlob = b.quickBlob), quickKey))
        // Keystore anahtarı yokken taşınan kaydın kopyası düşer; onarım yazar.
        assertNull(KayitBicimi.tasi(surum1("Ev adresi", quick = true), key, null)!!.quickBlob)
    }

    @Test
    fun `surum 1 hizli kopyasi okunmaya devam eder`() {
        val eski = surum1("Ev adresi", quick = true)
        assertEquals("Ev adresi" to veri, KayitBicimi.hizliyiAc(eski, quickKey))
    }

    @Test
    fun `etiket adi sifrelenir ve etiketine bagli`() {
        val eski = TagEntity(id = 3, name = "kripto", color = 1)
        assertEquals("kripto", KayitBicimi.etiketAdi(eski, key))
        val yeni = KayitBicimi.etiketiTasi(eski, key)!!
        assertEquals("", yeni.name)
        assertEquals("kripto", KayitBicimi.etiketAdi(yeni, key))
        assertNull(KayitBicimi.etiketiTasi(yeni, key))
        val baska = KayitBicimi.etiketiMuhurle(TagEntity(id = 4, name = "", color = 1), "banka", key)
        assertNull(KayitBicimi.etiketAdi(yeni.copy(nameBlob = baska.nameBlob), key))
    }

    @Test
    fun `gunluk satiri tablosuna bagli - eski satir okunur ve tasinir`() {
        val olay = UsageEvent(1, UsageKind.KOPYALANDI, 5).toBytes()
        val eski = Crypto.encrypt(key, olay)
        assertArrayEquals(olay, KayitBicimi.gunluguAc(key, eski, KayitBicimi.GUNLUK_AAD))
        val yeni = KayitBicimi.gunluguTasi(eski, key)!!
        assertArrayEquals(olay, KayitBicimi.gunluguAc(key, yeni, KayitBicimi.GUNLUK_AAD))
        assertNull(KayitBicimi.gunluguTasi(yeni, key))
        // Kaydın gövdesi günlüğe taşınırsa açılmaz, tampon mühürü günlükte açılmaz.
        val govde = KayitBicimi.tasi(surum1("Banka"), key, null)!!.blob
        assertNull(KayitBicimi.gunluguAc(key, govde, KayitBicimi.GUNLUK_AAD))
        val tampon = Crypto.encrypt(key, olay, KayitBicimi.TAMPON_AAD)
        assertNull(KayitBicimi.gunluguAc(key, tampon, KayitBicimi.GUNLUK_AAD))
    }

    @Test
    fun `duz metin baslik ve veriyi tasir - surum 1 json'unda baslik bos`() {
        val acik = KayitBicimi.acikMetin("İş Bankası", veri)
        assertEquals("İş Bankası" to veri, KayitBicimi.coz(acik))
        assertEquals("" to veri, KayitBicimi.coz(veri.toJson().toString().toByteArray()))
        assertNull(KayitBicimi.coz("bozuk".toByteArray()))
    }
}

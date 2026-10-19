package com.afgover.vault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afgover.vault.core.Crypto
import com.afgover.vault.core.KeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * İçe aktarmada araç çıpası, Room üzerinden (ikinci tur denetim). Yalnız çıpa
 * taşıyan yedek "tümünü değiştir" ile kasayı boşaltıyordu; "ekle" ikinci bir
 * çıpa satırı açıyordu; bildirilen sayı çıpayı da sayıyordu. iOS'taki
 * `VaultStoreTests` ile aynı kurallar.
 *
 * Test uygulamasının kendi veritabanını kullanır: her test tabloları boşaltır.
 */
@RunWith(AndroidJUnit4::class)
class CipaIceAktarmaCihazTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = VaultRepository(context, KeyManager(context))
    private val key = Crypto.randomKey()

    private fun kayit(baslik: String) = DecryptedEntry(
        id = 0, type = EntryType.LOGIN, title = baslik, data = EntryData(username = baslik),
        createdAt = 1, updatedAt = 1
    )

    private fun capa(deger: String, guncel: Long) = DecryptedEntry(
        id = 0, type = EntryType.NOTE, title = VaultRepository.ANCHOR_TITLE, data = EntryData(notes = deger),
        createdAt = guncel, updatedAt = guncel, noteKind = NoteKind.PARMAK_IZI, anchor = true
    )

    @Before
    fun temizle() = VaultDatabase.get(context).clearAllTables()

    @Test
    fun yalnizCipaTasiyanYedekKasayiBosaltmaz() = runBlocking {
        repo.addAll(listOf(kayit("Kalsın")), key)
        assertEquals(0, repo.replaceAll(listOf(capa("sha", 5)), key))
        assertEquals(0, repo.addAll(listOf(capa("sha", 5)), key))
        assertEquals(listOf("Kalsın"), repo.getAllDecrypted(key).map { it.title })
    }

    @Test
    fun ekleIkinciCipaAcmazEnYenisiKalir() = runBlocking {
        assertEquals(1, repo.addAll(listOf(kayit("a"), capa("ilk", 10)), key))
        assertEquals(1, repo.addAll(listOf(kayit("b"), capa("yeni", 20)), key))
        assertEquals(1, repo.addAll(listOf(kayit("c"), capa("eski", 5)), key))
        val capalar = repo.getAllDecrypted(key).filter { it.anchor }
        assertEquals(listOf("yeni"), capalar.map { it.data.notes })
        assertEquals("yeni", repo.getAnchorDecrypted(key)?.data?.notes)
    }

    @Test
    fun tumunuDegistirSayisiCipasizTekCipaKalir() = runBlocking {
        repo.addAll(listOf(kayit("eski"), capa("kasadaki", 1)), key)
        assertEquals(2, repo.replaceAll(listOf(kayit("x"), kayit("y"), capa("a", 3), capa("b", 9)), key))
        val hepsi = repo.getAllDecrypted(key)
        assertEquals(listOf("x", "y"), hepsi.filter { !it.anchor }.map { it.title })
        assertEquals(listOf("b"), hepsi.filter { it.anchor }.map { it.data.notes })
    }
}

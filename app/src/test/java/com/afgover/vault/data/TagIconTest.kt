package com.afgover.vault.data

import com.afgover.vault.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Etiket ikonlarının sözleşmesi.
 *
 * İkon veritabanında ANAHTARLA durur (sayıyla değil) ve yedeğe de anahtar
 * olarak girer; buradaki testler o anahtarın yolculukta kaybolmadığını ve
 * tanınmayan bir değerin uygulamayı bozmak yerine sessizce ikonsuza
 * düştüğünü gösterir.
 */
class TagIconTest {

    private val parola = "yedek-parolasi".toCharArray()

    @Test
    fun `palet yirmi ikon ve hepsi benzersiz`() {
        assertEquals(20, TagIcons.keys.size)
        assertEquals(TagIcons.keys.size, TagIcons.keys.toSet().size)
    }

    @Test
    fun `taninmayan anahtar ikonsuza duser`() {
        assertEquals("", TagIcons.normalize(null))
        assertEquals("", TagIcons.normalize(""))
        // İleride kaldırılmış ya da başka sürümden gelmiş bir anahtar:
        // uygulama çökmemeli, etiket yalnız rengiyle görünmeli.
        assertEquals("", TagIcons.normalize("uzay-istasyonu"))
        assertEquals("work", TagIcons.normalize("work"))
    }

    @Test
    fun `ikon yedege girer ve geri doner`() {
        val kayit = DecryptedEntry(
            id = 1,
            type = EntryType.LOGIN,
            title = "İş hesabı",
            data = EntryData(username = "a", password = "b"),
            createdAt = 1,
            updatedAt = 1,
            tagIds = listOf(3)
        )
        val etiket = TagEntity(3, "iş", 0xFF64B5F6.toInt(), "work")

        val bytes = ByteArrayOutputStream().use { out ->
            BackupManager.export(out, parola, listOf(kayit), listOf(etiket))
            out.toByteArray()
        }
        val sonuc = BackupManager.import(bytes.inputStream(), parola)

        assertEquals(mapOf("iş" to TagDef(0xFF64B5F6.toInt(), "work")), sonuc.tagDefs)
    }

    @Test
    fun `ikonsuz etiket ikonsuz doner - eski yedekler de boyle okunur`() {
        val kayit = DecryptedEntry(
            id = 1,
            type = EntryType.LOGIN,
            title = "x",
            data = EntryData(password = "p"),
            createdAt = 1,
            updatedAt = 1,
            tagIds = listOf(5)
        )
        val bytes = ByteArrayOutputStream().use { out ->
            BackupManager.export(
                out, parola, listOf(kayit),
                listOf(TagEntity(5, "kişisel", 0xFF81C784.toInt()))
            )
            out.toByteArray()
        }
        val sonuc = BackupManager.import(bytes.inputStream(), parola)
        assertEquals("", sonuc.tagDefs.getValue("kişisel").icon)
        // Bu sürümden ÖNCEKİ yedeklerde alan hiç yok; okunabilir kalmalı.
        assertTrue(sonuc.tagDefs.isNotEmpty())
    }
}

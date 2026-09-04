package com.afgover.vault.backup

import com.afgover.vault.data.CustomField
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryData
import com.afgover.vault.data.EntryType
import com.afgover.vault.data.TagDef
import com.afgover.vault.data.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Yedek biçiminin sözleşmesi: dışa verdiğimiz her zarf içe alınabilmeli,
 * her bozuk girdi GÜRÜLTÜLÜ ve doğru istisnayla düşmeli (B-024 — B-032'nin
 * yapıştırma ekranı bu fonksiyona rastgele metin besleyecek).
 */
class BackupManagerTest {

    private val password = "Deneme-2026x".toCharArray()

    private fun ornekKayitlar() = listOf(
        DecryptedEntry(
            id = 1, type = EntryType.LOGIN, title = "Banka",
            data = EntryData(
                username = "afgover", password = "çokGizli\"Parola\"\n2. satır",
                url = "banka.example.com",
                custom = listOf(CustomField("şube", "kadıköy")),
                passwordChangedAt = 1_755_000_000_000
            ),
            createdAt = 100, updatedAt = 200, quick = false, tagIds = listOf(7L), sortIndex = 5
        ),
        DecryptedEntry(
            id = 2, type = EntryType.NOTE, title = "Not",
            data = EntryData(notes = "düz not"),
            createdAt = 300, updatedAt = 300, quick = true
        )
    )

    private fun disaVer(
        entries: List<DecryptedEntry> = ornekKayitlar(),
        tags: List<TagEntity> = listOf(TagEntity(7, "finans", 0xFFE57373.toInt()))
    ): String {
        val out = ByteArrayOutputStream()
        BackupManager.export(out, password, entries, tags)
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `disa ver - ice al gidis donusu her alani korur`() {
        val sonuc = BackupManager.import(disaVer().byteInputStream(), password)
        assertEquals(2, sonuc.entries.size)
        val banka = sonuc.entries[0]
        assertEquals("Banka", banka.title)
        assertEquals(EntryType.LOGIN, banka.type)
        assertEquals("çokGizli\"Parola\"\n2. satır", banka.data.password)
        assertEquals(listOf(CustomField("şube", "kadıköy")), banka.data.custom)
        assertEquals(1_755_000_000_000, banka.data.passwordChangedAt)
        assertEquals(100, banka.createdAt)
        // Etiket adla taşınır, id cihaza özgüdür
        assertEquals(listOf("finans"), banka.tagNames)
        assertEquals(mapOf("finans" to TagDef(0xFFE57373.toInt())), sonuc.tagDefs)
        assertTrue(sonuc.entries[1].quick)
    }

    @Test
    fun `yanlis parola WrongPasswordException firlatir`() {
        val zarf = disaVer()
        assertThrows(BackupManager.WrongPasswordException::class.java) {
            BackupManager.import(zarf.byteInputStream(), "YanlisParola1".toCharArray())
        }
    }

    @Test
    fun `duz metin veya rastgele cop InvalidFormatException firlatir`() {
        for (cop in listOf("merhaba dünya", "{}", "[1,2,3]", "", "   ", "a".repeat(100_000))) {
            assertThrows(BackupManager.InvalidFormatException::class.java) {
                BackupManager.import(cop.byteInputStream(), password)
            }
        }
    }

    @Test
    fun `vault olmayan uygulama alani reddedilir`() {
        val sahte = """{"app":"baska","version":1,"kdf":{"salt":"aaaa"},"data":"aaaa"}"""
        assertThrows(BackupManager.InvalidFormatException::class.java) {
            BackupManager.import(sahte.byteInputStream(), password)
        }
    }

    @Test
    fun `bozuk base64 InvalidFormatException firlatir`() {
        val zarf = disaVer().replace(Regex("\"data\": \"[^\"]{10}"), "\"data\": \"%%%çğü!!!")
        assertThrows(BackupManager.InvalidFormatException::class.java) {
            BackupManager.import(zarf.byteInputStream(), password)
        }
    }

    @Test
    fun `kirpilmis sifreli veri parola hatasi olarak duser - cokme yok`() {
        // Geçerli base64 ama kısaltılmış şifreli veri: GCM etiketi tutmaz.
        val zarf = disaVer()
        val m = Regex("\"data\": \"([^\"]+)\"").find(zarf)!!.groupValues[1]
        val kirpik = zarf.replace(m, m.dropLast(24))
        assertThrows(BackupManager.WrongPasswordException::class.java) {
            BackupManager.import(kirpik.byteInputStream(), password)
        }
    }

    @Test
    fun `etiketsiz eski zarf sorunsuz okunur - tagNames bos`() {
        val sonuc = BackupManager.import(
            disaVer(tags = emptyList()).byteInputStream(), password
        )
        assertTrue(sonuc.entries.all { it.tagNames.isEmpty() })
        assertTrue(sonuc.tagDefs.isEmpty())
    }

    @Test
    fun `vault-clip zarfi ice alinabilir - tek kayitlik ornek bicim`() {
        // tools/vault-clip.py encrypt() çıktısının bire bir yapısı (girinti yok).
        val out = ByteArrayOutputStream()
        BackupManager.export(
            out, password,
            listOf(
                DecryptedEntry(
                    id = 0, type = EntryType.LOGIN, title = "Sunucu",
                    data = EntryData(custom = listOf(CustomField("API anahtarı", "sk-123"))),
                    createdAt = 1, updatedAt = 1
                )
            )
        )
        val kompakt = out.toString(Charsets.UTF_8.name())
            .replace("\n", "").replace("  ", "")
        val sonuc = BackupManager.import(kompakt.byteInputStream(), password)
        assertEquals("sk-123", sonuc.entries[0].data.custom[0].value)
    }

    // ---- Denetim regresyonları (pazar öncesi A-Z) ----

    @Test
    fun `iterations disi zarf gecersiz sayilir - DoS engeli`() {
        val zarf = org.json.JSONObject(disaVer())
        zarf.getJSONObject("kdf").put("iterations", 500_000_000)
        assertThrows(BackupManager.InvalidFormatException::class.java) {
            BackupManager.import(zarf.toString().byteInputStream(), password)
        }
    }

    @Test
    fun `cok kucuk iterations da reddedilir`() {
        val zarf = org.json.JSONObject(disaVer())
        zarf.getJSONObject("kdf").put("iterations", 10)
        assertThrows(BackupManager.InvalidFormatException::class.java) {
            BackupManager.import(zarf.toString().byteInputStream(), password)
        }
    }

    @Test
    fun `boyut siniri asan girdi gurultulu duser - OOM yerine mesaj`() {
        val dev = ByteArray(BackupManager.MAX_ENVELOPE_BYTES + 1) { '{'.code.toByte() }
        assertThrows(BackupManager.InvalidFormatException::class.java) {
            BackupManager.import(dev.inputStream(), password)
        }
    }

    @Test
    fun `sortIndex gidis donusunde korunur - elle sira kaybolmaz`() {
        val sonuc = BackupManager.import(disaVer().byteInputStream(), password)
        assertEquals(5, sonuc.entries[0].sortIndex)
    }
}

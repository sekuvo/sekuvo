package com.afgover.vault.backup

import com.afgover.vault.core.Crypto
import com.afgover.vault.core.QrTransfer
import com.afgover.vault.data.DecryptedEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * Ortak biçim: `bicim/vektorler/` altındaki değerleri UYGULAMANIN KENDİ
 * koduyla açar (bkz. `bicim/README.md`).
 *
 * Aynı dosyaları iOS'taki `SekuvoCore` testleri de okur; değerleri üçüncü
 * bir uygulama (Python `cryptography`, `bicim/uret.py`) üretir. Üçü
 * anlaştığı sürece Android'in yazdığı `.vaultbak` ve QR zarfı iOS'ta, iOS'un
 * yazdığı da Android'de açılır. Bu test kırılırsa biçim değişmiş demektir:
 * önce `bicim/README.md`, sonra iki platform birlikte güncellenir.
 *
 * Dosyalar diskten okunduğu için Gradle'a test girdisi olarak bildirildi
 * (`app/build.gradle.kts`); yoksa test, değerler değişince "güncel" sayılıp
 * sessizce atlanırdı (bkz. KaynakMetinTest dersi).
 */
class OrtakBicimTest {

    private val kok = File("../bicim/vektorler")

    private fun oku(ad: String) = JSONObject(File(kok, ad).readText(Charsets.UTF_8))
    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JSONArray.nesneler() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.metinler() = (0 until length()).map { getString(it) }

    @Test
    fun `anahtar turetme ortak degerlerle ayni`() {
        oku("kdf.json").getJSONArray("vektorler").nesneler().forEach { v ->
            val anahtar = Crypto.deriveKey(
                v.getString("parola").toCharArray(), b64(v.getString("tuz")), v.getInt("tur")
            )
            assertEquals(v.getString("parola"), v.getString("anahtar_hex"), hex(anahtar.encoded))
        }
    }

    @Test
    fun `aes gcm ortak degerleri acar, bozugu reddeder`() {
        val d = oku("aesgcm.json")
        d.getJSONArray("vektorler").nesneler().forEach { v ->
            val anahtar = SecretKeySpec(v.getString("anahtar_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")
            val acik = Crypto.decrypt(anahtar, b64(v.getString("blob")))
            assertNotNull(v.getString("aciklama"), acik)
            assertArrayEquals(v.getString("aciklama"), v.getString("acik").toByteArray(Charsets.UTF_8), acik)
        }
        val bozuk = d.getJSONObject("bozuk")
        val anahtar = SecretKeySpec(bozuk.getString("anahtar_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")
        assertNull(Crypto.decrypt(anahtar, b64(bozuk.getString("blob"))))
    }

    @Test
    fun `aad li muhur yalniz ayni aad ile acilir`() {
        oku("aesgcm.json").getJSONArray("aad").nesneler().forEach { v ->
            val anahtar = SecretKeySpec(v.getString("anahtar_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")
            val blob = b64(v.getString("blob"))
            val aad = v.getString("aad").toByteArray(Charsets.UTF_8)
            assertArrayEquals(v.getString("aciklama"), v.getString("acik").toByteArray(Charsets.UTF_8), Crypto.decrypt(anahtar, blob, aad))
            assertNull(v.getString("aciklama"), Crypto.decrypt(anahtar, blob))
            assertNull(v.getString("aciklama"), Crypto.decrypt(anahtar, blob, aad + 0x30.toByte()))
        }
    }

    private fun kayitEsit(beklenen: JSONObject, e: DecryptedEntry) {
        val t = beklenen.getString("title")
        assertEquals(t, beklenen.getString("type"), e.type.name)
        assertEquals(t, beklenen.getString("noteKind"), e.noteKind.name)
        assertEquals(t, beklenen.getLong("createdAt"), e.createdAt)
        assertEquals(t, beklenen.getLong("updatedAt"), e.updatedAt)
        assertEquals(t, beklenen.getBoolean("quick"), e.quick)
        assertEquals(t, beklenen.getInt("sortIndex"), e.sortIndex)
        assertEquals(t, beklenen.getBoolean("anchor"), e.anchor)
        assertEquals(t, beklenen.getJSONArray("tags").metinler(), e.tagNames)
        val d = beklenen.getJSONObject("data")
        val v = e.data
        assertEquals(t, d.getString("username"), v.username)
        assertEquals(t, d.getString("password"), v.password)
        assertEquals(t, d.getString("url"), v.url)
        assertEquals(t, d.getString("cardholder"), v.cardholder)
        assertEquals(t, d.getString("cardNumber"), v.cardNumber)
        assertEquals(t, d.getString("expiry"), v.expiry)
        assertEquals(t, d.getString("cvv"), v.cvv)
        assertEquals(t, d.getString("iban"), v.iban)
        assertEquals(t, d.getString("notes"), v.notes)
        assertEquals(t, d.getString("fullName"), v.fullName)
        assertEquals(t, d.getString("phone"), v.phone)
        assertEquals(t, d.getString("email"), v.email)
        assertEquals(t, d.getString("address"), v.address)
        assertEquals(t, d.getLong("passwordChangedAt"), v.passwordChangedAt)
        assertEquals(
            t,
            d.getJSONArray("custom").nesneler().map { Triple(it.getString("label"), it.getString("value"), it.getString("type")) },
            v.custom.map { Triple(it.label, it.value, it.type.stable) }
        )
        assertEquals(
            t,
            d.getJSONArray("passwordHistory").nesneler().map { it.getString("value") to it.getLong("changedAt") },
            v.passwordHistory.map { it.value to it.changedAt }
        )
    }

    @Test
    fun `gecerli zarflar acilir ve kayitlar beklenen gibi doner`() {
        oku("zarf.json").getJSONArray("gecerli").nesneler().forEach { z ->
            val sonuc = BackupManager.import(
                z.getString("metin").toByteArray(Charsets.UTF_8).inputStream(),
                z.getString("parola").toCharArray()
            )
            val beklenen = z.getJSONArray("beklenen_kayitlar").nesneler()
            assertEquals(z.getString("ad"), beklenen.size, sonuc.entries.size)
            beklenen.zip(sonuc.entries).forEach { (b, e) -> kayitEsit(b, e) }
            val etiketler = z.getJSONArray("beklenen_etiketler").nesneler()
                .associate { it.getString("name") to (it.getInt("color") to it.getString("icon")) }
            assertEquals(z.getString("ad"), etiketler, sonuc.tagDefs.mapValues { it.value.color to it.value.icon })
        }
    }

    @Test
    fun `yanlis parola ve gecersiz zarflar ayri hata verir`() {
        val d = oku("zarf.json")
        val y = d.getJSONObject("yanlis_parola")
        assertThrows(BackupManager.WrongPasswordException::class.java) {
            BackupManager.import(y.getString("metin").byteInputStream(), y.getString("parola").toCharArray())
        }
        d.getJSONArray("gecersiz").nesneler().forEach { g ->
            assertThrows(g.getString("ad"), BackupManager.InvalidFormatException::class.java) {
                BackupManager.import(g.getString("metin").byteInputStream(), "herhangi".toCharArray())
            }
        }
    }

    @Test
    fun `qr kareleri sirasiz birlesir, bozuk kume reddedilir`() {
        val d = oku("qr.json")
        assertEquals(QrTransfer.CHUNK, d.getInt("parca"))
        d.getJSONArray("gecerli").nesneler().forEach { g ->
            val kareler = g.getJSONArray("kareler").metinler().map { requireNotNull(QrTransfer.parse(it)) }
            // Okuyucu kareleri kameranın yakaladığı sırayla alır: ters sırayla da birleşmeli.
            assertEquals(g.getString("ad"), g.getString("zarf_metni"), QrTransfer.assemble(kareler.reversed()))
        }
        d.getJSONArray("ayristirilamaz").metinler().forEach { assertNull(it, QrTransfer.parse(it)) }
        d.getJSONArray("birlesmez").nesneler().forEach { g ->
            val kareler = g.getJSONArray("kareler").metinler().mapNotNull { QrTransfer.parse(it) }
            assertNull(g.getString("ad"), QrTransfer.assemble(kareler))
        }
    }

    @Test
    fun `qr ile gelen tam yedek uygulamanin ice aktarmasiyla acilir`() {
        val q = oku("qr.json").getJSONArray("gecerli").nesneler().first { it.getString("ad") == "cok-kare-Z" }
        val zarf = QrTransfer.assemble(q.getJSONArray("kareler").metinler().map { requireNotNull(QrTransfer.parse(it)) })
        val sonuc = BackupManager.import(requireNotNull(zarf).byteInputStream(), "yedek-parolasi-123".toCharArray())
        assertEquals(7, sonuc.entries.size)
    }
}

package com.afgover.vault.core

import com.afgover.vault.backup.BackupManager
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryData
import com.afgover.vault.data.EntryType
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Tur sayısının sözleşmesi: yeni yazılanlar 600.000 turla, eski kayıtlar
 * yazıldıkları sayıyla açılır. Tur sayısı sargıda ve yedekte yazılı; PIN
 * sargısında yazılı olmadığı için PIN eski sayıda sabit kalmalı.
 */
class KdfTurTest {

    private val parola = "Deneme-2026x".toCharArray()

    @Test
    fun `yeni yazimlar 600 bin tur, eski kayitlar 310 bin`() {
        assertEquals(600_000, Crypto.KDF_ITERATIONS)
        assertEquals(310_000, Crypto.LEGACY_KDF_ITERATIONS)
        assertTrue(Crypto.KDF_ITERATIONS <= BackupManager.MAX_ITERATIONS)
    }

    @Test
    fun `eski turla sarilmis kasa yukseltilir, yenisi yukseltilmez`() {
        assertTrue(Crypto.needsKdfUpgrade(Crypto.LEGACY_KDF_ITERATIONS))
        assertTrue(Crypto.needsKdfUpgrade(10_000))
        assertFalse(Crypto.needsKdfUpgrade(Crypto.KDF_ITERATIONS))
        assertFalse(Crypto.needsKdfUpgrade(1_000_000))
    }

    @Test
    fun `pin turu sabit - bugunku pin sargilari acilmaya devam eder`() {
        assertEquals(310_000, PinLock.ITERATIONS)
        // 1.2 ve öncesinin yazdığı iç sargı: PIN 310.000 turla türetilmişti.
        val salt = Crypto.randomBytes(16)
        val dataKey = Crypto.randomKey()
        val eskiSargi = Crypto.encrypt(
            Crypto.deriveKey("482913".toCharArray(), salt, 310_000),
            dataKey.encoded
        )
        assertArrayEquals(dataKey.encoded, PinLock.unwrap(eskiSargi, "482913", salt)?.encoded)
    }

    @Test
    fun `yedek 600 bin turla yazilir ve geri acilir`() {
        val out = ByteArrayOutputStream()
        val kayit = DecryptedEntry(
            id = 1, type = EntryType.NOTE, title = "Not",
            data = EntryData(notes = "içerik"), createdAt = 1, updatedAt = 1, quick = false
        )
        BackupManager.export(out, parola, listOf(kayit))
        val zarf = JSONObject(out.toString(Charsets.UTF_8.name()))
        assertEquals(600_000, zarf.getJSONObject("kdf").getInt("iterations"))
        val geri = BackupManager.import(out.toByteArray().inputStream(), parola)
        assertEquals("içerik", geri.entries.single().data.notes)
    }

    @Test
    fun `310 bin turlu eski yedek acilir`() {
        val sonuc = BackupManager.import(eskiZarf(turYaz = true).byteInputStream(), parola)
        assertEquals("Eski", sonuc.entries.single().title)
    }

    @Test
    fun `tur alani olmayan zarf 310 bin turla okunur`() {
        val sonuc = BackupManager.import(eskiZarf(turYaz = false).byteInputStream(), parola)
        assertEquals("Eski", sonuc.entries.single().title)
    }

    /** 1.2'nin yazdığı biçimde, 310.000 turla mühürlenmiş zarf. */
    private fun eskiZarf(turYaz: Boolean): String {
        val salt = Crypto.randomBytes(16)
        val anahtar = Crypto.deriveKey(parola, salt, 310_000)
        val icerik = """{"entries":[{"type":"NOTE","title":"Eski","data":{"notes":"x"}}]}"""
        val blob = Crypto.encrypt(anahtar, icerik.toByteArray(Charsets.UTF_8))
        val kdf = JSONObject()
            .put("algo", "PBKDF2WithHmacSHA256")
            .put("salt", Base64.getEncoder().encodeToString(salt))
        if (turYaz) kdf.put("iterations", 310_000)
        return JSONObject()
            .put("app", "vault").put("version", 1).put("kdf", kdf)
            .put("cipher", "AES-256-GCM")
            .put("data", Base64.getEncoder().encodeToString(blob))
            .toString()
    }
}

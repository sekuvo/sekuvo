package com.afgover.vault.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Yedek parolası uyarısının kuralı: 16 karakterden kısa ya da tek tür
 * karakter. ASCII'de aktar.html ve vault-clip.py ile aynı karar verilmeli;
 * iOS'taki karşılığı aynı örneklerle sınanır.
 */
class ZayifParolaTest {

    @Test
    fun `16 karakterden kisa parola zayif`() {
        assertTrue(BackupManager.isWeakPassword("Kisa-Parola1"))
        assertTrue(BackupManager.isWeakPassword("Aa1!Aa1!Aa1!Aa1"))  // 15
    }

    @Test
    fun `tek tur karakter zayif`() {
        assertTrue(BackupManager.isWeakPassword("abcdefghijklmnop"))
        assertTrue(BackupManager.isWeakPassword("1234567890123456"))
        assertTrue(BackupManager.isWeakPassword("ABCDEFGHIJKLMNOPQ"))
        assertTrue(BackupManager.isWeakPassword("!@#$%^&*()-_=+[]"))
    }

    @Test
    fun `iki tur ve 16 karakter yeter`() {
        assertFalse(BackupManager.isWeakPassword("abcdefghijklmno1"))
        assertFalse(BackupManager.isWeakPassword("kedi masa kalem defter"))  // boşluk da bir tür
        assertFalse(BackupManager.isWeakPassword("Correct horse battery staple"))
    }

    @Test
    fun `karakter sayisi kod noktasiyla - ascii disi harfler de sayilir`() {
        assertFalse(BackupManager.isWeakPassword("çok gizli şifre öğün"))
        // Büyük-küçük harfi olmayan yazı kendi türünü sayar; tek tür yine zayıf.
        assertTrue(BackupManager.isWeakPassword("数据加密很重要因为隐私是基本的权利"))
        assertFalse(BackupManager.isWeakPassword("数据加密很重要因为隐私是基本的权利。"))
    }
}

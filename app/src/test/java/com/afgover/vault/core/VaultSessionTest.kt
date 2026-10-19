package com.afgover.vault.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kilit durumunun TEK doğruluk kaynağı olduğunu koruyan testler.
 *
 * Bildirilen hata: telefon ekranı açılıp uygulama açıldığında kayıt listesi
 * görünüyor, bir kayda tıklayınca boş ekran çıkıyor, sonra kilit ekranı
 * geliyordu. Sebep: `lock()` yalnız anahtarı siliyordu, arayüzün kendi kilit
 * durumu bundan habersizdi ve ikisi yalnız `onResume`'da eşitleniyordu.
 * Android yayınları önbellekteki sürece ERTELEDİĞİ için ekran-kapandı yayını
 * uygulama öne geldikten sonra geliyor, o aralıkta kasa kilitli olmasına
 * rağmen içerik görünüyordu.
 */
class VaultSessionTest {

    @After
    fun temizle() = VaultSession.lock()

    private fun anahtar() = Crypto.randomKey()

    @Test
    fun `kilit acilinca akis da acilir`() {
        VaultSession.unlock(anahtar())
        assertTrue(VaultSession.isUnlocked)
        assertTrue(VaultSession.unlocked.value)
    }

    /**
     * Asıl nöbet noktası: anahtarın silinmesiyle akışın kapanması AYNI
     * işlemde olmalı. Ayrılırlarsa arayüz "kilitli değil" sanıp içerik
     * göstermeye devam eder.
     */
    @Test
    fun `kilitlenince anahtar ve akis birlikte kapanir`() {
        VaultSession.unlock(anahtar())
        VaultSession.lock()
        assertNull(VaultSession.key())
        assertFalse(VaultSession.isUnlocked)
        assertFalse(VaultSession.unlocked.value)
    }

    @Test
    fun `akis her zaman anahtarin varligini yansitir`() {
        VaultSession.lock()
        assertFalse(VaultSession.unlocked.value)
        VaultSession.unlock(anahtar())
        assertTrue(VaultSession.unlocked.value)
        VaultSession.lock()
        assertFalse(VaultSession.unlocked.value)
    }

    @Test
    fun `art arda kilitleme durumu bozmaz`() {
        VaultSession.unlock(anahtar())
        repeat(3) { VaultSession.lock() }
        assertFalse(VaultSession.unlocked.value)
        assertNull(VaultSession.key())
    }

    /**
     * Kilit açma PBKDF2 sürerken ekran kapanırsa (kilit) türetmenin sonucu
     * atılır: yoksa kasa kapalı ekranın arkasında açılırdı.
     */
    @Test
    fun `bekleme sirasinda kilit olduysa acma sonucu atilir`() {
        val nesil = VaultSession.generation
        VaultSession.lock()
        assertFalse(VaultSession.unlockIfCurrent(nesil, anahtar()))
        assertFalse(VaultSession.isUnlocked)
        assertFalse(VaultSession.unlocked.value)
    }

    @Test
    fun `bekleme sirasinda kilit olmadiysa acma sonucu gecer`() {
        val nesil = VaultSession.generation
        assertTrue(VaultSession.unlockIfCurrent(nesil, anahtar()))
        assertTrue(VaultSession.isUnlocked)
    }
}

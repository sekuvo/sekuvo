package com.afgover.vault.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `webDomain` beyanı yalnız tarayıcılardan kabul edilir: başka bir uygulama
 * "banka.com" diyerek kaydı listenin başına çıkaramaz, kaydettirdiği girdiye
 * yabancı URL yazdıramaz.
 */
class WebDomainGuvenTest {

    @Test
    fun `tarayicidan gelen alan adi kabul edilir`() {
        assertEquals("banka.com", StructureParser.trustedWebDomain("com.android.chrome", "banka.com"))
        assertEquals("banka.com", StructureParser.trustedWebDomain("org.mozilla.firefox", "banka.com"))
    }

    @Test
    fun `tarayici olmayan uygulamanin alan adi beyani yok sayilir`() {
        assertNull(StructureParser.trustedWebDomain("com.kotu.uygulama", "banka.com"))
        assertNull(StructureParser.trustedWebDomain("com.afgover.vault", "banka.com"))
    }

    @Test
    fun `bos beyan tarayicida da yok sayilir`() {
        assertNull(StructureParser.trustedWebDomain("com.android.chrome", ""))
        assertNull(StructureParser.trustedWebDomain("com.android.chrome", "   "))
        assertNull(StructureParser.trustedWebDomain("com.android.chrome", null))
    }

    @Test
    fun `tarayici listesi noktali paket adlaridir`() {
        StructureParser.BROWSER_PACKAGES.forEach { assertTrue(it, it.contains('.')) }
    }
}

package com.afgover.vault.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Başlık eşleşmesinin dayandığı çekirdek ad. `.com.tr` hedefinde çekirdek
 * "com" çıkıyordu ve başlığında "com" geçen her kayıt banka kaydının önüne
 * geçiyordu; Türkiye'deki bankaların çoğu `.com.tr`.
 */
class AlanAdiCekirdekTest {

    @Test
    fun `ikinci duzey ulke uzantisi atlanir`() {
        assertEquals("garanti", AutofillResponses.cekirdek("garanti.com.tr"))
        assertEquals("isbank", AutofillResponses.cekirdek("isbank.com.tr"))
        assertEquals("turkiye", AutofillResponses.cekirdek("turkiye.gov.tr"))
        assertEquals("bbc", AutofillResponses.cekirdek("news.bbc.co.uk"))
        assertEquals("amazon", AutofillResponses.cekirdek("amazon.co.jp"))
    }

    @Test
    fun `tek parcali uzantida son parca atlanir`() {
        assertEquals("google", AutofillResponses.cekirdek("accounts.google.com"))
        assertEquals("github", AutofillResponses.cekirdek("github.com"))
        assertEquals("web", AutofillResponses.cekirdek("web.de"))
    }

    @Test
    fun `ulke uzantisinin onundeki gercek ad korunur`() {
        // "turkcell" ikinci düzey bir uzantı değil: atlanmaz.
        assertEquals("turkcell", AutofillResponses.cekirdek("turkcell.tr"))
        assertEquals("abc", AutofillResponses.cekirdek("abc.net.au"))
    }

    @Test
    fun `tek parcali ad cekirdek vermez`() {
        assertNull(AutofillResponses.cekirdek("localhost"))
    }
}

package com.afgover.vault.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PinBackoffTest {

    @Test
    fun `ilk dort yanlis serbest, sonra ussel bekleme`() {
        assertEquals(0L, PinBackoff.waitMs(4))
        assertEquals(30_000L, PinBackoff.waitMs(5))
        assertEquals(60_000L, PinBackoff.waitMs(6))
        assertEquals(300_000L, PinBackoff.waitMs(7))
        assertEquals(900_000L, PinBackoff.waitMs(8))
        assertEquals(900_000L, PinBackoff.waitMs(40))
    }

    @Test
    fun `saati ileri almak beklemeyi atlatmaz`() {
        // 5. yanlış: 30 sn. Duvar saati 15 dk ileri alındı, monoton saat 3 sn ilerledi.
        val kalan = PinBackoff.remainingMs(
            fails = 5, failWall = 1_000_000L, failMono = 50_000L,
            nowWall = 1_000_000L + 15 * 60_000L, nowMono = 53_000L
        )
        assertEquals(27_000L, kalan)
    }

    @Test
    fun `saati geri almak beklemeyi uzatmaz, monoton saat belirler`() {
        val kalan = PinBackoff.remainingMs(
            fails = 5, failWall = 1_000_000L, failMono = 50_000L,
            nowWall = 1_000_000L - 60_000L, nowMono = 80_001L
        )
        assertEquals(0L, kalan)   // duvar saati geri: 90 sn derdi; monoton saat süre doldu diyor
    }

    @Test
    fun `yeniden baslatmada monoton damga dusulur, duvar saati kalir`() {
        // nowMono < failMono: cihaz yeniden başlamış.
        val kalan = PinBackoff.remainingMs(
            fails = 5, failWall = 1_000_000L, failMono = 500_000L,
            nowWall = 1_010_000L, nowMono = 4_000L
        )
        assertEquals(20_000L, kalan)
    }

    @Test
    fun `eski kayitta monoton damga yoksa yalniz duvar saati`() {
        assertEquals(10_000L, PinBackoff.remainingMs(5, 100_000L, 0L, 120_000L, 999_999L))
        assertEquals(0L, PinBackoff.remainingMs(5, 100_000L, 0L, 130_000L, 999_999L))
    }
}

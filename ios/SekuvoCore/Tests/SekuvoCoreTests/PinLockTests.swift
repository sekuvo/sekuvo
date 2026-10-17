import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Android'deki `PinLockTest` ve PIN sayacıyla aynı kurallar.
struct PinLockTests {

    let tuz = Crypto.randomBytes(16)
    let dataKey = Crypto.randomBytes(32)

    @Test func dogruPinDataKeyiGeriVerirYanlisiVermez() throws {
        let ic = try PinLock.wrap(dataKey: dataKey, pin: "4821", salt: tuz)
        #expect(PinLock.unwrap(ic, pin: "4821", salt: tuz) == dataKey)
        #expect(PinLock.unwrap(ic, pin: "4822", salt: tuz) == nil)
        #expect(PinLock.unwrap(ic, pin: "4821", salt: Crypto.randomBytes(16)) == nil)
    }

    @Test func dogrulamaBlobuPiniSargiyaDokunmadanSinar() throws {
        let kontrol = try PinLock.wrapCheck(pin: "123456", salt: tuz)
        #expect(PinLock.unwrapCheck(kontrol, pin: "123456", salt: tuz))
        #expect(!PinLock.unwrapCheck(kontrol, pin: "123457", salt: tuz))
    }

    @Test func pinYalniz4Ile12HaneRakamOlabilir() {
        #expect(PinLock.isValid("1234"))
        #expect(PinLock.isValid("123456789012"))
        #expect(!PinLock.isValid("123"))
        #expect(!PinLock.isValid("1234567890123"))
        #expect(!PinLock.isValid("12a4"))
        #expect(!PinLock.isValid("١٢٣٤"))   // ASCII dışı rakam: Android'in klavyesi de üretmez
    }

    @Test func beklemeAndroidSayaciylaAyni() {
        #expect((0...4).allSatisfy { PinBackoff.waitMs(fails: $0) == 0 })
        #expect(PinBackoff.waitMs(fails: 5) == 30_000)
        #expect(PinBackoff.waitMs(fails: 6) == 60_000)
        #expect(PinBackoff.waitMs(fails: 7) == 300_000)
        #expect(PinBackoff.waitMs(fails: 9) == 900_000)
        #expect(PinBackoff.remainingMs(fails: 5, lastFailAt: 1_000, now: 11_000) == 20_000)
        #expect(PinBackoff.remainingMs(fails: 5, lastFailAt: 1_000, now: 40_000) == 0)
        #expect(PinBackoff.remainingMs(fails: 3, lastFailAt: 1_000, now: 1_000) == 0)
        #expect(PinBackoff.maxFails == 10)
    }
}

struct PinBackoffSaatTests {

    @Test func saatiIleriAlmakBeklemeyiAtlatmaz() {
        // 5. yanlış: 30 sn. Duvar saati 15 dk ileri, monoton saat 3 sn ilerledi.
        let kalan = PinBackoff.remainingMs(fails: 5, failWall: 1_000_000, failMono: 50_000,
                                           nowWall: 1_000_000 + 15 * 60_000, nowMono: 53_000)
        #expect(kalan == 27_000)
    }

    @Test func saatiGeriAlmakBeklemeyiUzatmaz() {
        let kalan = PinBackoff.remainingMs(fails: 5, failWall: 1_000_000, failMono: 50_000,
                                           nowWall: 1_000_000 - 60_000, nowMono: 80_001)
        #expect(kalan == 0)
    }

    @Test func yenidenBaslatmadaMonotonDamgaDuserDuvarSaatiKalir() {
        let kalan = PinBackoff.remainingMs(fails: 5, failWall: 1_000_000, failMono: 500_000,
                                           nowWall: 1_010_000, nowMono: 4_000)
        #expect(kalan == 20_000)
    }

    @Test func eskiKayittaDamgaYoksaYalnizDuvarSaati() {
        #expect(PinBackoff.remainingMs(fails: 5, failWall: 100_000, failMono: 0, nowWall: 120_000, nowMono: 999_999) == 10_000)
        #expect(PinBackoff.remainingMs(fails: 5, failWall: 100_000, failMono: 0, nowWall: 130_000, nowMono: 999_999) == 0)
    }
}

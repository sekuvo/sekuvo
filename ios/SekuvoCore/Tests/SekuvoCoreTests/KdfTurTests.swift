import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Android'deki `KdfTurTest` ve `ZayifParolaTest` ile aynı kurallar: yeni
/// yazılanlar 600.000 turla, eski kayıtlar yazıldıkları sayıyla açılır; PIN
/// sargısı sayıyı saklamadığı için eski sayıda sabittir.
struct KdfTurTests {

    let parola = "Deneme-2026x"

    @Test func yeniYazimlar600BinEskiKayitlar310Bin() {
        #expect(Crypto.kdfIterations == 600_000)
        #expect(Crypto.legacyKdfIterations == 310_000)
        #expect(Crypto.kdfIterations <= Backup.maxIterations)
        #expect(Crypto.needsKdfUpgrade(310_000))
        #expect(!Crypto.needsKdfUpgrade(600_000))
    }

    @Test func pinTuruSabitBugunkuSargilarAcilir() throws {
        #expect(PinLock.iterations == 310_000)
        let tuz = Crypto.randomBytes(16)
        let dataKey = Crypto.randomBytes(32)
        // 1.2 ve öncesinin yazdığı iç sargı: PIN 310.000 turla türetilmişti.
        let eski = try Crypto.encrypt(dataKey, key: Crypto.deriveKey(password: "4821", salt: tuz, iterations: 310_000))
        #expect(PinLock.unwrap(eski, pin: "4821", salt: tuz) == dataKey)
    }

    @Test func yedek600BinTurlaYazilirVeAcilir() throws {
        var veri = EntryData()
        veri.notes = "içerik"
        let kayit = DecryptedEntry(type: .note, title: "Not", data: veri, createdAt: 1, updatedAt: 1)
        let zarf = try Backup.export(password: parola, entries: [kayit])
        let json = try #require(try JSONSerialization.jsonObject(with: zarf) as? [String: Any])
        let kdf = try #require(json["kdf"] as? [String: Any])
        #expect(kdf["iterations"] as? Int == 600_000)
        #expect(try Backup.import(zarf, password: parola).entries.first?.data.notes == "içerik")
    }

    @Test func eskiYedekVeTurAlaniOlmayanZarfAcilir() throws {
        for turYaz in [true, false] {
            let sonuc = try Backup.import(eskiZarf(turYaz: turYaz), password: parola)
            #expect(sonuc.entries.first?.title == "Eski")
        }
    }

    @Test func eskiSargiIlkParolaliAcilistaYukseltilir() throws {
        let klasor = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: klasor) }
        let km = KeyManager(file: klasor.appendingPathComponent("keys.json"))
        // 1.2'nin yazdığı sargı: 310.000 tur.
        let dataKey = Crypto.randomBytes(32)
        let tuz = Crypto.randomBytes(16)
        let sargi = try Crypto.encrypt(dataKey, key: Crypto.deriveKey(password: parola, salt: tuz, iterations: 310_000))
        try FileManager.default.createDirectory(at: klasor, withIntermediateDirectories: true)
        try JSONEncoder().encode(KeyManager.Wrapped(salt: tuz.base64EncodedString(), iterations: 310_000,
                                                    wrappedKey: sargi.base64EncodedString()))
            .write(to: km.file)

        let acilan = try #require(km.unlock(password: parola))
        #expect(acilan.withUnsafeBytes { Data($0) } == dataKey)
        #expect(try km.upgradeIfNeeded(password: parola, dataKey: acilan))
        #expect(km.storedIterations == 600_000)
        // Yeni sargı aynı dataKey'i aynı parolayla açar; ikinci yükseltme olmaz.
        let yeniden = try #require(km.unlock(password: parola))
        #expect(yeniden.withUnsafeBytes { Data($0) } == dataKey)
        #expect(try !km.upgradeIfNeeded(password: parola, dataKey: yeniden))
        #expect(km.unlock(password: "baska") == nil)
    }

    @Test func zayifYedekParolasiKurali() {
        // Android'deki ZayifParolaTest ile aynı örnekler.
        #expect(Backup.isWeakPassword("Kisa-Parola1"))
        #expect(Backup.isWeakPassword("Aa1!Aa1!Aa1!Aa1"))
        #expect(Backup.isWeakPassword("abcdefghijklmnop"))
        #expect(Backup.isWeakPassword("1234567890123456"))
        #expect(Backup.isWeakPassword("ABCDEFGHIJKLMNOPQ"))
        #expect(Backup.isWeakPassword("!@#$%^&*()-_=+[]"))
        #expect(!Backup.isWeakPassword("abcdefghijklmno1"))
        #expect(!Backup.isWeakPassword("kedi masa kalem defter"))
        #expect(!Backup.isWeakPassword("Correct horse battery staple"))
        #expect(!Backup.isWeakPassword("çok gizli şifre öğün"))
        #expect(Backup.isWeakPassword("数据加密很重要因为隐私是基本的权利"))
        #expect(!Backup.isWeakPassword("数据加密很重要因为隐私是基本的权利。"))
    }

    /// 1.2'nin yazdığı biçimde, 310.000 turla mühürlenmiş zarf.
    private func eskiZarf(turYaz: Bool) throws -> Data {
        let tuz = Crypto.randomBytes(16)
        let anahtar = Crypto.deriveKey(password: parola, salt: tuz, iterations: 310_000)
        let icerik = #"{"entries":[{"type":"NOTE","title":"Eski","data":{"notes":"x"}}]}"#
        let blob = try Crypto.encrypt(Data(icerik.utf8), key: anahtar)
        var kdf: [String: Any] = ["algo": "PBKDF2WithHmacSHA256", "salt": tuz.base64EncodedString()]
        if turYaz { kdf["iterations"] = 310_000 }
        let zarf: [String: Any] = ["app": "vault", "version": 1, "kdf": kdf, "cipher": "AES-256-GCM",
                                   "data": blob.base64EncodedString()]
        return try JSONSerialization.data(withJSONObject: zarf)
    }
}

import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Ortak biçim: `bicim/vektorler/` değerlerini iOS çekirdeğiyle açar.
///
/// Aynı dosyaları Android'de `OrtakBicimTest` (JVM) okur; değerleri
/// `bicim/uret.py` (Python `cryptography`) üretir. Üçü anlaştığı sürece bir
/// platformun yazdığı `.vaultbak` ve QR zarfı diğerinde açılır.
struct OrtakBicimTests {

    static let kok = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // SekuvoCoreTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // SekuvoCore
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // depo kökü
        .appendingPathComponent("bicim/vektorler")

    static func oku(_ ad: String) throws -> [String: Any] {
        let veri = try Data(contentsOf: kok.appendingPathComponent(ad))
        return try #require(try JSONSerialization.jsonObject(with: veri) as? [String: Any])
    }

    static func b64(_ s: Any?) throws -> Data {
        let metin = try #require(s as? String)
        return try #require(Data(base64Encoded: metin))
    }
    static func hex(_ s: String) -> Data {
        var d = Data(); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return d
    }
    static func nesneler(_ v: Any?) -> [[String: Any]] { (v as? [Any])?.compactMap { $0 as? [String: Any] } ?? [] }

    @Test func anahtarTuretmeOrtakDegerlerleAyni() throws {
        for v in Self.nesneler(try Self.oku("kdf.json")["vektorler"]) {
            let anahtar = Crypto.deriveKey(password: v["parola"] as! String, salt: try Self.b64(v["tuz"]),
                                           iterations: v["tur"] as! Int)
            let hex = anahtar.withUnsafeBytes { Data($0) }.map { String(format: "%02x", $0) }.joined()
            #expect(hex == v["anahtar_hex"] as? String, "\(v["parola"]!)")
        }
    }

    @Test func aesGcmOrtakDegerleriAcarVeAyniIvIleAyniBlobuUretir() throws {
        let d = try Self.oku("aesgcm.json")
        for v in Self.nesneler(d["vektorler"]) {
            let anahtar = SymmetricKey(data: Self.hex(v["anahtar_hex"] as! String))
            let blob = try Self.b64(v["blob"])
            let acik = try #require(Crypto.decrypt(blob, key: anahtar))
            #expect(String(data: acik, encoding: .utf8) == v["acik"] as? String)
            // Ters yön: aynı IV ile mühürlenen metin aynı baytları vermeli.
            let yeniden = try Crypto.encrypt(Data((v["acik"] as! String).utf8), key: anahtar, iv: blob.prefix(12))
            #expect(yeniden == blob)
        }
        let bozuk = try #require(d["bozuk"] as? [String: Any])
        #expect(Crypto.decrypt(try Self.b64(bozuk["blob"]),
                               key: SymmetricKey(data: Self.hex(bozuk["anahtar_hex"] as! String))) == nil)
    }

    /// Cihazdaki biçim sürüm 2: mühür yalnız aynı AAD ile açılır (Android aynı değerleri sınar).
    @Test func aadliMuhurYalnizAyniAadIleAcilir() throws {
        let d = try Self.oku("aesgcm.json")
        for v in Self.nesneler(d["aad"]) {
            let anahtar = SymmetricKey(data: Self.hex(v["anahtar_hex"] as! String))
            let blob = try Self.b64(v["blob"])
            let aad = Data((v["aad"] as! String).utf8)
            #expect(Crypto.decrypt(blob, key: anahtar, aad: aad) == Data((v["acik"] as! String).utf8))
            #expect(Crypto.decrypt(blob, key: anahtar) == nil)
            #expect(Crypto.decrypt(blob, key: anahtar, aad: aad + Data("0".utf8)) == nil)
            // Ters yön: aynı IV ve AAD ile mühürlenen metin aynı baytları vermeli.
            let yeniden = try Crypto.encrypt(Data((v["acik"] as! String).utf8), key: anahtar, iv: blob.prefix(12), aad: aad)
            #expect(yeniden == blob)
        }
    }

    static func kayitEsit(_ b: [String: Any], _ e: DecryptedEntry) {
        let t = b["title"] as! String
        #expect(e.title == t)
        #expect(e.type.rawValue == b["type"] as? String, "\(t)")
        #expect(e.noteKind.rawValue == b["noteKind"] as? String, "\(t)")
        #expect(e.createdAt == (b["createdAt"] as! NSNumber).int64Value, "\(t)")
        #expect(e.updatedAt == (b["updatedAt"] as! NSNumber).int64Value, "\(t)")
        #expect(e.quick == b["quick"] as? Bool, "\(t)")
        #expect(e.sortIndex == b["sortIndex"] as? Int, "\(t)")
        #expect(e.anchor == b["anchor"] as? Bool, "\(t)")
        #expect(e.tagNames == b["tags"] as? [String], "\(t)")
        let d = b["data"] as! [String: Any]
        for (yol, ad) in EntryData.metinAlanlari {
            #expect(e.data[keyPath: yol] == d[ad] as? String, "\(t).\(ad)")
        }
        #expect(e.data.passwordChangedAt == (d["passwordChangedAt"] as! NSNumber).int64Value, "\(t)")
        let ozel = nesneler(d["custom"]).map { "\($0["label"]!)|\($0["value"]!)|\($0["type"]!)" }
        #expect(e.data.custom.map { "\($0.label)|\($0.value)|\($0.type.rawValue)" } == ozel, "\(t)")
        let gecmis = nesneler(d["passwordHistory"]).map { "\($0["value"]!)|\(($0["changedAt"] as! NSNumber).int64Value)" }
        #expect(e.data.passwordHistory.map { "\($0.value)|\($0.changedAt)" } == gecmis, "\(t)")
    }

    @Test func gecerliZarflarAcilirVeKayitlarBeklenenGibiDoner() throws {
        for z in Self.nesneler(try Self.oku("zarf.json")["gecerli"]) {
            let sonuc = try Backup.import(Data((z["metin"] as! String).utf8), password: z["parola"] as! String)
            let beklenen = Self.nesneler(z["beklenen_kayitlar"])
            #expect(sonuc.entries.count == beklenen.count, "\(z["ad"]!)")
            for (b, e) in zip(beklenen, sonuc.entries) { Self.kayitEsit(b, e) }
            var etiketler: [String: TagDef] = [:]
            for t in Self.nesneler(z["beklenen_etiketler"]) {
                etiketler[t["name"] as! String] = TagDef(color: Int32((t["color"] as! NSNumber).intValue), icon: t["icon"] as! String)
            }
            #expect(sonuc.tagDefs == etiketler, "\(z["ad"]!)")
        }
    }

    @Test func yanlisParolaVeGecersizZarflarAyriHataVerir() throws {
        let d = try Self.oku("zarf.json")
        let y = try #require(d["yanlis_parola"] as? [String: Any])
        #expect(throws: Backup.Failure.wrongPassword) {
            try Backup.import(Data((y["metin"] as! String).utf8), password: y["parola"] as! String)
        }
        for g in Self.nesneler(d["gecersiz"]) {
            #expect(throws: Backup.Failure.invalidFormat, "\(g["ad"]!)") {
                try Backup.import(Data((g["metin"] as! String).utf8), password: "herhangi")
            }
        }
    }

    @Test func qrKareleriSirasizBirlesirBozukKumeReddedilir() throws {
        let d = try Self.oku("qr.json")
        #expect(d["parca"] as? Int == QrTransfer.chunk)
        for g in Self.nesneler(d["gecerli"]) {
            let kareler = try (g["kareler"] as! [String]).map { try #require(QrTransfer.parse($0)) }
            #expect(QrTransfer.assemble(kareler.reversed()) == g["zarf_metni"] as? String, "\(g["ad"]!)")
        }
        for m in d["ayristirilamaz"] as! [String] { #expect(QrTransfer.parse(m) == nil, "\(m)") }
        for g in Self.nesneler(d["birlesmez"]) {
            let kareler = (g["kareler"] as! [String]).compactMap(QrTransfer.parse)
            #expect(QrTransfer.assemble(kareler) == nil, "\(g["ad"]!)")
        }
    }

    @Test func qrIleGelenTamYedekAcilir() throws {
        let g = try #require(Self.nesneler(try Self.oku("qr.json")["gecerli"]).first { $0["ad"] as? String == "cok-kare-Z" })
        let zarf = try #require(QrTransfer.assemble((g["kareler"] as! [String]).compactMap(QrTransfer.parse)))
        let sonuc = try Backup.import(Data(zarf.utf8), password: "yedek-parolasi-123")
        #expect(sonuc.entries.count == 7)
    }
}

/// iOS'un kendi ürettiği zarf ve kareler: gidiş-dönüş.
struct GidisDonusTests {

    @Test func yedekGidisDonusHicbirSeyDusurmez() throws {
        var veri = EntryData()
        veri.username = "deniz@example.com"; veri.password = "yeni"
        veri.custom = [CustomField(label: "PIN", value: "1234", type: .password)]
        veri.passwordChangedAt = 3_000
        veri.passwordHistory = [OldPassword(value: "eski", changedAt: 2_000)]
        let kayit = DecryptedEntry(id: 5, type: .login, title: "Örnek · ğüşıöç 🔐", data: veri,
                                   createdAt: 1_000, updatedAt: 3_000, quick: true, tagIds: [9],
                                   noteKind: .genel, sortIndex: 4, anchor: false)
        let capa = DecryptedEntry(id: 6, type: .note, title: "aktar.html", data: { var d = EntryData(); d.notes = "85ee"; return d }(),
                                  createdAt: 1, updatedAt: 1, noteKind: .parmakIzi, anchor: true)
        let zarf = try Backup.export(password: "yedek-parolasi-123", entries: [kayit, capa],
                                     tags: [Backup.Tag(id: 9, name: "iş", color: -11549705, icon: "work")])
        let sonuc = try Backup.import(zarf, password: "yedek-parolasi-123")
        var beklenen = kayit; beklenen.id = 0; beklenen.tagIds = []; beklenen.tagNames = ["iş"]
        var beklenenCapa = capa; beklenenCapa.id = 0
        #expect(sonuc.entries == [beklenen, beklenenCapa])
        #expect(sonuc.tagDefs == ["iş": TagDef(color: -11549705, icon: "work")])
        #expect(throws: Backup.Failure.wrongPassword) { try Backup.import(zarf, password: "baska") }
    }

    @Test func qrUretimiOkuyucuylaGeriAcilir() throws {
        for metin in ["kısa", String(repeating: "{\"app\":\"vault\",\"data\":\"AAAA\"}", count: 400)] {
            let kareler = QrTransfer.frames(for: metin)
            let ayrik = kareler.compactMap(QrTransfer.parse)
            #expect(ayrik.count == kareler.count)
            #expect(QrTransfer.assemble(ayrik.shuffled()) == metin)
            #expect(QrTransfer.missing(Array(ayrik.dropFirst())) == (ayrik.count > 1 ? [1] : []))
        }
    }

    @Test func zipBombasiReddedilir() throws {
        // 5 MB sıfır → birkaç KB deflate: tavanı (4 MB) aşan açılım reddedilmeli.
        let bomba = try #require(QrTransfer.deflate(Data(count: 5 * 1024 * 1024)))
        #expect(bomba.count < 64 * 1024)
        #expect(QrTransfer.inflate(bomba) == nil)
    }
}

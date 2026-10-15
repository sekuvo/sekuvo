import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Kasa motoru: içe aktarma, geri yükleme, tümünü değiştirme, çıpa ve etiket kuralları.
/// Android'deki `VaultRepository` davranışının karşılığı; ortak yedek değeriyle sınanır.
struct VaultStoreTests {

    let dosya = FileManager.default.temporaryDirectory
        .appendingPathComponent("sekuvo-test-\(UUID().uuidString)").appendingPathComponent("vault.sqlite")
    let anahtar = SymmetricKey(size: .bits256)

    func kasa() throws -> VaultStore { try VaultStore(file: dosya, key: anahtar) }

    func tamYedek() throws -> Backup.ImportResult {
        let d = try OrtakBicimTests.oku("zarf.json")
        let z = try #require(OrtakBicimTests.nesneler(d["gecerli"]).first { $0["ad"] as? String == "tam-yedek" })
        return try Backup.import(Data((z["metin"] as! String).utf8), password: z["parola"] as! String)
    }

    func kayit(_ baslik: String, _ tur: EntryType = .login, sifre: String = "") -> DecryptedEntry {
        var d = EntryData(); d.password = sifre
        return DecryptedEntry(type: tur, title: baslik, data: d, createdAt: 0, updatedAt: 0)
    }

    @Test func androidYedegiAcilirEtiketlerKurulurCipaGizlenir() throws {
        let k = try kasa()
        let sonuc = try tamYedek()
        #expect(try k.apply(sonuc, replace: false) == 7)

        // Listede 6 kayıt: çıpa kendi düğmesinden açılır.
        let liste = try k.items()
        #expect(liste.count == 6)
        #expect(!liste.contains { $0.title == "aktar.html" })

        // Etiketler yedekteki renk ve ikonla kuruldu; bilinmeyen ikon düştü.
        let etiketler = try k.tags()
        #expect(Set(etiketler.map(\.name)) == ["iş", "finans"])
        #expect(etiketler.first { $0.name == "iş" }?.color == -11549705)
        #expect(etiketler.first { $0.name == "iş" }?.icon == "work")

        // Kayıtların etiket id'leri adlara geri çözülüyor.
        let ad = Dictionary(uniqueKeysWithValues: etiketler.map { ($0.id, $0.name) })
        let deploy = try #require(liste.first { $0.title == "Deploy anahtarı (ed25519)" })
        #expect(deploy.tagIds.compactMap { ad[$0] } == ["iş", "finans"])
        #expect(deploy.noteKind == .anahtar)

        // Şifreli gövde birebir döndü (parola geçmişi, ek alan türleri dahil).
        let github = try #require(liste.first { $0.title == "GitHub" })
        let acik = try #require(k.load(id: github.id))
        let beklenen = try #require(sonuc.entries.first { $0.title == "GitHub" })
        #expect(acik.data == beklenen.data)
        #expect(acik.quick && acik.sortIndex == 2 && acik.createdAt == beklenen.createdAt)
    }

    @Test func iOSYedegiAndroidinOkuduguBicimdeGeriDoner() throws {
        let k = try kasa()
        try k.apply(try tamYedek(), replace: false)
        let (hepsi, eksik) = try k.exportable()
        #expect(eksik == 0)
        #expect(hepsi.count == 7)
        let zarf = try Backup.export(password: "yeni-yedek-parolasi", entries: hepsi, tags: try k.tags())

        // Aynı zarf ikinci bir kasaya (başka bir cihaz) kurulunca her şey aynı.
        let ikinci = try VaultStore(file: dosya.deletingLastPathComponent().appendingPathComponent("ikinci.sqlite"),
                                    key: SymmetricKey(size: .bits256))
        let geri = try Backup.import(zarf, password: "yeni-yedek-parolasi")
        try ikinci.apply(geri, replace: false)
        let a = try k.exportable().entries.map(\.title).sorted()
        let b = try ikinci.exportable().entries.map(\.title).sorted()
        #expect(a == b)
        let cipa = try #require(try ikinci.exportable().entries.first(where: \.anchor))
        #expect(cipa.noteKind == .parmakIzi && cipa.title == "aktar.html")
    }

    @Test func bosZarfTumunuDegistirmedeKasayaDokunmaz() throws {
        let k = try kasa()
        try k.save(kayit("Kalmalı"), now: 1)
        #expect(try k.apply(Backup.ImportResult(entries: [], tagDefs: [:]), replace: true) == 0)
        #expect(try k.items().map(\.title) == ["Kalmalı"])
    }

    @Test func tumunuDegistirEskiyiSilerYenisiniYazar() throws {
        let k = try kasa()
        try k.save(kayit("Eski"), now: 1)
        try k.apply(try tamYedek(), replace: true)
        let basliklar = try k.items().map(\.title)
        #expect(!basliklar.contains("Eski"))
        #expect(basliklar.count == 6)
    }

    @Test func sinirAsanKayitVarsaKasayaHicDokunulmaz() throws {
        let k = try kasa()
        try k.save(kayit("Kalmalı"), now: 1)
        var dev = EntryData(); dev.notes = String(repeating: "x", count: VaultStore.maxEntryBytes + 1)
        let sonuc = Backup.ImportResult(entries: [kayit("Küçük"), DecryptedEntry(type: .note, title: "Dev", data: dev, createdAt: 1, updatedAt: 1)],
                                        tagDefs: [:])
        #expect(throws: VaultStore.Failure.self) { try k.apply(sonuc, replace: true) }
        #expect(try k.items().map(\.title) == ["Kalmalı"])
    }

    @Test func kasadaEnCokBirCipaYeniOlanKalir() throws {
        let k = try kasa()
        func capa(_ deger: String, _ zaman: Int64) -> DecryptedEntry {
            var d = EntryData(); d.notes = deger
            return DecryptedEntry(type: .note, title: "aktar.html", data: d, createdAt: zaman, updatedAt: zaman,
                                  noteKind: .parmakIzi, anchor: true)
        }
        try k.apply(Backup.ImportResult(entries: [capa("eski", 10)], tagDefs: [:]), replace: false)
        try k.apply(Backup.ImportResult(entries: [capa("daha-eski", 5)], tagDefs: [:]), replace: false)
        try k.apply(Backup.ImportResult(entries: [capa("yeni", 20)], tagDefs: [:]), replace: false)
        let capalar = try k.exportable().entries.filter(\.anchor)
        #expect(capalar.count == 1)
        #expect(capalar.first?.data.notes == "yeni")
    }

    @Test func etiketAdiBuyukKucukHarfGozetmedenEslesir() throws {
        let k = try kasa()
        var e = kayit("A"); e.tagNames = ["Finans"]
        try k.apply(Backup.ImportResult(entries: [e], tagDefs: [:]), replace: false)
        var f = kayit("B"); f.tagNames = ["finans"]
        try k.apply(Backup.ImportResult(entries: [f], tagDefs: [:]), replace: false)
        #expect(try k.tags().count == 1)
        #expect(try k.tags().first?.color == TagPalette.color(for: 0))
    }

    @Test func parolaDegisinceEskisiKonduguTarihleGecmiseGirer() throws {
        let k = try kasa()
        let id = try k.save(kayit("Hesap", sifre: "bir"), now: 100)
        var e = try #require(k.load(id: id))
        #expect(e.data.passwordChangedAt == 100)
        e.data.password = "iki"
        try k.save(e, now: 200)
        var son = try #require(k.load(id: id))
        // Android ile aynı: geçmişteki tarih, eski parolanın konduğu an.
        #expect(son.data.passwordHistory == [OldPassword(value: "bir", changedAt: 100)])
        #expect(son.data.passwordChangedAt == 200)
        #expect(son.createdAt == 100 && son.updatedAt == 200)
        // Üçüncü parola: en yenisi başta, her biri kendi konduğu anla.
        son.data.password = "uc"
        try k.save(son, now: 300)
        let ucuncu = try #require(k.load(id: id))
        #expect(ucuncu.data.passwordHistory.map(\.value) == ["iki", "bir"])
        #expect(ucuncu.data.passwordHistory.map(\.changedAt) == [200, 100])
        // Parola aynı kalınca geçmiş büyümez.
        try k.save(ucuncu, now: 400)
        #expect(try #require(k.load(id: id)).data.passwordHistory.count == 2)
    }

    @Test func damgasizEskiParolaGecmiseDustuguAnlaGirer() throws {
        let k = try kasa()
        var d = EntryData(); d.password = "eski"   // passwordChangedAt 0: eski sürümün kaydı
        let gelen = DecryptedEntry(type: .login, title: "Eski", data: d, createdAt: 50, updatedAt: 50)
        try k.apply(Backup.ImportResult(entries: [gelen], tagDefs: [:]), replace: false)
        let id = try #require(try k.items().first?.id)
        var e = try #require(k.load(id: id))
        #expect(e.data.passwordChangedAt == 0)
        e.data.password = "yeni"
        try k.save(e, now: 400)
        let son = try #require(k.load(id: id))
        #expect(son.data.passwordHistory == [OldPassword(value: "eski", changedAt: 400)])
        #expect(son.data.passwordChangedAt == 400)
    }
}

struct QrCollectorTests {

    func kareler(_ ad: String) throws -> [String] {
        let d = try OrtakBicimTests.oku("qr.json")
        let g = try #require(OrtakBicimTests.nesneler(d["gecerli"]).first { $0["ad"] as? String == ad })
        return g["kareler"] as! [String]
    }

    @Test func karelerSirasizVeTekrarliGelirKumeTamamlanincaZarfKurulur() throws {
        let k = try kareler("cok-kare-Z")
        var t = QrCollector()
        #expect(t.add("başka bir QR") == .ignored)
        #expect(t.add(k[2]) == .progress(received: 1, total: k.count))
        #expect(t.add(k[2]) == .ignored)                       // aynı kare tekrar
        #expect(t.add(k[0]) == .progress(received: 2, total: k.count))
        #expect(t.missing == Array(1...k.count).filter { $0 != 1 && $0 != 3 })
        // Tamamlanma bir kez olur; ardından gelen tekrar kareler yok sayılır.
        var son: QrCollector.Event = .ignored
        for kare in k.reversed() {
            let olay = t.add(kare)
            if case .complete = olay { son = olay }
        }
        guard case .complete(let zarf) = son else { Issue.record("tamamlanmadı: \(son)"); return }
        #expect(try Backup.import(Data(zarf.utf8), password: "yedek-parolasi-123").entries.count == 7)
    }

    @Test func baskaAktarimdanGelenKareYeniKumeBaslatir() throws {
        let cok = try kareler("cok-kare-Z")
        let tek = try kareler("tek-kare-Z")
        var t = QrCollector()
        _ = t.add(cok[0])
        _ = t.add(cok[1])
        guard case .complete = t.add(tek[0]) else { Issue.record("tek karelik aktarım tamamlanmalıydı"); return }
    }

    @Test func celisenKumeSifirlanir() throws {
        let k = try kareler("cok-kare-Z")
        var t = QrCollector()
        for kare in k.dropLast() { _ = t.add(kare) }
        // Son kare bozuk veriyle: küme tamam ama zarf kurulamıyor.
        let bozuk = k.last!.components(separatedBy: "|").prefix(3).joined(separator: "|") + "|@@@@"
        #expect(t.add(bozuk) == .mismatch)
        #expect(t.frames.isEmpty)
    }
}

extension VaultStoreTests {
    @Test func logoHaritasiAdresVeSecimdenKurulur() throws {
        let k = try kasa()
        var a = kayit("İş"); a.data.url = "https://github.com/x"
        var b = kayit("GitHub"); b.data.brand = Brands.none
        let c = kayit("Akbank")
        try k.apply(Backup.ImportResult(entries: [a, b, c], tagDefs: [:]), replace: false)
        let harita = try k.brandIds()
        let adla = Dictionary(uniqueKeysWithValues: try k.items().map { ($0.title, harita[$0.id]) })
        #expect(adla["İş"] == "github")
        #expect(adla["GitHub"] == .some(nil))
        #expect(adla["Akbank"] == "akbank")
    }

    @Test func yalnizCipaTasiyanZarfTumunuDegistirIleKasayiBosaltmaz() throws {
        let k = try kasa()
        try k.apply(Backup.ImportResult(entries: [kayit("Kalsın")], tagDefs: [:]), replace: false)
        var d = EntryData(); d.notes = "sha"
        let capa = DecryptedEntry(type: .note, title: "aktar.html", data: d, createdAt: 1, updatedAt: 1,
                                  noteKind: .parmakIzi, anchor: true)
        #expect(try k.apply(Backup.ImportResult(entries: [capa], tagDefs: [:]), replace: true) == 0)
        #expect(try k.items().map(\.title) == ["Kalsın"])
    }

    @Test func geriYuklemeSayisiYazilanSatirlardir() throws {
        let k = try kasa()
        func capa(_ zaman: Int64) -> DecryptedEntry {
            var d = EntryData(); d.notes = "x"
            return DecryptedEntry(type: .note, title: "aktar.html", data: d, createdAt: zaman, updatedAt: zaman,
                                  noteKind: .parmakIzi, anchor: true)
        }
        try k.apply(Backup.ImportResult(entries: [capa(20)], tagDefs: [:]), replace: false)
        // Eski çıpa atlanır, sayıya girmez; kayıt sayılır.
        #expect(try k.apply(Backup.ImportResult(entries: [kayit("A"), capa(5)], tagDefs: [:]), replace: false) == 1)
    }
}

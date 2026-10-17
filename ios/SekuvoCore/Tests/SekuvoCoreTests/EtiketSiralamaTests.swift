import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Etiket işlemleri ve liste sıralaması — Android'deki `VaultRepository`
/// etiket işlevleri ve `EntrySort` kuralıyla aynı davranış.
struct EtiketSiralamaTests {

    let dosya = FileManager.default.temporaryDirectory
        .appendingPathComponent("sekuvo-test-\(UUID().uuidString)").appendingPathComponent("vault.sqlite")

    func kasa() throws -> VaultStore { try VaultStore(file: dosya, key: SymmetricKey(size: .bits256)) }

    func kayit(_ baslik: String, _ tur: EntryType = .login, etiket: [Int64] = [], oncelik: Int = 0) -> DecryptedEntry {
        DecryptedEntry(type: tur, title: baslik, createdAt: 0, updatedAt: 0, tagIds: etiket, sortIndex: oncelik)
    }

    @Test func silinenEtiketKayitlardanDuserKayitDegismez() throws {
        let k = try kasa()
        let is_ = try k.addTag(name: "iş", color: TagPalette.color(for: 0), icon: "work")
        let banka = try k.addTag(name: "banka", color: TagPalette.color(for: 1))
        let a = try k.save(kayit("A", etiket: [is_, banka]), now: 100)
        let b = try k.save(kayit("B", etiket: [banka]), now: 200)
        let oncesi = try k.load(id: a)

        try k.deleteTag(id: banka)

        #expect(try k.tags().map(\.name) == ["iş"])
        let liste = try k.items()
        #expect(liste.first { $0.id == a }?.tagIds == [is_])
        #expect(liste.first { $0.id == b }?.tagIds == [])
        // Şifreli gövde ve tarihler dokunulmadan kaldı.
        let sonrasi = try #require(k.load(id: a))
        #expect(sonrasi.data == oncesi?.data && sonrasi.updatedAt == 100 && sonrasi.createdAt == 100)
    }

    @Test func etiketDuzenlenirAdKirpilirBilinmeyenIkonDuser() throws {
        let k = try kasa()
        let id = try k.addTag(name: "  Ev  ", color: TagPalette.color(for: 2))
        var t = try #require(try k.tags().first)
        #expect(t.id == id && t.name == "Ev")
        t.name = " Aile "; t.color = TagPalette.color(for: 5); t.icon = "olmayan-ikon"
        try k.updateTag(t)
        let g = try #require(try k.tags().first)
        #expect(g.name == "Aile" && g.color == TagPalette.color(for: 5) && g.icon == "")
        // Boş ada çevrilemez.
        t.name = "   "
        try k.updateTag(t)
        #expect(try k.tags().first?.name == "Aile")
    }

    @Test func ayniAdliEtiketIkinciKezAcilmaz() throws {
        let k = try kasa()
        let ilk = try k.addTag(name: "Banka", color: TagPalette.color(for: 0))
        #expect(try k.addTag(name: "banka", color: TagPalette.color(for: 3)) == ilk)
        #expect(try k.tags().count == 1)
    }

    @Test func etiketlerAdaGoreSiralanir() throws {
        let k = try kasa()
        for ad in ["zeta", "Alfa", "beta"] { try k.addTag(name: ad, color: TagPalette.color(for: 0)) }
        #expect(try k.tags().map(\.name) == ["Alfa", "beta", "zeta"])
    }

    @Test func listeOlusturmaTarihiniTasir() throws {
        let k = try kasa()
        let id = try k.save(kayit("A"), now: 1_000)
        try k.save(DecryptedEntry(id: id, type: .login, title: "A", createdAt: 1_000, updatedAt: 1_000), now: 5_000)
        let i = try #require(try k.items().first)
        #expect(i.createdAt == 1_000 && i.updatedAt == 5_000)
    }

    // MARK: Sıralama

    func satir(_ id: Int64, _ baslik: String, _ tur: EntryType = .login, oncelik: Int = 0,
               olusturma: Int64 = 0, guncelleme: Int64 = 0) -> VaultStore.Item {
        VaultStore.Item(id: id, type: tur, title: baslik, sortIndex: oncelik, createdAt: olusturma, updatedAt: guncelleme)
    }

    @Test func oncelikSirasiNumaralilarOnceNumarasizlarSondaAlfabetik() {
        let l = [satir(1, "Dört"), satir(2, "Bir", oncelik: 3), satir(3, "Alfa"), satir(4, "Üç", oncelik: 1)]
        #expect(EntrySort.manual.sorted(l).map(\.id) == [4, 2, 3, 1])
        #expect(priorityKey(0) == Int.max && priorityKey(-2) == Int.max && priorityKey(7) == 7)
    }

    @Test func altiSecenekAndroidinKuraliylaDizer() {
        let l = [
            satir(1, "banka", .card, olusturma: 30, guncelleme: 10),
            satir(2, "Alfa", .note, olusturma: 10, guncelleme: 30),
            satir(3, "Çay", .login, olusturma: 20, guncelleme: 20),
        ]
        #expect(EntrySort.titleAsc.sorted(l).map(\.id) == [2, 1, 3])
        #expect(EntrySort.titleDesc.sorted(l).map(\.id) == [3, 1, 2])
        #expect(EntrySort.updatedDesc.sorted(l).map(\.id) == [2, 3, 1])
        #expect(EntrySort.createdDesc.sorted(l).map(\.id) == [1, 3, 2])
        #expect(EntrySort.type.sorted(l).map(\.id) == [3, 1, 2])   // Hesap, Kart, Not
    }

    @Test func esitlikteBaslikSonraIdKararVerir() {
        let l = [satir(9, "B", guncelleme: 5), satir(4, "A", guncelleme: 5), satir(2, "A", guncelleme: 5)]
        #expect(EntrySort.updatedDesc.sorted(l).map(\.id) == [2, 4, 9])
    }

    @Test func sayilarDogalSiradaBilinmeyenTercihVarsayilanaDuser() {
        let l = [satir(1, "Kayıt 10"), satir(2, "Kayıt 2")]
        #expect(EntrySort.titleAsc.sorted(l).map(\.id) == [2, 1])
        #expect(EntrySort.of("MANUAL") == .manual && EntrySort.of("YOK") == .titleAsc && EntrySort.of(nil) == .titleAsc)
    }
}

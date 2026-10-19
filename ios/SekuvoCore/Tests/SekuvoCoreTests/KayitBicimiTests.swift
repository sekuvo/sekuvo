import CryptoKit
import Foundation
import Testing
@testable import SekuvoCore

/// Cihazdaki kayıt biçimi sürüm 2 — Android'deki `KayitBicimiTest` ile aynı
/// kurallar: başlık ve etiket adı şifreli, her mühür yerine bağlı; 1.2'nin
/// yazdığı satırlar okunur ve kasa açılırken kayıpsız taşınır.
struct KayitBicimiTests {

    let klasor = FileManager.default.temporaryDirectory.appendingPathComponent("sekuvo-bicim-\(UUID().uuidString)")
    var dosya: URL { klasor.appendingPathComponent("vault.sqlite") }
    let anahtar = SymmetricKey(size: .bits256)

    func veri(_ sifre: String = "çok-gizli") -> EntryData {
        var d = EntryData()
        d.username = "fatih"; d.password = sifre; d.url = "banka.example"
        return d
    }

    /// 1.2'nin yazdığı satır: başlık açık, gövde verinin JSON'u, AAD yok.
    func surum1(_ baslik: String, _ d: EntryData, tur: String = "LOGIN") throws -> EntryStore.Row {
        EntryStore.Row(type: tur, title: baslik, blob: try Crypto.encrypt(try JSONSerialization.data(withJSONObject: d.json()), key: anahtar),
                       createdAt: 1, updatedAt: 2)
    }

    @Test func aadMetinleriAndroidIleAyni() {
        let uid = "00112233445566778899aabbccddeeff"
        #expect(String(decoding: KayitBicimi.govdeAad(uid: uid, type: "LOGIN", anchor: false), as: UTF8.self)
                == "sekuvo/v2/entries/\(uid)/blob/LOGIN/0")
        #expect(String(decoding: KayitBicimi.govdeAad(uid: uid, type: "NOTE", anchor: true), as: UTF8.self)
                == "sekuvo/v2/entries/\(uid)/blob/NOTE/1")
        #expect(String(decoding: KayitBicimi.hizliAad(uid: uid, type: "CARD"), as: UTF8.self) == "sekuvo/v2/entries/\(uid)/quick/CARD")
        #expect(String(decoding: KayitBicimi.etiketAad(uid: uid), as: UTF8.self) == "sekuvo/v2/tags/\(uid)/name")
        #expect(KayitBicimi.yeniUid().range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil)
    }

    @Test func surum1SatiriTasinirBaslikGovdeyeGirer() throws {
        let eski = try surum1("Banka", veri())
        #expect(KayitBicimi.govdeyiAc(eski, key: anahtar)?.title == "Banka")
        let yeni = try #require(try KayitBicimi.tasi(eski, key: anahtar))
        #expect(!yeni.uid.isEmpty && yeni.title.isEmpty)
        let acik = try #require(KayitBicimi.govdeyiAc(yeni, key: anahtar))
        #expect(acik.title == "Banka" && acik.data == veri())
        #expect(Crypto.decrypt(yeni.blob, key: anahtar) == nil)
        #expect(try KayitBicimi.tasi(yeni, key: anahtar) == nil)
    }

    @Test func takasEdilenGovdeVeDegisenTurAcilmaz() throws {
        let a = try #require(try KayitBicimi.tasi(try surum1("Netflix", veri()), key: anahtar))
        let b = try #require(try KayitBicimi.tasi(try surum1("Banka", veri("banka-parolasi")), key: anahtar))
        var takas = a; takas.blob = b.blob
        #expect(KayitBicimi.govdeyiAc(takas, key: anahtar) == nil)
        var tur = a; tur.type = "NOTE"
        #expect(KayitBicimi.govdeyiAc(tur, key: anahtar) == nil)
        var capa = a; capa.anchor = true
        #expect(KayitBicimi.govdeyiAc(capa, key: anahtar) == nil)
    }

    @Test func etiketAdiSifrelenirVeEtiketineBagli() throws {
        let eski = EntryStore.TagRow(id: 3, name: "kripto", color: 1, icon: "")
        #expect(KayitBicimi.etiketAdi(eski, key: anahtar) == "kripto")
        let yeni = try #require(try KayitBicimi.etiketiTasi(eski, key: anahtar))
        #expect(yeni.name.isEmpty && KayitBicimi.etiketAdi(yeni, key: anahtar) == "kripto")
        let baska = try KayitBicimi.etiketiMuhurle(EntryStore.TagRow(id: 4, name: "", color: 1, icon: ""), ad: "banka", key: anahtar)
        var takas = yeni; takas.nameBlob = baska.nameBlob
        #expect(KayitBicimi.etiketAdi(takas, key: anahtar) == nil)
    }

    /// Uçtan uca: 1.2'nin dosyası kasa açılırken taşınır, açık başlık dosyadan da silinir.
    @Test func eskiKasaAcilirkenTasinirVeAcikBaslikDosyadanSilinir() throws {
        defer { try? FileManager.default.removeItem(at: klasor) }
        let gizliBaslik = "Gizli-Banka-Hesabi-7731"
        do {
            let ham = try EntryStore(file: dosya)
            var satir = try surum1(gizliBaslik, veri())
            satir.tags = "[1]"
            try ham.insert(satir)
            try ham.insert(try surum1("aktar.html", veri()).with { $0.type = "NOTE"; $0.anchor = true })
            try ham.insertTag(EntryStore.TagRow(id: 0, name: "kripto-etiketi-5521", color: 7, icon: "bank"))
        }
        #expect(try Data(contentsOf: dosya).range(of: Data(gizliBaslik.utf8)) != nil)

        let kasa = try VaultStore(file: dosya, key: anahtar)
        let liste = try kasa.items()
        #expect(liste.map(\.title) == [gizliBaslik])
        #expect(try kasa.tags().map(\.name) == ["kripto-etiketi-5521"])
        #expect(kasa.load(id: liste[0].id)?.data == veri())
        #expect(try kasa.upgradeStorage() == 0)

        let ham = try EntryStore(file: dosya)
        #expect(try ham.all().allSatisfy { !$0.uid.isEmpty && $0.title.isEmpty })
        #expect(try ham.allTags().allSatisfy { !$0.uid.isEmpty && $0.name.isEmpty && $0.nameBlob != nil })
        let icerik = try Data(contentsOf: dosya)
        #expect(icerik.range(of: Data(gizliBaslik.utf8)) == nil)
        #expect(icerik.range(of: Data("kripto-etiketi-5521".utf8)) == nil)
    }

    @Test func yeniYazilanKayitVeEtiketSurum2() throws {
        defer { try? FileManager.default.removeItem(at: klasor) }
        let kasa = try VaultStore(file: dosya, key: anahtar)
        let etiket = try kasa.addTag(name: "iş", color: 1)
        let id = try kasa.save(DecryptedEntry(type: .login, title: "GitHub", data: veri(), createdAt: 0, updatedAt: 0, tagIds: [etiket]), now: 5)
        let ham = try EntryStore(file: dosya)
        let satir = try #require(try ham.get(id: id))
        #expect(!satir.uid.isEmpty && satir.title.isEmpty)
        #expect(try kasa.items().first?.title == "GitHub")
        #expect(try kasa.tags().first?.name == "iş")
        // Ad değiştirmek mühürü yeniler; renk korunur.
        try kasa.updateTag(VaultStore.Tag(id: etiket, name: "İş", color: 1))
        #expect(try kasa.tags().first?.name == "İş")
    }
}

private extension EntryStore.Row {
    func with(_ degistir: (inout Self) -> Void) -> Self { var k = self; degistir(&k); return k }
}

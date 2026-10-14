import CryptoKit
import Foundation

/// Açık kasa: kayıtları `dataKey` ile şifreleyip saklar, çözer, içe ve dışa
/// aktarır. Android'deki `data/VaultRepository.kt`'nin karşılığı; arayüzden
/// bağımsız olduğu için macOS'ta `swift test` ile sınanır.
///
/// Ağır iş (PBKDF2) burada yok: zarf açma ve kurma `Backup`'ta, çağıran onu
/// ana iş parçacığı dışında yapar ve sonucu buraya verir.
public final class VaultStore {

    /// Kayıt başına üst sınır (Android: `MAX_PLAIN_BYTES`).
    public static let maxEntryBytes = 256 * 1024

    public enum Failure: Error, Equatable {
        /// Kaydın şifrelenecek içeriği sınırı aşıyor: başlık ve bayt.
        case entryTooLarge(title: String, bytes: Int)
    }

    /// Listede gösterilen satır: şifre çözmeden okunabilen alanlar.
    public struct Item: Identifiable, Equatable, Sendable {
        public let id: Int64
        public let type: EntryType
        public let title: String
        public let noteKind: NoteKind
        public let quick: Bool
        public let sortIndex: Int
        public let tagIds: [Int64]
        public let createdAt: Int64
        public let updatedAt: Int64

        public init(id: Int64, type: EntryType, title: String, noteKind: NoteKind = .genel, quick: Bool = false,
                    sortIndex: Int = 0, tagIds: [Int64] = [], createdAt: Int64 = 0, updatedAt: Int64 = 0) {
            self.id = id; self.type = type; self.title = title; self.noteKind = noteKind; self.quick = quick
            self.sortIndex = sortIndex; self.tagIds = tagIds; self.createdAt = createdAt; self.updatedAt = updatedAt
        }
    }

    /// Etiket: ad ve görünüm (renk, ikon). Ad ve renk Android'deki gibi şifresiz
    /// durur; süzgeç liste ekranında, şifre çözmeden çalışır.
    public typealias Tag = Backup.Tag

    private let store: EntryStore
    private let key: SymmetricKey

    public init(file: URL, key: SymmetricKey) throws {
        store = try EntryStore(file: file)
        self.key = key
    }

    // MARK: Okuma

    /// Liste: araç çıpası hariç (o, kendi düğmesinden açılır).
    public func items() throws -> [Item] {
        try store.all().filter { !$0.anchor }.map { r in
            Item(id: r.id, type: .of(r.type), title: r.title, noteKind: .of(r.noteKind), quick: r.quick,
                 sortIndex: r.sortIndex, tagIds: TagIds.parse(r.tags), createdAt: r.createdAt, updatedAt: r.updatedAt)
        }
    }

    public func load(id: Int64) -> DecryptedEntry? {
        guard let r = try? store.get(id: id) else { return nil }
        return cozulmus(r)
    }

    /// Yedek için bütün kayıtlar (çıpa dahil) ve çözülemeyenlerin sayısı.
    /// Çözülemeyen kayıt sessizce düşmez: eksik bir yedeği tam sanmak, kaybın
    /// en sinsi biçimi (Android'deki denetim bulgusu).
    public func exportable() throws -> (entries: [DecryptedEntry], undecryptable: Int) {
        let satirlar = try store.all()
        let cozulen = satirlar.compactMap(cozulmus)
        return (cozulen, satirlar.count - cozulen.count)
    }

    /// Etiketler ada göre (Android: `ORDER BY name COLLATE NOCASE`).
    public func tags() throws -> [Tag] {
        try store.allTags()
            .map { Tag(id: $0.id, name: $0.name, color: $0.color, icon: $0.icon) }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    // MARK: Yazma

    /// Yeni kayıt (`id == 0`) ya da güncelleme. Parola değiştiyse eskisi
    /// geçmişe girer, en yenisi başta (Android: `EditScreen.kaydet`).
    ///
    /// Geçmişteki tarih, eski parolanın **konduğu** andır (o günkü
    /// `passwordChangedAt`). Bilinmiyorsa (damgasız eski kayıt) geçmişe
    /// düştüğü an yazılır: uydurulmuş bir tarih değil, gerçek bir olay.
    @discardableResult
    public func save(_ e: DecryptedEntry, now: Int64) throws -> Int64 {
        var veri = e.data
        if e.id != 0, let eski = load(id: e.id) {
            if eski.data.password != veri.password {
                if !eski.data.password.isEmpty {
                    let kondugu = eski.data.passwordChangedAt > 0 ? eski.data.passwordChangedAt : now
                    veri.passwordHistory.insert(OldPassword(value: eski.data.password, changedAt: kondugu), at: 0)
                }
                veri.passwordChangedAt = veri.password.isEmpty ? 0 : now
            }
        } else if !veri.password.isEmpty && veri.passwordChangedAt == 0 {
            veri.passwordChangedAt = now
        }
        let blob = try muhurle(title: e.title, data: veri)
        var satir = EntryStore.Row(id: e.id, type: e.type.rawValue, title: e.title, blob: blob,
                                   createdAt: e.id == 0 ? now : e.createdAt, updatedAt: now,
                                   quick: e.quick, tags: TagIds.serialize(e.tagIds),
                                   noteKind: e.noteKind.rawValue, sortIndex: e.sortIndex, anchor: e.anchor)
        if e.id == 0 {
            satir.id = try store.insert(satir)
        } else {
            try store.update(satir)
        }
        return satir.id
    }

    public func delete(id: Int64) throws { try store.delete(id: id) }

    // MARK: Etiketler

    /// Yeni etiket; aynı adda (büyük/küçük harf gözetmeden) etiket varsa yenisi
    /// açılmaz, onun id'si döner. Android ikinci bir kopya açıyor; içe
    /// aktarma ise adla eşlediği için iki kopyadan birini sessizce seçerdi.
    @discardableResult
    public func addTag(name: String, color: Int32, icon: String = "") throws -> Int64 {
        let ad = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let mevcut = try store.allTags().first(where: { $0.name.lowercased() == ad.lowercased() }) { return mevcut.id }
        return try store.insertTag(name: ad, color: color, icon: TagIcons.normalize(icon))
    }

    public func updateTag(_ t: Tag) throws {
        let ad = t.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ad.isEmpty else { return }
        try store.updateTag(id: t.id, name: ad, color: t.color, icon: TagIcons.normalize(t.icon))
    }

    /// Etiketi siler ve taşıyan her kayıttan düşürür, tek işlemde. Kayıtların
    /// kendisi ve güncellenme tarihleri değişmez (Android: `deleteTag`).
    public func deleteTag(id: Int64) throws {
        try store.transaction {
            try store.deleteTag(id: id)
            for r in try store.all() {
                let ids = TagIds.parse(r.tags)
                if ids.contains(id) { try store.setTags(entry: r.id, tags: TagIds.serialize(ids.filter { $0 != id })) }
            }
        }
    }

    // MARK: İçe aktarma

    /// Zarftan gelen kayıtları uygular ve kaç kayıt yazıldığını döndürür.
    ///
    /// - Boş zarfta HİÇBİR şey yapılmaz ve 0 döner: "tümünü değiştir" ile boş
    ///   zarf, kasanın tamamını silerdi (Android'deki kritik denetim bulgusu).
    /// - Şifreleme ve boyut denetimi silmeden ÖNCE yapılır; etiketlerin
    ///   oluşturulması, silme ve yazma tek işlemdedir. Bir kayıt hatalıysa
    ///   kasaya hiç dokunulmaz.
    /// - Etiketler adla, büyük/küçük harf gözetmeden eşleşir; olmayan etiket
    ///   yedekteki renk ve ikonla, renk yoksa paletten oluşturulur.
    /// - Kasada en çok bir araç çıpası olur: gelen çıpa mevcut olandan
    ///   yeniyse onun yerine geçer, değilse alınmaz.
    @discardableResult
    public func apply(_ sonuc: Backup.ImportResult, replace: Bool) throws -> Int {
        guard !sonuc.entries.isEmpty else { return 0 }

        // Çıpalar: gelenlerden yalnız en yenisi kalır.
        var gelen = sonuc.entries.filter { !$0.anchor }
        let yeniCapa = sonuc.entries.filter(\.anchor).max { $0.updatedAt < $1.updatedAt }

        // 1. Kasaya dokunmadan önce: boyut denetimi ve şifreleme.
        var hazir: [(DecryptedEntry, Data)] = []
        if let yeniCapa { gelen.append(yeniCapa) }
        for e in gelen { hazir.append((e, try muhurle(title: e.title, data: e.data))) }

        // 2. Tek işlem: etiketler, (gerekirse) silme, yazma.
        try store.transaction {
            var adla = Dictionary(try store.allTags().map { ($0.name.lowercased(), $0.id) }, uniquingKeysWith: { a, _ in a })
            var sayi = adla.count
            for ad in Self.sirali(hazir.flatMap { $0.0.tagNames }) where adla[ad.lowercased()] == nil {
                let tanim = sonuc.tagDefs[ad]
                let id = try store.insertTag(name: ad, color: tanim?.color ?? TagPalette.color(for: sayi),
                                             icon: TagIcons.normalize(tanim?.icon))
                adla[ad.lowercased()] = id
                sayi += 1
            }
            if replace { try store.deleteAllEntries() }
            var mevcutCapa: EntryStore.Row?
            if !replace { mevcutCapa = try store.all().first(where: \.anchor) }
            for (e, blob) in hazir {
                var satir = EntryStore.Row(type: e.type.rawValue, title: e.title, blob: blob,
                                           createdAt: e.createdAt, updatedAt: e.updatedAt, quick: e.quick,
                                           tags: TagIds.serialize(e.tagNames.compactMap { adla[$0.lowercased()] }),
                                           noteKind: e.noteKind.rawValue, sortIndex: e.sortIndex, anchor: e.anchor)
                if e.anchor, let mevcutCapa {
                    guard e.updatedAt >= mevcutCapa.updatedAt else { continue }
                    satir.id = mevcutCapa.id
                    try store.update(satir)
                } else {
                    try store.insert(satir)
                }
            }
        }
        return sonuc.entries.count
    }

    // MARK: Yardımcılar

    private func muhurle(title: String, data: EntryData) throws -> Data {
        let acik = try JSONSerialization.data(withJSONObject: data.json())
        guard acik.count <= Self.maxEntryBytes else { throw Failure.entryTooLarge(title: title, bytes: acik.count) }
        return try Crypto.encrypt(acik, key: key)
    }

    private func cozulmus(_ r: EntryStore.Row) -> DecryptedEntry? {
        guard let acik = Crypto.decrypt(r.blob, key: key),
              let json = (try? JSONSerialization.jsonObject(with: acik)) as? [String: Any]
        else { return nil }
        return DecryptedEntry(id: r.id, type: .of(r.type), title: r.title, data: EntryData(json: json),
                              createdAt: r.createdAt, updatedAt: r.updatedAt, quick: r.quick,
                              tagIds: TagIds.parse(r.tags), noteKind: .of(r.noteKind),
                              sortIndex: r.sortIndex, anchor: r.anchor)
    }

    /// Tekrarsız, ilk görülme sırasıyla.
    private static func sirali(_ adlar: [String]) -> [String] {
        var gorulen = Set<String>()
        return adlar.filter { gorulen.insert($0.lowercased()).inserted }
    }
}

/// Etiket renk paleti (ARGB) — Android'deki `TagPalette` ile aynı sıra.
public enum TagPalette {
    public static let colors: [Int32] = [
        Int32(bitPattern: 0xFFE5_7373), Int32(bitPattern: 0xFFFF_B74D), Int32(bitPattern: 0xFFFF_F176),
        Int32(bitPattern: 0xFF81_C784), Int32(bitPattern: 0xFF4F_C3F7), Int32(bitPattern: 0xFF95_75CD),
        Int32(bitPattern: 0xFFF0_6292), Int32(bitPattern: 0xFF90_A4AE),
    ]

    public static func color(for index: Int) -> Int32 { colors[((index % colors.count) + colors.count) % colors.count] }
}

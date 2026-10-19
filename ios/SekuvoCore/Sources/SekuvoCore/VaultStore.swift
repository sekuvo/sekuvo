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

    /// Listede gösterilen satır. Başlık şifreli gövdeden çözülür (biçim sürüm 2);
    /// açılamayan satır listeden düşmez, `unreadableTitle` adıyla görünür.
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

    /// Etiket: ad ve görünüm (renk, ikon). Ad şifreli durur (biçim sürüm 2) ve
    /// burada çözülmüş döner; renk ve ikon açıktır.
    public typealias Tag = Backup.Tag

    private let store: EntryStore
    private let key: SymmetricKey

    /// Açılamayan kaydın listedeki adı; arayüz kendi dilindeki metni koyar.
    public var unreadableTitle = "?"

    /// Kasayı açar. Biçim sürüm 1'den kalan satırlar (1.3 öncesi) burada bir
    /// kez taşınır; taşıma başarısız olursa kasa yine açılır ve eski satırlar
    /// eskisi gibi okunur, bir sonraki açılışta yeniden denenir.
    public init(file: URL, key: SymmetricKey) throws {
        store = try EntryStore(file: file)
        self.key = key
        _ = try? upgradeStorage()
    }

    // MARK: Okuma

    /// Liste: araç çıpası hariç (o, kendi düğmesinden açılır).
    public func items() throws -> [Item] {
        try store.all().filter { !$0.anchor }.map { item($0, KayitBicimi.govdeyiAc($0, key: key)) }
    }

    /// Liste ve logo eşleşmesi tek geçişte: her satır bir kez çözülür. Kasa
    /// açıkken her değişiklikte ana iş parçacığında çağrılıyor; `items()` ve
    /// `brandIds()` ayrı ayrı bütün kasayı iki kez çözüyordu (ikinci tur
    /// denetim; Android `VaultViewModel.cozulmus` tek geçişte yapıyor).
    public func itemsAndBrands() throws -> (items: [Item], brands: [Int64: String]) {
        var liste: [Item] = []
        var markalar: [Int64: String] = [:]
        for r in try store.all() where !r.anchor {
            let c = KayitBicimi.govdeyiAc(r, key: key)
            liste.append(item(r, c))
            if let c, let b = Brands.match(title: c.title, url: c.data.url, choice: c.data.brand,
                                           isCard: EntryType.of(r.type) == .card, cardNumber: c.data.cardNumber) {
                markalar[r.id] = b.id
            }
        }
        return (liste, markalar)
    }

    private func item(_ r: EntryStore.Row, _ c: (title: String, data: EntryData)?) -> Item {
        let baslik = c?.title ?? (r.title.isEmpty ? unreadableTitle : r.title)
        return Item(id: r.id, type: .of(r.type), title: baslik, noteKind: .of(r.noteKind), quick: r.quick,
                    sortIndex: r.sortIndex, tagIds: TagIds.parse(r.tags), createdAt: r.createdAt, updatedAt: r.updatedAt)
    }

    public func load(id: Int64) -> DecryptedEntry? {
        guard let r = try? store.get(id: id) else { return nil }
        return cozulmus(r)
    }

    /// Kayıt kimliği → kurum logosu kimliği (`Brands.match`). Adres ve elle
    /// seçim şifreli veride durduğu için kilit açıkken bellekte hesaplanır;
    /// veritabanına açık yazılmaz.
    public func brandIds() throws -> [Int64: String] {
        var sonuc: [Int64: String] = [:]
        for r in try store.all() where !r.anchor {
            guard let e = cozulmus(r) else { continue }
            if let b = Brands.match(title: e.title, url: e.data.url, choice: e.data.brand,
                                    isCard: e.type == .card, cardNumber: e.data.cardNumber) {
                sonuc[r.id] = b.id
            }
        }
        return sonuc
    }

    /// Yedek için bütün kayıtlar (çıpa dahil) ve çözülemeyenlerin sayısı.
    /// Çözülemeyen kayıt sessizce düşmez: eksik bir yedeği tam sanmak, kaybın
    /// en sinsi biçimi (Android'deki denetim bulgusu).
    public func exportable() throws -> (entries: [DecryptedEntry], undecryptable: Int) {
        let satirlar = try store.all()
        let cozulen = satirlar.compactMap(cozulmus)
        return (cozulen, satirlar.count - cozulen.count)
    }

    /// Etiketler, adları çözülmüş ve ada göre sıralı. Açılamayan etiket düşer.
    public func tags() throws -> [Tag] {
        try store.allTags()
            .compactMap { t in KayitBicimi.etiketAdi(t, key: key).map { Tag(id: t.id, name: $0, color: t.color, icon: t.icon) } }
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
        // Sürüm 1 satırı düzenlenince sürüm 2'ye geçer (uid verilir).
        let eskiUid = e.id == 0 ? "" : ((try? store.get(id: e.id))?.uid ?? "")
        let uid = eskiUid.isEmpty ? KayitBicimi.yeniUid() : eskiUid
        let blob = try muhurle(title: e.title, data: veri, uid: uid, type: e.type.rawValue, anchor: e.anchor)
        var satir = EntryStore.Row(id: e.id, type: e.type.rawValue, title: "", blob: blob,
                                   createdAt: e.id == 0 ? now : e.createdAt, updatedAt: now,
                                   quick: e.quick, tags: TagIds.serialize(e.tagIds),
                                   noteKind: e.noteKind.rawValue, sortIndex: e.sortIndex, anchor: e.anchor, uid: uid)
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
        if let mevcut = try tags().first(where: { $0.name.lowercased() == ad.lowercased() }) { return mevcut.id }
        let yeni = EntryStore.TagRow(id: 0, name: "", color: color, icon: TagIcons.normalize(icon))
        return try store.insertTag(try KayitBicimi.etiketiMuhurle(yeni, ad: ad, key: key))
    }

    public func updateTag(_ t: Tag) throws {
        let ad = t.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ad.isEmpty, let mevcut = try store.allTags().first(where: { $0.id == t.id }) else { return }
        var satir = mevcut
        satir.color = t.color
        satir.icon = TagIcons.normalize(t.icon)
        try store.updateTag(try KayitBicimi.etiketiMuhurle(satir, ad: ad, key: key))
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
    /// - Dönen sayı kullanıcının listede göreceği kayıtlardır: çıpa sayılmaz
    ///   (eskiden "6 kayıt eklendi" deyip listeye 5 kayıt koyuyordu).
    @discardableResult
    public func apply(_ sonuc: Backup.ImportResult, replace: Bool) throws -> Int {
        guard !sonuc.entries.isEmpty else { return 0 }

        // Çıpalar: gelenlerden yalnız en yenisi kalır.
        var gelen = sonuc.entries.filter { !$0.anchor }
        let yeniCapa = sonuc.entries.filter(\.anchor).max { $0.updatedAt < $1.updatedAt }
        // Yalnız çıpa taşıyan zarf boştur ve hiçbir şey yapmaz: "tümünü
        // değiştir" ile kasayı boşaltamaz, "ekle" ile de yalnız çıpayı
        // değiştirmez (Android ile aynı kural).
        if gelen.isEmpty { return 0 }

        // 1. Kasaya dokunmadan önce: boyut denetimi ve şifreleme.
        var hazir: [(DecryptedEntry, String, Data)] = []
        var yazilan = 0
        if let yeniCapa { gelen.append(yeniCapa) }
        for e in gelen {
            let uid = KayitBicimi.yeniUid()
            hazir.append((e, uid, try muhurle(title: e.title, data: e.data, uid: uid, type: e.type.rawValue, anchor: e.anchor)))
        }

        // 2. Tek işlem: etiketler, (gerekirse) silme, yazma.
        try store.transaction {
            var adla = Dictionary(try tags().map { ($0.name.lowercased(), $0.id) }, uniquingKeysWith: { a, _ in a })
            var sayi = adla.count
            for ad in Self.sirali(hazir.flatMap { $0.0.tagNames }) where adla[ad.lowercased()] == nil {
                let tanim = sonuc.tagDefs[ad]
                let yeni = EntryStore.TagRow(id: 0, name: "", color: tanim?.color ?? TagPalette.color(for: sayi),
                                             icon: TagIcons.normalize(tanim?.icon))
                let id = try store.insertTag(try KayitBicimi.etiketiMuhurle(yeni, ad: ad, key: key))
                adla[ad.lowercased()] = id
                sayi += 1
            }
            if replace { try store.deleteAllEntries() }
            var mevcutCapa: EntryStore.Row?
            if !replace { mevcutCapa = try store.all().first(where: \.anchor) }
            yazilan = 0
            for (e, uid, blob) in hazir {
                var satir = EntryStore.Row(type: e.type.rawValue, title: "", blob: blob,
                                           createdAt: e.createdAt, updatedAt: e.updatedAt, quick: e.quick,
                                           tags: TagIds.serialize(e.tagNames.compactMap { adla[$0.lowercased()] }),
                                           noteKind: e.noteKind.rawValue, sortIndex: e.sortIndex, anchor: e.anchor, uid: uid)
                if e.anchor, let mevcutCapa {
                    guard e.updatedAt >= mevcutCapa.updatedAt else { continue }
                    satir.id = mevcutCapa.id
                    try store.update(satir)
                } else {
                    try store.insert(satir)
                }
                if !e.anchor { yazilan += 1 }
            }
        }
        // Kasaya yazılan kayıt sayısı; çıpa sayılmaz.
        return yazilan
    }

    // MARK: Biçim geçişi

    /// Biçim sürüm 1 → 2 (1.3). Eski satırlar (uid boş) tek işlemde taşınır:
    /// başlık ve etiket adı şifreli veriye girer, gövdeler satırlarına bağlanır.
    /// Açılamayan satır olduğu gibi kalır. Bir şey taşındıysa dosya baştan
    /// yazılır (VACUUM): yoksa eski başlıklar boş sayfalarda okunabilir kalırdı.
    /// - Returns: taşınan satır sayısı (kayıt + etiket)
    @discardableResult
    public func upgradeStorage() throws -> Int {
        var tasinan = 0
        try store.transaction {
            for r in try store.all() {
                if let yeni = try KayitBicimi.tasi(r, key: key) { try store.update(yeni); tasinan += 1 }
            }
            for t in try store.allTags() {
                if let yeni = try KayitBicimi.etiketiTasi(t, key: key) { try store.updateTag(yeni); tasinan += 1 }
            }
        }
        if tasinan > 0 { try? store.vacuum() }
        return tasinan
    }

    // MARK: Yardımcılar

    /// Sürüm 2 gövdesi: başlık ve veri, satırına bağlı.
    private func muhurle(title: String, data: EntryData, uid: String, type: String, anchor: Bool) throws -> Data {
        let acik = try KayitBicimi.acikMetin(title: title, data: data)
        guard acik.count <= Self.maxEntryBytes else { throw Failure.entryTooLarge(title: title, bytes: acik.count) }
        return try Crypto.encrypt(acik, key: key, aad: KayitBicimi.govdeAad(uid: uid, type: type, anchor: anchor))
    }

    private func cozulmus(_ r: EntryStore.Row) -> DecryptedEntry? {
        guard let c = KayitBicimi.govdeyiAc(r, key: key) else { return nil }
        return DecryptedEntry(id: r.id, type: .of(r.type), title: c.title, data: c.data,
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

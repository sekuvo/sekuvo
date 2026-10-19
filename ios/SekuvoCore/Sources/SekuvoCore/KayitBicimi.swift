import CryptoKit
import Foundation

/// Cihazdaki kayıt biçimi, sürüm 2 (1.3) — Android'deki `data/KayitBicimi.kt`
/// ile aynı kurallar ve aynı AAD metinleri (`bicim/README.md` §6).
///
/// - Kaydın başlığı şifreli gövdeye girer (verinin JSON'unda `title`);
///   veritabanındaki `title` sütunu boş kalır.
/// - Etiket adı `nameBlob`'da şifreli durur, `name` sütunu boş kalır.
/// - Her mühür yerine ilişkili veriyle (AAD) bağlanır: başka satıra ya da
///   başka sütuna taşınan mühür açılmaz.
///
/// Satırın sürümü `uid` sütunundan okunur: boşsa sürüm 1 (başlık açık, AAD
/// yok), doluysa sürüm 2. Kasa açılırken `VaultStore` eski satırları tek
/// işlemde taşır. Yedek zarfı bu biçimden bağımsızdır ve değişmedi.
enum KayitBicimi {

    private static let onek = "sekuvo/v2"

    /// Satırın kalıcı, rastgele kimliği: 16 bayt, 32 onaltılı karakter.
    static func yeniUid() -> String {
        Crypto.randomBytes(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Kaydın gövdesi: satır kimliği, tür ve çıpa bayrağı.
    static func govdeAad(uid: String, type: String, anchor: Bool) -> Data {
        Data("\(onek)/entries/\(uid)/blob/\(type)/\(anchor ? 1 : 0)".utf8)
    }

    /// Kaydın hızlı erişim kopyası (Android'de klavye için; iOS yazmıyor).
    static func hizliAad(uid: String, type: String) -> Data {
        Data("\(onek)/entries/\(uid)/quick/\(type)".utf8)
    }

    /// Etiketin adı.
    static func etiketAad(uid: String) -> Data {
        Data("\(onek)/tags/\(uid)/name".utf8)
    }

    /// Sürüm 2 gövdesinin düz metni: verinin JSON'u ve `title`.
    static func acikMetin(title: String, data: EntryData) throws -> Data {
        var json = data.json()
        json["title"] = title
        return try JSONSerialization.data(withJSONObject: json)
    }

    /// Gövde düz metni → (başlık, veri). Sürüm 1 gövdesinde başlık yoktur: "".
    static func coz(_ acik: Data) -> (title: String, data: EntryData)? {
        guard let json = (try? JSONSerialization.jsonObject(with: acik)) as? [String: Any] else { return nil }
        return (json["title"] as? String ?? "", EntryData(json: json))
    }

    /// Satırın gövdesi → (başlık, veri), sürümüne göre; açılmazsa `nil`.
    static func govdeyiAc(_ r: EntryStore.Row, key: SymmetricKey) -> (title: String, data: EntryData)? {
        if r.uid.isEmpty {
            guard let acik = Crypto.decrypt(r.blob, key: key), let c = coz(acik) else { return nil }
            return (r.title, c.data)
        }
        guard let acik = Crypto.decrypt(r.blob, key: key, aad: govdeAad(uid: r.uid, type: r.type, anchor: r.anchor))
        else { return nil }
        return coz(acik)
    }

    /// Etiketin adı; sürüm 1'de açık sütundur. Açılmazsa `nil`.
    static func etiketAdi(_ t: EntryStore.TagRow, key: SymmetricKey) -> String? {
        if t.uid.isEmpty { return t.name }
        guard let blob = t.nameBlob, let acik = Crypto.decrypt(blob, key: key, aad: etiketAad(uid: t.uid)) else { return nil }
        return String(data: acik, encoding: .utf8)
    }

    /// Etiketi verilen adla mühürler; uid yoksa verilir.
    static func etiketiMuhurle(_ t: EntryStore.TagRow, ad: String, key: SymmetricKey) throws -> EntryStore.TagRow {
        var yeni = t
        if yeni.uid.isEmpty { yeni.uid = yeniUid() }
        yeni.name = ""
        yeni.nameBlob = try Crypto.encrypt(Data(ad.utf8), key: key, aad: etiketAad(uid: yeni.uid))
        return yeni
    }

    /// Sürüm 1 satırını sürüm 2'ye taşır: yeni uid, başlık gövdeye, gövde
    /// AAD'li, açık başlık silinir. Sürüm 2 ya da açılamayan satır `nil`.
    static func tasi(_ r: EntryStore.Row, key: SymmetricKey) throws -> EntryStore.Row? {
        guard r.uid.isEmpty, let acik = Crypto.decrypt(r.blob, key: key), let c = coz(acik) else { return nil }
        var yeni = r
        yeni.uid = yeniUid()
        yeni.blob = try Crypto.encrypt(try acikMetin(title: r.title, data: c.data), key: key,
                                       aad: govdeAad(uid: yeni.uid, type: r.type, anchor: r.anchor))
        yeni.title = ""
        return yeni
    }

    /// Sürüm 1 etiketini mühürler; sürüm 2 ise `nil`.
    static func etiketiTasi(_ t: EntryStore.TagRow, key: SymmetricKey) throws -> EntryStore.TagRow? {
        guard t.uid.isEmpty else { return nil }
        return try etiketiMuhurle(t, ad: t.name, key: key)
    }
}

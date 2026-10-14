import Foundation

// Kayıt modeli — Android'deki `data/EntryEntity.kt` ve `data/EntryData.kt`'nin
// karşılığı. Adlar bilerek aynı: iki platformun kodu yan yana okunabilsin.
// JSON biçimi `bicim/README.md` §4'te; ikisi de `bicim/vektorler/zarf.json`
// ile sınanır.

/// Kayıt türü. Bilinmeyen tür (daha yeni bir sürümün yazdığı) Güvenli Not
/// okunur — Android'deki `runCatching { valueOf } .getOrDefault(NOTE)`.
public enum EntryType: String, CaseIterable, Sendable {
    case login = "LOGIN", everyday = "EVERYDAY", card = "CARD", note = "NOTE"

    public static func of(_ ad: String?) -> EntryType { ad.flatMap(EntryType.init(rawValue:)) ?? .note }
}

/// Güvenli notun ne taşıdığı; ikon ve düzenleme ipucu bundan türer.
/// Bilinmeyen ya da eksik değer `GENEL` okunur.
public enum NoteKind: String, CaseIterable, Sendable {
    case genel = "GENEL", betik = "BETIK", anahtar = "ANAHTAR"
    case parmakIzi = "PARMAK_IZI", kurtarma = "KURTARMA", yapilandirma = "YAPILANDIRMA"

    public static func of(_ ad: String?) -> NoteKind { ad.flatMap(NoteKind.init(rawValue:)) ?? .genel }
}

/// Kullanıcının eklediği alanın türü. `rawValue` JSON'a yazılan, dile bağlı
/// olmayan addır. Bilinmeyen ya da eksik tür metin okunur; metin JSON'a
/// yazılmaz (türsüz eski biçimle aynı kalır).
public enum CustomFieldType: String, CaseIterable, Sendable {
    case text, password, email, phone, url, number, multiline

    public static func of(_ ad: String?) -> CustomFieldType { ad.flatMap(CustomFieldType.init(rawValue:)) ?? .text }
}

public struct CustomField: Equatable, Sendable {
    public var label: String
    public var value: String
    public var type: CustomFieldType

    public init(label: String, value: String, type: CustomFieldType = .text) {
        self.label = label; self.value = value; self.type = type
    }
}

/// Geride bırakılmış bir parola ve ne zaman bırakıldığı (epoch ms).
public struct OldPassword: Equatable, Sendable {
    public var value: String
    public var changedAt: Int64

    public init(value: String, changedAt: Int64) { self.value = value; self.changedAt = changedAt }
}

/// Bir kaydın şifrelenen alanları. Boş alanlar JSON'a yazılmaz; eksik alan
/// boş okunur — biçim iki yönde de geriye dönük uyumludur.
public struct EntryData: Equatable, Sendable {
    public var username = ""
    public var password = ""
    public var url = ""
    public var cardholder = ""
    public var cardNumber = ""
    public var expiry = ""
    public var cvv = ""
    public var iban = ""
    public var notes = ""
    public var fullName = ""
    public var phone = ""
    public var email = ""
    public var address = ""
    public var custom: [CustomField] = []
    /// Şifrenin son değiştiği an (epoch ms); 0 = bilinmiyor.
    public var passwordChangedAt: Int64 = 0
    /// Eski parolalar, en yenisi başta.
    public var passwordHistory: [OldPassword] = []

    public init() {}

    /// Metin alanları ve JSON adları, Android'deki `toJson` sırasıyla.
    /// (Hesaplanan özellik: anahtar yolları Sendable değil, küresel durum tutulmaz.)
    static var metinAlanlari: [(WritableKeyPath<EntryData, String>, String)] {
        [(\.username, "username"), (\.password, "password"), (\.url, "url"),
         (\.cardholder, "cardholder"), (\.cardNumber, "cardNumber"), (\.expiry, "expiry"),
         (\.cvv, "cvv"), (\.iban, "iban"), (\.notes, "notes"), (\.fullName, "fullName"),
         (\.phone, "phone"), (\.email, "email"), (\.address, "address")]
    }

    public init(json o: [String: Any]) {
        for (yol, ad) in Self.metinAlanlari { self[keyPath: yol] = JSON.string(o, ad) }
        custom = JSON.objects(o["custom"]).compactMap { c in
            let label = JSON.string(c, "label")
            guard !label.isEmpty else { return nil }
            return CustomField(label: label, value: JSON.string(c, "value"),
                               type: .of(c["type"] as? String))
        }
        passwordChangedAt = JSON.long(o, "passwordChangedAt")
        passwordHistory = JSON.objects(o["passwordHistory"]).compactMap { p in
            let v = JSON.string(p, "value")
            return v.isEmpty ? nil : OldPassword(value: v, changedAt: JSON.long(p, "changedAt"))
        }
    }

    public func json() -> [String: Any] {
        var o: [String: Any] = [:]
        for (yol, ad) in Self.metinAlanlari where !self[keyPath: yol].isEmpty { o[ad] = self[keyPath: yol] }
        if !custom.isEmpty {
            o["custom"] = custom.map { c -> [String: Any] in
                var j: [String: Any] = ["label": c.label, "value": c.value]
                if c.type != .text { j["type"] = c.type.rawValue }
                return j
            }
        }
        if passwordChangedAt > 0 { o["passwordChangedAt"] = passwordChangedAt }
        if !passwordHistory.isEmpty {
            o["passwordHistory"] = passwordHistory.map { ["value": $0.value, "changedAt": $0.changedAt] as [String: Any] }
        }
        return o
    }
}

/// Bellekte çözülmüş kayıt.
public struct DecryptedEntry: Equatable, Sendable {
    public var id: Int64
    public var type: EntryType
    public var title: String
    public var data: EntryData
    public var createdAt: Int64
    public var updatedAt: Int64
    /// Klavyede kasa kilitliyken de kullanılabilir mi?
    public var quick: Bool
    public var tagIds: [Int64]
    /// Yalnız yedekten içe aktarmada dolu: id'ler cihaza özgüdür, yedek ad taşır.
    public var tagNames: [String]
    public var noteKind: NoteKind
    public var sortIndex: Int
    /// Araç çıpası mı (aktar.html'in SHA-256'sı; listede görünmez).
    public var anchor: Bool

    public init(id: Int64 = 0, type: EntryType, title: String, data: EntryData = EntryData(),
                createdAt: Int64, updatedAt: Int64, quick: Bool = false, tagIds: [Int64] = [],
                tagNames: [String] = [], noteKind: NoteKind = .genel, sortIndex: Int = 0, anchor: Bool = false) {
        self.id = id; self.type = type; self.title = title; self.data = data
        self.createdAt = createdAt; self.updatedAt = updatedAt; self.quick = quick
        self.tagIds = tagIds; self.tagNames = tagNames; self.noteKind = noteKind
        self.sortIndex = sortIndex; self.anchor = anchor
    }
}

/// Etiket tanımı (yedekte adla taşınır). Renk Android'deki gibi ARGB ve
/// işaretli 32 bit (`0xFF4FC3F7` → -11549705).
public struct TagDef: Equatable, Sendable {
    public var color: Int32
    public var icon: String

    public init(color: Int32, icon: String = "") { self.color = color; self.icon = icon }
}

/// Etiket ikon anahtarları — Android'deki `TagIcons` ile aynı sıra.
public enum TagIcons {
    public static let keys = [
        "work", "home", "person", "group", "bank", "card", "shopping", "mail",
        "cloud", "server", "code", "key", "shield", "wifi", "game", "media",
        "music", "school", "health", "travel",
    ]

    /// Bilinmeyen anahtar (eski ya da ileri sürüm yedeği) ikonsuza düşer.
    public static func normalize(_ key: String?) -> String {
        guard let key, keys.contains(key) else { return "" }
        return key
    }
}

/// org.json'un `optString/optLong/optBoolean` gevşekliğinin karşılığı.
enum JSON {
    static func string(_ o: [String: Any], _ k: String) -> String {
        switch o[k] {
        case let s as String: return s
        case let n as NSNumber where !isBool(n): return n.stringValue
        default: return ""
        }
    }

    static func long(_ o: [String: Any], _ k: String, _ varsayilan: Int64 = 0) -> Int64 {
        switch o[k] {
        case let n as NSNumber where !isBool(n): return n.int64Value
        case let s as String: return Int64(s) ?? varsayilan
        default: return varsayilan
        }
    }

    static func int(_ o: [String: Any], _ k: String, _ varsayilan: Int = 0) -> Int {
        Int(clamping: long(o, k, Int64(varsayilan)))
    }

    static func bool(_ o: [String: Any], _ k: String, _ varsayilan: Bool = false) -> Bool {
        switch o[k] {
        case let n as NSNumber where isBool(n): return n.boolValue
        case let s as String where s.lowercased() == "true": return true
        case let s as String where s.lowercased() == "false": return false
        default: return varsayilan
        }
    }

    static func objects(_ v: Any?) -> [[String: Any]] {
        (v as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }

    static func isBool(_ n: NSNumber) -> Bool { CFGetTypeID(n) == CFBooleanGetTypeID() }
}

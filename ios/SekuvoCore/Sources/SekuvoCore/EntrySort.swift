import Foundation

/// Kayıt sıralaması — Android'deki `data/EntrySort.kt` ile aynı altı seçenek.
/// `rawValue` Android'deki enum adıdır. Tercih sır değil; uygulama onu
/// UserDefaults'ta tutar.
///
/// Bilinçli fark: başlıklar Android'deki gibi küçük harfe çevrilip kod
/// noktasına göre değil, `localizedStandardCompare` ile karşılaştırılır.
/// Türkçede "Ç" "C"den sonra, "Z"den önce gelir ve "Kayıt 2" "Kayıt 10"dan
/// önce durur (Dosyalar uygulamasıyla aynı).
public enum EntrySort: String, CaseIterable, Sendable {
    case titleAsc = "TITLE_ASC"
    case titleDesc = "TITLE_DESC"
    case updatedDesc = "UPDATED_DESC"
    case createdDesc = "CREATED_DESC"
    case type = "TYPE"
    case manual = "MANUAL"

    public static func of(_ ad: String?) -> EntrySort { ad.flatMap(EntrySort.init(rawValue:)) ?? .titleAsc }

    /// Listeyi bu sıraya dizer. Eşitlikte başlık, sonra id karar verir: aynı
    /// liste her açılışta aynı sırada görünür.
    public func sorted(_ items: [VaultStore.Item]) -> [VaultStore.Item] {
        items.sorted { a, b in
            switch self {
            case .titleAsc: break
            case .titleDesc:
                let c = a.title.localizedStandardCompare(b.title)
                if c != .orderedSame { return c == .orderedDescending }
            case .updatedDesc where a.updatedAt != b.updatedAt: return a.updatedAt > b.updatedAt
            case .createdDesc where a.createdAt != b.createdAt: return a.createdAt > b.createdAt
            case .type where a.type != b.type: return a.type.order < b.type.order
            case .manual where priorityKey(a.sortIndex) != priorityKey(b.sortIndex):
                return priorityKey(a.sortIndex) < priorityKey(b.sortIndex)
            default: break
            }
            let c = a.title.localizedStandardCompare(b.title)
            return c == .orderedSame ? a.id < b.id : c == .orderedAscending
        }
    }
}

/// Öncelik sırasının tek kuralı (Android: `priorityKey`): numara verilmiş
/// kayıtlar 1, 2, 3… diye önce, numarasızlar (0) en sonda. Sıfırı olduğu gibi
/// sıralamak numarasız her kaydı listenin başına toplardı.
public func priorityKey(_ sortIndex: Int) -> Int { sortIndex <= 0 ? Int.max : sortIndex }

extension EntryType {
    /// Android'deki `ordinal`: Hesap, Gündelik, Kart, Not.
    var order: Int { EntryType.allCases.firstIndex(of: self) ?? 0 }
}

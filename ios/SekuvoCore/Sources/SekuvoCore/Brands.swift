import Foundation

/// Kurum logosu: katalogdaki bir marka. Logolu markanın çizimi uygulamanın
/// varlık kataloğunda (`Markalar/marka-<id>`); logosuzlar `badge` metniyle,
/// marka renginde rozet olarak çizilir. Katalog `markalar/katalog.json`'dan üretilir.
public struct Brand: Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    /// 0xRRGGBB.
    public let color: UInt32
    /// Logosuz markada rozet metni (1–3 karakter); logolu markada boş.
    public let badge: String
    public let hasLogo: Bool
    public let domains: [String]
    public let keywords: [String]
}

/// Bir kaydın hangi markaya ait olduğu — Android'deki `Brands` ile aynı kural;
/// ikisi de `bicim/vektorler/marka-eslesme.json` ile sınanır. Sıra: kullanıcı
/// seçimi (`none` → yok), site adresinin alan adı (en uzun eşleşen), başlıkta
/// tam sözcük olarak geçen anahtar (en uzun), kart kaydında kart ağı.
public enum Brands {

    public static let none = "none"

    private static let byIdMap: [String: Brand] = Dictionary(uniqueKeysWithValues: catalog.map { ($0.id, $0) })
    private static let anahtarlar: [(String, Brand)] = catalog.flatMap { b in
        b.keywords.map { normalize($0) }.filter { !$0.isEmpty }.map { ($0, b) }
    }

    public static func byId(_ id: String) -> Brand? { byIdMap[id] }

    public static func match(title: String, url: String, choice: String = "", isCard: Bool = false, cardNumber: String = "") -> Brand? {
        if choice == none { return nil }
        if let b = byIdMap[choice] { return b }
        if let h = host(url) {
            var enIyi: (String, Brand)?
            for b in catalog {
                for d in b.domains where h == d || h.hasSuffix("." + d) {
                    if d.count > (enIyi?.0.count ?? -1) { enIyi = (d, b) }
                }
            }
            if let enIyi { return enIyi.1 }
        }
        let t = " " + normalize(title) + " "
        var enIyi: (String, Brand)?
        for (k, b) in anahtarlar where t.contains(" " + k + " ") {
            if k.count > (enIyi?.0.count ?? -1) { enIyi = (k, b) }
        }
        if let enIyi { return enIyi.1 }
        if isCard, let ag = cardNetwork(cardNumber) { return byIdMap[ag] }
        return nil
    }

    /// Küçük harf, aksan ve Türkçe harf katlanır; harf/rakam dışı boşluk olur.
    public static func normalize(_ s: String) -> String {
        let acik = String(String.UnicodeScalarView(
            s.lowercased().decomposedStringWithCompatibilityMapping.unicodeScalars
                .filter { $0.properties.generalCategory != .nonspacingMark }))
            .replacingOccurrences(of: "ı", with: "i")
        var cikti = "", bosluk = false
        for ch in acik.unicodeScalars {
            if ("a"..."z").contains(ch) || ("0"..."9").contains(ch) {
                if bosluk && !cikti.isEmpty { cikti += " " }
                cikti.unicodeScalars.append(ch); bosluk = false
            } else { bosluk = true }
        }
        return cikti
    }

    /// Adresin alan adı: şema, kullanıcı, kapı, yol ve "www." atılır; yoksa nil.
    public static func host(_ url: String) -> String? {
        var h = url.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let r = h.range(of: "://") { h = String(h[r.upperBound...]) }
        for ayrac in ["/", "?", "#"] { if let r = h.range(of: ayrac) { h = String(h[..<r.lowerBound]) } }
        if let r = h.range(of: "@", options: .backwards) { h = String(h[r.upperBound...]) }
        if let r = h.range(of: ":") { h = String(h[..<r.lowerBound]) }
        while h.hasSuffix(".") { h.removeLast() }
        if h.hasPrefix("www.") { h.removeFirst(4) }
        guard h.contains("."), !h.contains(where: \.isWhitespace) else { return nil }
        return h
    }

    /// Kart numarasının ağı: 4 → Visa, 34/37 → Amex, 51–55 ve 2221–2720 → Mastercard.
    public static func cardNetwork(_ number: String) -> String? {
        let d = number.filter(\.isASCIIDigitBrand)
        guard d.count >= 4, let iki = Int(d.prefix(2)), let dort = Int(d.prefix(4)) else { return nil }
        if d.first == "4" { return "visa" }
        if iki == 34 || iki == 37 { return "amex" }
        if (51...55).contains(iki) || (2221...2720).contains(dort) { return "mastercard" }
        return nil
    }
}

private extension Character {
    var isASCIIDigitBrand: Bool { ("0"..."9").contains(self) }
}

import Foundation

/// Kart ve IBAN alanlarının giriş kuralları ve görüntü biçimi — Android'deki
/// `core/FieldFormats.kt` ile aynı.
///
/// Saklanan değer ham ve boşluksuzdur; boşluklar yalnız ekranda eklenir.
/// Böylece kopyalanan ve yedeğe giren değer temiz kalır.
public enum FieldFormats {

    /// Kart ağı, numaranın ilk hanelerinden (IIN) yerel olarak türetilir.
    public enum CardNetwork: CaseIterable, Sendable {
        case visa, mastercard, amex, troy, discover, diners, jcb, unknown

        public var displayName: String {
            switch self {
            case .visa: "Visa"
            case .mastercard: "Mastercard"
            case .amex: "American Express"
            case .troy: "Troy"
            case .discover: "Discover"
            case .diners: "Diners Club"
            case .jcb: "JCB"
            case .unknown: ""
            }
        }

        /// Amex'in güvenlik kodu 4 hane, diğerleri 3.
        public var cvvLength: Int { self == .amex ? 4 : 3 }

        var groups: [Int] {
            switch self {
            case .amex: [4, 6, 5]
            case .diners: [4, 6, 4]
            default: [4, 4, 4, 4]
            }
        }
    }

    /// Kısmi girişte de çalışır: kullanıcı yazarken ilk haneden daralır.
    public static func cardNetwork(_ number: String) -> CardNetwork {
        let n = Array(number.filter(\.isASCIIDigit))
        guard let ilkHane = n.first else { return .unknown }
        func ilk(_ k: Int) -> Int { Int(String(n.prefix(k)).padding(toLength: k, withPad: "0", startingAt: 0)) ?? 0 }
        if ilkHane == "4" { return .visa }
        if n.count >= 2, (51...55).contains(ilk(2)) { return .mastercard }
        if n.count >= 4, (2221...2720).contains(ilk(4)) { return .mastercard }
        if n.count >= 2, [34, 37].contains(ilk(2)) { return .amex }
        if n.count >= 4, ilk(4) == 9792 { return .troy }
        if n.count >= 4, ilk(4) == 6011 { return .discover }
        if n.count >= 2, ilk(2) == 65 { return .discover }
        if n.count >= 3, (644...649).contains(ilk(3)) { return .discover }
        if n.count >= 2, [36, 38].contains(ilk(2)) { return .diners }
        if n.count >= 4, (3528...3589).contains(ilk(4)) { return .jcb }
        return .unknown
    }

    /// Yalnız rakam, en fazla 19 (en uzun PAN).
    public static func cardNumberInput(_ raw: String) -> String { String(raw.filter(\.isASCIIDigit).prefix(19)) }

    /// Yalnız rakam, AAYY.
    public static func expiryInput(_ raw: String) -> String { String(raw.filter(\.isASCIIDigit).prefix(4)) }

    public static func cvvInput(_ raw: String, network: CardNetwork = .unknown) -> String {
        String(raw.filter(\.isASCIIDigit).prefix(network.cvvLength))
    }

    /// ISO 13616: 1-2 ülke kodu (harf), 3-4 kontrol hanesi (rakam), 5+ BBAN
    /// (harf ya da rakam), en çok 34. Uygunsuz tuş kesmez, yok sayılır.
    public static func ibanInput(_ raw: String) -> String {
        var sonuc = ""
        for c in raw.uppercased() {
            if sonuc.count >= 34 { break }
            let uygun: Bool
            switch sonuc.count {
            case 0, 1: uygun = c.isASCIILetter
            case 2, 3: uygun = c.isASCIIDigit
            default: uygun = c.isASCIILetter || c.isASCIIDigit
            }
            if uygun { sonuc.append(c) }
        }
        return sonuc
    }

    /// MOD-97-10 kontrol hanesi. Boş ve yarım giriş (15'ten kısa) geçerli sayılır.
    public static func ibanIsValid(_ deger: String) -> Bool {
        let v = Array(deger.filter { $0.isASCIILetter || $0.isASCIIDigit }.uppercased())
        if v.count < 15 { return true }
        if v.count > 34 { return false }
        guard v.prefix(2).allSatisfy(\.isASCIILetter), v.dropFirst(2).prefix(2).allSatisfy(\.isASCIIDigit) else { return false }
        var kalan = 0
        for c in v.dropFirst(4) + v.prefix(4) {
            let basamak = c.isASCIIDigit ? Int(String(c))! : Int(c.asciiValue! - Character("A").asciiValue!) + 10
            kalan = basamak > 9 ? (kalan * 100 + basamak) % 97 : (kalan * 10 + basamak) % 97
        }
        return kalan == 1
    }

    /// "1234567890" → "1234 5678 90".
    public static func group(_ ham: String, size: Int = 4) -> String {
        stride(from: 0, to: ham.count, by: size).map { i in
            let a = ham.index(ham.startIndex, offsetBy: i)
            let b = ham.index(a, offsetBy: size, limitedBy: ham.endIndex) ?? ham.endIndex
            return String(ham[a..<b])
        }.joined(separator: " ")
    }

    /// Ağın kendi düzeni: Amex 4-6-5, Diners 4-6-4, diğerleri dörderli.
    public static func groupCard(_ ham: String, network: CardNetwork? = nil) -> String {
        let ag = network ?? cardNetwork(ham)
        var parcalar: [String] = []
        var i = ham.startIndex
        for uzunluk in ag.groups where i < ham.endIndex {
            let j = ham.index(i, offsetBy: uzunluk, limitedBy: ham.endIndex) ?? ham.endIndex
            parcalar.append(String(ham[i..<j]))
            i = j
        }
        if i < ham.endIndex { parcalar.append(group(String(ham[i...]))) }
        return parcalar.joined(separator: " ")
    }

    /// AAYY → "AA/YY"; yarım girişte bozulmaz.
    public static func expiryDisplay(_ ham: String) -> String {
        ham.count <= 2 ? ham : "\(ham.prefix(2))/\(ham.dropFirst(2))"
    }

    /// Boş kabul; doluysa dört hane ve ay 01–12. Yıl denetlenmez.
    public static func expiryIsValid(_ ham: String) -> Bool {
        if ham.isEmpty { return true }
        guard ham.count == 4, let ay = Int(ham.prefix(2)) else { return false }
        return (1...12).contains(ay)
    }

    /// Kasıtlı olarak gevşek: "@ ve nokta unuttum" hatasını yakalar.
    public static func emailIsValid(_ deger: String) -> Bool {
        if deger.isEmpty { return true }
        guard let at = deger.firstIndex(of: "@"), at != deger.startIndex, at == deger.lastIndex(of: "@") else { return false }
        let alan = deger[deger.index(after: at)...]
        guard let nokta = alan.lastIndex(of: "."), nokta != alan.startIndex, alan.index(after: nokta) != alan.endIndex else { return false }
        return !deger.contains(where: \.isWhitespace) && alan.distance(from: nokta, to: alan.endIndex) - 1 >= 2
    }
}

extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
    var isASCIILetter: Bool { isASCII && isLetter }
}

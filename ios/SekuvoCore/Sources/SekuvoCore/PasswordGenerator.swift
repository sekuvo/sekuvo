import Foundation
import Security

/// Kriptografik rastgelelikle şifre üretir — Android'deki
/// `core/PasswordGenerator.kt` ile aynı kurallar: seçilen her sınıftan en az
/// bir karakter, karıştırılabilen karakterler istenirse elenir, sonuç
/// Fisher–Yates ile karıştırılır.
public enum PasswordGenerator {

    static let lower = "abcdefghijklmnopqrstuvwxyz"
    static let upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    static let digits = "0123456789"
    static let symbols = "!@#$%^&*()-_=+[]{};:,.?/"
    /// Karıştırılması kolay karakterler: l, I, 1, O, 0, o.
    static let ambiguous = Set("lI1O0o")

    public struct Options: Equatable, Sendable {
        public var length = 20
        public var lower = true
        public var upper = true
        public var digits = true
        public var symbols = true
        public var avoidAmbiguous = true

        public init() {}
    }

    static func pools(_ o: Options) -> [[Character]] {
        var havuzlar: [String] = []
        if o.lower { havuzlar.append(lower) }
        if o.upper { havuzlar.append(upper) }
        if o.digits { havuzlar.append(digits) }
        if o.symbols { havuzlar.append(symbols) }
        let sonuc = havuzlar.map { Array($0).filter { !o.avoidAmbiguous || !ambiguous.contains($0) } }
        // Hiç sınıf seçilmediyse küçük harfe düş.
        return sonuc.isEmpty ? [Array(lower)] : sonuc
    }

    public static func generate(_ o: Options = Options()) -> String {
        let havuzlar = pools(o)
        let hepsi = havuzlar.flatMap { $0 }
        let uzunluk = min(max(o.length, 4), 128)
        var karakterler = [Character](repeating: "a", count: uzunluk)
        // Önce her sınıftan bir karakter garanti et.
        for (i, havuz) in havuzlar.enumerated() where i < uzunluk {
            karakterler[i] = havuz[rastgele(havuz.count)]
        }
        for i in min(havuzlar.count, uzunluk)..<uzunluk {
            karakterler[i] = hepsi[rastgele(hepsi.count)]
        }
        // Fisher–Yates: garanti karakterler başta kalmasın.
        for i in stride(from: uzunluk - 1, to: 0, by: -1) {
            karakterler.swapAt(i, rastgele(i + 1))
        }
        return String(karakterler)
    }

    /// Yaklaşık entropi (bit) — güç göstergesi için.
    public static func entropyBits(_ o: Options) -> Int {
        let boyut = pools(o).reduce(0) { $0 + $1.count }
        return Int(Double(o.length) * log2(Double(boyut)))
    }

    /// [0, ust) aralığında, modülo yanlılığı olmadan (reddetme örneklemesi).
    static func rastgele(_ ust: Int) -> Int {
        precondition(ust > 0)
        let sinir = UInt32.max - UInt32.max % UInt32(ust)
        while true {
            var x: UInt32 = 0
            let durum = withUnsafeMutableBytes(of: &x) { SecRandomCopyBytes(kSecRandomDefault, 4, $0.baseAddress!) }
            precondition(durum == errSecSuccess)
            if x < sinir { return Int(x % UInt32(ust)) }
        }
    }
}

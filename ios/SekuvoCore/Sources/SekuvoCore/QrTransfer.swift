import Compression
import Foundation

/// QR ile doğrudan aktarımın kare biçimi — Android'deki `core/QrTransfer.kt`,
/// `tools/aktar.html` ve `tools/vault-clip.py` ile aynı:
///
/// ```
/// VLT1|i/n|F|veri
///   i,n : 1'den başlayan kare sırası ve toplam
///   F   : Z = ham deflate + base64 · P = base64
///   veri: kodlanmış yükün bu kareye düşen parçası
/// ```
///
/// Zarf ekrandan kameraya ağ olmadan geçer. Büyük zarflar parçalanır, kareler
/// döngüyle oynar, okuyucu sırasız toplar. Buradaki denetimler gizlilik için
/// değil (zarf zaten AES-GCM'li), **sessizce yanlış veri üretmemek** için.
public enum QrTransfer {

    public static let prefix = "VLT1"
    /// Kare başına base64 uzunluğu — telefon kamerasının rahat okuduğu yoğunluk.
    public static let chunk = 1200
    /// Düşman bir kare `n`'i şişirip okuyucuyu dev bir liste kurmaya zorlamasın.
    public static let maxFrames = 512
    /// Deflate çıktısı tavanı — zip bombasına karşı.
    static let maxInflated = 4 * 1024 * 1024

    public struct Frame: Equatable, Hashable, Sendable {
        public let index: Int
        public let total: Int
        public let flag: Character
        public let data: String
    }

    /// Bir QR metnini kareye çevirir; bizim biçimimiz değilse `nil`.
    public static func parse(_ text: String) -> Frame? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(prefix + "|") else { return nil }
        let parcalar = t.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
        guard parcalar.count == 4 else { return nil }
        let sira = parcalar[1].split(separator: "/", omittingEmptySubsequences: false)
        guard sira.count == 2, let i = Int(sira[0]), let n = Int(sira[1]),
              i >= 1, n >= 1, i <= n, n <= maxFrames,
              let bayrak = parcalar[2].first, bayrak == "Z" || bayrak == "P"
        else { return nil }
        return Frame(index: i, total: n, flag: bayrak, data: String(parcalar[3]))
    }

    /// Toplanan karelerden zarf metnini kurar. Küme eksikse ya da kareler
    /// çelişiyorsa `nil` — yarım veri asla çözülmeye gönderilmez.
    public static func assemble(_ frames: [Frame]) -> String? {
        guard let ilk = frames.first else { return nil }
        guard frames.allSatisfy({ $0.total == ilk.total && $0.flag == ilk.flag }) else { return nil }
        var sirayla: [Int: Frame] = [:]
        for f in frames { sirayla[f.index] = f }
        guard sirayla.count == ilk.total else { return nil }
        var kodlu = ""
        for i in 1...ilk.total {
            guard let f = sirayla[i] else { return nil }
            kodlu += f.data
        }
        // Android MIME çözücüsü gibi: alfabe dışı karakterler yok sayılır.
        let temiz = kodlu.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "/") }
        let eksik = (4 - temiz.count % 4) % 4
        guard let baytlar = Data(base64Encoded: temiz + String(repeating: "=", count: eksik)) else { return nil }
        switch ilk.flag {
        case "P": return String(data: baytlar, encoding: .utf8)
        case "Z": return inflate(baytlar).flatMap { String(data: $0, encoding: .utf8) }
        default: return nil
        }
    }

    /// Tarama ekranının "3/4 alındı" göstergesi için eksik kare listesi.
    public static func missing(_ frames: [Frame]) -> [Int] {
        guard let toplam = frames.first?.total else { return [] }
        let var_ = Set(frames.map(\.index))
        return (1...toplam).filter { !var_.contains($0) }
    }

    /// Zarfı kareye böler (aktar.html ve vault-clip ile aynı kural): sıkıştırma
    /// kazandırıyorsa `Z`, yoksa `P`. Telefonun bilgisayara QR göstereceği
    /// gün (Bluetooth klavyesinin iOS karşılığı) ve testler için.
    public static func frames(for envelopeText: String) -> [String] {
        let ham = Data(envelopeText.utf8)
        var bayrak: Character = "P"
        var yuk = ham
        if let sikisik = deflate(ham), sikisik.count < ham.count {
            bayrak = "Z"; yuk = sikisik
        }
        let veri = yuk.base64EncodedString()
        var parcalar: [String] = []
        var i = veri.startIndex
        while i < veri.endIndex {
            let j = veri.index(i, offsetBy: chunk, limitedBy: veri.endIndex) ?? veri.endIndex
            parcalar.append(String(veri[i..<j]))
            i = j
        }
        if parcalar.isEmpty { parcalar = [""] }
        let n = parcalar.count
        return parcalar.enumerated().map { "\(prefix)|\($0.offset + 1)/\(n)|\(bayrak)|\($0.element)" }
    }

    // MARK: Ham deflate (zlib başlığı yok) — Python zlib -15 ve tarayıcı deflate-raw ile aynı

    static func inflate(_ veri: Data) -> Data? {
        akis(veri, islem: COMPRESSION_STREAM_DECODE, tavan: maxInflated)
    }

    static func deflate(_ veri: Data) -> Data? {
        akis(veri, islem: COMPRESSION_STREAM_ENCODE, tavan: Int.max)
    }

    /// Compression çerçevesinin COMPRESSION_ZLIB'i ham DEFLATE'tir (RFC 1951).
    /// Akış parça parça işlenir; çıktı tavanı aşılırsa (zip bombası) `nil`.
    private static func akis(_ veri: Data, islem: compression_stream_operation, tavan: Int) -> Data? {
        let hedef = UnsafeMutablePointer<UInt8>.allocate(capacity: 64 * 1024)
        defer { hedef.deallocate() }
        var akis = compression_stream(dst_ptr: hedef, dst_size: 0, src_ptr: UnsafePointer(hedef), src_size: 0, state: nil)
        guard compression_stream_init(&akis, islem, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(&akis) }
        var cikti = Data()
        let sonuc: Data? = veri.withUnsafeBytes { (kaynak: UnsafeRawBufferPointer) -> Data? in
            guard let taban = kaynak.bindMemory(to: UInt8.self).baseAddress ?? Optional(UnsafePointer(hedef)) else { return nil }
            akis.src_ptr = taban
            akis.src_size = kaynak.count
            while true {
                akis.dst_ptr = hedef
                akis.dst_size = 64 * 1024
                let durum = compression_stream_process(&akis, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let uretilen = 64 * 1024 - akis.dst_size
                if uretilen > 0 { cikti.append(hedef, count: uretilen) }
                if cikti.count > tavan { return nil }
                switch durum {
                case COMPRESSION_STATUS_END: return cikti
                case COMPRESSION_STATUS_OK:
                    // Girdi bitti ve çıktı da üretilmiyorsa akış kesik: sessizce yarım veri dönme.
                    if akis.src_size == 0 && uretilen == 0 { return nil }
                default: return nil
                }
            }
        }
        guard let sonuc, !sonuc.isEmpty || islem == COMPRESSION_STREAM_ENCODE else { return nil }
        return sonuc
    }
}

import Foundation

/// Kameradan gelen QR metinlerini toplar — Android'deki `QrScanScreen`'in
/// kare mantığı, arayüzden ayrı. Kareler sırasız gelir, aynı kare defalarca
/// okunur; küme tamamlanınca zarf kurulur. Tamamlanan küme çelişirse
/// (farklı aktarımlardan karışmış kareler) baştan başlanır.
public struct QrCollector: Sendable {

    public enum Event: Equatable, Sendable {
        /// Bizim biçimimiz değil ya da zaten alınmış kare: ekranda değişiklik yok.
        case ignored
        /// Yeni kare alındı: kaç tanesi geldi, toplam kaç.
        case progress(received: Int, total: Int)
        /// Küme tamam ve zarf kuruldu.
        case complete(envelope: String)
        /// Küme tamamlandı ama kareler birbirini tutmadı; sayaç sıfırlandı.
        case mismatch
    }

    public private(set) var frames: [Int: QrTransfer.Frame] = [:]

    public init() {}

    public var total: Int? { frames.values.first?.total }
    public var missing: [Int] { QrTransfer.missing(Array(frames.values)) }

    public mutating func add(_ text: String) -> Event {
        guard let kare = QrTransfer.parse(text) else { return .ignored }
        // Başka bir aktarımın karesi (toplam ya da bayrak farklı): yeni küme başlar.
        if let ilk = frames.values.first, ilk.total != kare.total || ilk.flag != kare.flag {
            frames = [:]
        }
        guard frames[kare.index] != kare else { return .ignored }
        frames[kare.index] = kare
        guard frames.count == kare.total else { return .progress(received: frames.count, total: kare.total) }
        if let zarf = QrTransfer.assemble(Array(frames.values)) {
            return .complete(envelope: zarf)
        }
        frames = [:]
        return .mismatch
    }

    public mutating func reset() { frames = [:] }
}

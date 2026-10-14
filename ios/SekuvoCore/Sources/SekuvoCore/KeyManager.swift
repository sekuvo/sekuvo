import CryptoKit
import Foundation

/// Anahtar hiyerarşisi — Android'deki `core/KeyManager.kt` ile aynı:
///
///  - `dataKey`: rastgele 256 bit; bütün kayıtları şifreler.
///  - `KEK`: ana paroladan PBKDF2 ile türetilir (310.000 tur); `dataKey`'i
///    AES-GCM ile sarar. GCM doğrulaması sayesinde yanlış parola açılırken
///    belli olur.
///
/// Sargı, uygulamanın kendi kabındaki küçük bir dosyada durur (Android'de
/// SharedPreferences). Keychain'e bilerek konmadı: Keychain kaydı uygulama
/// silinince de kalır, yani yeniden kurulumda verisi olmayan bir kasanın
/// anahtarı ortada kalırdı. Face ID ve PIN katmanı bu sargının
/// üstüne ayrıca gelecek; ana parola her zaman tek başına açar.
public struct KeyManager: Sendable {

    struct Wrapped: Codable {
        var salt: String
        var iterations: Int
        var wrappedKey: String
    }

    public let file: URL

    public init(file: URL) { self.file = file }

    public var isInitialized: Bool { FileManager.default.fileExists(atPath: file.path) }

    /// İlk kurulum: yeni `dataKey` üretir, ana parolayla sarıp saklar.
    /// PBKDF2 ağır olduğu için ana iş parçacığında çağrılmaz.
    public func setup(password: String) throws -> SymmetricKey {
        let dataKey = Crypto.randomKey()
        try store(password: password, payload: dataKey.withUnsafeBytes { Data($0) })
        return dataKey
    }

    /// Ana parola doğruysa `dataKey`.
    public func unlock(password: String) -> SymmetricKey? {
        guard let veri = try? Data(contentsOf: file),
              let w = try? JSONDecoder().decode(Wrapped.self, from: veri),
              let tuz = Data(base64Encoded: w.salt),
              let sargi = Data(base64Encoded: w.wrappedKey)
        else { return nil }
        let kek = Crypto.deriveKey(password: password, salt: tuz, iterations: w.iterations)
        guard let ham = Crypto.decrypt(sargi, key: kek), ham.count == Crypto.keyBytes else { return nil }
        return SymmetricKey(data: ham)
    }

    /// Ana parolayı değiştirir: `dataKey` aynı kalır, yalnız sargı yenilenir.
    public func changePassword(old: String, new: String) throws -> Bool {
        guard let dataKey = unlock(password: old) else { return false }
        try store(password: new, payload: dataKey.withUnsafeBytes { Data($0) })
        return true
    }

    private func store(password: String, payload: Data) throws {
        let tuz = Crypto.randomBytes(16)
        let kek = Crypto.deriveKey(password: password, salt: tuz)
        let sargi = try Crypto.encrypt(payload, key: kek)
        let w = Wrapped(salt: tuz.base64EncodedString(), iterations: Crypto.kdfIterations,
                        wrappedKey: sargi.base64EncodedString())
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Atomik yazım + tam koruma: cihaz kilitliyken dosya okunamaz.
        try JSONEncoder().encode(w).write(to: file, options: [.atomic, .completeFileProtection])
    }
}

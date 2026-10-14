import CommonCrypto
import CryptoKit
import Foundation
import Security

/// Tüm şifreleme burada: AES-256-GCM + PBKDF2-HMAC-SHA256.
///
/// Android'deki `core/Crypto.kt`'nin karşılığı ve onunla **bayt bayt aynı**:
/// - anahtar türetme PBKDF2-HMAC-SHA256, 310.000 tur, 32 bayt; parola UTF-8
///   baytlarıyla girer,
/// - mühür `iv(12) || şifreli metin || etiket(16)`, AAD yok.
///
/// İkisi `bicim/vektorler/` değerleriyle sınanır; biçim tek yerde,
/// `bicim/README.md`'de tarif edilir. Anahtar hiyerarşisi Keychain'e
/// bağımlı değildir: yedek dosyası cihaz değişse de yalnız parolayla açılır.
public enum Crypto {

    public static let kdfIterations = 310_000
    public static let keyBytes = 32
    static let ivBytes = 12
    static let tagBytes = 16

    /// Kriptografik rastgele baytlar (SecRandomCopyBytes).
    public static func randomBytes(_ count: Int) -> Data {
        var data = Data(count: count)
        let durum = data.withUnsafeMutableBytes { tampon in
            SecRandomCopyBytes(kSecRandomDefault, count, tampon.baseAddress!)
        }
        precondition(durum == errSecSuccess, "SecRandomCopyBytes başarısız: \(durum)")
        return data
    }

    public static func randomKey() -> SymmetricKey { SymmetricKey(size: .bits256) }

    /// PBKDF2-HMAC-SHA256. Parola UTF-8 baytlarıyla türetilir — Android'in
    /// `PBEKeySpec`'iyle ASCII'de aynı; ASCII dışı karakterler Android
    /// çalışma zamanında henüz ölçülmedi (bicim/README.md §1).
    public static func deriveKey(password: String, salt: Data, iterations: Int = kdfIterations) -> SymmetricKey {
        let parola = Array(password.utf8)
        var cikti = [UInt8](repeating: 0, count: keyBytes)
        let durum = parola.withUnsafeBufferPointer { p in
            salt.withUnsafeBytes { t in
                p.baseAddress!.withMemoryRebound(to: CChar.self, capacity: max(parola.count, 1)) { pc in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pc, parola.count,
                        t.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        &cikti, cikti.count
                    )
                }
            }
        }
        precondition(durum == kCCSuccess, "PBKDF2 başarısız: \(durum)")
        // Ara bellekteki anahtar baytları silinir (memset_s derleyicinin atlayamadığı silmedir).
        defer { _ = cikti.withUnsafeMutableBytes { memset_s($0.baseAddress, $0.count, 0, $0.count) } }
        return SymmetricKey(data: cikti)
    }

    /// Çıktı: `iv(12) || şifreli veri || etiket(16)` — Android'in `encrypt` çıktısı.
    public static func encrypt(_ plaintext: Data, key: SymmetricKey) throws -> Data {
        try encrypt(plaintext, key: key, iv: randomBytes(ivBytes))
    }

    /// Sabit IV'li mühür — yalnız testler ve biçim değerleri için. Aynı IV'yi
    /// aynı anahtarla iki kez kullanmak GCM'i kırar; uygulama kodu bunu çağırmaz.
    static func encrypt(_ plaintext: Data, key: SymmetricKey, iv: Data) throws -> Data {
        let kutu = try AES.GCM.seal(plaintext, using: key, nonce: AES.GCM.Nonce(data: iv))
        guard let birlesik = kutu.combined else { throw CryptoError.sealFailed }
        return birlesik
    }

    /// Girdi: `iv(12) || şifreli veri || etiket(16)`. Yanlış anahtar ya da bozuk
    /// veri `nil` döner (Android: `decrypt` null).
    public static func decrypt(_ blob: Data, key: SymmetricKey) -> Data? {
        guard blob.count > ivBytes else { return nil }
        guard let kutu = try? AES.GCM.SealedBox(combined: blob) else { return nil }
        return try? AES.GCM.open(kutu, using: key)
    }

    public enum CryptoError: Error { case sealFailed }
}

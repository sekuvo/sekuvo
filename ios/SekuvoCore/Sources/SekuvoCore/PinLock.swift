import CryptoKit
import Foundation

/// İsteğe bağlı ikinci kapı: PIN — Android'deki `core/PinLock.kt` ile aynı.
///
/// PIN bir arayüz kontrolü değil, kriptografik bir katmandır. Açıkken
/// biyometrik kayıtta doğrudan `dataKey` değil, PIN'den türeyen anahtarla
/// şifrelenmiş hâli (iç sargı) durur:
///
/// ```
/// parola  → Enc(KEK_parola, dataKey)                 (PIN sorulmaz)
/// Face ID → Keychain(biyometri) ⟶ Enc(K_pin, dataKey) (PIN şart)
/// ```
///
/// PIN ana parola yolunu değil, biyometrik yolu korur: korunmak istenen
/// senaryo "biri yüzümü ya da parmağımı kullanır"dır. Düşük entropi kabul
/// edilebilir, çünkü iç sargıya ulaşmak için önce biyometrik Keychain kaydını
/// geçmek gerekir. Kurtarma garantisi korunur: ana parola her zaman tek
/// başına açar.
public enum PinLock {

    /// PIN kısa olduğu için tur sayısı ana paroladakiyle aynı tutulur.
    public static let iterations = Crypto.kdfIterations
    public static let minLength = 4
    public static let maxLength = 12
    static let checkText = "vault-pin-ok"

    public static func isValid(_ pin: String) -> Bool {
        (minLength...maxLength).contains(pin.count) && pin.allSatisfy { $0.isASCII && $0.isNumber }
    }

    static func key(_ pin: String, _ salt: Data) -> SymmetricKey {
        Crypto.deriveKey(password: pin, salt: salt, iterations: iterations)
    }

    /// `dataKey` → iç sargı.
    public static func wrap(dataKey: Data, pin: String, salt: Data) throws -> Data {
        try Crypto.encrypt(dataKey, key: key(pin, salt))
    }

    /// İç sargı → `dataKey`; PIN yanlışsa `nil` (GCM etiketi tutmaz).
    public static func unwrap(_ inner: Data, pin: String, salt: Data) -> Data? {
        Crypto.decrypt(inner, key: key(pin, salt))
    }

    /// PIN'i sargıya dokunmadan sınamak için küçük doğrulama blobu.
    public static func wrapCheck(pin: String, salt: Data) throws -> Data {
        try Crypto.encrypt(Data(checkText.utf8), key: key(pin, salt))
    }

    public static func unwrapCheck(_ blob: Data, pin: String, salt: Data) -> Bool {
        Crypto.decrypt(blob, key: key(pin, salt)).map { String(decoding: $0, as: UTF8.self) } == checkText
    }
}

/// PIN kaba kuvvet kısıtlaması — Android'deki `KeyManager` sayacı ile aynı:
/// ilk 4 yanlış serbest, sonra 30 sn, 1 dk, 5 dk, 15 dk; 10. yanlışta
/// biyometrik yol tamamen kapanır (yalnız ana parola kalır).
public enum PinBackoff {

    public static let maxFails = 10

    /// `fails` yanlıştan sonra beklenecek süre (ms).
    public static func waitMs(fails: Int) -> Int64 {
        switch fails {
        case ..<5: 0
        case 5: 30_000
        case 6: 60_000
        case 7: 5 * 60_000
        default: 15 * 60_000
        }
    }

    /// Şu an denemeye izin verilene kadar kalan süre (ms); 0 = serbest.
    public static func remainingMs(fails: Int, lastFailAt: Int64, now: Int64) -> Int64 {
        let bekleme = waitMs(fails: fails)
        guard bekleme > 0 else { return 0 }
        return max(lastFailAt + bekleme - now, 0)
    }
}

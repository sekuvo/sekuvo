import Foundation
import LocalAuthentication
import Security

/// Face ID / Touch ID kaydı — Android'deki biyometrik Keystore anahtarının
/// karşılığı.
///
/// İçerik (PIN kapalıyken `dataKey`, açıkken PIN'le sarılmış iç sargı)
/// Keychain'de `biometryCurrentSet` erişim denetimiyle durur: okumak için
/// sistem Face ID ister, kayıtlı yüzler ya da parmaklar değişince kayıt
/// geçersizleşir (Android: `setInvalidatedByBiometricEnrollment(true)`).
/// `WhenPasscodeSetThisDeviceOnly`: cihaz kodu kaldırılırsa kayıt da gider,
/// yedeğe ve başka cihaza geçmez. Ana parola her zaman tek başına açar.
struct BiometricStore: Sendable {

    enum Kind: Sendable {
        case faceID, touchID, opticID

        var name: String {
            switch self {
            case .faceID: "Face ID"
            case .touchID: "Touch ID"
            case .opticID: "Optic ID"
            }
        }
    }

    enum Failure: LocalizedError, Equatable {
        case canceled
        /// Kayıtlı yüz/parmak değişti ya da kayıt yok.
        case invalidated
        case failed(OSStatus)

        /// Keychain'in kendi açıklaması ve kodu: "işlem tamamlanamadı" yerine
        /// neyin olmadığı görünsün (ör. -34018 yetki eksik).
        var errorDescription: String? {
            guard case .failed(let durum) = self else { return nil }
            let metin = SecCopyErrorMessageString(durum, nil) as String? ?? "OSStatus"
            return "\(metin) (\(durum))"
        }
    }

    static let service = "com.sekuvo.app.biometric"
    static let account = "vault-key"

    /// Cihazda kullanılabilir biyometri; kurulmamışsa `nil`.
    static func available() -> Kind? {
        let c = LAContext()
        guard c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return nil }
        switch c.biometryType {
        case .faceID: return .faceID
        case .touchID: return .touchID
        case .opticID: return .opticID
        default: return nil
        }
    }

    private var temel: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: Self.account]
    }

    /// Kayıt var mı? Arayüz göstermeden sorulur.
    var isEnabled: Bool {
        let c = LAContext()
        c.interactionNotAllowed = true
        var q = temel
        q[kSecUseAuthenticationContext as String] = c
        q[kSecReturnAttributes as String] = true
        let durum = SecItemCopyMatching(q as CFDictionary, nil)
        return durum == errSecSuccess || durum == errSecInteractionNotAllowed
    }

    /// İçeriği biyometrik kilitli olarak yazar (eskisinin yerine).
    func store(_ payload: Data) throws {
        clear()
        var hata: Unmanaged<CFError>?
        guard let erisim = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, &hata
        ) else { throw Failure.failed(errSecParam) }
        var q = temel
        q[kSecValueData as String] = payload
        q[kSecAttrAccessControl as String] = erisim
        let durum = SecItemAdd(q as CFDictionary, nil)
        guard durum == errSecSuccess else { throw Failure.failed(durum) }
    }

    #if DEBUG
    /// Yazılan kayıt erişim denetimini taşıyor mu? (Ölçüm için; uygulama kullanmaz.)
    var debugAccessControlPresent: Bool {
        let c = LAContext()
        c.interactionNotAllowed = true
        var q = temel
        q[kSecReturnAttributes as String] = true
        q[kSecUseAuthenticationContext as String] = c
        var sonuc: CFTypeRef?
        let durum = SecItemCopyMatching(q as CFDictionary, &sonuc)
        let ozellikler = sonuc as? [String: Any]
        return durum == errSecInteractionNotAllowed || ozellikler?[kSecAttrAccessControl as String] != nil
    }

    /// İçerik, etkileşime izin vermeyen bir bağlamla okunabiliyor mu? Erişim
    /// denetimi uygulanıyorsa sistem okumayı reddeder (-25308); simülatörde
    /// içerik döner (0). Denetimin *uygulandığını* cihazda ölçmek için.
    var debugEnforcementProbe: OSStatus {
        let c = LAContext()
        c.interactionNotAllowed = true
        var q = temel
        q[kSecReturnData as String] = true
        q[kSecUseAuthenticationContext as String] = c
        var sonuc: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &sonuc)
    }
    #endif

    /// Face ID ister ve içeriği döndürür.
    ///
    /// Önce sistemden açık bir biyometrik onay alınır, Keychain ancak bu
    /// onaylanmış bağlamla okunur. Kaydın kendi erişim denetimi ayrıca
    /// bekçidir. Ölçüldü:
    /// - iPad Air 3'te (Touch ID, iPadOS 26.2) denetim uygulanıyor:
    ///   etkileşimsiz okuma -25308 (`errSecInteractionNotAllowed`) ile
    ///   reddedildi.
    /// - Simülatörde uygulanmıyor: okuma istem olmadan 0,02 sn'de döndü.
    /// Onay adımı bu yüzden her koşulda kendisi istenir.
    func read(reason: String) async throws -> Data {
        let c = LAContext()
        c.localizedFallbackTitle = ""   // "Parola gir" düğmesi yok: yedek yol ana paroladır
        do {
            guard try await c.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) else {
                throw Failure.failed(errSecAuthFailed)
            }
        } catch let e as LAError {
            #if DEBUG
            Log.kilit.notice("biyometri: onay alınamadı, LAError \(e.code.rawValue)")
            #endif
            switch e.code {
            case .userCancel, .appCancel, .systemCancel, .userFallback: throw Failure.canceled
            case .biometryNotEnrolled, .biometryNotAvailable: throw Failure.invalidated
            default: throw Failure.failed(errSecAuthFailed)
            }
        }
        var q = temel
        q[kSecReturnData as String] = true
        q[kSecUseAuthenticationContext as String] = c
        var sonuc: CFTypeRef?
        let durum = SecItemCopyMatching(q as CFDictionary, &sonuc)
        #if DEBUG
        Log.kilit.notice("biyometri: Keychain okuması \(durum)")
        #endif
        switch durum {
        case errSecSuccess:
            guard let veri = sonuc as? Data else { throw Failure.failed(durum) }
            return veri
        case errSecUserCanceled: throw Failure.canceled
        // Onay alındığı hâlde okunamıyorsa kayıt bu yüz/parmak setiyle
        // kullanılamaz (`biometryCurrentSet`): ikisi de geçersiz sayılır.
        case errSecItemNotFound, errSecAuthFailed: throw Failure.invalidated
        default: throw Failure.failed(durum)
        }
    }

    func clear() { SecItemDelete(temel as CFDictionary) }
}

/// PIN'in tuzu, doğrulama blobu ve yanlış deneme sayacı.
///
/// Keychain'de, yalnız bu cihaza bağlı (`AfterFirstUnlockThisDeviceOnly`)
/// durur: cihazdan alınmış bir dosya dökümü doğrulama blobunu içermez, yani
/// PIN çevrimdışı denenemez (Android'de aynı iş blobu Keystore anahtarıyla
/// sarmakla yapılıyor). Sayaç da aynı yerde; uygulama verisini silerek
/// sıfırlanamaz.
struct PinStore: Sendable {

    struct Record: Codable, Sendable {
        var salt: Data
        var check: Data
        var fails: Int = 0
        var failAt: Int64 = 0
    }

    static let service = "com.sekuvo.app.pin"

    private var temel: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: "pin"]
    }

    func load() -> Record? {
        var q = temel
        q[kSecReturnData as String] = true
        var sonuc: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &sonuc) == errSecSuccess, let veri = sonuc as? Data else { return nil }
        return try? JSONDecoder().decode(Record.self, from: veri)
    }

    func save(_ r: Record) throws {
        let veri = try JSONEncoder().encode(r)
        let guncelle = SecItemUpdate(temel as CFDictionary, [kSecValueData as String: veri] as CFDictionary)
        if guncelle == errSecItemNotFound {
            var q = temel
            q[kSecValueData as String] = veri
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let durum = SecItemAdd(q as CFDictionary, nil)
            guard durum == errSecSuccess else { throw BiometricStore.Failure.failed(durum) }
        } else if guncelle != errSecSuccess {
            throw BiometricStore.Failure.failed(guncelle)
        }
    }

    func clear() { SecItemDelete(temel as CFDictionary) }
}

/// Kilit tercihleri (gizli değil): ana parola hatırlatıcısı.
struct LockPreferences {
    private let d = UserDefaults.standard

    /// 0 = süresiz (kapalı). Seçenekler Android'deki gibi: 0, 30, 60, 90, 180.
    var reminderDays: Int {
        get { d.integer(forKey: "masterReminderDays") }
        nonmutating set { d.set(newValue, forKey: "masterReminderDays") }
    }

    func markMasterPasswordUsed(now: Date = .now) { d.set(now.timeIntervalSince1970, forKey: "lastMasterPasswordAt") }

    /// Biyometri yerine ana parola istenmeli mi? Amaç güvenlik değil, unutmayı önlemek.
    func masterPasswordDue(now: Date = .now) -> Bool {
        let gun = reminderDays
        let son = d.double(forKey: "lastMasterPasswordAt")
        guard gun > 0, son > 0 else { return false }
        return now.timeIntervalSince1970 - son >= Double(gun) * 86_400
    }

    func clear() {
        d.removeObject(forKey: "masterReminderDays")
        d.removeObject(forKey: "lastMasterPasswordAt")
    }
}

import Foundation
import LocalAuthentication
import Security
#if DEBUG
import os
#endif

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

/// PIN doğrulama blobunu cihaza bağlayan Secure Enclave anahtarı.
///
/// Android'de `pin_check` Keystore anahtarıyla sarılır ([SEC-030]): cihaz
/// dökümünü ya da Keychain kopyasını ele geçiren biri blobu çözemez, yani
/// 4 haneli PIN'i çevrimdışı deneyemez. Burada aynı iş enclave'deki bir
/// P-256 anahtarıyla yapılır: blob ECIES ile şifrelenir, özel anahtar
/// enclave'den çıkmaz, çözme yalnız bu cihazda ve cihaz kilidi açıkken olur
/// (`WhenUnlockedThisDeviceOnly`). Enclave yoksa (simülatör) blob sarılmadan
/// durur ve kayıt bunu `sealed: false` ile söyler — Android'in "Keystore
/// yoksa eski davranışa düş" kuralı.
struct PinCheckSealer: Sendable {

    static let tag = Data("com.sekuvo.app.pin.sealer".utf8)
    static let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorVariableIVX963SHA256AESGCM

    private var query: [String: Any] {
        [kSecClass as String: kSecClassKey,
         kSecAttrApplicationTag as String: Self.tag,
         kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom]
    }

    private func existingKey() -> SecKey? {
        var q = query
        q[kSecReturnRef as String] = true
        var ref: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &ref) == errSecSuccess, let ref else { return nil }
        return (ref as! SecKey)
    }

    private func makeKey() -> SecKey? {
        var hata: Unmanaged<CFError>?
        guard let erisim = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .privateKeyUsage, &hata) else { return nil }
        let ozel: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: Self.tag,
            kSecAttrAccessControl as String: erisim
        ]
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: ozel
        ]
        return SecKeyCreateRandomKey(attrs as CFDictionary, &hata)
    }

    /// Blobu yeni bir enclave anahtarıyla sarar; enclave yoksa `nil`.
    func seal(_ plain: Data) -> Data? {
        clear()   // her PIN kurulumu kendi anahtarını alır
        guard let ozel = makeKey(), let acik = SecKeyCopyPublicKey(ozel) else { return nil }
        var hata: Unmanaged<CFError>?
        let sarili = SecKeyCreateEncryptedData(acik, Self.algorithm, plain as CFData, &hata) as Data?
        #if DEBUG
        if sarili == nil {
            let aciklama = hata?.takeRetainedValue().localizedDescription ?? "?"
            Logger(subsystem: "com.sekuvo.app", category: "kilit").notice("PIN blobu: enclave sarması düştü: \(aciklama, privacy: .public)")
        }
        #endif
        return sarili
    }

    /// Sarılı blobu açar; anahtar yoksa (silinmiş, başka cihaz) `nil`.
    func open(_ sealed: Data) -> Data? {
        guard let ozel = existingKey() else {
            #if DEBUG
            Logger(subsystem: "com.sekuvo.app", category: "kilit").notice("PIN blobu: enclave anahtarı bulunamadı")
            #endif
            return nil
        }
        var hata: Unmanaged<CFError>?
        let acik = SecKeyCreateDecryptedData(ozel, Self.algorithm, sealed as CFData, &hata) as Data?
        #if DEBUG
        if acik == nil {
            let aciklama = hata?.takeRetainedValue().localizedDescription ?? "?"
            Logger(subsystem: "com.sekuvo.app", category: "kilit").notice("PIN blobu: enclave çözmesi düştü: \(aciklama, privacy: .public)")
        }
        #endif
        return acik
    }

    func clear() { SecItemDelete(query as CFDictionary) }
}

/// PIN'in tuzu, doğrulama blobu ve yanlış deneme sayacı.
///
/// Keychain'de, yalnız bu cihaza bağlı ve yalnız cihaz kilidi açıkken
/// (`WhenUnlockedThisDeviceOnly`) durur; doğrulama blobu ayrıca
/// [PinCheckSealer] ile enclave anahtarına bağlıdır. Böylece cihazdan alınmış
/// bir dosya dökümü ya da Keychain kopyası PIN'i çevrimdışı denemeye yetmez.
/// Sayaç da aynı yerde; uygulama verisini silerek sıfırlanamaz.
struct PinStore: Sendable {

    struct Record: Codable, Sendable {
        var salt: Data
        var check: Data
        var fails: Int = 0
        var failAt: Int64 = 0
        /// Son yanlışın monoton saat damgası (ms); saat ayarıyla oynanmasına karşı.
        var failAtMono: Int64 = 0
        /// `check` enclave anahtarıyla sarılı mı. Eski kayıtlarda alan yok → düz.
        var sealed: Bool = false

        init(salt: Data, check: Data, fails: Int = 0, failAt: Int64 = 0, failAtMono: Int64 = 0, sealed: Bool = false) {
            self.salt = salt; self.check = check; self.fails = fails; self.failAt = failAt
            self.failAtMono = failAtMono; self.sealed = sealed
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            salt = try c.decode(Data.self, forKey: .salt)
            check = try c.decode(Data.self, forKey: .check)
            fails = try c.decodeIfPresent(Int.self, forKey: .fails) ?? 0
            failAt = try c.decodeIfPresent(Int64.self, forKey: .failAt) ?? 0
            failAtMono = try c.decodeIfPresent(Int64.self, forKey: .failAtMono) ?? 0
            sealed = try c.decodeIfPresent(Bool.self, forKey: .sealed) ?? false
        }
    }

    private let sealer = PinCheckSealer()

    /// Yeni PIN kaydı: blob mümkünse sarılı.
    func sealedRecord(salt: Data, check: Data) -> Record {
        if let sarili = sealer.seal(check) { return Record(salt: salt, check: sarili, sealed: true) }
        return Record(salt: salt, check: check, sealed: false)
    }

    /// Kayıttaki doğrulama blobunun düz hâli; sarılıysa enclave açar, açamazsa `nil`.
    func openCheck(_ r: Record) -> Data? { r.sealed ? sealer.open(r.check) : r.check }

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
        guard var kayit = try? JSONDecoder().decode(Record.self, from: veri) else { return nil }
        if !kayit.sealed, let sarili = sealer.seal(kayit.check) {
            // Eski (düz) kayıt: ilk okumada enclave'e bağlanır.
            kayit.check = sarili; kayit.sealed = true
            try? save(kayit)
        }
        return kayit
    }

    /// Kaydı siler ve yeniden yazar: erişilebilirlik sınıfı da böylece eski
    /// kayıtlarda (`AfterFirstUnlock`) `WhenUnlocked`a çekilir.
    func save(_ r: Record) throws {
        let veri = try JSONEncoder().encode(r)
        SecItemDelete(temel as CFDictionary)
        var q = temel
        q[kSecValueData as String] = veri
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let durum = SecItemAdd(q as CFDictionary, nil)
        guard durum == errSecSuccess else { throw BiometricStore.Failure.failed(durum) }
    }

    func clear() {
        SecItemDelete(temel as CFDictionary)
        sealer.clear()
    }
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

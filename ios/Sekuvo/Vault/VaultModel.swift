import CryptoKit
import Foundation
import LocalAuthentication
import Observation
import SekuvoCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import os

/// Yalnız Debug derlemesindeki ölçüm satırları için. `notice` düzeyinde:
/// cihazda `debug` düzeyi hiç üretilmiyor, konsola (`devicectl --console`,
/// `OS_ACTIVITY_DT_MODE`) yalnız `notice` ve üstü geliyor (iPad'de ölçüldü).
enum Log {
    static let kilit = Logger(subsystem: "com.sekuvo.app", category: "kilit")
}

/// Kasanın durumu — Android'deki `ui/VaultViewModel.kt`'nin karşılığı.
///
/// Kayıt mantığı `SekuvoCore.VaultStore`'da (macOS'ta sınanır); burası yalnız
/// arayüz durumunu ve PBKDF2 gibi ağır işlerin ana iş parçacığı dışına
/// taşınmasını tutar. `dataKey` yalnız kilit açıkken bellekte durur; uygulama
/// arka plana geçince kasa kilitlenir (Android'de ekranın kararması).
@Observable
@MainActor
final class VaultModel {

    enum State { case setup, locked, needsPin, unlocked }

    /// İçe aktarmanın kaynağı: hata ve başarı metni buna göre seçilir.
    enum ImportSource { case qr, text, file }

    private(set) var state: State
    /// Kayıtlar, seçili sırada (`sort`).
    private(set) var items: [VaultStore.Item] = []
    /// Etiketler, ada göre.
    private(set) var tags: [VaultStore.Tag] = []
    /// Liste sırası (Android: `EntrySort`). Sır değil: UserDefaults'ta durur.
    private(set) var sort = EntrySort.of(UserDefaults.standard.string(forKey: "entrySort"))
    private(set) var busy = false
    /// Face ID / Touch ID okuması sürüyor. `busy`'den ayrı: biyometri istemi
    /// açıkken parola düğmesinde gösterge dönmesin.
    private(set) var biometricBusy = false
    var error: String?
    var privacyCover = false
    /// Kısa bildirim (Android'deki Toast).
    var toast: String?
    /// Kurulum "yedeğim var" ile yapıldı: liste açılınca dosya seçici gelir.
    var restoreAfterSetup = false

    // Biyometri ve PIN (Android: KeyManager'ın biyometrik ve PIN bölümleri)
    private(set) var biometry: BiometricStore.Kind? = BiometricStore.available()
    private(set) var biometricEnabled = false
    private(set) var pinEnabled = false
    private(set) var reminderDays = 0
    /// Kurulumdan hemen sonra biyometri önerilir (Android ile aynı an).
    var offerBiometric = false
    /// PIN yeni açıldı: biyometriyi bu PIN'le bağlamayı öner.
    var offerBiometricPin: String?
    /// Kilit ekranı biyometriyi kendiliğinden sorar mı. Soğuk açılışta ve arka
    /// plandan dönüşte evet; elle kilitlenince ya da PIN'den vazgeçilince hayır:
    /// Face ID ekrana bakan yüzü anında tanır, kasa hemen yeniden açılırdı.
    var autoBiometricArmed = true
    /// Kilit açma yarışına karşı nesil sayacı. Her kilitlenmede artar; PBKDF2
    /// ya da Keychain okuması bitip anahtar gelince, bekleme sırasında kilit
    /// (arka plana geçiş) olduysa anahtar ATILIR. Yoksa kullanıcı "Kilidi aç"a
    /// basıp uygulamayı ana ekrana atınca kasa arka planda perdesiz açılıyor ve
    /// dönüşte kimlik doğrulaması yapılmıyordu.
    private var unlockGeneration = 0

    private let keys: KeyManager
    private let storeFile: URL
    private var store: VaultStore?
    private var dataKey: SymmetricKey?
    /// Face ID'den gelen ve PIN bekleyen iç sargı.
    private var pendingInner: Data?
    private let biometric = BiometricStore()
    private let pins = PinStore()
    private let lockPrefs = LockPreferences()

    var biometryName: String { biometry?.name ?? "Face ID" }
    var masterPasswordDue: Bool { lockPrefs.masterPasswordDue() }
    /// Kilit ekranında biyometri sunulur mu: cihazda var, kayıt var, hatırlatma zamanı değil.
    var biometricUsable: Bool { biometry != nil && biometricEnabled && !masterPasswordDue }

    init() {
        let kok = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sekuvo", isDirectory: true)
        keys = KeyManager(file: kok.appendingPathComponent("keys.json"))
        storeFile = kok.appendingPathComponent("vault.sqlite")
        state = keys.isInitialized ? .locked : .setup
        // Keychain kayıtları uygulama silinince de kalır: kasası olmayan bir
        // kurulumda eskiden kalan biyometri ve PIN kaydı temizlenir.
        if !keys.isInitialized {
            biometric.clear(); pins.clear(); lockPrefs.clear()
        }
        refreshLockOptions()
    }

    func refreshLockOptions() {
        biometry = BiometricStore.available()
        biometricEnabled = biometric.isEnabled
        pinEnabled = pins.load() != nil
        reminderDays = lockPrefs.reminderDays
    }

    private var simdi: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: Kurulum ve kilit

    /// İlk kurulum. Kural Android'deki gibi: en az 8 karakter, iki kez aynı.
    func setup(password: String, again: String, restoreAfter: Bool = false) async {
        guard password.count >= 8 else { error = L("vm_master_password_too_short"); return }
        guard password == again else { error = L("vm_passwords_mismatch"); return }
        busy = true; defer { busy = false }
        let k = keys
        do {
            let nesil = unlockGeneration
            let ham = try await Task.detached { try k.setup(password: password).withUnsafeBytes { Data($0) } }.value
            await debugUnlockDelay()
            guard nesil == unlockGeneration else { return }   // kurulum sürerken kilitlendi: anahtar atılır
            restoreAfterSetup = restoreAfter
            try open(with: SymmetricKey(data: ham))
            lockPrefs.markMasterPasswordUsed()
            // Teklif kurulumun hemen ardından: sonraya bırakılırsa özellik çoğu
            // zaman hiç fark edilmez (Android ile aynı gerekçe).
            offerBiometric = biometry != nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func unlock(password: String) async {
        busy = true; defer { busy = false }
        let k = keys
        // PBKDF2 (310.000 tur) arayüzü dondurmasın.
        let nesil = unlockGeneration
        let ham = await Task.detached { k.unlock(password: password)?.withUnsafeBytes { Data($0) } }.value
        await debugUnlockDelay()
        guard nesil == unlockGeneration else {
            #if DEBUG
            Log.kilit.notice("kilit açma: bekleme sırasında kilitlendi, anahtar atıldı")
            #endif
            return
        }
        guard let ham else { error = L("vm_wrong_password"); return }
        do {
            try open(with: SymmetricKey(data: ham))
            // Ana parola tek başına açar: PIN yalnız biyometrik yolu korur.
            lockPrefs.markMasterPasswordUsed()
        } catch { self.error = error.localizedDescription }
    }

    private func open(with key: SymmetricKey) throws {
        #if DEBUG
        Log.kilit.notice("kasa açılıyor")
        #endif
        store = try VaultStore(file: storeFile, key: key)
        dataKey = key
        error = nil
        try reload()
        state = .unlocked
    }

    func lock() {
        unlockGeneration &+= 1       // süren bir kilit açma varsa sonucu geçersiz
        autoBiometricArmed = false   // elle kilit; arka plana geçiş bunu yeniden kurar
        if state == .needsPin { pendingInner = nil; state = .locked; return }
        guard state == .unlocked else { return }
        store = nil
        dataKey = nil
        items = []
        tags = []
        state = .locked
    }

    /// Sahne etkin değilken perde iner (uygulama değiştirici anlık görüntüsü),
    /// arka planda kasa kilitlenir.
    func sceneChanged(_ faz: ScenePhase) {
        switch faz {
        case .active:
            privacyCover = false
            refreshLockOptions()   // Face ID Ayarlar'dan yeni kurulmuş olabilir
        case .inactive: privacyCover = state == .unlocked
        case .background:
            privacyCover = state == .unlocked
            lock()
            autoBiometricArmed = true
        @unknown default: break
        }
    }

    // MARK: Face ID / Touch ID ve PIN

    /// Biyometriyle açar; PIN açıksa ikinci aşamaya (PIN ekranı) geçer.
    func unlockWithBiometric() async {
        guard biometricUsable, state == .locked, !busy, !biometricBusy else { return }
        biometricBusy = true; defer { biometricBusy = false }
        let b = biometric, sebep = L("ios_biometric_reason")
        #if DEBUG
        let baslangic = Date()
        // Parola düğmesindeki gösterge yalnız `busy`'ye bağlı: okuma sürerken false olmalı.
        Log.kilit.notice("biyometri: okuma başlıyor (busy: \(self.busy), biometricBusy: \(self.biometricBusy))")
        defer { Log.kilit.notice("biyometri: okuma bitti, \(Date().timeIntervalSince(baslangic), format: .fixed(precision: 3)) sn") }
        #endif
        do {
            let nesil = unlockGeneration
            let icerik = try await b.read(reason: sebep)
            await debugUnlockDelay()
            guard nesil == unlockGeneration else { return }   // okuma sürerken kilitlendi
            if pinEnabled {
                pendingInner = icerik
                error = nil
                state = .needsPin
            } else if icerik.count == Crypto.keyBytes {
                try open(with: SymmetricKey(data: icerik))
            } else {
                error = L("vm_key_unexpected_size")
            }
        } catch BiometricStore.Failure.canceled {
            // Vazgeçildi: parola alanı yerinde.
        } catch BiometricStore.Failure.invalidated {
            biometric.clear(); refreshLockOptions()
            error = L("ios_biometric_invalidated", biometryName)
        } catch {
            self.error = L("ios_biometric_failed", biometryName)
        }
    }

    /// İkinci aşama: Face ID'den gelen iç sargıyı PIN ile çözer (Android: `unlockWithPin`).
    func unlockWithPin(_ pin: String) async {
        guard let ic = pendingInner, var kayit = pins.load() else { cancelPin(); return }
        let kalan = PinBackoff.remainingMs(fails: kayit.fails, lastFailAt: kayit.failAt, now: simdi)
        if kalan > 0 { error = Lp("vm_pin_locked_wait", Int(max(kalan / 1000, 1))); return }
        busy = true; defer { busy = false }
        let tuz = kayit.salt
        let kontrol = pins.openCheck(kayit)   // cihaz anahtarıyla sarılıysa burada açılır; açılamazsa yalnız sargı sınanır
        let nesil = unlockGeneration
        let (anahtar, pinDogru) = await Task.detached { () -> (Data?, Bool) in
            if let k = PinLock.unwrap(ic, pin: pin, salt: tuz) { return (k, true) }
            guard let kontrol else { return (nil, false) }
            return (nil, PinLock.unwrapCheck(kontrol, pin: pin, salt: tuz))
        }.value
        await debugUnlockDelay()
        guard nesil == unlockGeneration else { return }   // PIN çözülürken kilitlendi
        if let anahtar, anahtar.count == Crypto.keyBytes {
            kayit.fails = 0; kayit.failAt = 0
            try? pins.save(kayit)
            pendingInner = nil
            do { try open(with: SymmetricKey(data: anahtar)) } catch { self.error = error.localizedDescription }
            return
        }
        if pinDogru {
            // PIN doğru ama biyometrik kayıt bayat (PIN'den önce yazılmış):
            // sayaç bir güvenlik olayı değil; kayıt silinir, onarıma yönlendirilir.
            kayit.fails = 0; kayit.failAt = 0
            try? pins.save(kayit)
            biometric.clear(); refreshLockOptions()
            pendingInner = nil; state = .locked
            error = L("ios_biometric_stale", biometryName)
            return
        }
        kayit.fails += 1; kayit.failAt = simdi
        try? pins.save(kayit)
        if kayit.fails >= PinBackoff.maxFails {
            // Çok fazla yanlış: biyometrik yol tamamen kapanır, yalnız ana parola kalır.
            biometric.clear(); refreshLockOptions()
            pendingInner = nil; state = .locked
            error = L("ios_pin_biometric_off", biometryName)
        } else {
            let yeni = PinBackoff.remainingMs(fails: kayit.fails, lastFailAt: kayit.failAt, now: simdi)
            error = yeni > 0 ? Lp("vm_pin_wrong_wait", Int(max(yeni / 1000, 1))) : L("vm_pin_wrong")
        }
    }

    func cancelPin() {
        pendingInner = nil
        error = nil
        autoBiometricArmed = false   // vazgeçen kullanıcı ana parolayı seçti
        if state == .needsPin { state = .locked }
    }

    /// Biyometrik kaydı yazar. PIN açıksa içerik PIN'le sarılır (PIN gerekir).
    /// Önce sistem bir kez Face ID ister: kayıt, kullanıcının kendi onayıyla yazılır.
    @discardableResult
    func enableBiometric(pin: String? = nil) async -> Bool {
        guard let dataKey, biometry != nil else { return false }
        let ham = dataKey.withUnsafeBytes { Data($0) }
        var icerik = ham
        if let kayit = pins.load() {
            guard let pin, PinLock.isValid(pin) else { error = L("vm_pin_wrong"); return false }
            guard let kontrol = pins.openCheck(kayit) else { error = L("vm_pin_wrong"); return false }
            busy = true
            let sarili = await Task.detached { () -> Data? in
                guard PinLock.unwrapCheck(kontrol, pin: pin, salt: kayit.salt) else { return nil }
                return try? PinLock.wrap(dataKey: ham, pin: pin, salt: kayit.salt)
            }.value
            busy = false
            guard let sarili else { error = L("vm_pin_wrong"); return false }
            icerik = sarili
        }
        let baglam = LAContext()
        do {
            guard try await baglam.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                                  localizedReason: L("ios_biometric_reason")) else { return false }
            try biometric.store(icerik)
            #if DEBUG
            Log.kilit.notice("biyometri: kayıt yazıldı, erişim denetimi: \(self.biometric.debugAccessControlPresent), etkileşimsiz okuma: \(self.biometric.debugEnforcementProbe)")
            #endif
            refreshLockOptions()
            error = nil
            show(L("ios_biometric_enabled", biometryName))
            return true
        } catch let e as LAError where [.userCancel, .appCancel, .systemCancel].contains(e.code) {
            return false
        } catch {
            self.error = L("ios_biometric_enable_failed", biometryName, error.localizedDescription)
            return false
        }
    }

    func disableBiometric() {
        biometric.clear()
        refreshLockOptions()
        show(L("vm_biometric_disabled"))
    }

    /// PIN'i açar. Ana parola yalnız DOĞRULAMA için istenir. Ham anahtarı saran
    /// eski biyometrik kayıt silinir; biyometri hemen PIN'e bağlanmak üzere önerilir.
    @discardableResult
    func enablePin(password: String, pin: String, again: String) async -> Bool {
        guard PinLock.isValid(pin) else { error = L("vm_pin_enable_failed"); return false }
        guard pin == again else { error = L("vm_passwords_mismatch"); return false }
        busy = true; defer { busy = false }
        let k = keys
        let tuz = Crypto.randomBytes(16)
        let kontrol = await Task.detached { () -> Data? in
            guard k.unlock(password: password) != nil else { return nil }
            return try? PinLock.wrapCheck(pin: pin, salt: tuz)
        }.value
        guard let kontrol else { error = L("vm_pin_enable_failed"); return false }
        let kayit = pins.sealedRecord(salt: tuz, check: kontrol)
        do { try pins.save(kayit) } catch {
            self.error = L("vm_pin_enable_failed"); return false
        }
        #if DEBUG
        Log.kilit.notice("PIN kaydı yazıldı, doğrulama blobu enclave anahtarıyla sarılı: \(kayit.sealed)")
        #endif
        biometric.clear()
        lockPrefs.markMasterPasswordUsed()
        refreshLockOptions()
        error = nil
        show(L("ios_pin_enabled", biometryName))
        if biometry != nil { offerBiometricPin = pin }
        return true
    }

    /// PIN'i kapatır. Yalnız ana parola ister: PIN zaten yalnız biyometrik yolu korur.
    @discardableResult
    func disablePin(password: String) async -> Bool {
        busy = true; defer { busy = false }
        let k = keys
        let dogru = await Task.detached { k.unlock(password: password) != nil }.value
        guard dogru else { error = L("vm_pin_disable_failed"); return false }
        pins.clear()
        biometric.clear()
        lockPrefs.markMasterPasswordUsed()
        refreshLockOptions()
        error = nil
        show(L("ios_pin_disabled", biometryName))
        offerBiometric = biometry != nil
        return true
    }

    func setReminder(days: Int) {
        lockPrefs.reminderDays = days
        reminderDays = days
    }

    // MARK: Kayıtlar

    private func reload() throws {
        guard let store else { return }
        items = sort.sorted(try store.items())
        tags = try store.tags()
    }

    func selectSort(_ s: EntrySort) {
        sort = s
        UserDefaults.standard.set(s.rawValue, forKey: "entrySort")
        items = s.sorted(items)
    }

    // MARK: Etiketler (Android: VaultViewModel.addTag / updateTag / deleteTag)

    /// Yeni etiketin id'si; aynı adda etiket varsa onunki.
    @discardableResult
    func addTag(name: String, color: Int32, icon: String = "") -> Int64? {
        guard let store else { error = L("vm_vault_locked_retry"); return nil }
        do {
            let id = try store.addTag(name: name, color: color, icon: icon)
            try reload()
            return id
        } catch {
            self.error = error.localizedDescription
            return nil
        }
    }

    func updateTag(_ t: VaultStore.Tag) {
        guard let store else { error = L("vm_vault_locked_retry"); return }
        do { try store.updateTag(t); try reload() } catch { self.error = error.localizedDescription }
    }

    func deleteTag(id: Int64) {
        guard let store else { error = L("vm_vault_locked_retry"); return }
        do { try store.deleteTag(id: id); try reload() } catch { self.error = error.localizedDescription }
    }

    func load(id: Int64) -> DecryptedEntry? { store?.load(id: id) }

    @discardableResult
    func save(_ e: DecryptedEntry) -> Int64? {
        guard let store else { error = L("vm_vault_locked_retry"); return nil }
        do {
            let id = try store.save(e, now: simdi)
            try reload()
            return id
        } catch let VaultStore.Failure.entryTooLarge(title, bytes) {
            error = L("entry_too_large", title, bytes / 1024, VaultStore.maxEntryBytes / 1024)
        } catch {
            self.error = error.localizedDescription
        }
        return nil
    }

    func delete(id: Int64) {
        guard let store else { return }
        do { try store.delete(id: id); try reload() } catch { self.error = error.localizedDescription }
    }

    // MARK: İçe aktarma (QR, metin, yedek dosyası)

    /// Yapıştırılan metin için Android'deki dostça ön tanı: en sık iki yanlış
    /// yapıştırma (bağlantı, zarf olmayan metin) ayrı adlandırılır.
    func pasteProblem(_ ham: String) -> String? {
        let t = Backup.normalize(ham)
        if t.isEmpty { return L("vm_pasted_text_empty") }
        if t.count > 1_000_000 { return L("vm_text_too_large") }
        if t.hasPrefix("http://") || t.hasPrefix("https://") { return L("vm_pasted_is_link") }
        if !(t.hasPrefix("{") && t.contains("\"app\"") && t.contains("\"vault\"")) { return L("vm_not_an_envelope") }
        return nil
    }

    /// Zarfı açar (PBKDF2 arka planda) ve kasaya uygular. Başarıda `true`.
    func importEnvelope(_ zarf: Data, password: String, replace: Bool, source: ImportSource) async -> Bool {
        guard store != nil else { error = L("vm_vault_locked_retry"); return false }
        guard !password.isEmpty else {
            error = L(source == .file ? "settings_import_pw_missing"
                      : (source == .qr ? "paste_err_password_required_qr" : "paste_err_password_missing"))
            return false
        }
        error = nil
        busy = true; defer { busy = false }
        do {
            let sonuc = try await Task.detached { try Backup.import(zarf, password: password) }.value
            // Kilit bu arada düşmüş olabilir (uygulama arka plana gitti).
            guard let store else { error = L("vm_vault_locked_retry"); return false }
            let sayi = try store.apply(sonuc, replace: replace)
            guard sayi > 0 else {
                error = L(source == .file ? "vm_backup_empty" : "vm_envelope_empty")
                return false
            }
            try reload()
            switch source {
            case .qr: show(Lp("ios_qr_import_added", sayi))
            case .text:
                // Şifreli de olsa zarf panoda sürüklenmesin (Android ile aynı).
                UIPasteboard.general.items = []
                show(Lp("vm_import_added", sayi))
            case .file: show(Lp("vm_restored", sayi))
            }
            return true
        } catch Backup.Failure.wrongPassword {
            error = L("vm_backup_password_wrong")
        } catch Backup.Failure.invalidFormat {
            error = L(source == .file ? "vm_invalid_backup_file" : "vm_invalid_envelope")
        } catch let VaultStore.Failure.entryTooLarge(title, bytes) {
            error = L("entry_too_large", title, bytes / 1024, VaultStore.maxEntryBytes / 1024)
        } catch {
            self.error = L(source == .file ? "vm_restore_failed" : "vm_import_failed", error.localizedDescription)
        }
        return false
    }

    /// Zarftaki ilk kaydın parolası (Android: `readFromEnvelope`): düzenleme
    /// ekranında "Parolayı QR ile güncelle". Kasaya hiçbir şey eklenmez,
    /// yalnız değer döner. Olmazsa `error` dolar ve `nil` döner.
    func passwordFromEnvelope(_ zarf: Data, password: String) async -> String? {
        error = nil
        busy = true; defer { busy = false }
        do {
            let sonuc = try await Task.detached { try Backup.import(zarf, password: password) }.value
            guard let deger = sonuc.entries.first?.data.password, !deger.isEmpty else {
                error = L("vm_envelope_no_value")
                return nil
            }
            return deger
        } catch Backup.Failure.wrongPassword {
            error = L("vm_backup_password_wrong")
        } catch Backup.Failure.invalidFormat {
            error = L("vm_invalid_envelope")
        } catch {
            self.error = L("vm_import_failed", error.localizedDescription)
        }
        return nil
    }

    // MARK: Dosyalar'dan "Sekuvo ile aç"

    /// Dosyalar'dan ya da paylaşımdan gelen, okunmuş ve geri yüklenmeyi
    /// bekleyen yedek. Kasa kilitliyse kilit açılınca sunulur.
    struct IncomingFile: Identifiable {
        let id = UUID()
        let data: Data
    }
    var incomingBackup: IncomingFile?

    /// `onOpenURL` ile gelen dosyayı okur. Yerinde açılan dosya güvenlik
    /// kapsamlıdır ve koordineli okunur (iCloud'da inmemişse önce iner).
    /// Dosya uygulamanın kendi Inbox'ına kopyalanarak geldiyse (AirDrop,
    /// paylaşım) okunduktan sonra kopya silinir: şifreli de olsa yedeğin
    /// fazladan bir kopyası cihazda kalmasın.
    func receive(fileAt url: URL) {
        guard url.isFileURL else { return }
        let erisim = url.startAccessingSecurityScopedResource()
        defer { if erisim { url.stopAccessingSecurityScopedResource() } }
        var veri: Data?
        var buyuk = false
        var hata: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &hata) { u in
            let boyut = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if boyut > Backup.maxEnvelopeBytes { buyuk = true; return }
            veri = try? Data(contentsOf: u)
        }
        if let inbox = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Inbox", isDirectory: true).resolvingSymlinksInPath().path,
           url.resolvingSymlinksInPath().path.hasPrefix(inbox + "/") {
            try? FileManager.default.removeItem(at: url)
        }
        guard let veri else {
            let m = L(buyuk ? "vm_invalid_backup_file" : "vm_file_open_failed")
            error = m
            show(m)
            return
        }
        incomingBackup = IncomingFile(data: veri)
    }

    // MARK: Yedek alma

    /// Şifreli yedeğin baytları; dosyaya yazmayı çağıran yapar (Dosyalar).
    /// Önce bellekte tamamlanır: yazma yarıda kalırsa yarım bir `.vaultbak`
    /// geride kalmaz (Android'deki denetim bulgusu).
    func makeBackup(password: String, again: String) async -> Data? {
        guard password.count >= 8 else { error = L("vm_backup_password_too_short"); return nil }
        guard password == again else { error = L("vm_passwords_mismatch"); return nil }
        guard let store else { error = L("vm_vault_locked_retry"); return nil }
        error = nil
        busy = true; defer { busy = false }
        do {
            let (kayitlar, eksik) = try store.exportable()
            let etiketler = try store.tags()
            let veri = try await Task.detached { try Backup.export(password: password, entries: kayitlar, tags: etiketler) }.value
            pendingIncomplete = eksik
            return veri
        } catch {
            self.error = L("vm_backup_failed", error.localizedDescription)
            return nil
        }
    }

    /// Dosya yazıldıktan sonra: eksik kayıt varsa söylenir, yoksa "kaydedildi".
    private var pendingIncomplete = 0
    func backupWritten(_ sonuc: Result<URL, Error>) {
        switch sonuc {
        case .success:
            if pendingIncomplete > 0 { error = Lp("vm_backup_saved_incomplete", pendingIncomplete) } else { show(L("vm_backup_saved")) }
        case .failure(let e):
            if (e as NSError).code != NSUserCancelledError { error = L("vm_backup_failed", e.localizedDescription) }
        }
        pendingIncomplete = 0
    }

    // MARK: Pano ve bildirim

    /// Panoya yalnız bu cihaz için ve 45 saniyelik koyar: `localOnly`
    /// Universal Clipboard'u kapatır, `expirationDate` silmeyi sistemin
    /// kendisine bırakır — Android'in tutamadığı 45 sn sözünü iOS platform
    /// olarak tutar.
    func copy(label: String, value: String) {
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: value]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(45)]
        )
        show(L("ios_copied", label))
    }

    func show(_ mesaj: String) {
        #if DEBUG
        // Bildirim 2,5 sn görünür: ekran görüntüsüyle sınarken zamanı buradan okunur.
        Log.kilit.notice("bildirim: \(mesaj)")
        #endif
        toast = mesaj
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.5))
            if toast == mesaj { toast = nil }
        }
    }

    /// `-ornekAcilisGecikmesi <sn>` (yalnız Debug): kilit açma sonucunu bu kadar
    /// geciktirir, böylece "açılırken arka plana at" yarışı elle sınanabilir.
    private func debugUnlockDelay() async {
        #if DEBUG
        let a = ProcessInfo.processInfo.arguments
        if let i = a.firstIndex(of: "-ornekAcilisGecikmesi"), i + 1 < a.count, let sn = Double(a[i + 1]), sn > 0 {
            try? await Task.sleep(for: .seconds(sn))
        }
        #endif
    }

    // MARK: Örnek kasa (yalnız Debug)

    #if DEBUG
    /// `-ornekKasa` başlatma argümanıyla sabit parolalı bir örnek kasa kurar
    /// ya da açar ve boşsa örnek kayıtlarla doldurur. Yalnız Debug derlemesinde
    /// var: simülatörde ekranları dokunmadan doğrulamak ve mağaza ekran
    /// görüntülerini gerçek kullanıcı verisi olmadan üretmek için.
    func seedDemoIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-ornekKasa"), state != .unlocked else { return }
        let parola = "ornek-parola-2026"
        let a = ProcessInfo.processInfo.arguments
        if state == .setup {
            // `-ornekYedegimVar`: kurulum "yedeğim var" ile yapılmış gibi.
            await setup(password: parola, again: parola, restoreAfter: a.contains("-ornekYedegimVar"))
        } else {
            await unlock(password: parola)
        }
        guard state == .unlocked else { return }
        // Öneri yalnız istenince: diğer denemelerin önüne pencere düşmesin.
        offerBiometric = a.contains("-ornekFaceIDAc") && biometry != nil
        if let i = a.firstIndex(of: "-ornekPinAc"), i + 1 < a.count {
            await enablePin(password: parola, pin: a[i + 1], again: a[i + 1])
        }
        guard items.isEmpty else { return }
        let is_ = addTag(name: "İş", color: TagPalette.color(for: 4), icon: "work") ?? 0
        let gelistirici = addTag(name: "Geliştirici", color: TagPalette.color(for: 5), icon: "code") ?? 0
        let banka = addTag(name: "Banka", color: TagPalette.color(for: 3), icon: "bank") ?? 0
        let ev = addTag(name: "Ev", color: TagPalette.color(for: 1)) ?? 0
        func kayit(_ tur: EntryType, _ baslik: String, kind: NoteKind = .genel, quick: Bool = false,
                   sira: Int = 0, etiket: [Int64] = [], _ doldur: (inout EntryData) -> Void) {
            var d = EntryData(); doldur(&d)
            save(DecryptedEntry(type: tur, title: baslik, data: d, createdAt: 0, updatedAt: 0,
                                quick: quick, tagIds: etiket, noteKind: kind, sortIndex: sira))
        }
        kayit(.login, "GitHub", etiket: [is_, gelistirici]) { $0.username = "deniz.dev@example.com"; $0.password = PasswordGenerator.generate(); $0.url = "github.com" }
        kayit(.login, "Google hesabı") { $0.username = "deniz@example.com"; $0.password = PasswordGenerator.generate(); $0.url = "accounts.google.com" }
        kayit(.login, "Ev Wi-Fi", quick: true, etiket: [ev]) {
            $0.password = PasswordGenerator.generate()
            $0.custom = [CustomField(label: "Ağ adı (SSID)", value: "EvAgi_5G")]
        }
        kayit(.login, "Northwind Bank", sira: 1, etiket: [banka]) { $0.username = "48213907"; $0.password = "604918"; $0.url = "Northwind mobil" }
        kayit(.card, "Visa · günlük", etiket: [banka]) { $0.cardholder = "DENIZ YILMAZ"; $0.cardNumber = "4242424242424242"; $0.expiry = "0829"; $0.cvv = "123" }
        kayit(.everyday, "Kimlik ve adres", etiket: [ev]) { $0.fullName = "Deniz Yılmaz"; $0.email = "deniz@example.com"; $0.address = "Demo Sok. No: 7, Kadıköy / İstanbul" }
        kayit(.note, "Deploy anahtarı (ed25519)", kind: .anahtar, etiket: [is_, gelistirici]) {
            $0.notes = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END OPENSSH PRIVATE KEY-----"
            $0.custom = [CustomField(label: "parmak izi", value: "SHA256:hcm3lXKROlj0")]
        }
        kayit(.note, "prod.env · API anahtarları", kind: .yapilandirma, etiket: [is_, gelistirici]) {
            $0.notes = "DATABASE_URL=postgres://app:\(PasswordGenerator.generate())@db.internal:5432/app\nSTRIPE_SECRET_KEY=sk_test_example"
        }
        kayit(.note, "Yedek kodlar · Google", kind: .kurtarma) { $0.notes = "4821 9930\n1187 2045\n6390 7712" }
    }
    #endif
}

import CryptoKit
import Foundation
import Observation
import SekuvoCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Kasanın durumu — Android'deki `ui/VaultViewModel.kt`'nin karşılığı.
///
/// Kayıt mantığı `SekuvoCore.VaultStore`'da (macOS'ta sınanır); burası yalnız
/// arayüz durumunu ve PBKDF2 gibi ağır işlerin ana iş parçacığı dışına
/// taşınmasını tutar. `dataKey` yalnız kilit açıkken bellekte durur; uygulama
/// arka plana geçince kasa kilitlenir (Android'de ekranın kararması).
@Observable
@MainActor
final class VaultModel {

    enum State { case setup, locked, unlocked }

    /// İçe aktarmanın kaynağı: hata ve başarı metni buna göre seçilir.
    enum ImportSource { case qr, text, file }

    private(set) var state: State
    private(set) var items: [VaultStore.Item] = []
    private(set) var busy = false
    var error: String?
    var privacyCover = false
    /// Kısa bildirim (Android'deki Toast).
    var toast: String?
    /// Kurulum "yedeğim var" ile yapıldı: liste açılınca dosya seçici gelir.
    var restoreAfterSetup = false

    private let keys: KeyManager
    private let storeFile: URL
    private var store: VaultStore?

    init() {
        let kok = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sekuvo", isDirectory: true)
        keys = KeyManager(file: kok.appendingPathComponent("keys.json"))
        storeFile = kok.appendingPathComponent("vault.sqlite")
        state = keys.isInitialized ? .locked : .setup
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
            let ham = try await Task.detached { try k.setup(password: password).withUnsafeBytes { Data($0) } }.value
            restoreAfterSetup = restoreAfter
            try open(with: SymmetricKey(data: ham))
        } catch {
            self.error = error.localizedDescription
        }
    }

    func unlock(password: String) async {
        busy = true; defer { busy = false }
        let k = keys
        // PBKDF2 (310.000 tur) arayüzü dondurmasın.
        let ham = await Task.detached { k.unlock(password: password)?.withUnsafeBytes { Data($0) } }.value
        guard let ham else { error = L("vm_wrong_password"); return }
        do { try open(with: SymmetricKey(data: ham)) } catch { self.error = error.localizedDescription }
    }

    private func open(with key: SymmetricKey) throws {
        store = try VaultStore(file: storeFile, key: key)
        error = nil
        try reload()
        state = .unlocked
    }

    func lock() {
        guard state == .unlocked else { return }
        store = nil
        items = []
        state = .locked
    }

    /// Sahne etkin değilken perde iner (uygulama değiştirici anlık görüntüsü),
    /// arka planda kasa kilitlenir.
    func sceneChanged(_ faz: ScenePhase) {
        switch faz {
        case .active: privacyCover = false
        case .inactive: privacyCover = state == .unlocked
        case .background:
            privacyCover = state == .unlocked
            lock()
        @unknown default: break
        }
    }

    // MARK: Kayıtlar

    private func reload() throws {
        guard let store else { return }
        items = try store.items().sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
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
        toast = mesaj
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.5))
            if toast == mesaj { toast = nil }
        }
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
        if state == .setup { await setup(password: parola, again: parola) } else { await unlock(password: parola) }
        guard state == .unlocked, items.isEmpty else { return }
        func kayit(_ tur: EntryType, _ baslik: String, kind: NoteKind = .genel, quick: Bool = false,
                   sira: Int = 0, _ doldur: (inout EntryData) -> Void) {
            var d = EntryData(); doldur(&d)
            save(DecryptedEntry(type: tur, title: baslik, data: d, createdAt: 0, updatedAt: 0,
                                quick: quick, noteKind: kind, sortIndex: sira))
        }
        kayit(.login, "GitHub") { $0.username = "deniz.dev@example.com"; $0.password = PasswordGenerator.generate(); $0.url = "github.com" }
        kayit(.login, "Google hesabı") { $0.username = "deniz@example.com"; $0.password = PasswordGenerator.generate(); $0.url = "accounts.google.com" }
        kayit(.login, "Ev Wi-Fi", quick: true) {
            $0.password = PasswordGenerator.generate()
            $0.custom = [CustomField(label: "Ağ adı (SSID)", value: "EvAgi_5G")]
        }
        kayit(.login, "Northwind Bank", sira: 1) { $0.username = "48213907"; $0.password = "604918"; $0.url = "Northwind mobil" }
        kayit(.card, "Visa · günlük") { $0.cardholder = "DENIZ YILMAZ"; $0.cardNumber = "4242424242424242"; $0.expiry = "0829"; $0.cvv = "123" }
        kayit(.everyday, "Kimlik ve adres") { $0.fullName = "Deniz Yılmaz"; $0.email = "deniz@example.com"; $0.address = "Demo Sok. No: 7, Kadıköy / İstanbul" }
        kayit(.note, "Deploy anahtarı (ed25519)", kind: .anahtar) {
            $0.notes = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END OPENSSH PRIVATE KEY-----"
            $0.custom = [CustomField(label: "parmak izi", value: "SHA256:hcm3lXKROlj0")]
        }
        kayit(.note, "prod.env · API anahtarları", kind: .yapilandirma) {
            $0.notes = "DATABASE_URL=postgres://app:\(PasswordGenerator.generate())@db.internal:5432/app\nSTRIPE_SECRET_KEY=sk_test_example"
        }
        kayit(.note, "Yedek kodlar · Google", kind: .kurtarma) { $0.notes = "4821 9930\n1187 2045\n6390 7712" }
    }
    #endif
}

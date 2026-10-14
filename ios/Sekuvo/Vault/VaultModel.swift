import CryptoKit
import Foundation
import Observation
import SekuvoCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Kasanın durumu ve kayıtları — Android'deki `ui/VaultViewModel.kt`'nin karşılığı.
///
/// `dataKey` yalnız kilit açıkken bellekte durur. Uygulama ekrandan çıkınca
/// (arka plan) kasa kilitlenir ve anahtar bırakılır; Android'de ekranın
/// kararması neyse burada sahnenin arka plana geçmesi odur.
@Observable
@MainActor
final class VaultModel {

    enum State { case setup, locked, unlocked }

    /// Listede gösterilen satır: şifre çözmeden okunabilen alanlar.
    struct Item: Identifiable, Equatable {
        let id: Int64
        let type: EntryType
        let title: String
        let noteKind: NoteKind
        let quick: Bool
        let sortIndex: Int
        let updatedAt: Int64
    }

    private(set) var state: State
    private(set) var items: [Item] = []
    private(set) var busy = false
    var error: String?
    var privacyCover = false
    /// Kısa bildirim (Android'deki Toast).
    var toast: String?

    private let keys: KeyManager
    private let storeFile: URL
    private var store: EntryStore?
    private var dataKey: SymmetricKey?

    init() {
        let kok = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sekuvo", isDirectory: true)
        keys = KeyManager(file: kok.appendingPathComponent("keys.json"))
        storeFile = kok.appendingPathComponent("vault.sqlite")
        state = keys.isInitialized ? .locked : .setup
    }

    // MARK: Kurulum ve kilit

    /// İlk kurulum. Kural Android'deki gibi: en az 8 karakter, iki kez aynı.
    func setup(password: String, again: String) async {
        guard password.count >= 8 else { error = L("vm_master_password_too_short"); return }
        guard password == again else { error = L("vm_passwords_mismatch"); return }
        busy = true; defer { busy = false }
        let k = keys
        do {
            let ham = try await Task.detached { try k.setup(password: password).withUnsafeBytes { Data($0) } }.value
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
        store = try EntryStore(file: storeFile)
        dataKey = key
        error = nil
        try reload()
        state = .unlocked
    }

    func lock() {
        guard state == .unlocked else { return }
        dataKey = nil
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
        items = try store.all().filter { !$0.anchor }.map { r in
            Item(id: r.id, type: .of(r.type), title: r.title, noteKind: .of(r.noteKind),
                 quick: r.quick, sortIndex: r.sortIndex, updatedAt: r.updatedAt)
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    /// Kaydı çözer; yanlış anahtar ya da bozuk kayıt `nil`.
    func load(id: Int64) -> DecryptedEntry? {
        guard let store, let dataKey, let r = try? store.get(id: id),
              let acik = Crypto.decrypt(r.blob, key: dataKey),
              let json = (try? JSONSerialization.jsonObject(with: acik)) as? [String: Any]
        else { return nil }
        return DecryptedEntry(id: r.id, type: .of(r.type), title: r.title, data: EntryData(json: json),
                              createdAt: r.createdAt, updatedAt: r.updatedAt, quick: r.quick,
                              noteKind: .of(r.noteKind), sortIndex: r.sortIndex, anchor: r.anchor)
    }

    /// Yeni kayıt (`id == 0`) ya da güncelleme. Parola değiştiyse eskisi
    /// tarihiyle geçmişe girer (Android: `withNewPassword`).
    @discardableResult
    func save(_ e: DecryptedEntry) -> Int64? {
        guard let store, let dataKey else { error = L("vm_vault_locked_retry"); return nil }
        let simdi = Int64(Date().timeIntervalSince1970 * 1000)
        var veri = e.data
        if e.id != 0, let eski = load(id: e.id) {
            if eski.data.password != veri.password {
                if !eski.data.password.isEmpty {
                    veri.passwordHistory.insert(OldPassword(value: eski.data.password, changedAt: simdi), at: 0)
                }
                veri.passwordChangedAt = simdi
            }
        } else if !veri.password.isEmpty {
            veri.passwordChangedAt = simdi
        }
        do {
            let json = try JSONSerialization.data(withJSONObject: veri.json())
            let blob = try Crypto.encrypt(json, key: dataKey)
            var satir = EntryStore.Row(id: e.id, type: e.type.rawValue, title: e.title, blob: blob,
                                       createdAt: e.id == 0 ? simdi : e.createdAt, updatedAt: simdi,
                                       quick: e.quick, noteKind: e.noteKind.rawValue,
                                       sortIndex: e.sortIndex, anchor: e.anchor)
            if e.id == 0 {
                satir.id = try store.insert(satir)
            } else {
                try store.update(satir)
            }
            try reload()
            return satir.id
        } catch {
            self.error = error.localizedDescription
            return nil
        }
    }

    /// Panoya yalnız bu cihaz için ve 45 saniyelik koyar: `localOnly`
    /// Universal Clipboard'u kapatır, `expirationDate` silmeyi sistemin
    /// kendisine bırakır — Android'in tutamadığı 45 sn sözünü iOS platform
    /// olarak tutar (SEC-038'in zorunlu sertleştirmesi).
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

    func delete(id: Int64) {
        guard let store else { return }
        do { try store.delete(id: id); try reload() } catch { self.error = error.localizedDescription }
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

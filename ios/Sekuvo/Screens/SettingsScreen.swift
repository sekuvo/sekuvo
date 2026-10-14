import SekuvoCore
import SwiftUI
import UniformTypeIdentifiers

/// Ayarlar — şimdilik yedek bölümü: şifreli yedek al, yedekten geri yükle,
/// metinden içe aktar (Android'deki `SettingsScreen.kt`'nin aynı bölümü).
struct SettingsScreen: View {
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var parolaSor = false
    @State private var belge: VaultbakDocument?
    @State private var disaAktar = false
    @State private var geriYukle = false
    @State private var metinden = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(L("settings_backup_desc")).font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                    Button { parolaSor = true } label: { Label(L("settings_backup_export"), systemImage: "square.and.arrow.down.on.square") }
                    Button { geriYukle = true } label: { Label(L("settings_backup_restore"), systemImage: "clock.arrow.circlepath") }
                    Button { metinden = true } label: { Label(L("settings_import_paste"), systemImage: "doc.on.clipboard") }
                } header: {
                    Text(L("settings_backup"))
                }
                Section {
                    LabeledContent(L("info_about_version"), value: "\(Bundle.main.surum) · \(L("ios_preview_badge"))")
                    Link(destination: URL(string: "https://github.com/sekuvo/sekuvo")!) {
                        Label(L("info_about_source"), systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(L("settings_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("home_close")) { kapat() } }
            }
            .sheet(isPresented: $parolaSor) {
                NavigationStack {
                    BackupPasswordSheet { veri in
                        belge = VaultbakDocument(data: veri)
                        disaAktar = true
                    }
                }
            }
            .fileExporter(isPresented: $disaAktar, document: belge, contentType: .data,
                          defaultFilename: VaultbakDocument.defaultName) { sonuc in
                kasa.backupWritten(sonuc)
                belge = nil
            }
            .restoreFlow(isPresented: $geriYukle)
            .sheet(isPresented: $metinden) {
                NavigationStack { ImportSheet(source: .text) }
            }
        }
    }
}

/// Yedek parolası penceresi (Android: "Yedek parolası belirle").
struct BackupPasswordSheet: View {
    let onReady: (Data) -> Void
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var parola = ""
    @State private var tekrar = ""

    var body: some View {
        Form {
            Section {
                Text(L("settings_export_pw_desc")).font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                SecureFieldRow(placeholder: L("settings_label_password"), text: $parola, contentType: .sekuvoNone)
                SecureFieldRow(placeholder: L("settings_label_password_again"), text: $tekrar, contentType: .sekuvoNone)
            } footer: {
                if let hata = kasa.error { Text(hata).foregroundStyle(Theme.error) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L("settings_export_pw_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("settings_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    let p = parola, t = tekrar
                    Task {
                        if let veri = await kasa.makeBackup(password: p, again: t) {
                            kapat()
                            onReady(veri)
                        }
                    }
                } label: {
                    if kasa.busy { ProgressView() } else { Text(L("settings_ok")) }
                }
                .disabled(kasa.busy || parola.isEmpty)
            }
        }
        .onAppear { kasa.error = nil }
    }
}

/// `.vaultbak` dosyası: şifreli zarfın baytları, olduğu gibi.
struct VaultbakDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }

    static var defaultName: String {
        "\(L("ios_backup_file_name"))-\(Date().formatted(.iso8601.year().month().day())).vaultbak"
    }
}

/// Yedekten geri yükleme akışı: dosya seç → parola ve kip → uygula.
/// Ayarlar'dan ve "yedeğim var" kurulumundan aynı yolla çağrılır.
struct RestoreFlow: ViewModifier {
    @Binding var isPresented: Bool
    @Environment(VaultModel.self) private var kasa
    @State private var secilen: SelectedFile?

    struct SelectedFile: Identifiable {
        let id = UUID()
        let data: Data
    }

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $isPresented, allowedContentTypes: [.data, .json, .item]) { sonuc in
                guard case .success(let url) = sonuc else { return }
                let erisim = url.startAccessingSecurityScopedResource()
                defer { if erisim { url.stopAccessingSecurityScopedResource() } }
                // Yedek olamayacak kadar büyük dosya belleği doldurmasın.
                let boyut = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                guard boyut <= Backup.maxEnvelopeBytes, let veri = try? Data(contentsOf: url) else {
                    kasa.error = L(boyut > Backup.maxEnvelopeBytes ? "vm_invalid_backup_file" : "vm_file_open_failed")
                    if let e = kasa.error { kasa.show(e) }
                    return
                }
                secilen = SelectedFile(data: veri)
            }
            .sheet(item: $secilen) { s in
                NavigationStack { ImportSheet(source: .file, envelope: s.data) }
            }
    }
}

extension View {
    func restoreFlow(isPresented: Binding<Bool>) -> some View { modifier(RestoreFlow(isPresented: isPresented)) }
}

extension Bundle {
    var surum: String {
        let v = object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }
}

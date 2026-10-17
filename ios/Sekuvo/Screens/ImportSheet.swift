import SekuvoCore
import SwiftUI

/// Şifreli zarfı içe aktarma penceresi — Android'deki `PasteImportDialog`
/// (QR'dan ya da metinden) ve Ayarlar'daki geri yükleme penceresi tek yerde.
///
/// - QR: zarf hazır gelir; yalnız aktarım (yedek) parolası istenir.
/// - Metin: zarf yapıştırılır; dostça ön tanı (bağlantı, zarf olmayan metin).
/// - Dosya: `.vaultbak`; "mevcuta ekle" ya da uyarıyla "tümünü değiştir".
struct ImportSheet: View {
    let source: VaultModel.ImportSource
    var envelope: Data? = nil
    var onDone: () -> Void = {}

    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var metin = ""
    @State private var parola = ""
    @State private var degistir = false
    @FocusState private var parolaOdak: Bool

    var body: some View {
        @Bindable var kasa = kasa
        Form {
            switch source {
            case .qr:
                Section {
                    Text(qrHazir).font(.callout)
                }
            case .text:
                Section {
                    Text(L("paste_intro")).font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                    TextField(L("paste_label_envelope"), text: $metin, axis: .vertical)
                        .lineLimit(3...8)
                        .font(.footnote.monospaced())
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    HStack {
                        // Sistem yapıştırma düğmesi: izin sorusu çıkmadan panodan alır.
                        PasteButton(payloadType: String.self) { gelen in
                            if let ilk = gelen.first { metin = ilk; parolaOdak = true }
                        }
                        .labelStyle(.titleAndIcon)
                        Spacer()
                        if !metin.isEmpty {
                            Text(L("paste_char_count", metin.count)).font(.caption).foregroundStyle(Theme.onSurfaceVariant)
                        }
                    }
                }
            case .file:
                Section {
                    Picker(L("settings_backup_restore"), selection: $degistir) {
                        Text(L("settings_import_merge")).tag(false)
                        Text(L("settings_import_replace")).tag(true)
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    if degistir { Text(L("settings_import_replace_warning")).foregroundStyle(Theme.error) }
                }
            }

            Section {
                SecureFieldRow(placeholder: L(source == .file ? "settings_label_backup_password" : "paste_label_backup_password"),
                               text: $parola, contentType: .sekuvoNone)
                    .focused($parolaOdak)
                    .submitLabel(.go)
                    .onSubmit(aktar)
            } footer: {
                if let hata = kasa.error { Text(hata).foregroundStyle(Theme.error) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(baslik)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("paste_btn_cancel")) { kapat() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(action: aktar) {
                    if kasa.busy { ProgressView() } else {
                        Text(L(source == .file ? "settings_import_confirm" : "paste_btn_import"))
                    }
                }
                .disabled(kasa.busy || parola.isEmpty || (source == .text && metin.isEmpty))
            }
        }
        .interactiveDismissDisabled(kasa.busy)
        .onAppear {
            kasa.error = nil
            if source != .text { parolaOdak = true }
        }
        #if DEBUG
        .task {
            // `-ornekQrOtomatik`: simülatörde dokunmadan uçtan uca doğrulama.
            let a = ProcessInfo.processInfo.arguments
            if source == .qr, a.contains("-ornekQrOtomatik") {
                parola = "ornek-aktarim-2026"
                try? await Task.sleep(for: .seconds(1.2))
                aktar()
            }
            if source == .file, let i = a.firstIndex(of: "-ornekGeriYukleParola"), i + 1 < a.count {
                parola = a[i + 1]
                degistir = a.contains("-ornekDegistir")
                try? await Task.sleep(for: .seconds(1.5))
                aktar()
            }
        }
        #endif
    }

    private var baslik: String {
        switch source {
        case .qr: L("paste_title_qr")
        case .text: L("paste_title_text")
        case .file: L("settings_backup_restore")
        }
    }

    /// Android metnindeki **kalın** işaretleri iOS'ta gerçekten kalın çizilir.
    private var qrHazir: AttributedString {
        let ham = L("paste_qr_ready", envelope.map { String(decoding: $0, as: UTF8.self).count } ?? 0)
        let secenek = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: ham, options: secenek)) ?? AttributedString(ham)
    }

    private func aktar() {
        guard !kasa.busy else { return }
        let zarf: Data
        if source == .text {
            if let sorun = kasa.pasteProblem(metin) { kasa.error = sorun; return }
            zarf = Data(Backup.normalize(metin).utf8)
        } else {
            guard let envelope else { return }
            zarf = envelope
        }
        let p = parola, d = source == .file && degistir
        Task {
            if await kasa.importEnvelope(zarf, password: p, replace: d, source: source) {
                parola = ""
                kapat()
                onDone()
            }
        }
    }
}

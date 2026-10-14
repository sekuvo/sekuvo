import SwiftUI

/// Karşılama (ilk kurulum) ve kilit ekranı — Android'deki `UnlockScreen.kt`.
struct UnlockScreen: View {
    let setup: Bool
    @Environment(VaultModel.self) private var kasa
    @State private var parola = ""
    @State private var tekrar = ""
    @FocusState private var odak: Bool

    var body: some View {
        @Bindable var kasa = kasa
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Spacer(minLength: 56)
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(Theme.primary)
                VStack(alignment: .leading, spacing: 6) {
                    Text(L(setup ? "unlock_welcome_title" : "unlock_locked_title"))
                        .font(.largeTitle.weight(.semibold))
                        .foregroundStyle(Theme.onSurface)
                    if setup {
                        Text(L("unlock_slogan")).font(.title3).foregroundStyle(Theme.primary)
                    }
                }
                Text(L(setup ? "unlock_setup_desc" : "unlock_enter_desc"))
                    .font(.body)
                    .foregroundStyle(Theme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                SecretInput(label: L(setup ? "unlock_master_password_label" : "unlock_password_label"), text: $parola)
                    .focused($odak)
                    .submitLabel(setup ? .next : .go)
                    .onSubmit { if !setup { gonder() } }
                if setup {
                    SecretInput(label: L("unlock_password_again_label"), text: $tekrar)
                        .submitLabel(.go)
                        .onSubmit(gonder)
                }

                if let hata = kasa.error {
                    Text(hata).font(.callout).foregroundStyle(Theme.error)
                }

                Button(action: gonder) {
                    HStack {
                        if kasa.busy { ProgressView().tint(Theme.onPrimaryContainer) }
                        Text(L(setup ? "unlock_create_vault" : "unlock_open_button"))
                    }
                    .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(FilledButton())
                .disabled(kasa.busy || parola.isEmpty)

                if !setup {
                    Label(L("ios_locked_hint"), systemImage: "eye.slash")
                        .font(.footnote)
                        .foregroundStyle(Theme.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { odak = true }
    }

    private func gonder() {
        let p = parola, t = tekrar
        Task {
            if setup { await kasa.setup(password: p, again: t) } else { await kasa.unlock(password: p) }
            if kasa.state == .unlocked { parola = ""; tekrar = "" }
        }
    }
}

/// Göster/gizle düğmeli parola alanı (Android: `SecretField`). Varsayılan gizli.
struct SecretInput: View {
    let label: String
    @Binding var text: String
    /// Ana parola için bilerek `nil`: `.password` ipucu iOS'un "Parolalar"
    /// önerisini açar ve ana parolayı iCloud'a kaydetmeye davet eder — ağsız
    /// bir kasanın anahtarı buluta gitmemeli.
    var contentType: UITextContentType? = nil
    @State private var gorunur = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.footnote.weight(.medium)).foregroundStyle(Theme.onSurfaceVariant)
            HStack {
                Group {
                    if gorunur {
                        TextField("", text: $text)
                    } else {
                        SecureField("", text: $text)
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(contentType)
                .font(.body.monospaced())
                Button { gorunur.toggle() } label: {
                    Image(systemName: gorunur ? "eye.slash" : "eye")
                }
                .accessibilityLabel(L(gorunur ? "main_hide" : "main_show"))
                .foregroundStyle(Theme.onSurfaceVariant)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.outline, lineWidth: 1))
        }
    }
}

/// Dolu düğme: altın yüzey + koyu yazı (Android: `vaultButtonColors`).
struct FilledButton: ButtonStyle {
    @Environment(\.isEnabled) private var etkin

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Theme.onPrimaryContainer)
            .background(Theme.primaryContainer.opacity(etkin ? (configuration.isPressed ? 0.8 : 1) : 0.45))
            .clipShape(RoundedRectangle(cornerRadius: 25))
    }
}

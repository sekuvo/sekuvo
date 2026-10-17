import SekuvoCore
import SwiftUI

/// İkinci kapı — Android'deki `PinScreen.kt`. Face ID'den sonra PIN istenir;
/// ana parola bu ekrandan bağımsız olarak her zaman tek başına açar.
struct PinScreen: View {
    @Environment(VaultModel.self) private var kasa
    @State private var pin = ""
    @FocusState private var odak: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Spacer(minLength: 56)
                Image(systemName: "lock.rotation")
                    .font(.system(size: 48))
                    .foregroundStyle(Theme.primary)
                Text(L("unlock_pin_title"))
                    .font(.largeTitle.weight(.semibold))
                    .foregroundStyle(Theme.onSurface)
                Text(L("ios_pin_unlock_desc", kasa.biometryName))
                    .foregroundStyle(Theme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("unlock_pin_label", PinLock.minLength, PinLock.maxLength))
                        .font(.footnote.weight(.medium)).foregroundStyle(Theme.onSurfaceVariant)
                    SecureField("", text: Binding(
                        get: { pin },
                        set: { pin = String($0.filter { $0.isASCII && $0.isNumber }.prefix(PinLock.maxLength)) }))
                        .keyboardType(.numberPad)
                        .textContentType(.sekuvoNone)
                        .font(.title2.monospaced())
                        .focused($odak)
                        .padding(.horizontal, 14).frame(minHeight: 52)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.outline, lineWidth: 1))
                }
                if let hata = kasa.error {
                    Text(hata).font(.callout).foregroundStyle(Theme.error)
                }
                Button(action: gonder) {
                    HStack {
                        if kasa.busy { ProgressView().tint(Theme.onPrimaryContainer) }
                        Text(L("unlock_pin_open"))
                    }
                }
                .buttonStyle(FilledButton())
                .disabled(kasa.busy || !PinLock.isValid(pin))
                Button(L("unlock_pin_cancel")) { kasa.cancelPin() }
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(Theme.primary)
            }
            .padding(.horizontal, 24)
        }
        .background(Theme.background.ignoresSafeArea())
        .onAppear { odak = true }
        #if DEBUG
        .task {
            // `-ornekPinGir <pin>`: simülatörde dokunmadan PIN aşamasını sınamak için.
            let a = ProcessInfo.processInfo.arguments
            if let i = a.firstIndex(of: "-ornekPinGir"), i + 1 < a.count {
                pin = a[i + 1]
                try? await Task.sleep(for: .seconds(1))
                gonder()
            }
            // `-ornekPinVazgec`: "Vazgeç"e basılmış gibi.
            if a.contains("-ornekPinVazgec") {
                try? await Task.sleep(for: .seconds(1))
                kasa.cancelPin()
            }
        }
        #endif
    }

    private func gonder() {
        let p = pin
        Task {
            await kasa.unlockWithPin(p)
            pin = ""
        }
    }
}

/// Biyometri önerisi: kurulumdan sonra ya da PIN değişince (Android:
/// "Parmak izi ile aç" penceresi). PIN açıksa kayıt o PIN'le mühürlenir.
struct BiometricOfferSheet: View {
    let pin: String?
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: kasa.biometry == .touchID ? "touchid" : (kasa.biometry == .opticID ? "opticid" : "faceid"))
                .font(.system(size: 44)).foregroundStyle(Theme.primary)
            Text(pin == nil ? L("ios_unlock_biometric", kasa.biometryName) : L("ios_biometric_pin_title", kasa.biometryName))
                .font(.title2.weight(.semibold)).foregroundStyle(Theme.onSurface)
            Text(pin == nil ? L("ios_biometric_offer_body", kasa.biometryName) : L("ios_biometric_pin_desc", kasa.biometryName))
                .foregroundStyle(Theme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            if let hata = kasa.error { Text(hata).font(.callout).foregroundStyle(Theme.error) }
            Spacer(minLength: 0)
            Button {
                Task { if await kasa.enableBiometric(pin: pin) { kapat() } }
            } label: { Text(L("main_enable")) }
                .buttonStyle(FilledButton())
                .disabled(kasa.busy)
            Button(L("main_password_for_now")) { kapat() }
                .frame(maxWidth: .infinity)
                .foregroundStyle(Theme.primary)
        }
        .padding(24)
        .background(Theme.background.ignoresSafeArea())
        .presentationDetents([.medium])
        .onAppear { kasa.error = nil }
        #if DEBUG
        .task {
            if ProcessInfo.processInfo.arguments.contains("-ornekBiyometriKabul") {
                try? await Task.sleep(for: .seconds(1))
                if await kasa.enableBiometric(pin: pin) { kapat() }
            }
        }
        #endif
    }
}

/// PIN'i açma ya da kapatma penceresi. Açarken ana parola (doğrulama) + PIN
/// iki kez; kapatırken yalnız ana parola istenir.
struct PinSetupSheet: View {
    let disabling: Bool
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var parola = ""
    @State private var pin = ""
    @State private var tekrar = ""

    var body: some View {
        Form {
            Section {
                Text(L(disabling ? "ios_pin_disable_desc" : "ios_pin_set_desc", kasa.biometryName))
                    .font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                SecureFieldRow(placeholder: L("settings_label_password"), text: $parola, contentType: .sekuvoNone)
                if !disabling {
                    SecureFieldRow(placeholder: L("settings_label_pin"), text: $pin, keyboard: .numberPad, contentType: .sekuvoNone)
                    SecureFieldRow(placeholder: L("settings_label_pin_again"), text: $tekrar, keyboard: .numberPad, contentType: .sekuvoNone)
                }
            } footer: {
                if let hata = kasa.error { Text(hata).foregroundStyle(Theme.error) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L(disabling ? "settings_pin_turn_off" : "settings_pin_set"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("settings_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    let p = parola, n = pin, t = tekrar
                    Task {
                        let tamam = disabling ? await kasa.disablePin(password: p)
                                              : await kasa.enablePin(password: p, pin: n, again: t)
                        if tamam { kapat() }
                    }
                } label: {
                    if kasa.busy { ProgressView() } else { Text(L("settings_ok")) }
                }
                .disabled(kasa.busy || parola.isEmpty || (!disabling && pin.isEmpty))
            }
        }
        .onAppear { kasa.error = nil }
    }
}

/// PIN açıkken biyometriyi etkinleştirmek için PIN sorulur (Android:
/// "Parmak izini PIN'e bağla").
struct BiometricPinSheet: View {
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var pin = ""

    var body: some View {
        Form {
            Section {
                Text(L("ios_biometric_pin_desc", kasa.biometryName)).font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                SecureFieldRow(placeholder: L("settings_label_pin"), text: $pin, keyboard: .numberPad, contentType: .sekuvoNone)
            } footer: {
                if let hata = kasa.error { Text(hata).foregroundStyle(Theme.error) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L("ios_biometric_pin_title", kasa.biometryName))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("settings_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("settings_ok")) {
                    let p = pin
                    Task { if await kasa.enableBiometric(pin: p) { kapat() } }
                }
                .disabled(kasa.busy || !PinLock.isValid(pin))
            }
        }
        .onAppear { kasa.error = nil }
    }
}

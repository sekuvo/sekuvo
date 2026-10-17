import SekuvoCore
import SwiftUI

/// Şifre üretici (Android: `GeneratorDialog`): uzunluk 8–64, beş seçenek,
/// yaklaşık güç. `onUse` verilirse "Kullan" alanı doldurur; "Kopyala" her
/// durumda var. Her ayar değişikliği yeni bir parola üretir.
struct GeneratorSheet: View {
    /// Pano bildiriminde görünen ad: alanın adı, yoksa "Şifre".
    var copyLabel = L("field_password")
    var onUse: ((String) -> Void)? = nil

    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var secenek = PasswordGenerator.Options()
    @State private var parola = PasswordGenerator.generate()

    private var entropi: Int { PasswordGenerator.entropyBits(secenek) }

    var body: some View {
        Form {
            Section {
                // Beş satırlık sabit yer: en uzun parola (64 karakter) sığar,
                // uzunluk kaydırılırken pencere zıplamaz (Android'deki gerekçe).
                Text(parola)
                    .font(.body.monospaced())
                    .foregroundStyle(Theme.onSurface)
                    .lineLimit(5, reservesSpace: true)
                    .textSelection(.disabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 14) {
                    Text(L("unlock_generator_summary", secenek.length, entropi, L(gucAnahtari)))
                        .font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                    Spacer(minLength: 0)
                    Button { kasa.copy(label: copyLabel, value: parola) } label: { Image(systemName: "doc.on.doc") }
                        .accessibilityLabel(L("unlock_generator_copy"))
                    Button { parola = PasswordGenerator.generate(secenek) } label: { Image(systemName: "arrow.clockwise") }
                        .accessibilityLabel(L("unlock_generator_refresh"))
                }
                .buttonStyle(.borderless)
                .tint(Theme.primary)
                Slider(value: Binding(get: { Double(secenek.length) }, set: { secenek.length = Int($0.rounded()) }),
                       in: 8...64, step: 1)
                    .tint(Theme.primary)
            }
            Section {
                Toggle(isOn: $secenek.upper) { Text(verbatim: "A-Z") }
                Toggle(isOn: $secenek.lower) { Text(verbatim: "a-z") }
                Toggle(isOn: $secenek.digits) { Text(verbatim: "0-9") }
                Toggle(isOn: $secenek.symbols) { Text(verbatim: "!@#") }
                Toggle(L("unlock_generator_avoid_ambiguous"), isOn: $secenek.avoidAmbiguous)
            }
            .tint(Theme.primary)
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L("unlock_generator_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("unlock_generator_close")) { kapat() } }
            if let onUse {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("unlock_generator_use")) { onUse(parola); kapat() }
                }
            }
        }
        .onChange(of: secenek) { _, yeni in parola = PasswordGenerator.generate(yeni) }
    }

    /// Android ile aynı eşikler: 90 bit ve üstü çok güçlü, 60 ve üstü güçlü.
    private var gucAnahtari: String {
        entropi >= 90 ? "unlock_generator_very_strong" : (entropi >= 60 ? "unlock_generator_strong" : "unlock_generator_weak")
    }
}

import SekuvoCore
import SwiftUI

/// Yumuşak renkli kapsül içinde tür ikonu (Android: `TypeBadge`).
struct TypeBadge: View {
    let type: EntryType
    var kind: NoteKind = .genel
    var size: CGFloat = 42

    var body: some View {
        Image(systemName: type == .note ? kind.symbol : type.symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(type.color)
            .frame(width: size, height: size)
            .background(type.color.opacity(0.16), in: RoundedRectangle(cornerRadius: size / 3))
    }
}

/// Kurum logosu (Android: `BrandBadge`): marka renginde kapsül; logolu
/// markada beyaz (açık renkli markada koyu) logo, logosuzda 1–3 harflik rozet.
struct BrandBadge: View {
    let brand: Brand
    var size: CGFloat = 42

    private var zemin: Color {
        Color(red: Double((brand.color >> 16) & 0xFF) / 255, green: Double((brand.color >> 8) & 0xFF) / 255,
              blue: Double(brand.color & 0xFF) / 255)
    }
    /// Göreli parlaklık (sRGB yaklaşık): açık zeminde koyu yazı.
    private var acik: Bool {
        let r = Double((brand.color >> 16) & 0xFF) / 255, g = Double((brand.color >> 8) & 0xFF) / 255, b = Double(brand.color & 0xFF) / 255
        return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6
    }

    var body: some View {
        let on: Color = acik ? Color(white: 0.1) : .white
        ZStack {
            RoundedRectangle(cornerRadius: size / 3).fill(zemin)
            RoundedRectangle(cornerRadius: size / 3).strokeBorder(Theme.outline.opacity(0.35), lineWidth: 1)
            if brand.hasLogo {
                Image("Markalar/marka-\(brand.id)")
                    .resizable().scaledToFit()
                    .foregroundStyle(on)
                    .frame(width: size * 0.52, height: size * 0.52)
            } else {
                Text(verbatim: brand.badge)
                    .font(.system(size: size * (brand.badge.count == 1 ? 0.46 : brand.badge.count == 2 ? 0.36 : 0.28), weight: .bold))
                    .foregroundStyle(on)
                    .lineLimit(1).minimumScaleFactor(0.6)
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel(brand.name)
    }
}

/// Logosuz seçim ("Logo yok") için boş kapsül.
struct EmptyBrandBadge: View {
    var size: CGFloat = 36
    var symbol = "nosign"
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .medium))
            .foregroundStyle(Theme.onSurfaceVariant)
            .frame(width: size, height: size)
            .background(Theme.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: size / 3))
    }
}

/// Düzenleme ekranındaki "Logo" satırı ve seçici (Android: `BrandRow`).
/// `choice`: "" otomatik, `none` yok, ya da katalog kimliği.
struct BrandRow: View {
    @Binding var choice: String
    let auto: Brand?
    @State private var acik = false

    var body: some View {
        let secili = Brands.byId(choice)
        let gosterilen = choice == Brands.none ? nil : (secili ?? auto)
        Button { acik = true } label: {
            HStack(spacing: 12) {
                if let gosterilen { BrandBadge(brand: gosterilen, size: 36) } else { EmptyBrandBadge() }
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("edit_brand_title")).foregroundStyle(Theme.onSurface)
                    Text(choice == Brands.none ? L("edit_brand_none")
                         : secili?.name ?? (auto.map { L("edit_brand_auto_found", $0.name) } ?? L("edit_brand_auto_none")))
                        .font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
            }
        }
        .sheet(isPresented: $acik) {
            NavigationStack { BrandPickerSheet(choice: $choice, auto: auto) }
        }
    }
}

/// Arama kutulu marka listesi: Otomatik, Logo yok, sonra katalog (ada göre).
struct BrandPickerSheet: View {
    @Binding var choice: String
    let auto: Brand?
    @Environment(\.dismiss) private var kapat
    @State private var arama = ""

    var body: some View {
        let q = Brands.normalize(arama)
        let liste = Brands.catalog
            .filter { b in q.isEmpty || Brands.normalize(b.name).contains(q)
                || b.domains.contains { $0.contains(arama.trimmingCharacters(in: .whitespaces).lowercased()) } }
            .sorted { Brands.normalize($0.name) < Brands.normalize($1.name) }
        List {
            if q.isEmpty {
                satir(secili: choice.isEmpty || (choice != Brands.none && Brands.byId(choice) == nil),
                      metin: auto.map { L("edit_brand_auto_found", $0.name) } ?? L("edit_brand_auto")) {
                    if let auto { BrandBadge(brand: auto, size: 32) } else { EmptyBrandBadge(size: 32, symbol: "sparkles") }
                } secim: { choice = "" }
                satir(secili: choice == Brands.none, metin: L("edit_brand_none")) { EmptyBrandBadge(size: 32) } secim: { choice = Brands.none }
            }
            Section {
                ForEach(liste) { b in
                    satir(secili: choice == b.id, metin: b.name) { BrandBadge(brand: b, size: 32) } secim: { choice = b.id }
                }
            } footer: {
                Text(L("edit_brand_note"))
            }
        }
        .searchable(text: $arama, placement: .navigationBarDrawer(displayMode: .always), prompt: Text(L("edit_brand_search")))
        .navigationTitle(L("edit_brand_choose"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("settings_cancel")) { kapat() } } }
    }

    private func satir<R: View>(secili: Bool, metin: String, @ViewBuilder rozet: () -> R, secim: @escaping () -> Void) -> some View {
        Button { secim(); kapat() } label: {
            HStack(spacing: 12) {
                rozet()
                Text(metin).foregroundStyle(Theme.onSurface).lineLimit(1)
                Spacer()
                if secili { Image(systemName: "checkmark").foregroundStyle(Theme.primary) }
            }
        }
    }
}

/// Öncelik numarası rozeti (Android: `PriorityBadge`).
struct PriorityBadge: View {
    let number: Int

    var body: some View {
        Text(verbatim: "\(number)")
            .font(.caption2.weight(.medium))
            .foregroundStyle(Theme.onSecondaryContainer)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// Detay ekranındaki alan kartı (Android: `FieldCard`): etiket, göster/gizle,
/// kopyala, değer. Gizli alan (şifre, CVV, şifre türündeki ek alan) varsayılan
/// olarak maskelidir; uzunluğu belli etmemek için sabit 8 nokta.
struct FieldCard: View {
    let label: String
    let value: String
    var display: String? = nil
    var hidden: Bool = false
    var icon: String? = nil
    var onCopy: () -> Void

    @State private var gorunur = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.caption) }
                Text(label).font(.caption.weight(.medium)).tracking(0.4)
                Spacer()
                if hidden {
                    Button { gorunur.toggle() } label: { Image(systemName: gorunur ? "eye.slash" : "eye") }
                        .accessibilityLabel(L(gorunur ? "detail_hide" : "detail_show"))
                }
                Button(action: onCopy) { Image(systemName: "doc.on.doc") }
                    .accessibilityLabel(L("detail_copy"))
            }
            .foregroundStyle(Theme.primary)
            .buttonStyle(.borderless)
            .tint(Theme.onSurface)
            Text(hidden && !gorunur ? "••••••••" : (display ?? value))
                .font(.body.monospaced())
                .foregroundStyle(Theme.onSurface)
                .textSelection(.disabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant, lineWidth: 1))
    }
}

/// Tarih ve saat, cihazın dilinde (Android: `d MMM yyyy HH:mm`).
func tarihMetni(_ ms: Int64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
}


import SekuvoCore
import SwiftUI

/// Kayıt ayrıntısı — Android'deki `DetailScreen.kt`. Alan sırası Android'in
/// `EntryData.fields()` sırasıdır; gizli alanlar (şifre, CVV, şifre türündeki
/// ek alan) maskeli açılır.
struct DetailScreen: View {
    let id: Int64
    @Environment(VaultModel.self) private var kasa
    @State private var kayit: DecryptedEntry?
    @State private var duzenle = false

    struct Field: Identifiable {
        let id: String
        let label: String
        let value: String
        var display: String? = nil
        var hidden = false
        var icon: String? = nil
    }

    var body: some View {
        ScrollView {
            if let k = kayit {
                VStack(alignment: .leading, spacing: 8) {
                    let etiketler = kasa.tags.filter { k.tagIds.contains($0.id) }
                    if !etiketler.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(etiketler) { t in
                                    HStack(spacing: 4) {
                                        TagDot(color: t.color, icon: t.icon, size: 12)
                                        Text(t.name).font(.caption.weight(.medium)).foregroundStyle(Theme.onSurface)
                                    }
                                }
                            }
                        }
                        .padding(.bottom, 4)
                    }
                    ForEach(alanlar(k)) { a in
                        FieldCard(label: a.label, value: a.value, display: a.display, hidden: a.hidden, icon: a.icon) {
                            kasa.copy(label: a.label, value: a.value)
                        }
                    }
                    if Features.keyboardExtension {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("detail_quick_title")).font(.caption.weight(.medium)).foregroundStyle(Theme.primary)
                            Text(L(k.quick ? "detail_quick_on" : "detail_quick_off")).font(.subheadline).foregroundStyle(Theme.onSurface)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant, lineWidth: 1))
                        .padding(.top, 8)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("detail_dates", tarihMetni(k.createdAt), tarihMetni(k.updatedAt)))
                        if k.data.passwordChangedAt > 0 {
                            Text(L("detail_password_changed", tarihMetni(k.data.passwordChangedAt)))
                        }
                    }
                    .font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                    .padding(.horizontal, 4).padding(.top, 6)
                    if !k.data.passwordHistory.isEmpty {
                        OldPasswordsCard(history: k.data.passwordHistory) { kasa.copy(label: L("field_password"), value: $0) }
                            .padding(.top, 8)
                    }
                }
                .padding(16)
            }
        }
        .background(Theme.background)
        .navigationTitle(kayit?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Başlıkta kurum logosu (Android: TopAppBar'daki BrandBadge).
            ToolbarItem(placement: .principal) { baslikSatiri }
            ToolbarItem(placement: .primaryAction) {
                if kayit != nil {
                    Button { duzenle = true } label: { Image(systemName: "pencil") }
                        .accessibilityLabel(L("detail_edit"))
                }
            }
        }
        .sheet(isPresented: $duzenle, onDismiss: yenile) {
            if let kayit { NavigationStack { EditScreen(existing: kayit, newType: kayit.type) } }
        }
        .onAppear(perform: yenile)
    }

    private func yenile() { kayit = kasa.load(id: id) }

    /// Başlıkta kurum logosu ve başlık (Android: TopAppBar'daki BrandBadge).
    @ViewBuilder private var baslikSatiri: some View {
        if let k = kayit {
            HStack(spacing: 8) {
                if let b = marka(k) { BrandBadge(brand: b, size: 26) }
                Text(k.title).font(.headline).foregroundStyle(Theme.onSurface).lineLimit(1)
            }
        }
    }

    private func marka(_ k: DecryptedEntry) -> Brand? {
        Brands.match(title: k.title, url: k.data.url, choice: k.data.brand, isCard: k.type == .card, cardNumber: k.data.cardNumber)
    }

    private func alanlar(_ k: DecryptedEntry) -> [Field] {
        let d = k.data
        var sonuc: [Field] = []
        func ekle(_ anahtar: String, _ deger: String, display: String? = nil, hidden: Bool = false, ek: String = "") {
            guard !deger.isEmpty else { return }
            sonuc.append(Field(id: anahtar, label: L(anahtar) + ek, value: deger, display: display, hidden: hidden))
        }
        ekle("field_full_name", d.fullName)
        ekle("field_username", d.username)
        ekle("field_password", d.password, hidden: true)
        ekle("field_url", d.url)
        ekle("field_phone", d.phone)
        ekle("field_email", d.email)
        ekle("field_address", d.address)
        ekle("field_cardholder", d.cardholder)
        let ag = FieldFormats.cardNetwork(d.cardNumber)
        ekle("field_card_number", d.cardNumber, display: FieldFormats.groupCard(d.cardNumber, network: ag),
             ek: ag == .unknown ? "" : " · \(ag.displayName)")
        ekle("field_expiry", d.expiry, display: FieldFormats.expiryDisplay(d.expiry))
        ekle("field_cvv", d.cvv, hidden: true)
        ekle("field_iban", d.iban, display: FieldFormats.group(d.iban))
        for (i, c) in d.custom.enumerated() where !c.value.isEmpty {
            sonuc.append(Field(id: "custom\(i)", label: c.label, value: c.value, hidden: c.type == .password, icon: c.type.symbol))
        }
        ekle("field_notes", d.notes)
        return sonuc
    }
}

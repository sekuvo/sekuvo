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
                    ForEach(alanlar(k)) { a in
                        FieldCard(label: a.label, value: a.value, display: a.display, hidden: a.hidden, icon: a.icon) {
                            kasa.copy(label: a.label, value: a.value)
                        }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("detail_quick_title")).font(.caption.weight(.medium)).foregroundStyle(Theme.primary)
                        Text(L(k.quick ? "detail_quick_on" : "detail_quick_off")).font(.subheadline).foregroundStyle(Theme.onSurface)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant, lineWidth: 1))
                    .padding(.top, 8)
                    Text(L("detail_dates", tarih(k.createdAt), tarih(k.updatedAt)))
                        .font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                        .padding(.horizontal, 4).padding(.top, 6)
                }
                .padding(16)
            }
        }
        .background(Theme.background)
        .navigationTitle(kayit?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if kayit != nil {
                Button { duzenle = true } label: { Image(systemName: "pencil") }
                    .accessibilityLabel(L("detail_edit"))
            }
        }
        .sheet(isPresented: $duzenle, onDismiss: yenile) {
            if let kayit { NavigationStack { EditScreen(existing: kayit, newType: kayit.type) } }
        }
        .onAppear(perform: yenile)
    }

    private func yenile() { kayit = kasa.load(id: id) }

    private func tarih(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
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

import SekuvoCore
import SwiftUI

/// Formdaki ek alan (Android: `OzelAlanSatiri`): ikon · ad · değer. Dokununca
/// düzenleme penceresi açılır; şifre türünde değer formda da gizli kalır.
struct CustomFieldRow: View {
    let field: CustomField
    var onEdit: () -> Void
    var onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onEdit) {
                HStack(spacing: 12) {
                    Image(systemName: field.type.symbol)
                        .foregroundStyle(Theme.primary)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(field.label).font(.caption.weight(.medium)).foregroundStyle(Theme.primary)
                        Text(field.type == .password && !field.value.isEmpty ? "••••••••" : field.value)
                            .foregroundStyle(Theme.onSurface)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: onRemove) { Image(systemName: "xmark") }
                .buttonStyle(.borderless)
                .foregroundStyle(Theme.onSurfaceVariant)
                .accessibilityLabel(L("edit_remove_field"))
        }
    }
}

/// Ek alan ekleme ve düzenleme (Android: `OzelAlanDialog`). Tür, değer
/// alanının nasıl davrandığını belirler: açılan klavye, tek ya da çok satır,
/// şifrede üretici, e-postada denetim. Ad boşsa kaydedilmez; ad ve değer
/// kırpılır (Android ile aynı).
struct CustomFieldSheet: View {
    let initial: CustomField?
    let onSave: (CustomField) -> Void

    @Environment(\.dismiss) private var kapat
    @State private var ad = ""
    @State private var deger = ""
    @State private var tur: CustomFieldType = .text
    @State private var uretici = false
    @State private var hazir = false

    var body: some View {
        Form {
            Section {
                TextField(L("edit_field_name"), text: $ad)
                Picker(L("edit_field_type"), selection: $tur) {
                    ForEach(CustomFieldType.allCases, id: \.self) { t in
                        Label(t.label, systemImage: t.symbol).tag(t)
                    }
                }
            }
            Section {
                degerAlani
            } footer: {
                if tur == .email && !FieldFormats.emailIsValid(deger) {
                    Text(L("edit_email_invalid")).foregroundStyle(Theme.error)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L(initial == nil ? "edit_field_dialog_new" : "edit_field_dialog_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("edit_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(L(initial == nil ? "edit_field_add_confirm" : "edit_field_done")) {
                    onSave(CustomField(label: ad.trimmingCharacters(in: .whitespacesAndNewlines),
                                       value: deger.trimmingCharacters(in: .whitespacesAndNewlines), type: tur))
                    kapat()
                }
                .disabled(ad.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .onAppear {
            guard !hazir else { return }
            if let f = initial { ad = f.label; deger = f.value; tur = f.type }
            hazir = true
        }
        .sheet(isPresented: $uretici) {
            // Pano bildiriminde alanın adı; adsızsa "Şifre" (Android ile aynı).
            let etiket = ad.trimmingCharacters(in: .whitespacesAndNewlines)
            NavigationStack { GeneratorSheet(copyLabel: etiket.isEmpty ? L("field_password") : etiket) { deger = $0 } }
        }
    }

    @ViewBuilder private var degerAlani: some View {
        switch tur {
        case .password:
            HStack {
                SecureFieldRow(placeholder: L("edit_field_value"), text: $deger)
                Button { uretici = true } label: { Image(systemName: "dice.fill") }
                    .accessibilityLabel(L("edit_generate_password"))
                    .buttonStyle(.borderless)
            }
        case .text:
            TextField(L("edit_field_value"), text: $deger, axis: .vertical).lineLimit(1...4)
        case .multiline:
            TextField(L("edit_field_value"), text: $deger, axis: .vertical).lineLimit(4...12)
        case .email:
            TextField(L("edit_field_value"), text: $deger)
                .keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
        case .phone:
            TextField(L("edit_field_value"), text: $deger).keyboardType(.phonePad)
        case .url:
            TextField(L("edit_field_value"), text: $deger)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
        case .number:
            TextField(L("edit_field_value"), text: $deger).keyboardType(.numberPad)
        }
    }
}

/// Eski parola satırı (Android: `OldPasswordRow`): maskeli değer, tarih,
/// göster/gizle, kopyala. Maske sabit 8 nokta, detaydaki alanlar gibi:
/// uzunluk belli olmasın (Android uzunluk kadar, en çok 16 nokta çiziyor).
struct OldPasswordRow: View {
    let old: OldPassword
    var onCopy: () -> Void
    @State private var gorunur = false

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(gorunur ? old.value : "••••••••")
                    .font(gorunur ? .body.monospaced() : .body)
                    .foregroundStyle(Theme.onSurface)
                    .textSelection(.disabled)
                Text(old.changedAt > 0 ? tarihMetni(old.changedAt) : L("old_passwords_unknown_date"))
                    .font(.caption)
                    .foregroundStyle(Theme.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            Button { gorunur.toggle() } label: { Image(systemName: gorunur ? "eye.slash" : "eye") }
                .accessibilityLabel(L(gorunur ? "detail_hide" : "detail_show"))
            Button(action: onCopy) { Image(systemName: "doc.on.doc") }
                .accessibilityLabel(L("detail_copy"))
        }
        .buttonStyle(.borderless)
        .tint(Theme.primary)
    }
}

/// Eski parolalar kartı (Android: detaydaki kart). Parola değişince eskisi
/// silinmez, burada kalır: "sızıntı duyuldu, o tarihte hangi parolayı
/// kullanıyordum" sorusunun cevabı bu liste. Salt okunur.
struct OldPasswordsCard: View {
    let history: [OldPassword]
    var onCopy: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("old_passwords_title")).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.onSurface)
            Text(L("old_passwords_desc"))
                .font(.caption).foregroundStyle(Theme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(history.enumerated()), id: \.offset) { i, eski in
                if i > 0 { Divider().overlay(Theme.outlineVariant) }
                OldPasswordRow(old: eski) { onCopy(eski.value) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant, lineWidth: 1))
    }
}

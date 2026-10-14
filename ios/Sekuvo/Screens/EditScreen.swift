import SekuvoCore
import SwiftUI

/// Kayıt ekleme ve düzenleme — Android'deki `EditScreen.kt`. Alanlar türe
/// göre değişir; kart alanları Android'in giriş kurallarını kullanır
/// (`FieldFormats`), IBAN ve e-posta kaydetmeden önce denetlenir.
struct EditScreen: View {
    let existing: DecryptedEntry?
    let newType: EntryType

    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var baslik = ""
    @State private var veri = EntryData()
    @State private var notTuru: NoteKind = .genel
    @State private var hizli = false
    @State private var oncelik = ""
    @State private var silmeOnayi = false
    @State private var cikisOnayi = false
    @State private var hazir = false

    private var tur: EntryType { existing?.type ?? newType }

    var body: some View {
        Form {
            Section {
                TextField(L("edit_field_title"), text: $baslik)
            }
            switch tur {
            case .login:
                Section {
                    TextField(L("edit_field_username"), text: $veri.username)
                        .textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    HStack {
                        SecureFieldRow(placeholder: L("field_password"), text: $veri.password)
                        Button { veri.password = PasswordGenerator.generate() } label: { Image(systemName: "dice.fill") }
                            .accessibilityLabel(L("edit_generate_password"))
                            .buttonStyle(.borderless)
                    }
                    TextField(L("field_url"), text: $veri.url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
            case .everyday:
                Section {
                    TextField(L("field_full_name"), text: $veri.fullName).textContentType(.name)
                    TextField(L("field_phone"), text: $veri.phone).keyboardType(.phonePad)
                    TextField(L("field_email"), text: $veri.email)
                        .keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(L("field_address"), text: $veri.address, axis: .vertical).lineLimit(2...5)
                } footer: {
                    if !FieldFormats.emailIsValid(veri.email) { Text(L("edit_email_invalid")).foregroundStyle(Theme.error) }
                }
            case .card:
                Section {
                    TextField(L("field_cardholder"), text: $veri.cardholder).textInputAutocapitalization(.characters)
                    TextField(L("edit_field_card_number"), text: Binding(
                        get: { FieldFormats.groupCard(veri.cardNumber) },
                        set: { veri.cardNumber = FieldFormats.cardNumberInput($0) }))
                        .keyboardType(.numberPad).font(.body.monospaced())
                    TextField(L("edit_field_expiry"), text: Binding(
                        get: { FieldFormats.expiryDisplay(veri.expiry) },
                        set: { veri.expiry = FieldFormats.expiryInput($0) }))
                        .keyboardType(.numberPad)
                    SecureFieldRow(placeholder: L("field_cvv"), text: Binding(
                        get: { veri.cvv },
                        set: { veri.cvv = FieldFormats.cvvInput($0, network: FieldFormats.cardNetwork(veri.cardNumber)) }),
                        keyboard: .numberPad)
                    TextField(L("edit_field_iban"), text: Binding(
                        get: { FieldFormats.group(veri.iban) },
                        set: { veri.iban = FieldFormats.ibanInput($0) }))
                        .textInputAutocapitalization(.characters).autocorrectionDisabled().font(.body.monospaced())
                } footer: {
                    if !FieldFormats.expiryIsValid(veri.expiry) {
                        Text(L("edit_expiry_invalid")).foregroundStyle(Theme.error)
                    } else if !FieldFormats.ibanIsValid(veri.iban) {
                        Text(L("edit_iban_invalid")).foregroundStyle(Theme.error)
                    }
                }
            case .note:
                Section {
                    Picker(L("edit_note_kind"), selection: $notTuru) {
                        ForEach(NoteKind.allCases, id: \.self) { k in Label(k.label, systemImage: k.symbol).tag(k) }
                    }
                }
            }
            Section(L("field_notes")) {
                TextField(L("field_notes"), text: $veri.notes, axis: .vertical)
                    .lineLimit(tur == .note ? 6...20 : 2...8)
                    .font(tur == .note ? .body.monospaced() : .body)
                    .textInputAutocapitalization(.never).autocorrectionDisabled(tur == .note)
            }
            Section {
                Toggle(L("edit_quick_title"), isOn: $hizli)
                TextField(L("edit_priority"), text: Binding(
                    get: { oncelik }, set: { oncelik = String($0.filter(\.isASCIIDigitApp).prefix(3)) }))
                    .keyboardType(.numberPad)
            } footer: {
                Text(L(hizli ? "edit_quick_desc" : "edit_priority_desc"))
            }
            if existing != nil {
                Section {
                    Button(L("edit_delete"), role: .destructive) { silmeOnayi = true }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(existing == nil ? L("edit_title_new", tur.label) : L("edit_title_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("edit_cancel")) { degisti ? (cikisOnayi = true) : kapat() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("edit_save"), action: kaydet)
                    .disabled(baslik.trimmingCharacters(in: .whitespaces).isEmpty
                              || !FieldFormats.emailIsValid(veri.email) || !FieldFormats.expiryIsValid(veri.expiry))
            }
        }
        .interactiveDismissDisabled(degisti)
        .confirmationDialog(L("edit_delete_title"), isPresented: $silmeOnayi, titleVisibility: .visible) {
            Button(L("edit_delete"), role: .destructive) {
                if let existing { kasa.delete(id: existing.id) }
                kapat()
            }
        } message: { Text(L("edit_delete_text", baslik)) }
        .confirmationDialog(L("edit_discard_title"), isPresented: $cikisOnayi, titleVisibility: .visible) {
            Button(L("edit_discard_confirm"), role: .destructive) { kapat() }
            Button(L("edit_discard_cancel"), role: .cancel) {}
        } message: { Text(L("edit_discard_text")) }
        .onAppear(perform: yukle)
    }

    private var degisti: Bool {
        guard hazir else { return false }
        if let e = existing {
            return e.title != baslik || e.data != veri || e.noteKind != notTuru || e.quick != hizli
                || e.sortIndex != (Int(oncelik) ?? 0)
        }
        return !baslik.isEmpty || veri != EntryData()
    }

    private func yukle() {
        guard !hazir else { return }
        if let e = existing {
            baslik = e.title; veri = e.data; notTuru = e.noteKind; hizli = e.quick
            oncelik = e.sortIndex > 0 ? String(e.sortIndex) : ""
        }
        hazir = true
    }

    private func kaydet() {
        var e = existing ?? DecryptedEntry(type: tur, title: "", createdAt: 0, updatedAt: 0)
        e.title = baslik.trimmingCharacters(in: .whitespaces)
        e.data = veri
        e.noteKind = tur == .note ? notTuru : .genel
        e.quick = hizli
        e.sortIndex = Int(oncelik) ?? 0
        if kasa.save(e) != nil { kapat() }
    }
}

/// Maskeli giriş + göz düğmesi (Form satırı).
struct SecureFieldRow: View {
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    @State private var gorunur = false

    var body: some View {
        HStack {
            Group {
                if gorunur { TextField(placeholder, text: $text) } else { SecureField(placeholder, text: $text) }
            }
            .keyboardType(keyboard)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .font(.body.monospaced())
            Button { gorunur.toggle() } label: { Image(systemName: gorunur ? "eye.slash" : "eye") }
                .buttonStyle(.borderless)
                .foregroundStyle(Theme.onSurfaceVariant)
                .accessibilityLabel(L(gorunur ? "main_hide" : "main_show"))
        }
    }
}

private extension Character {
    var isASCIIDigitApp: Bool { isASCII && isNumber }
}

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
    @State private var etiketler: [Int64] = []
    @State private var yeniEtiket = false
    /// Açık ek alan penceresi: `index == nil` yeni alan, değilse o sıradaki alan.
    @State private var alanPenceresi: AlanHedefi?
    @State private var silmeOnayi = false
    @State private var cikisOnayi = false
    @State private var ibanOnayi = false
    @State private var uretici = false
    /// "Parolayı QR ile güncelle": tarayıcı kapanınca açılacak zarf.
    @State private var qrTarama = false
    @State private var bekleyenZarf: Data?
    @State private var qrZarfi: QrZarfi?
    @State private var hazir = false

    private var tur: EntryType { existing?.type ?? newType }

    struct AlanHedefi: Identifiable {
        let id = UUID()
        let index: Int?
    }

    struct QrZarfi: Identifiable {
        let id = UUID()
        let data: Data
    }

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
                        Button { qrTarama = true } label: { Image(systemName: "qrcode.viewfinder") }
                            .accessibilityLabel(L("edit_password_from_qr"))
                            .buttonStyle(.borderless)
                        Button { uretici = true } label: { Image(systemName: "dice.fill") }
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
            // Ek alanlar her türde düzenlenebilir: aktarımla gelen bir kaydın
            // içeriği "ad + değer" çiftlerinde olabilir (Android ile aynı).
            Section(L("edit_custom_fields")) {
                ForEach(Array(veri.custom.enumerated()), id: \.offset) { i, alan in
                    CustomFieldRow(field: alan,
                                   onEdit: { alanPenceresi = AlanHedefi(index: i) },
                                   onRemove: { veri.custom.remove(at: i) })
                }
                Button(L("edit_add_field")) { alanPenceresi = AlanHedefi(index: nil) }
                    .foregroundStyle(Theme.primary)
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
            Section(L("edit_tags")) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(kasa.tags) { t in
                            TagChip(tag: t, selected: etiketler.contains(t.id)) {
                                if let i = etiketler.firstIndex(of: t.id) { etiketler.remove(at: i) } else { etiketler.append(t.id) }
                            }
                        }
                        Button(L("edit_new_tag")) { yeniEtiket = true }
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 12).frame(height: 32)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.outline, lineWidth: 1))
                            .buttonStyle(.borderless)
                            .foregroundStyle(Theme.primary)
                    }
                    .padding(.vertical, 2)
                }
            }
            // Eski parolalar salt okunur: form alanı değil, kaydın geçmişi.
            // Parola değiştikçe kaydederken kendiliğinden büyür.
            if !veri.passwordHistory.isEmpty {
                Section {
                    ForEach(Array(veri.passwordHistory.enumerated()), id: \.offset) { _, eski in
                        OldPasswordRow(old: eski) { kasa.copy(label: L("field_password"), value: eski.value) }
                    }
                } header: {
                    Text(L("old_passwords_title"))
                } footer: {
                    Text(L("old_passwords_desc"))
                }
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
                Button(L("edit_save")) {
                    // Geçersiz IBAN engellenmez, sorulur (Android ile aynı): MOD-97'ye
                    // uymayan meşru bir değeri olan tıkanmasın, yanlış yazan da fark etsin.
                    if tur == .card && !FieldFormats.ibanIsValid(veri.iban) { ibanOnayi = true } else { kaydet() }
                }
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
        .sheet(item: $alanPenceresi) { h in
            NavigationStack {
                CustomFieldSheet(initial: h.index.map { veri.custom[$0] }) { alan in
                    if let i = h.index, veri.custom.indices.contains(i) { veri.custom[i] = alan } else { veri.custom.append(alan) }
                }
            }
        }
        .sheet(isPresented: $yeniEtiket) {
            // Yeni etiket bu kayıt için açıldı: hemen seçili gelir (Android ile aynı).
            NavigationStack {
                TagEditSheet(initial: nil) { id in if !etiketler.contains(id) { etiketler.append(id) } }
            }
        }
        .alert(L("edit_iban_confirm_title"), isPresented: $ibanOnayi) {
            Button(L("edit_iban_confirm_save"), role: .destructive) { kaydet() }
            Button(L("edit_iban_confirm_fix"), role: .cancel) {}
        } message: { Text(L("edit_iban_confirm_text")) }
        .sheet(isPresented: $uretici) {
            NavigationStack { GeneratorSheet { veri.password = $0 } }
        }
        .fullScreenCover(isPresented: $qrTarama, onDismiss: {
            // Parola penceresi tarayıcı kapandıktan sonra açılır: iki pencere
            // aynı anda sunulamaz.
            if let z = bekleyenZarf { bekleyenZarf = nil; qrZarfi = QrZarfi(data: z) }
        }) {
            QrScanScreen { bekleyenZarf = $0 }
        }
        .sheet(item: $qrZarfi) { z in
            NavigationStack { PasswordFromQrSheet(envelope: z.data) { veri.password = $0 } }
        }
        .onAppear(perform: yukle)
        #if DEBUG
        .task {
            // `-ornekIban <değer>` (yeni kart): başlık ve IBAN dolu, sonra Kaydet'e basılmış gibi.
            let a = ProcessInfo.processInfo.arguments
            if let i = a.firstIndex(of: "-ornekIban"), i + 1 < a.count, existing == nil, tur == .card {
                baslik = "  Deneme kartı  "
                veri.iban = FieldFormats.ibanInput(a[i + 1])
                veri.cardholder = "  DENIZ YILMAZ "
                try? await Task.sleep(for: .seconds(1))
                if !FieldFormats.ibanIsValid(veri.iban) { ibanOnayi = true } else { kaydet() }
            }
        }
        #endif
    }

    private var degisti: Bool {
        guard hazir else { return false }
        if let e = existing {
            return e.title != baslik || e.data != veri || e.noteKind != notTuru || e.quick != hizli
                || e.sortIndex != (Int(oncelik) ?? 0) || Set(e.tagIds) != Set(etiketler)
        }
        return !baslik.isEmpty || veri != EntryData() || !etiketler.isEmpty
    }

    private func yukle() {
        guard !hazir else { return }
        if let e = existing {
            baslik = e.title; veri = e.data; notTuru = e.noteKind; hizli = e.quick
            oncelik = e.sortIndex > 0 ? String(e.sortIndex) : ""
            etiketler = e.tagIds
        } else {
            // Gündelik bilgiler klavyede parolasız kullanılsın diye açık başlar (Android ile aynı).
            hizli = newType == .everyday
        }
        hazir = true
    }

    private func kaydet() {
        var e = existing ?? DecryptedEntry(type: tur, title: "", createdAt: 0, updatedAt: 0)
        e.title = baslik.trimmingCharacters(in: .whitespaces)
        e.data = veri.trimmedForSave()
        e.noteKind = tur == .note ? notTuru : .genel
        e.quick = hizli
        e.sortIndex = Int(oncelik) ?? 0
        // Bu arada silinmiş bir etiket kayda geri yazılmaz.
        e.tagIds = etiketler.filter { id in kasa.tags.contains { $0.id == id } }
        if kasa.save(e) != nil { kapat() }
    }
}

/// Maskeli giriş + göz düğmesi (Form satırı).
struct SecureFieldRow: View {
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    /// Yedek ve aktarım parolalarında `.sekuvoNone`: buluta kaydedilmesinler.
    /// Kayıt düzenlerken `nil` (sistem varsayılanı) — oradaki öneri işe yarar.
    var contentType: UITextContentType? = nil
    @State private var gorunur = false

    var body: some View {
        HStack {
            Group {
                if gorunur { TextField(placeholder, text: $text) } else { SecureField(placeholder, text: $text) }
            }
            .keyboardType(keyboard)
            .textContentType(contentType)
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

/// "Parolayı QR ile güncelle" (Android: düzenleme ekranındaki pencere). Zarf
/// aktarım parolasıyla açılır, içindeki parola alınır ve yalnız forma
/// yazılır: zarfın taşıdığı kayıt kasaya eklenmez.
struct PasswordFromQrSheet: View {
    let envelope: Data
    let onValue: (String) -> Void

    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var parola = ""

    var body: some View {
        Form {
            Section {
                Text(L("edit_password_qr_hint")).font(.footnote).foregroundStyle(Theme.onSurfaceVariant)
                SecureFieldRow(placeholder: L("paste_label_backup_password"), text: $parola, contentType: .sekuvoNone)
            } footer: {
                if let hata = kasa.error { Text(hata).foregroundStyle(Theme.error) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L("edit_password_from_qr"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("settings_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    let p = parola
                    Task {
                        if let deger = await kasa.passwordFromEnvelope(envelope, password: p) {
                            onValue(deger)
                            kapat()
                        }
                    }
                } label: {
                    if kasa.busy { ProgressView() } else { Text(L("paste_btn_import")) }
                }
                .disabled(parola.isEmpty || kasa.busy)
            }
        }
        .onAppear { kasa.error = nil }
        #if DEBUG
        .task {
            // `-ornekQrParola <parola>`: benzetimli zarfı dokunmadan açmak için.
            let a = ProcessInfo.processInfo.arguments
            if let i = a.firstIndex(of: "-ornekQrParola"), i + 1 < a.count { parola = a[i + 1] }
        }
        #endif
    }
}


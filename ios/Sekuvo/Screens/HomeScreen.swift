import SekuvoCore
import SwiftUI

/// Ana liste — Android'deki `HomeScreen.kt`: arama, tür ve etiket süzgeçleri,
/// sıralama, kayıtlar, + menüsü. Liste şifre çözmeden çizilir (başlık, tür ve
/// etiketler açık durur).
struct HomeScreen: View {
    @Environment(VaultModel.self) private var kasa
    @State private var arama = ""
    @State private var tur: EntryType?
    @State private var etiketSecimi: [Int64] = []
    @State private var etiketYonetimi = false
    @State private var yeni: NewEntry?
    @State private var yol: [Int64] = []
    @State private var qr = false
    @State private var metinden = false
    @State private var ayarlar = false
    @State private var geriYukle = false
    #if DEBUG
    @State private var ornekDosya: RestoreFlow.SelectedFile?
    #endif

    struct NewEntry: Identifiable {
        let id = UUID()
        let type: EntryType
    }

    /// Seçimler daraltarak birleşir: tür VE seçili etiketlerin tamamı. Sıra
    /// modelin sırasıdır (`EntrySort`).
    private var gorunen: [VaultStore.Item] {
        kasa.items.filter { i in
            (arama.isEmpty || i.title.localizedStandardContains(arama)) && (tur == nil || i.type == tur)
                && etiketSecimi.allSatisfy(i.tagIds.contains)
        }
    }

    /// Etiket satırı seçili türe göre daralır: tür seçiliyken yalnız o türdeki
    /// kayıtlarda geçen etiketler görünür. Kaynak arama sonucu değil bütün
    /// liste; aramaya göre de daralsa çipler kullanıcı yazarken kayardı.
    private var gorunenEtiketler: [VaultStore.Tag] {
        guard let tur else { return kasa.tags }
        let kullanilan = Set(kasa.items.filter { $0.type == tur }.flatMap(\.tagIds))
        return kasa.tags.filter { kullanilan.contains($0.id) }
    }

    var body: some View {
        NavigationStack(path: $yol) {
            List {
                Section {
                    // Süzgeçler iki sabit satır: üstte türler, altta etiketler
                    // (Android ile aynı). Satırlar sarmaz, yatay kayar.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(EntryType.allCases, id: \.self) { t in
                                FilterChip(label: t.label, selected: tur == t) { tur = (tur == t) ? nil : t }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(gorunenEtiketler) { t in
                                TagChip(tag: t, selected: etiketSecimi.contains(t.id)) {
                                    if let i = etiketSecimi.firstIndex(of: t.id) { etiketSecimi.remove(at: i) } else { etiketSecimi.append(t.id) }
                                }
                            }
                            Button { etiketYonetimi = true } label: {
                                Image(systemName: "tag").font(.subheadline.weight(.semibold))
                                    .frame(width: 40, height: 32)
                            }
                            .buttonStyle(.borderless)
                            .foregroundStyle(Theme.primary)
                            .accessibilityLabel(L("home_manage_tags_cd"))
                        }
                        .padding(.horizontal, 16)
                    }
                    .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 4, trailing: 0))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                }
                ForEach(gorunen) { item in
                    NavigationLink(value: item.id) { EntryRow(item: item, tags: etiketler) }
                        .listRowBackground(Theme.surfaceContainer)
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .overlay {
                // Kilitlenirken liste 0,2 sn solarak çıkar ve kayıtlar o an boşalmıştır:
                // boş kasa metni yalnız kasa açıkken (videoda iki karede görünüyordu).
                if gorunen.isEmpty && kasa.state == .unlocked {
                    ContentUnavailableView {
                        Label(kasa.items.isEmpty ? L("ios_empty_add_hint") : L("home_empty_filter"),
                              systemImage: kasa.items.isEmpty ? "lock" : "magnifyingglass")
                    } description: {
                        if kasa.items.isEmpty { Text(L("home_empty_vault")) }
                    }
                    .foregroundStyle(Theme.onSurfaceVariant)
                }
            }
            .navigationTitle(Text(verbatim: "Sekuvo"))
            .searchable(text: $arama, prompt: L("home_search_hint"))
            .navigationDestination(for: Int64.self) { DetailScreen(id: $0) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { kasa.lock() } label: { Image(systemName: "lock.fill") }
                        .accessibilityLabel(L("home_lock_cd"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker(L("home_sort_cd", kasa.sort.label),
                               selection: Binding(get: { kasa.sort }, set: { kasa.selectSort($0) })) {
                            ForEach(EntrySort.allCases, id: \.self) { Text($0.label).tag($0) }
                        }
                    } label: {
                        Image(systemName: "arrow.up.arrow.down")
                    }
                    .accessibilityLabel(L("home_sort_cd", kasa.sort.label))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { ayarlar = true } label: { Image(systemName: "gearshape.fill") }
                        .accessibilityLabel(L("home_settings_cd"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(EntryType.allCases, id: \.self) { t in
                            Button { yeni = NewEntry(type: t) } label: { Label(t.label, systemImage: t.symbol) }
                        }
                        Divider()
                        Button { qr = true } label: { Label(L("home_import_qr"), systemImage: "qrcode.viewfinder") }
                        Button { metinden = true } label: { Label(L("home_import_text"), systemImage: "doc.on.clipboard") }
                    } label: {
                        Image(systemName: "plus.circle.fill").font(.title2)
                    }
                    .accessibilityLabel(L("home_add_cd"))
                }
            }
            .sheet(item: $yeni) { y in
                NavigationStack { EditScreen(existing: nil, newType: y.type) }
            }
            .fullScreenCover(isPresented: $qr) { QrScanScreen() }
            .sheet(isPresented: $metinden) { NavigationStack { ImportSheet(source: .text) } }
            .sheet(isPresented: $ayarlar) { SettingsScreen() }
            .sheet(isPresented: $etiketYonetimi) { NavigationStack { TagManageSheet() } }
            // Görünmez süzgeç bırakılmaz: seçili bir etiket tür değişince ya da
            // silinince satırdan düşerse seçimi de düşer. Yoksa liste boşalır ve
            // boşaltan çip ekranda olmadığı için geri alınamaz.
            .onChange(of: gorunenEtiketler.map(\.id)) { _, ids in
                etiketSecimi.removeAll { !ids.contains($0) }
            }
            .restoreFlow(isPresented: $geriYukle)
            .sheet(isPresented: Binding(
                get: { kasa.offerBiometric || kasa.offerBiometricPin != nil },
                set: { if !$0 { kasa.offerBiometric = false; kasa.offerBiometricPin = nil } }
            ), onDismiss: geriYukleGerekirse) {
                BiometricOfferSheet(pin: kasa.offerBiometricPin)
            }
            // Dosyalar'dan gelen yedek: biyometri önerisi açıksa o kapandıktan sonra.
            .sheet(item: Binding(
                get: { kasa.offerBiometric || kasa.offerBiometricPin != nil ? nil : kasa.incomingBackup },
                set: { kasa.incomingBackup = $0 }
            )) { f in
                NavigationStack { ImportSheet(source: .file, envelope: f.data) }
            }
            .onAppear(perform: geriYukleGerekirse)
            #if DEBUG
            .task {
                let a = ProcessInfo.processInfo.arguments
                if a.contains("-ornekQr") { qr = true }
                if a.contains("-ornekAyarlar") { ayarlar = true }
                // `-ornekBildirim <metin>`: 2 sn sonra bildirim (açık sayfaların üstünde görünmeli).
                if let i = a.firstIndex(of: "-ornekBildirim"), i + 1 < a.count {
                    try? await Task.sleep(for: .seconds(2))
                    kasa.show(a[i + 1])
                }
                // `-ornekYeni <TÜR>`: o türde yeni kayıt ekranı (LOGIN, EVERYDAY, CARD, NOTE).
                if let i = a.firstIndex(of: "-ornekYeni"), i + 1 < a.count, let t = EntryType(rawValue: a[i + 1]) {
                    yeni = NewEntry(type: t)
                }
                // `-ornekElleKilitle`: kilit düğmesine basılmış gibi, süreç başına bir kez.
                if a.contains("-ornekElleKilitle"), !Self.elleKilitlendi {
                    Self.elleKilitlendi = true
                    try? await Task.sleep(for: .seconds(1.5))
                    kasa.lock()
                }
                // `-ornekGeriYukle <dosya>`: dosya seçiciye dokunmadan geri yükleme penceresi.
                if let i = a.firstIndex(of: "-ornekGeriYukle"), i + 1 < a.count,
                   let veri = try? Data(contentsOf: URL(fileURLWithPath: a[i + 1])) {
                    ornekDosya = RestoreFlow.SelectedFile(data: veri)
                }
            }
            .sheet(item: $ornekDosya) { s in NavigationStack { ImportSheet(source: .file, envelope: s.data) } }
            // `-ornekDetay <başlık>`: ekran görüntüsü için o kaydın detayıyla açılır.
            .task(id: kasa.items.count) {
                let a = ProcessInfo.processInfo.arguments
                if let i = a.firstIndex(of: "-ornekDetay"), i + 1 < a.count, yol.isEmpty,
                   let hedef = kasa.items.first(where: { $0.title == a[i + 1] }) {
                    yol = [hedef.id]
                }
            }
            #endif
        }
    }
}

extension HomeScreen {
    private var etiketler: [Int64: VaultStore.Tag] {
        Dictionary(kasa.tags.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    #if DEBUG
    static var elleKilitlendi = false
    #endif

    /// Kurulum "yedeğim var" ile yapıldıysa dosya seçici açılır — biyometri
    /// önerisi önce gelir, iki pencere aynı anda açılamaz.
    func geriYukleGerekirse() {
        guard kasa.restoreAfterSetup, !kasa.offerBiometric, kasa.offerBiometricPin == nil else { return }
        kasa.restoreAfterSetup = false
        // Dosyalar'dan bir yedek geldiyse seçiciye gerek yok: o pencere açılır.
        if kasa.incomingBackup == nil { geriYukle = true }
    }
}

/// Liste satırı (Android: kart satırı): tür rozeti, başlık; alt satırda tür
/// adı ve etiket işaretleri; sağ uçta ⚡ ve öncelik.
struct EntryRow: View {
    let item: VaultStore.Item
    var tags: [Int64: VaultStore.Tag] = [:]

    var body: some View {
        HStack(spacing: 14) {
            TypeBadge(type: item.type, kind: item.noteKind)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.semibold)).foregroundStyle(Theme.onSurface).lineLimit(1)
                HStack(spacing: 6) {
                    Text(item.type.label).font(.caption).foregroundStyle(Theme.onSurfaceVariant)
                    ForEach(item.tagIds.compactMap { tags[$0] }) { t in
                        TagDot(color: t.color, icon: t.icon, size: 11)
                            .accessibilityLabel(t.name)
                    }
                }
            }
            Spacer(minLength: 8)
            if item.quick { Text(verbatim: "⚡").font(.callout) }
            if item.sortIndex > 0 { PriorityBadge(number: item.sortIndex) }
        }
        .padding(.vertical, 4)
    }
}

/// Süzgeç çipi (Android: FilterChip).
struct FilterChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                Text(label).font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 14).frame(height: 32)
            .foregroundStyle(selected ? Theme.onSecondaryContainer : Theme.onSurfaceVariant)
            .background(selected ? Theme.secondaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? Color.clear : Theme.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

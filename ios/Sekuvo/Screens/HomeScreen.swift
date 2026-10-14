import SekuvoCore
import SwiftUI

/// Ana liste — Android'deki `HomeScreen.kt`: arama, tür süzgeci, kayıtlar,
/// + menüsü. Liste şifre çözmeden çizilir (başlık ve tür açık durur).
struct HomeScreen: View {
    @Environment(VaultModel.self) private var kasa
    @State private var arama = ""
    @State private var tur: EntryType?
    @State private var yeni: NewEntry?
    @State private var yol: [Int64] = []

    struct NewEntry: Identifiable {
        let id = UUID()
        let type: EntryType
    }

    private var gorunen: [VaultModel.Item] {
        kasa.items.filter { i in
            (arama.isEmpty || i.title.localizedStandardContains(arama)) && (tur == nil || i.type == tur)
        }
    }

    var body: some View {
        NavigationStack(path: $yol) {
            List {
                Section {
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
                }
                ForEach(gorunen) { item in
                    NavigationLink(value: item.id) { EntryRow(item: item) }
                        .listRowBackground(Theme.surfaceContainer)
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .overlay {
                if gorunen.isEmpty {
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
                        ForEach(EntryType.allCases, id: \.self) { t in
                            Button { yeni = NewEntry(type: t) } label: { Label(t.label, systemImage: t.symbol) }
                        }
                        Divider()
                        // QR ve metinden içe aktarma sıradaki adım; şimdilik kapalı.
                        Label(L("home_import_qr"), systemImage: "qrcode.viewfinder").foregroundStyle(.secondary)
                        Label(L("home_import_text"), systemImage: "doc.on.clipboard").foregroundStyle(.secondary)
                    } label: {
                        Image(systemName: "plus.circle.fill").font(.title2)
                    }
                    .accessibilityLabel(L("home_add_cd"))
                }
            }
            .sheet(item: $yeni) { y in
                NavigationStack { EditScreen(existing: nil, newType: y.type) }
            }
            #if DEBUG
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

/// Liste satırı (Android: kart satırı): tür rozeti, başlık, tür adı, ⚡, öncelik.
struct EntryRow: View {
    let item: VaultModel.Item

    var body: some View {
        HStack(spacing: 14) {
            TypeBadge(type: item.type, kind: item.noteKind)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.semibold)).foregroundStyle(Theme.onSurface).lineLimit(1)
                Text(item.type.label).font(.caption).foregroundStyle(Theme.onSurfaceVariant)
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

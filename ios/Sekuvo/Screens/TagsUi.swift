import SekuvoCore
import SwiftUI

/// Etiket işareti (Android: `TagDot`): ikon seçilmişse etiket renginde ikon,
/// seçilmemişse renk noktası. İkon rengin yerine değil, rengiyle çizilir;
/// renk etiketin kimliği olarak kalır.
struct TagDot: View {
    let color: Int32
    var icon: String = ""
    var size: CGFloat = 14

    var body: some View {
        // İkon ve nokta aynı kutuyu kaplar: satırlarda adlar aynı yerden
        // başlar. İkon kutuya sığdırılır (Android'deki `Icon` gibi), geniş
        // simgeler (ör. üç kişi) yanındaki yazıya taşmaz. İkon aynı kutuda
        // noktadan küçük görünür; kutu bu yüzden 3 nokta büyük (Android'de de).
        Group {
            if let sembol = TagSymbol.of(icon) {
                Image(systemName: sembol)
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.semibold)
                    .foregroundStyle(Color(argb: color))
            } else {
                Circle().fill(Color(argb: color)).frame(width: size, height: size)
            }
        }
        .frame(width: size + 3, height: size + 3)
    }
}

/// Seçilebilir etiket çipi (Android: `TagChip`); süzgeç satırı ve düzenleme
/// ekranı aynı görünümü kullanır.
struct TagChip: View {
    let tag: VaultStore.Tag
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                TagDot(color: tag.color, icon: tag.icon, size: 12)
                Text(tag.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .padding(.horizontal, 12).frame(height: 32)
            .foregroundStyle(selected ? Theme.onSecondaryContainer : Theme.onSurfaceVariant)
            .background(selected ? Theme.secondaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? Color.clear : Theme.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Etiket oluşturma ve düzenleme (Android: `TagEditDialog`): ad, palet rengi,
/// isteğe bağlı ikon. İkonlar seçili renkte önizlenir: etiketin listede nasıl
/// görüneceği seçim anında görünsün.
struct TagEditSheet: View {
    let initial: VaultStore.Tag?
    /// Yeni etiketin id'si (düzenleme ekranı onu hemen seçer).
    var onCreated: (Int64) -> Void = { _ in }

    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var ad = ""
    @State private var renk = TagPalette.colors[0]
    @State private var ikon = ""
    @State private var hazir = false

    private var temizAd: String { ad.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        Form {
            Section {
                TextField(L("home_tag_name"), text: $ad)
                HStack(spacing: 0) {
                    ForEach(TagPalette.colors, id: \.self) { c in
                        Button { renk = c } label: {
                            Circle().fill(Color(argb: c)).frame(width: 30, height: 30)
                                .overlay {
                                    if c == renk {
                                        Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(.black.opacity(0.7))
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(c == renk ? L("home_selected_color") : "")
                        .accessibilityAddTraits(c == renk ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
            }
            Section(L("home_tag_icon")) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 6), spacing: 6) {
                    // Boş anahtar "ikonsuz" seçeneği: kaydın listede alacağı
                    // görüntü, yani renk noktası.
                    ikonSecenegi("")
                    ForEach(TagIcons.keys, id: \.self) { ikonSecenegi($0) }
                }
                .padding(.vertical, 4)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L(initial == nil ? "home_tag_new" : "home_tag_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("home_cancel")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("home_save"), action: kaydet).disabled(temizAd.isEmpty)
            }
        }
        .onAppear {
            guard !hazir else { return }
            if let t = initial { ad = t.name; renk = t.color; ikon = TagIcons.normalize(t.icon) }
            hazir = true
        }
    }

    private func ikonSecenegi(_ anahtar: String) -> some View {
        Button { ikon = anahtar } label: {
            TagDot(color: renk, icon: anahtar, size: 18)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(ikon == anahtar ? Theme.primary.opacity(0.18) : Theme.surfaceContainerHighest.opacity(0.6),
                            in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(ikon == anahtar ? .isSelected : [])
    }

    private func kaydet() {
        guard !temizAd.isEmpty else { return }
        if var t = initial {
            t.name = temizAd; t.color = renk; t.icon = ikon
            kasa.updateTag(t)
        } else if let id = kasa.addTag(name: temizAd, color: renk, icon: ikon) {
            onCreated(id)
        }
        kapat()
    }
}

/// Etiket yönetimi (Android: `TagManageDialog`): listele, ekle, düzenle, sil.
/// Silme onay ister; etiket taşıyan kayıtlardan düşer, kayıtlar silinmez.
struct TagManageSheet: View {
    @Environment(VaultModel.self) private var kasa
    @Environment(\.dismiss) private var kapat
    @State private var yeni = false
    @State private var duzenlenen: VaultStore.Tag?
    @State private var silinecek: VaultStore.Tag?

    var body: some View {
        List {
            if kasa.tags.isEmpty {
                Text(L("home_tags_empty")).foregroundStyle(Theme.onSurfaceVariant)
            }
            ForEach(kasa.tags) { t in
                HStack(spacing: 12) {
                    TagDot(color: t.color, icon: t.icon, size: 16)
                    Text(t.name).foregroundStyle(Theme.onSurface)
                    Spacer()
                    Button { duzenlenen = t } label: { Image(systemName: "pencil") }
                        .accessibilityLabel(L("home_edit_cd"))
                        .foregroundStyle(Theme.primary)
                    Button { silinecek = t } label: { Image(systemName: "trash") }
                        .accessibilityLabel(L("home_delete"))
                        .foregroundStyle(Theme.error)
                }
                .buttonStyle(.borderless)
                .listRowBackground(Theme.surfaceContainer)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L("home_tags_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("home_close")) { kapat() } }
            ToolbarItem(placement: .confirmationAction) { Button(L("home_tag_add_new")) { yeni = true } }
        }
        .sheet(isPresented: $yeni) { NavigationStack { TagEditSheet(initial: nil) } }
        .sheet(item: $duzenlenen) { t in NavigationStack { TagEditSheet(initial: t) } }
        .confirmationDialog(L("home_tag_delete_title"),
                            isPresented: Binding(get: { silinecek != nil }, set: { if !$0 { silinecek = nil } }),
                            titleVisibility: .visible, presenting: silinecek) { t in
            Button(L("home_delete"), role: .destructive) { kasa.deleteTag(id: t.id) }
        } message: { t in
            Text(L("home_tag_delete_msg", t.name))
        }
    }
}

import SwiftUI

/// Sekuvo — iOS. Android uygulamasının (`app/`) iOS karşılığı; ikisi
/// `bicim/` altındaki ortak biçime bağlıdır: bir platformun yedeği ve QR
/// zarfı diğerinde açılır.
@main
struct SekuvoApp: App {
    @State private var kasa = VaultModel()
    @Environment(\.scenePhase) private var sahne

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(kasa)
                .tint(Theme.primary)
        }
        .onChange(of: sahne) { _, yeni in kasa.sceneChanged(yeni) }
    }
}

struct RootView: View {
    @Environment(VaultModel.self) private var kasa

    var body: some View {
        ZStack {
            switch kasa.state {
            case .setup: UnlockScreen(setup: true)
            case .locked: UnlockScreen(setup: false)
            case .needsPin: PinScreen()
            case .unlocked: HomeScreen()
            }
            // Uygulama değiştiricinin anlık görüntüsü kasa içeriğini göstermesin:
            // sahne etkin değilken üstte perde durur (FLAG_SECURE'un karşılığı yok).
            if kasa.privacyCover { PrivacyCover() }
        }
        .overlay(alignment: .bottom) {
            if let mesaj = kasa.toast {
                Text(mesaj)
                    .font(.subheadline)
                    .foregroundStyle(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: 0x332E27) : UIColor(rgb: 0xF6EFE3) }))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: 0xEDE3D3) : UIColor(rgb: 0x33302A) }), in: Capsule())
                    // Alttaki arama alanının üstünde dursun.
                    .padding(.bottom, 96).padding(.horizontal, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.3), value: kasa.toast)
        // Dosyalar'dan "Sekuvo ile aç" ya da paylaşım: dosya hemen okunur,
        // geri yükleme penceresi kasa açıkken gelir (HomeScreen).
        .onOpenURL { kasa.receive(fileAt: $0) }
        #if DEBUG
        .task { await kasa.seedDemoIfRequested() }
        #endif
        .animation(.easeOut(duration: 0.2), value: kasa.state)
    }
}

/// Uygulama ekrandan çıkarken içeriği örten perde.
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "lock.fill").font(.system(size: 44)).foregroundStyle(Theme.primary)
                Text(verbatim: "Sekuvo").font(.title2.weight(.semibold)).foregroundStyle(Theme.onSurface)
            }
        }
    }
}

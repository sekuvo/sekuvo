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
        // Bildirim ayrı bir pencerede: açık sayfaların altında kalmasın (Toast.swift).
        .background(ToastHost(kasa: kasa))
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

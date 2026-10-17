import SwiftUI
import UIKit

/// Kısa bildirim (Android'deki Toast) ayrı bir pencerede çizilir.
///
/// Kök görünüme eklenen bildirim, üstte açık duran sayfaların altında
/// kalıyordu. Ölçüldü: Ayarlar açıkken tetiklenen bildirim, görünmesi
/// gereken 2,5 sn boyunca hiç görünmedi. Ayarlar'daki yedek, Face ID ve PIN
/// bildirimleri ile düzenleme ve üretici sayfalarındaki "kopyalandı"
/// bildirimleri aynı nedenle sayfanın altında kalır. Bu pencere sahnenin
/// bütün pencerelerinin üstündedir, dokunuşları alttakilere geçirir ve
/// klavye odağını almaz.
struct ToastHost: UIViewRepresentable {
    let kasa: VaultModel

    func makeUIView(context: Context) -> Baglayici { Baglayici(kasa: kasa) }
    func updateUIView(_ uiView: Baglayici, context: Context) {}

    /// Görünmez yer tutucu: penceresine yerleşince sahneyi bulur ve bildirim
    /// penceresini o sahneye bir kez kurar.
    final class Baglayici: UIView {
        let kasa: VaultModel

        init(kasa: VaultModel) {
            self.kasa = kasa
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let sahne = window?.windowScene { ToastWindow.install(on: sahne, kasa: kasa) }
        }
    }
}

@MainActor
enum ToastWindow {
    /// Sahne başına bir pencere (iPad'de birden çok sahne olabilir).
    private static var pencereler: [ObjectIdentifier: UIWindow] = [:]

    static func install(on sahne: UIWindowScene, kasa: VaultModel) {
        let anahtar = ObjectIdentifier(sahne)
        guard pencereler[anahtar] == nil else { return }
        let pencere = GecirgenPencere(windowScene: sahne)
        pencere.windowLevel = .alert + 1
        pencere.backgroundColor = .clear
        let icerik = UIHostingController(rootView: ToastView().environment(kasa))
        icerik.view.backgroundColor = .clear
        pencere.rootViewController = icerik
        pencere.isHidden = false   // makeKeyAndVisible değil: klavye odağı alttaki pencerede kalır
        pencereler[anahtar] = pencere
    }
}

/// Hiçbir dokunuşu almayan pencere: bildirim yalnız gösterir.
private final class GecirgenPencere: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
}

/// Bildirimin kendisi: altta, arama alanının üstünde bir kapsül.
struct ToastView: View {
    @Environment(VaultModel.self) private var kasa

    var body: some View {
        VStack {
            Spacer()
            if let mesaj = kasa.toast {
                Text(mesaj)
                    .font(.subheadline)
                    .foregroundStyle(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: 0x332E27) : UIColor(rgb: 0xF6EFE3) }))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: 0xEDE3D3) : UIColor(rgb: 0x33302A) }), in: Capsule())
                    // Ana listedeki arama alanının üstünde dursun.
                    .padding(.bottom, 96).padding(.horizontal, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.spring(duration: 0.3), value: kasa.toast)
        .allowsHitTesting(false)
    }
}
